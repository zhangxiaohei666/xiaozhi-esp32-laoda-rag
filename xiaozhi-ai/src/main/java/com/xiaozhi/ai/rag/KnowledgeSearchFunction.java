package com.xiaozhi.ai.rag;

import com.xiaozhi.ai.llm.tool.ToolCallStringResultConverter;
import com.xiaozhi.ai.tool.ToolsGlobalRegistry;
import com.xiaozhi.ai.tool.session.ToolSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 知识库检索工具（S0，RAG 工具化第一步）：把 RAG 检索暴露为主 LLM 可自主调用的工具。
 *
 * <p><b>形态跃迁</b>：V1 的检索是每轮固定前置执行的管道（Persona/TextChatService 注入），
 * 本工具把它变成 <b>Agentic RAG</b> —— 调不调由模型看上下文决定（追问/寒暄零检索成本），
 * 检索词由主模型作为工具参数生成（自包含、天然解决多轮指代的错锚点问题）。
 *
 * <p>★ 设计红线（全部继承自 {@link RetrievalService} 的既有纪律）：
 * <ul>
 *   <li><b>userId 只从会话取</b>：{@link ToolSession#getUserId()}，绝不接受工具参数传入 ——
 *       与 {@code KnowledgeAccessGuard.currentUserId()} 同一纪律，否则可伪造归属</li>
 *   <li><b>fail-closed</b>：userId 为 null 时只查共享资料，绝不退化成"不过滤"</li>
 *   <li><b>任何异常不抛给对话链路</b>：失败按"没查到"返回，绝不打断整轮对话</li>
 * </ul>
 *
 * <p>内核复用（不重写检索逻辑，见 {@link RetrievalService#searchRaw}）：
 * 权限过滤下推 Qdrant、查询缓存（30 分钟）、两段式 rerank、每文档限额 ——
 * 与注入链路<b>同一套口径</b>，两条路径的检索质量永远一致。
 *
 * <p><b>与注入路径的差异</b>：跳过寒暄门控与规则改写 —— 工具路径里"要不要检索"已由
 * 主 LLM 决策（决定调用即判断需要查），查询词由模型生成（天然自包含），这两层 V1 设施
 * 在 Agentic 路径上是重复决策。
 *
 * <p><b>注册模式</b>：仿 {@code ChangeRoleFunction} —— 不用 @Tool 注解自动生成，
 * 而是 {@link #getFunctionCallTool} 里手工建 {@link FunctionToolCallback}，
 * 在闭包里绑定注册期捕获的 userId（Spring AI 的 @Tool 方法签名拿不到会话身份）。
 *
 * <p>放本包（ai/rag）而非 dialogue 的 function 包，依据：①内核依赖全在 xiaozhi-ai，
 * 无 dialogue 依赖；②先例 library 两工具就在 ai 包；③注册链路跨模块已验证可行。
 *
 * <p>开关：{@code xiaozhi.rag.enabled} 与检索总开关同步 —— RAG 关了工具就不注册，
 * 模型自然不会调用。计划文档：docs/02-RAG/RAG检索工具化改造计划.md
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "xiaozhi.rag.enabled", havingValue = "true", matchIfMissing = true)
public class KnowledgeSearchFunction implements ToolsGlobalRegistry.GlobalFunction {

    public static final String TOOL_NAME = "knowledge_search";

    /** 返回给模型的正文截断：检索块本体已有 1500 字预算约束，这里只是防御性上限 */
    private static final int MAX_TEXT_CHARS = 600;

    /** 最多列出几条命中：topK=5 已在内核限制，这里防御性再兜一层 */
    private static final int MAX_HITS_LISTED = 5;

    private static final String TOOL_DESCRIPTION =
            "查询校园知识库（图书馆规则、办事流程、通知公告、常见问题等静态资料）。"
            + "当用户的问题可能需要查资料才能准确回答时调用本工具。"
            + "【重要】请生成不依赖上下文的独立查询词：把用户问题中的指代（'那里''第二天'）"
            + "替换成具体实体（'图书馆''2026-11-25'），保留主题词与限定词。"
            + "结果里每条带相似度分数（0~1），低于 0.60 说明资料与问题不太相关，"
            + "可以调整查询词再调一次（最多重试一次）。"
            + "如果两次都查不到相关资料，就按你自己的知识回答并说明没查到资料。"
            + "纯闲聊、追问上一条回答的细节、用户明确说'继续'时不要调用。"
            + "实时数据（图书馆座位空闲数）不归本工具，用 library_seat_overview。";

    private final RetrievalService retrievalService;

    public KnowledgeSearchFunction(RetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @Override
    public ToolCallback getFunctionCallTool(ToolSession toolSession) {
        // 注册期绑定会话身份：userId 从 ToolSession 取（绝不接受模型/前端参数传入）。
        // userId 为 null 时工具仍注册 —— searchRaw 对 null 按 fail-closed 只查共享资料。
        Integer userId = toolSession.getUserId();
        return FunctionToolCallback
                .builder(TOOL_NAME, (Map<String, String> params, ToolContext toolContext) -> {
                    String query = params.get("query");
                    return execute(query, userId);
                })
                .description(TOOL_DESCRIPTION)
                .inputSchema("""
                        {
                            "type": "object",
                            "properties": {
                                "query": {
                                    "type": "string",
                                    "description": "自包含的检索查询词，不依赖对话上下文也能看懂"
                                }
                            },
                            "required": ["query"]
                        }
                        """)
                // ⚠️ 必须给 inputType，否则 builder.build() 抛 IllegalArgumentException: inputType cannot be null。
                // 与 ChangeRoleFunction 同一写法：闭包式 builder 不会自动推断参数类型。
                .inputType(Map.class)
                // 结果转换器：工具返回的是纯文本，需要它把返回值转成 ToolResponse 编码（同 ChangeRoleFunction）
                .toolCallResultConverter(ToolCallStringResultConverter.INSTANCE)
                .build();
    }

    /**
     * 实际执行体：检索 + 渲染。异常全部吞掉（红线：不许打断对话）。
     */
    String execute(String query, Integer userId) {
        try {
            if (query == null || query.isBlank()) {
                return "查询词为空，请给出明确的检索问题。";
            }
            long start = System.nanoTime();
            List<Document> documents = retrievalService.searchRaw(query, userId);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            log.info("knowledge_search 执行: query='{}', userId={}, 命中 {} 条, 耗时 {} ms",
                    brief(query), userId, documents.size(), elapsedMs);

            if (documents.isEmpty()) {
                return "知识库里没有找到与「" + brief(query) + "」相关的资料。"
                        + "可以换个更具体的查询词再试一次，或按你自己的知识回答。";
            }
            return renderHits(documents);
        } catch (Exception e) {
            log.warn("knowledge_search 执行失败，本轮按未查到处理: {}", e.getMessage());
            return "知识库检索暂时不可用，请按你自己的知识回答并说明资料暂缺。";
        }
    }

    /** 渲染命中列表：编号 + 分数 + 标题/章节 + 截断正文。纯中文文本（模型组织语言比 JSON 稳）。 */
    String renderHits(List<Document> documents) {
        StringBuilder sb = new StringBuilder();
        sb.append("知识库命中 ").append(Math.min(MAX_HITS_LISTED, documents.size())).append(" 条：");
        for (int i = 0; i < Math.min(MAX_HITS_LISTED, documents.size()); i++) {
            Document d = documents.get(i);
            sb.append("\n\n[").append(i + 1).append("] ");
            if (d.getScore() != null) {
                sb.append("(相似度 ").append(String.format("%.2f", d.getScore())).append(") ");
            }
            String title = metaOf(d, "title");
            String heading = metaOf(d, "heading");
            if (!title.isEmpty()) {
                sb.append("《").append(title).append("》");
            }
            if (!heading.isEmpty()) {
                sb.append(" ").append(heading);
            }
            sb.append("\n");
            String text = safeText(d);
            sb.append(text.length() > MAX_TEXT_CHARS ? text.substring(0, MAX_TEXT_CHARS) + "…" : text);
        }
        return sb.toString();
    }

    private static String metaOf(Document document, String key) {
        try {
            Object value = document.getMetadata().get(key);
            return value == null ? "" : String.valueOf(value).trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** Document 文本的安全读取（与 RetrievalService 同口径，独立拷贝避免跨包可见性问题） */
    private static String safeText(Document document) {
        try {
            String text = document.getText();
            return text == null ? "" : text.strip();
        } catch (Exception e) {
            return "";
        }
    }

    private static String brief(String text) {
        return text.length() > 40 ? text.substring(0, 40) + "…" : text;
    }

    @Override
    public String getToolName() {
        return TOOL_NAME;
    }

    @Override
    public String getToolDescription() {
        return "查询校园知识库（静态资料：图书馆规则、办事流程、通知、常见问题）";
    }
}
