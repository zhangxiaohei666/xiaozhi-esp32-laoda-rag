package com.xiaozhi.ai.rag;

import com.xiaozhi.common.config.RagProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 钉住「检索的用户隔离」这条安全底线。
 *
 * <p>为什么这条比清洗、限额都重要：
 * 这是多用户场景下唯一防止「A 的资料被 B 查到」的防线。
 * {@link RetrievalService#search(String, Integer)} 在 {@code userId == null} 时
 * <b>必须</b>只查 {@code scope=shared}，一旦这里写成「不过滤」，
 * 所有用户的私有资料会互相穿透。
 *
 * <p>审查报告 F-05 指出：11-05 补的 38 个单测里，
 * 清洗和限额都测了，唯独这条安全底线一条用例都没有。
 * 而 {@code RetrievalServiceCleanTest} 只测静态方法，够不到这条逻辑 ——
 * 所以本文件单独建，用 Mockito 捕获传给向量库的 {@code SearchRequest}。
 */
class RetrievalServiceSecurityTest {

    /** 造一个 enabled 的 mock，并捕获 similaritySearch 收到的请求 */
    private static SearchRequest captureRequest(Integer userId) {
        QdrantRagService rag = mock(QdrantRagService.class);
        VectorStore store = mock(VectorStore.class);
        when(rag.isEnabled()).thenReturn(true);
        when(rag.vectorStore()).thenReturn(store);

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        when(store.similaritySearch(captor.capture())).thenReturn(List.of());

        RetrievalService service = new RetrievalService(rag, new RagProperties(), null);
        service.search("图书馆几点开门", userId);

        SearchRequest request = captor.getValue();
        assertThat(request).as("检索请求必须真的发到向量库，否则这个测试什么都没验到").isNotNull();
        return request;
    }

    @Test
    @DisplayName("userId 为 null 时只查共享资料（绝不可退化成不过滤）")
    void nullUserIdQueriesOnlySharedScope() {
        SearchRequest request = captureRequest(null);

        var filter = request.getFilterExpression();
        assertThat(filter).as("userId 缺失时必须带 scope 过滤，不能裸查").isNotNull();

        String text = filter.toString();
        assertThat(text).as("过滤条件必须限定在 shared 上").contains("shared");
        assertThat(text)
                .as("userId 缺失时绝不能出现 user_id 条件 —— 那意味着查询范围失控")
                .doesNotContain("user_id");
    }

    @Test
    @DisplayName("有 userId 时查「自己的 + 共享的」两份")
    void userIdQueriesOwnAndShared() {
        SearchRequest request = captureRequest(42);

        var filter = request.getFilterExpression();
        assertThat(filter).as("有 userId 时同样必须带过滤").isNotNull();

        String text = filter.toString();
        assertThat(text).as("必须包含 user_id 条件").contains("user_id");
        assertThat(text).as("userId 的值要进过滤条件").contains("42");
        assertThat(text).as("同时要带 shared，否则查不到公共资料").contains("shared");
    }

    @Test
    @DisplayName("RAG 关闭时不发请求（避免无谓外呼）")
    void disabledRagReturnsEmptyWithoutQuerying() {
        QdrantRagService rag = mock(QdrantRagService.class);
        when(rag.isEnabled()).thenReturn(false);

        RetrievalService service = new RetrievalService(rag, new RagProperties(), null);
        assertThat(service.search("图书馆几点开门", 42)).isEmpty();

        // isEnabled=false 时连 vectorStore() 都不该碰
        org.mockito.Mockito.verify(rag, org.mockito.Mockito.never()).vectorStore();
    }

    @Test
    @DisplayName("空问题不发请求")
    void blankQuestionReturnsEmpty() {
        QdrantRagService rag = mock(QdrantRagService.class);
        RetrievalService service = new RetrievalService(rag, new RagProperties(), null);

        assertThat(service.search("", 42)).isEmpty();
        assertThat(service.search("   ", 42)).isEmpty();
        assertThat(service.search(null, 42)).isEmpty();

        org.mockito.Mockito.verify(rag, org.mockito.Mockito.never()).vectorStore();
    }
}