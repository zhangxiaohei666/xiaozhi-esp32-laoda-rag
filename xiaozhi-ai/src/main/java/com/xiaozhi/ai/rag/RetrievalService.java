package com.xiaozhi.ai.rag;

import com.xiaozhi.common.config.RagProperties;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 检索服务：把用户问题变成"参考资料"文本，供注入 system prompt。
 *
 * <p>按 docs/AI应用开发方案.md：
 * <ul>
 *   <li>§8.2 检索：topK=8 → 分数过滤(≥0.35) → 取前 5（这里 topK 与阈值都可配）</li>
 *   <li>§8.3 注入：拼成「[1] 《标题》· 章节 + 正文 + （来源：url）」</li>
 * </ul>
 *
 * <p>★ 设计红线：<b>任何异常都不许抛给对话链路</b>。检索失败就当"没查到"，
 * 让模型照常回答，绝不能让一次 RAG 故障把整轮对话打断。
 *
 * <p>环境变量：RAG_TOP_K（默认 5）、RAG_SIMILARITY_THRESHOLD（默认 0.50）
 */
@Slf4j
@Component
public class RetrievalService {

    /** payload 里的可见性字段值：所有人可见（与 sys_knowledge_doc.scope 保持一致） */
    private static final String SCOPE_SHARED = "shared";

    /**
     * 单次检索最多向 Qdrant 取多少条。
     *
     * <p>下面要按「每篇最多 maxPerDoc 条」收敛，取回数不够就凑不满 topK；
     * 但 maxPerDoc 被调大时取回数会跟着放大，这里设个上限兜住。
     */
    private static final int MAX_FETCH_K = 50;

    /**
     * 参考资料的字数上限。
     *
     * <p>为什么需要：topK=5 且 maxPerDoc=2 时最多能塞 10 块，
     * 实测当前 82 块中最长 10 块合计 6,044 字 —— 语音按 4 字/秒念完要 25 分钟。
     * 语音助手一轮回答通常 10~30 秒，参考资料给 1,500 字（约 6 分钟）已经远超需要。
     */
    private static final int MAX_CONTEXT_CHARS = 1500;

    /** URL：http(s):// 或 ftp:// 开头，到空白为止 */
    private static final java.util.regex.Pattern URL_PATTERN =
            java.util.regex.Pattern.compile("(?:https?|ftp)://\\S+");

    /** markdown 水平分隔线：--- / *** / ___ （整行） */
    private static final java.util.regex.Pattern HR_PATTERN =
            java.util.regex.Pattern.compile("(?m)^\\s*([-*_])\\1{2,}\\s*$");

    /** markdown 标题标记：行首的 # （保留标题文字，只去符号） */
    private static final java.util.regex.Pattern HEADING_MARK_PATTERN =
            java.util.regex.Pattern.compile("(?m)^#{1,6}\\s+");

    /** markdown 列表符：行首的 "- " / "* " / "+ " / "1. " / "[1] " */
    private static final java.util.regex.Pattern LIST_PATTERN =
            java.util.regex.Pattern.compile("(?m)^\\s*(?:[-*+]\\s+|\\d+[.、)]\\s+|\\[\\d+]\\s*)");

    /** 抓取脚本写的头部标签行（"- 来源栏目：xxx" 之类），正文里重复出现时去掉 */
    private static final java.util.regex.Pattern META_LINE_PATTERN =
            java.util.regex.Pattern.compile("(?m)^\\s*-\\s*(来源栏目|发文单位|发布日期|原文链接|来源)\\s*[:：].*$");

    /**
     * markdown 表格分隔行：{@code |---|---|}、{@code |:---|:---:|---:|}、{@code | --- | --- |}。
     *
     * <p>为什么需要单独一条（2026-11-06 修 F-06）：
     * 第 ⑥ 步只做 {@code replace('|','，')}，分隔行会被念成「，---，---，」。
     * 实测 {@code 12-图书馆-楼层与区域分布.md} 里有 6 处这种行，
     * 而它正是评估集 E04「图书馆都有哪些楼层」的期望文档 —— 垃圾文本真实进了 system 消息。
     *
     * <p>为什么不用更宽的正则：必须同时满足「整行只由 | - : 和空格组成」且「首尾都有 |」，
     * 否则 {@code | 楼层 | 区域 |} 这种正常表头（含中文）也会被误删。
     * 行内只用 {@code [ \t]} 不用 {@code \s} —— 后者含换行，{@code (?m)} 下可能跨行吃掉内容。
     */
    private static final java.util.regex.Pattern TABLE_SEPARATOR_PATTERN =
            java.util.regex.Pattern.compile("(?m)^[ \\t]*\\|[ \\t:|-]*\\|[ \\t]*$");

