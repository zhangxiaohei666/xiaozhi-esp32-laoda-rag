package com.xiaozhi.ai.rag;

import com.xiaozhi.common.config.RagProperties;
import com.xiaozhi.common.model.bo.ConfigBO;
import com.xiaozhi.common.port.ConfigLookup;

import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.rerank.TextReRank;
import com.alibaba.dashscope.rerank.TextReRankOutput;
import com.alibaba.dashscope.rerank.TextReRankParam;
import com.alibaba.dashscope.rerank.TextReRankResult;

import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * RAG 重排客户端（UP-17 两段式检索的第二段）：DashScope {@code gte-rerank-v2}。
 *
 * <p><b>职责边界</b>：本类只做「把一组候选按与 query 的相关性重排」这一件事。
 * 召回多少条、拒答门在哪、重排后取几条，全部在 {@link RetrievalService#doSearch} 决定 ——
 * 这里不读检索参数、不做业务判断，方便单测与替换模型。
 *
 * <p><b>返回值契约</b>（调用方据此分流）：
 * <ul>
 *   <li>返回重排后的新列表 → 按新顺序使用（每条带 {@code metadata.rerank_score}）</li>
 *   <li>返回原列表（候选 &lt; 2 条时）→ 顺序无变化可能，直接用</li>
 *   <li>返回 {@code null} → 重排失败，调用方必须走基线路径（按拒答线过滤 + 原序），
 *       <b>绝不</b>把 {@code null} 当"空结果"处理 —— 候选还在，只是没排成</li>
 * </ul>
 *
 * <p><b>为什么用 SDK 而不是自写 HTTP client</b>（对 RAG升级.md §9.3② 的一处修正）：
 * 文档写"非 OpenAI 兼容，要自写小 client"时还没查过 —— 项目已依赖的
 * dashscope-sdk-java 2.22.7 自带 {@code com.alibaba.dashscope.rerank.TextReRank}，
 * 与 AliyunTtsService/AliyunSttService 同一套 SDK 用法（key 走 param builder 的
 * {@code apiKey()}），不需要再引入手写 JSON client 的维护面。
 *
 * <p><b>几条实现红线</b>：
 * <ol>
 *   <li>任何异常（网络/超时/响应畸形/key 缺失）→ {@code log.warn} + 返回 {@code null}，
 *       绝不抛给对话链路；</li>
 *   <li>每次调用 {@code new TextReRank()}：SDK 的 {@code call()} 会改写实例内部
 *       {@code serviceOption} 状态（SDK 源码确认），复用同一实例并发调用有竞态，
 *       对象本身很轻、new 的成本可忽略 —— 与 AliyunTts 每请求新建 synthesizer 同款做法；</li>
 *   <li>超时用自写 {@link #withTimeout} 包裹而非 SDK 的 {@code ConnectionOptions}
 *       —— 后者 {@code useDefaultClient} 语义不明，不赌；超时后对话线程立即释放，
 *       悬挂的底层调用会随 SDK 自身超时自然结束（低 QPS 场景可接受，见方法注释）；</li>
 *   <li>{@code Document.score}（余弦）<b>原样保留</b>：评估口径锚在它上面；
 *       rerank 分数只写 {@code metadata.rerank_score}。官方文档明确 rerank 分数是
 *       "当前请求内的相对分数，不可跨请求比较" —— 永远不能拿它当绝对阈值用
 *       （这也是"用 rerank 分数替代 0.50 拒答线"这条不做的证据）。</li>
 * </ol>
 */
@Slf4j
@Component
public class RagRerankService {

    /** 重排分数写进 Document metadata 的键名；缺这个键 = 该条没经过重排（缓存回放也保留） */
    public static final String METADATA_RERANK_SCORE = "rerank_score";

    private final ConfigLookup configLookup;

    private final boolean enabled;
    private final String model;
    private final int timeoutMs;

    public RagRerankService(RagProperties properties, ConfigLookup configLookup) {
        this.configLookup = configLookup;
        RagProperties.Rerank rerank = properties.getRerank();
        this.enabled = rerank.isEnabled();
        this.model = rerank.getModel();
        this.timeoutMs = rerank.getTimeoutMs();
        log.info("RAG rerank 初始化: enabled={}, model={}, timeoutMs={}（来自 xiaozhi.rag.rerank）",
                enabled, model, timeoutMs);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 重排候选（契约见类注释）。
     *
     * @param query      送重排的问题文本 —— 与打向量库的是<b>同一份</b>：
     *                   业务口是改写后的检索词（改写就是为了检索质量，重排用同一份判断才自洽），
     *                   调试口是用户原句
     * @param candidates 召回候选（已过拒答门，余弦分在 {@code Document.score} 上）
     * @return 重排后的列表 / 原列表 / {@code null}（失败，调用方走基线）
     */
    public List<Document> rerank(String query, List<Document> candidates) {
        if (!enabled) {
            // 调用方有义务先判 isEnabled()；这里兜底：null = "没有重排，走你的基线路径"
            return null;
        }
        if (candidates == null || candidates.size() < 2) {
            // 1 条候选不存在"顺序"，调一次 API 纯浪费 ~300ms
            return candidates;
        }
        long start = System.nanoTime();
        try {
            String apiKey = apiKey();
            if (apiKey == null || apiKey.isBlank()) {
                log.warn("RAG rerank：拿不到 DashScope key（缺 embedding 配置），退回基线");
                return null;
            }
            List<String> documents = new ArrayList<>(candidates.size());
            for (Document candidate : candidates) {
                documents.add(candidate.getText() == null ? "" : candidate.getText());
            }
            // topN 不设：默认返回全部排序，每篇限额需要全量序（设小了会把限额可用候选截掉）
            // returnDocuments=false：只要 index + 分数，省 payload（官方默认即 false，显式写出来防歧义）
            TextReRankParam param = TextReRankParam.builder()
                    .apiKey(apiKey)
                    .model(model)
                    .query(query)
                    .documents(documents)
                    .returnDocuments(false)
                    .build();
            TextReRankResult result = withTimeout(timeoutMs, () -> {
                try {
                    return new TextReRank().call(param);
                } catch (ApiException | NoApiKeyException | InputRequiredException e) {
                    throw new IllegalStateException(e);
                }
            });
            if (result == null) {
                log.warn("RAG rerank：{} ms 内未返回，退回基线", timeoutMs);
                return null;
            }
            if (result.getCode() != null && !result.getCode().isBlank()) {
                // 业务层失败不抛异常而是塞在结果里（getCode/getMessage）——
                // 必须显式判，否则畸形响应会被当成功。
                // ★ 只判非空串：2026-11-17 实测（SDK 2.22.7 真端点）成功响应的 code 是空串
                //   ""而不是 null——只判 !=null 会把每次成功都当业务错误丢掉，全量降级。
                log.warn("RAG rerank：服务端错误 code={}，退回基线: {}", result.getCode(), result.getMessage());
                return null;
            }
            List<TextReRankOutput.Result> results =
                    result.getOutput() == null || result.getOutput().getResults() == null
                            ? List.of() : result.getOutput().getResults();
            if (results.isEmpty()) {
                log.warn("RAG rerank：响应无 results，退回基线");
                return null;
            }
            List<Document> reordered = applyResults(candidates, results);
            log.info("RAG rerank：{} 条候选重排完成，耗时 {} ms，top1 doc_id={}",
                    candidates.size(), (System.nanoTime() - start) / 1_000_000, metaDocId(reordered.get(0)));
            return reordered;
        } catch (Exception e) {
            log.warn("RAG rerank 失败，退回基线: {}", brief(e));
            return null;
        }
    }

    /**
     * 把服务端排序映射回候选列表。
     *
     * <p>防御性要求（都不该发生，但外部响应不可信）：
     * <ul>
     *   <li>index 越界 / null / 重复出现 → 忽略该条</li>
     *   <li>relevance_score 为 null → 顺序照用，metadata 不写 rerank_score</li>
     *   <li>候选没被任何 result 提到（缺项）→ 按原序补在<b>尾部</b>，保证一条不丢</li>
     * </ul>
     *
     * <p>重建 Document（而不是原地改 metadata）：Document 的 metadata 不保证可变；
     * id/text/余弦 score 原样带过去 —— score 是评估口径的锚，绝不允许被 rerank 分数覆盖。
     */
    static List<Document> applyResults(List<Document> candidates, List<TextReRankOutput.Result> results) {
        boolean[] ranked = new boolean[candidates.size()];
        List<Document> ordered = new ArrayList<>(candidates.size());
        // ① 服务端序（relevance_score 降序）逐条映射
        for (TextReRankOutput.Result result : results) {
            if (result == null || result.getIndex() == null) {
                continue;
            }
            int index = result.getIndex();
            if (index < 0 || index >= candidates.size() || ranked[index]) {
                continue;
            }
            ranked[index] = true;
            ordered.add(rebuildWithScore(candidates.get(index), result.getRelevanceScore()));
        }
        // ② 缺项按原序补尾
        for (int i = 0; i < candidates.size(); i++) {
            if (!ranked[i]) {
                ordered.add(candidates.get(i));
            }
        }
        return ordered;
    }

    private static Document rebuildWithScore(Document document, Double rerankScore) {
        Map<String, Object> metadata = new HashMap<>(
                document.getMetadata() == null ? Map.of() : document.getMetadata());
        if (rerankScore != null) {
            metadata.put(METADATA_RERANK_SCORE, Math.round(rerankScore * 10000) / 10000.0);
        }
        Document.Builder builder = Document.builder()
                .id(document.getId())
                .text(document.getText() == null ? "" : document.getText())
                .metadata(metadata);
        // 余弦分原样保留 —— 评估 top1 均分口径锚在这里
        if (document.getScore() != null) {
            builder.score(document.getScore());
        }
        return builder.build();
    }

    /**
     * 带 Millis 级超时地执行一个阻塞动作：超时返回 {@code null}，动作自身异常原样抛出。
     *
     * <p>为什么不用 SDK 的 {@code ConnectionOptions}：它的 {@code useDefaultClient} 语义
     * 不明（可能忽略自定义超时），不赌；这里的时间预算完全自控、可单测。
     *
     * <p>超时后调用方立即拿到 {@code null} 走基线（对话链路最多只被拖 timeoutMs）；
     * 底层线程无法强断，会随 SDK 自身超时自然结束 —— 语音助手 QPS 很低，
     * 最坏情况是挂起期多占一个 commonPool 线程，可接受（已在方案评审确认）。
     */
    static <T> T withTimeout(long timeoutMs, Supplier<T> action) {
        CompletableFuture<T> future = CompletableFuture.supplyAsync(action);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return null;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(cause);
        }
    }

    /**
     * 取 DashScope key：与 embedding 同源同配置 —— embedding 那条
     * （configType=llm、modelType=embedding，provider=openai 指向百炼兼容模式）的
     * {@code apiKey} 就是同一个 DashScope 账号 key，原生 rerank 端点直接可用
     * （2026-11-17 冒烟已证）。占位符 {@code ${aliqwen-api}} 已在 ConfigConvert 读取路径解析，
     * 本方法拿到的是真实 key —— <b>绝不入日志、绝不进异常消息</b>。
     *
     * <p>每次现取不缓存：admin 改配置后无需重启即可换 key；本地 MySQL 一次读 ≈1ms，
     * 相比重排调用的 ~300ms 可忽略。
     */
    private String apiKey() {
        ConfigBO config = configLookup.getDefaultConfig("llm", ConfigBO.ModelType.embedding.getValue());
        return config == null ? null : config.getApiKey();
    }

    private static String metaDocId(Document document) {
        try {
            Object value = document.getMetadata().get("doc_id");
            return value == null ? "?" : String.valueOf(value);
        } catch (Exception e) {
            return "?";
        }
    }

    private static String brief(Throwable e) {
        String message = e.getMessage();
        if (message == null) {
            return e.getClass().getSimpleName();
        }
        return message.length() > 200 ? message.substring(0, 200) + "..." : message;
    }
}
