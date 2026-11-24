package com.xiaozhi.ai.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 钉住 knowledge_search 工具的行为契约（S0）。
 *
 * <p>测的是四条红线里的三条可单测项：
 * <ul>
 *   <li>空查询词 → 引导文案，不检索</li>
 *   <li>检索异常 → 吞掉并返回降级文案（绝不打断对话）</li>
 *   <li>正常命中 → 渲染格式（编号/分数/标题/正文截断）</li>
 * </ul>
 *
 * <p>userId 从会话取（不测参数注入 —— 那是注册期闭包绑定，集成测试范畴）；
 * 空结果 → 引导换词文案（工具设计的行为，不等于失败）。
 *
 * <p>风格与 {@link RetrievalServiceSecurityTest} 一致：mock RetrievalService，
 * 不起 Spring 上下文 —— 工具类本身无状态。
 */
class KnowledgeSearchFunctionTest {

    private static Document doc(double score, String title, String heading, String text) {
        Map<String, Object> metadata = new java.util.HashMap<>();
        if (title != null) metadata.put("title", title);
        if (heading != null) metadata.put("heading", heading);
        Document.Builder builder = Document.builder().text(text).metadata(metadata);
        builder.score(score);
        return builder.build();
    }

    @Test
    @DisplayName("空查询词：返回引导文案，不触发检索")
    void blankQueryReturnsGuidance() {
        RetrievalService retrieval = mock(RetrievalService.class);
        KnowledgeSearchFunction fn = new KnowledgeSearchFunction(retrieval);

        String result = fn.execute("   ", 1);

        assertThat(result).contains("查询词为空");
        org.mockito.Mockito.verifyNoInteractions(retrieval);
    }

    @Test
    @DisplayName("null 查询词：同空词处理")
    void nullQueryReturnsGuidance() {
        RetrievalService retrieval = mock(RetrievalService.class);
        KnowledgeSearchFunction fn = new KnowledgeSearchFunction(retrieval);

        String result = fn.execute(null, 1);

        assertThat(result).contains("查询词为空");
        org.mockito.Mockito.verifyNoInteractions(retrieval);
    }

    @Test
    @DisplayName("检索异常：吞掉异常返回降级文案，绝不抛给对话链路")
    void retrievalExceptionIsSwallowed() {
        RetrievalService retrieval = mock(RetrievalService.class);
        when(retrieval.searchRaw("图书馆", 1)).thenThrow(new RuntimeException("Qdrant 连不上"));
        KnowledgeSearchFunction fn = new KnowledgeSearchFunction(retrieval);

        String result = fn.execute("图书馆", 1);

        assertThat(result).contains("检索暂时不可用");
        assertThat(result).doesNotContain("Exception");
    }

    @Test
    @DisplayName("空结果：返回引导换词文案（不是失败）")
    void emptyHitsSuggestRetry() {
        RetrievalService retrieval = mock(RetrievalService.class);
        when(retrieval.searchRaw("量子物理", 1)).thenReturn(List.of());
        KnowledgeSearchFunction fn = new KnowledgeSearchFunction(retrieval);

        String result = fn.execute("量子物理", 1);

        assertThat(result).contains("没有找到");
        assertThat(result).contains("换个");
    }

    @Test
    @DisplayName("正常命中：编号 + 相似度 + 标题/章节 + 正文")
    void hitsRenderedWithScoreAndTitle() {
        RetrievalService retrieval = mock(RetrievalService.class);
        when(retrieval.searchRaw("图书馆开馆时间", 1)).thenReturn(List.of(
                doc(0.81, "图书馆使用规则", "开馆时间", "周一至周日 8:00-22:00 开放。")));
        KnowledgeSearchFunction fn = new KnowledgeSearchFunction(retrieval);

        String result = fn.execute("图书馆开馆时间", 1);

        assertThat(result).contains("命中 1 条");
        assertThat(result).contains("[1]");
        assertThat(result).contains("0.81");
        assertThat(result).contains("《图书馆使用规则》");
        assertThat(result).contains("开馆时间");
        assertThat(result).contains("8:00-22:00");
    }