    private final QdrantRagService ragService;
    private final int topK;
    private final double threshold;

    /** 同一篇文档最多贡献几条命中；1=严格去重，topK=不去重 */
    private final int maxPerDoc;

    public RetrievalService(QdrantRagService ragService, RagProperties properties) {
        this.ragService = ragService;
        this.topK = properties.getRetrieval().getTopK();
        this.threshold = properties.getRetrieval().getSimilarityThreshold();
        this.maxPerDoc = properties.getRetrieval().getMaxPerDoc();
        log.info("RAG 检索配置: topK={}, 相似度阈值={}, 每篇最多 {} 条（来自 xiaozhi.rag.retrieval）", topK, threshold, maxPerDoc);
    }

    public int getTopK() {
        return topK;
    }

    public double getThreshold() {
        return threshold;
    }

    /**
     * 纯检索（**按用户过滤**）：只返回「本人的私有资料」与「所有人共享的资料」。
     *
     * <p>这是多用户场景**唯一安全的检索入口**，对话链路必须走这个重载。
     *
     * @param userId 当前会话归属人；传 null 时**只查共享资料**（宁可少给，不可多给）
     * @return 命中文档；任何失败返回空列表（不抛异常）
     */
    public List<Document> search(String question, Integer userId) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        if (!ragService.isEnabled()) {
            return List.of();
        }
        try {
            FilterExpressionBuilder b = new FilterExpressionBuilder();
            // userId 缺失时只给共享资料：绝不退化成"不过滤"
            Filter.Expression filter = userId == null
                    ? b.eq("scope", SCOPE_SHARED).build()
                    : b.or(b.eq("user_id", userId), b.eq("scope", SCOPE_SHARED)).build();

            // 多取一些：下面要按「每篇文档最多 N 条」收敛，取回数不够就凑不满 topK
            int fetchK = Math.min(Math.max(topK, topK * Math.max(1, maxPerDoc)), MAX_FETCH_K);
            SearchRequest request = SearchRequest.builder()
                    .query(question)
                    .topK(fetchK)
                    .similarityThreshold(threshold)
                    .filterExpression(filter)
                    .build();
            List<Document> documents = ragService.vectorStore().similaritySearch(request);
            if (documents == null || documents.isEmpty()) {
                return List.of();
            }
            return limitPerDocument(documents, topK, maxPerDoc);
        } catch (Exception e) {
            log.warn("RAG 检索失败，本轮不注入参考资料: {}", brief(e));
            return List.of();
        }
    }

    /**
     * 按「每篇文档最多 {@code maxPerDoc} 条」收敛，最多返回 {@code limit} 条。
     *
     * <p><b>为什么需要这一步</b>：一篇长文档会切成多个块，向量空间里这些块彼此很近。
     * 实测（2026-11-04）问"图书馆使用规则"，top5 <b>全部来自 doc_id=5</b> ——
     * 相当于 5 个额度被同一篇吃掉，其他 37 篇文档完全没有机会被参考。
     *
     * <p><b>为什么不是严格去重</b>：同一篇的不同小节往往都跟问题相关
     * （比如"预约规则"分散在三个块里），只留一条会丢信息。
     * 所以做成「每篇最多 N 条」而不是「每篇只留一条」：
     * <ul>
     *   <li>{@code maxPerDoc=1} → 严格去重，最大化多样性</li>
     *   <li>{@code maxPerDoc=2} → 默认，兼顾多样性与同篇内的信息完整</li>
     *   <li>{@code maxPerDoc=topK} → 等于不去重（旧行为）</li>
     * </ul>
     *
     * <p>Qdrant 返回结果已按分数降序，所以同一篇里先遇到的就是分最高的那条。
     *
     * @param documents Qdrant 返回的候选（已按分数降序）
     * @param limit     最多返回几条（即 topK）
     * @param maxPerDoc 同一篇文档最多取几条
     */
    static List<Document> limitPerDocument(List<Document> documents, int limit, int maxPerDoc) {
        if (maxPerDoc <= 0) {
            maxPerDoc = 1;
        }
        List<Document> picked = new ArrayList<>(Math.min(limit, documents.size()));
        Map<String, Integer> perDoc = new java.util.HashMap<>();
        for (Document document : documents) {
            if (picked.size() >= limit) {
                break;
            }
            // 没有 doc_id 的（极少见，早期数据）不参与限额，直接收
            String docId = metaOf(document, "doc_id", "");
            if (docId.isEmpty()) {
                picked.add(document);
                continue;
            }
            int used = perDoc.getOrDefault(docId, 0);
            if (used >= maxPerDoc) {
                continue;
            }
            perDoc.put(docId, used + 1);
            picked.add(document);
        }
        if (picked.size() < documents.size()) {
            log.debug("RAG 按文档限额收敛: 候选 {} 条 → 取 {} 条（topK={}, maxPerDoc={}）",
                    documents.size(), picked.size(), limit, maxPerDoc);
        }
        return picked;
    }

    /**
     * 纯检索（不过滤，**仅供内部/运维使用**）。
     *
     * @deprecated 多用户场景下会串数据，业务代码一律用 {@link #search(String, Integer)}。
     */
    @Deprecated
    public List<Document> search(String question) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        if (!ragService.isEnabled()) {
            return List.of();
        }
        try {
            SearchRequest request = SearchRequest.builder()
                    .query(question)
                    .topK(topK)
                    .similarityThreshold(threshold)
                    .build();
            List<Document> documents = ragService.vectorStore().similaritySearch(request);
            return documents == null ? List.of() : documents;
        } catch (Exception e) {
            // 集合还没建、Qdrant 挂了、embedding 401 —— 都当"没查到"
            log.warn("RAG 检索失败，本轮不注入参考资料: {}", brief(e));
            return List.of();
        }
    }

    /**
     * 拼成注入 system prompt 的「参考资料」正文（**按用户过滤**）。
     *
     * <p>三条规则：
     * <ol>
     *   <li><b>清洗成能念出来的文字</b>：资料里的 URL、markdown 分隔线、列表符、表格竖线
     *       都要去掉 —— 这与 {@code role_system_prompt.md} 里"不放链接、不分条"的约束一致。
     *       否则资料自己在违反约束，模型照抄，TTS 会把 URL 逐字母念出来（实测一个 URL 念 30 秒）。</li>
     *   <li><b>字数预算</b>：超过 {@link #MAX_CONTEXT_CHARS} 就停，不再追加后续命中。
     *       没有上限时 topK=5 × maxPerDoc=2 最多能塞 6,044 字，念完要 25 分钟。</li>
     *   <li><b>不重复拼 URL</b>：原文本体里已经有「原文链接：」，这里不再补「（来源：url）」。
     *       以前两处都拼，同一个 URL 出现两次、念两遍。</li>
     * </ol>
     *
     * <p>标题行带上 栏目 / 发布日期（有的话）：让模型知道"这是 2026-10-28 的通知"，
     * 从而能做时效判断，而不是把过期通知当现行规则念出来。
     *
     * @param userId 当前会话归属人；null 时只查共享资料
     * @return 有命中时返回多行文本；无命中或失败返回空串（调用方据此决定是否注入）
     */
    public String buildContext(String question, Integer userId) {
        List<Document> documents = search(question, userId);
        if (documents.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int index = 1;
        int skippedByBudget = 0;
        for (Document document : documents) {
            String title = metaOf(document, "title", "未命名资料");
            String heading = metaOf(document, "heading", "");
            String category = metaOf(document, "category", "");
            String publishDate = metaOf(document, "publish_date", "");

            // 清洗成"能念出来"的纯文本（去掉 URL / 分隔线 / 列表符 / 表格符）
            String text = safeText(document);
            if (text.isEmpty()) {
                continue;
            }

            // 字数预算：标题行 + 正文 会不会超上限
            int estimated = title.length() + heading.length() + category.length()
                    + publishDate.length() + text.length() + 16;
            if (sb.length() > 0 && sb.length() + estimated > MAX_CONTEXT_CHARS) {
                skippedByBudget++;
                continue;
            }

            sb.append('[').append(index++).append("] 《").append(title).append('》');
            if (!heading.isBlank()) {
                sb.append(" · ").append(heading);
            }
            if (!category.isBlank()) {
                sb.append(" · ").append(category);
            }
            if (!publishDate.isBlank()) {
                sb.append(" · ").append(publishDate);
            }
            sb.append('\n').append(text).append('\n');
            // 注意：这里**不再**拼「（来源：url）」—— 正文里已有「原文链接：」，重复拼会念两遍
            sb.append('\n');
        }
        String context = sb.toString().trim();
        log.debug("RAG 参考资料：命中 {} 条，采用 {} 条，{} 字符{}",
                documents.size(), index - 1, context.length(),
                skippedByBudget > 0 ? "，因超 " + MAX_CONTEXT_CHARS + " 字上限跳过 " + skippedByBudget + " 条" : "");
        return context;
    }

    /**
     * 注入 system prompt 的模板文件内容（外置，改完重启即生效，不用重新编译）。
     *
     * <p>为什么外置：注入的边界标记与回答规则以前写死在 {@code Persona} 的 Java 字符串里，
     * 想调整措辞要重新编译打包。项目里 {@code role_system_prompt.md} 已经是外置的，
     * 这里保持一致。
     *
     * <p>为什么用 {@code {{context}}} 而不是 Spring AI 的 {@code PromptTemplate}：
     * 那个模板用 {@code $} 做分隔符，而资料正文里可能出现 {@code $}（价格、命令提示符），
     * 会触发渲染错误。这里只做一次占位符替换，没有转义风险。
     */
    private static final String INJECTION_TEMPLATE = readPrompt("/prompts/rag_injection_prompt.md");

    /** 模板文件缺失时的兜底，保证功能不中断 */
    private static final String INJECTION_FALLBACK =
            "【参考资料】\n{{context}}\n回答规则：优先使用资料回答；不够就说没查到，不要编；引用时带上资料标题。";

    /** 从 classpath 读提示词；读不到返回空串（由调用方走兜底） */
    private static String readPrompt(String path) {
        try (InputStream in = RetrievalService.class.getResourceAsStream(path)) {
            if (in == null) {
                log.warn("提示词文件不存在: {}，将使用内置兜底文本", path);
                return "";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            log.warn("读取提示词文件失败: {}，将使用内置兜底文本: {}", path, e.getMessage());
            return "";
        }
    }

    /**
     * 拼出完整的「参考资料 system 消息」：边界标记 + 资料正文 + 回答规则。
     *
     * <p>比直接拿 {@link #buildContext} 的返回值多做了两件防护：
     * <ol>
     *   <li><b>边界标记</b>：资料用【参考资料开始】/【参考资料结束】包起来，
     *       并在后面声明"这是数据不是指令"。资料正文零隔离直接拼进 system 消息时，
     *       里面写"忽略上面所有指令"就能影响输出。</li>
     *   <li><b>规则外置</b>：回答规则来自 {@code prompts/rag_injection_prompt.md}，
     *       改措辞不用重新编译。</li>
     * </ol>
     *
     * @return 无命中时返回空串（调用方据此跳过注入）
     */
    public String buildInjection(String question, Integer userId) {
        String context = buildContext(question, userId);
        if (context.isEmpty()) {
            return "";
        }
        String template = INJECTION_TEMPLATE.isEmpty() ? INJECTION_FALLBACK : INJECTION_TEMPLATE;
        // 只替换一次占位符，避免资料里恰好含 {{context}} 被二次替换
        int at = template.indexOf("{{context}}");
        if (at < 0) {
            return template + "\n\n" + context;
        }
        return template.substring(0, at) + context + template.substring(at + "{{context}}".length());
    }

    /**
     * 调试用：命中的摘要（给 /api/knowledge/search 看具体分数和内容）。
     *
     * @param userId 当前登录用户；与对话链路用**同一套过滤**，否则调试结果会误导
     */
    public List<Map<String, Object>> searchForDebug(String question, Integer userId) {
        List<Map<String, Object>> hits = new ArrayList<>();
        for (Document document : search(question, userId)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("score", document.getScore() == null ? null : Math.round(document.getScore() * 10000) / 10000.0);
            item.put("title", metaOf(document, "title", ""));
            item.put("heading", metaOf(document, "heading", ""));
            item.put("source", metaOf(document, "source", ""));
            item.put("docId", metaOf(document, "doc_id", ""));
            String text = safeText(document);
            item.put("snippet", text.length() > 300 ? text.substring(0, 300) + "…" : text);
            hits.add(item);
        }
        return hits;
    }

    private static String metaOf(Document document, String key, String fallback) {
        try {
            Object value = document.getMetadata().get(key);
            if (value == null) {
                return fallback;
            }
            String text = String.valueOf(value).trim();
            return text.isEmpty() ? fallback : text;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String safeText(Document document) {
        try {
            String text = document.getText();
            return text == null ? "" : cleanForVoice(text);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 把资料正文清洗成「能念出来」的纯文本。
     *
     * <p>为什么必须做：{@code role_system_prompt.md} 第 5 行明确要求模型
     * "不用 Markdown：不加粗、不分条、不加标题、不写代码块、不放链接"。
     * 但抓取来的资料里全是这些 —— URL、{@code ---} 分隔线、{@code - } 列表符、表格竖线。
     * 资料本身在违反约束，模型就会照抄，TTS 把 URL 逐字母念出来（实测一个 URL 念 30 秒以上）。
     *
     * <p>清洗顺序有讲究：先删 URL（避免 URL 里的 {@code -} 被当成列表符），
     * 再删分隔线，最后删列表符。
     */
    static String cleanForVoice(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String s = text;

        // ① 抓取脚本写的头部标签行（"- 原文链接：https://..." 整行去掉，比只删 URL 干净）
        s = META_LINE_PATTERN.matcher(s).replaceAll("");

        // ② 剩下的 URL（正文里出现的）
        s = URL_PATTERN.matcher(s).replaceAll("");

        // ③ markdown 水平分隔线
        s = HR_PATTERN.matcher(s).replaceAll("");

        // ④ markdown 标题记号（保留标题文字）
        s = HEADING_MARK_PATTERN.matcher(s).replaceAll("");

        // ⑤ markdown 列表符
        s = LIST_PATTERN.matcher(s).replaceAll("");

        // ⑤.5 markdown 表格分隔行（必须早于 ⑥，否则会被转成「，---，---，」念出来）
        s = TABLE_SEPARATOR_PATTERN.matcher(s).replaceAll("");

        // ⑥ 表格竖线转逗号（保留内容，念出来是停顿）
        s = s.replace('|', '，');

        // ⑦ 合并被空格拆散的数字：抓取/OCR 出来的 "202 6"、"1 15 号"、"1 0 月"、"1 8 ： 0 0"
        //    TTS 会把它们念成"二零二 六"、"一 一五 号"，听感很差且容易误解。
        //    只合并【数字与数字之间】和【数字与冒号之间】的空格，不动"3 楼"这种数字+中文。
        s = s.replaceAll("(?<=\\d)\\s+(?=\\d)", "");
        s = s.replaceAll("(?<=\\d)\\s+(?=[：:])", "");
        s = s.replaceAll("(?<=[：:])\\s+(?=\\d)", "");

        // ⑧ 压缩空白与多余空行
        //    注意：这里**不要**再加"删掉短句冒号行"之类的规则 ——
        //    实测它会连「校内各单位：」这种正常正文一起删掉（正文开头常见这种称呼）。
        //    头部标签行已由第 ① 步按关键词精确删除，不需要更宽的兜底。
        s = s.replaceAll("[ \\t\\u00A0]+", " ");
        s = s.replaceAll("(?m)^\\s+$", "");
        s = s.replaceAll("\\n{3,}", "\n\n");

        return s.trim();
    }

    private static String brief(Throwable e) {
        String message = e.getMessage();
        if (message == null) {
            return e.getClass().getSimpleName();
        }
        return message.length() > 200 ? message.substring(0, 200) + "..." : message;
    }
}