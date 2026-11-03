package com.xiaozhi.ai.rag;

import com.xiaozhi.common.config.RagProperties;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

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
 * <p>环境变量：RAG_TOP_K（默认 5）、RAG_SIMILARITY_THRESHOLD（默认 0.35）
 */
@Slf4j
@Component
public class RetrievalService {

    /** payload 里的可见性字段值：所有人可见（与 sys_knowledge_doc.scope 保持一致） */
    private static final String SCOPE_SHARED = "shared";

    private final QdrantRagService ragService;
    private final int topK;
    private final double threshold;

    public RetrievalService(QdrantRagService ragService, RagProperties properties) {
        this.ragService = ragService;
        this.topK = properties.getRetrieval().getTopK();
        this.threshold = properties.getRetrieval().getSimilarityThreshold();
        log.info("RAG 检索配置: topK={}, 相似度阈值={}（来自 xiaozhi.rag.retrieval）", topK, threshold);
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

            SearchRequest request = SearchRequest.builder()
                    .query(question)
                    .topK(topK)
                    .similarityThreshold(threshold)
                    .filterExpression(filter)
                    .build();
            List<Document> documents = ragService.vectorStore().similaritySearch(request);
            return documents == null ? List.of() : documents;
        } catch (Exception e) {
            log.warn("RAG 检索失败，本轮不注入参考资料: {}", brief(e));
            return List.of();
        }
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
        for (Document document : documents) {
            String title = metaOf(document, "title", "未命名资料");
            String heading = metaOf(document, "heading", "");
            String url = metaOf(document, "url", "");
            sb.append('[').append(index++).append("] 《").append(title).append('》');
            if (!heading.isBlank()) {
                sb.append(" · ").append(heading);
            }
            sb.append('\n').append(safeText(document)).append('\n');
            if (!url.isBlank()) {
                sb.append("（来源：").append(url).append("）\n");
            }
            sb.append('\n');
        }
        String context = sb.toString().trim();
        log.debug("RAG 命中 {} 条，参考资料 {} 字符", documents.size(), context.length());
        return context;
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
            return text == null ? "" : text.trim();
        } catch (Exception e) {
            return "";
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