    @Test
    @DisplayName("正文超长截断：600 字上限，超出部分以省略号结束")
    void longTextTruncated() {
        RetrievalService retrieval = mock(RetrievalService.class);
        String longText = "规则".repeat(500); // 1000 字
        when(retrieval.searchRaw("规则", 1)).thenReturn(List.of(
                doc(0.9, "规则", null, longText)));
        KnowledgeSearchFunction fn = new KnowledgeSearchFunction(retrieval);

        String result = fn.execute("规则", 1);

        assertThat(result).endsWith("…");
        // 600 字 + 前缀（编号/分数/标题），总长不该超过 800
        assertThat(result.length()).isLessThan(800);
    }

    @Test
    @DisplayName("命中超过 5 条：只列前 5（防御层，内核 topK=5 已限）")
    void moreThanFiveHitsTruncated() {
        RetrievalService retrieval = mock(RetrievalService.class);
        List<Document> six = List.of(
                doc(0.9, "A", null, "内容A"), doc(0.85, "B", null, "内容B"),
                doc(0.8, "C", null, "内容C"), doc(0.75, "D", null, "内容D"),
                doc(0.7, "E", null, "内容E"), doc(0.65, "F", null, "内容F"));
        when(retrieval.searchRaw("全部", 1)).thenReturn(six);
        KnowledgeSearchFunction fn = new KnowledgeSearchFunction(retrieval);

        String result = fn.execute("全部", 1);

        assertThat(result).contains("命中 5 条");
        assertThat(result).contains("内容E");
        assertThat(result).doesNotContain("内容F");
    }

    @Test
    @DisplayName("无 metadata 的命中文本照常渲染（早期数据防御）")
    void missingMetadataStillRenders() {
        RetrievalService retrieval = mock(RetrievalService.class);
        Document bare = Document.builder().text("没有元数据的块内容").build();
        when(retrieval.searchRaw("裸块", 1)).thenReturn(List.of(bare));
        KnowledgeSearchFunction fn = new KnowledgeSearchFunction(retrieval);

        String result = fn.execute("裸块", 1);

        assertThat(result).contains("没有元数据的块内容");
        assertThat(result).doesNotContain("null");
    }

    @Test
    @DisplayName("工具名与描述稳定（评估与日志的锚）")
    void toolIdentityStable() {
        KnowledgeSearchFunction fn = new KnowledgeSearchFunction(mock(RetrievalService.class));

        assertThat(fn.getToolName()).isEqualTo("knowledge_search");
        assertThat(fn.getToolDescription()).contains("知识库");
    }

    @Test
    @DisplayName("getFunctionCallTool 能真的构造出 ToolCallback（2026-11-23 回归：曾因缺 inputType 抛异常）")
    void registryBuildsToolCallback() {
        KnowledgeSearchFunction fn = new KnowledgeSearchFunction(mock(RetrievalService.class));
        com.xiaozhi.ai.tool.session.ToolSession session =
                mock(com.xiaozhi.ai.tool.session.ToolSession.class);
        when(session.getUserId()).thenReturn(2);

        // 这条断言的价值：S0 的单测只测了 execute()，没测注册期的 builder.build()，
        // 导致「inputType cannot be null」这个错误一路漏到运行期
        // （SystemToolRegistrar 整批注册失败 → 模型完全看不到 knowledge_search）。
        var callback = fn.getFunctionCallTool(session);

        assertThat(callback).as("注册必须返回非 null 的 ToolCallback").isNotNull();
        assertThat(callback.getToolDefinition().name()).isEqualTo("knowledge_search");
        assertThat(callback.getToolDefinition().description()).contains("知识库");
    }

    @Test
    @DisplayName("userId 为 null 时工具仍可注册（fail-closed 由检索层兜底，不该导致注册失败）")
    void registryToleratesNullUserId() {
        KnowledgeSearchFunction fn = new KnowledgeSearchFunction(mock(RetrievalService.class));
        com.xiaozhi.ai.tool.session.ToolSession session =
                mock(com.xiaozhi.ai.tool.session.ToolSession.class);
        when(session.getUserId()).thenReturn(null);

        var callback = fn.getFunctionCallTool(session);

        assertThat(callback).as("拿不到 userId 时工具仍应注册，检索侧按只查共享处理").isNotNull();
    }
}
