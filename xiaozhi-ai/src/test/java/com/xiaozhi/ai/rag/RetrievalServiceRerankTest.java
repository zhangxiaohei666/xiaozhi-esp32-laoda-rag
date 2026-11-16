package com.xiaozhi.ai.rag;

import com.xiaozhi.common.config.RagProperties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 钉住两段式检索（UP-17）的四条行为契约。
 *
 * <p>rerank 是第一个给对话路径引入外呼 API 的检索环节，评估前必须先用单测把边界钉死：
 * <ol>
 *   <li><b>关 = 基线</b>：rerank 关闭时请求形状（0.50@fetchK10）与合入前逐位一致 ——
 *       这是零回归兜底，评估 off 轮数字对不上时从这里查起；</li>
 *   <li><b>拒答门在重排之前</b>：候选全低于 0.50 → 整体不答，且一次 rerank API 都不调
 *       （垃圾问题的重排成本恒为零）；</li>
 *   <li><b>降级 = 基线行为</b>：rerank 失败返回 null 时，按 0.50 重过滤 + fetchK 截断 + 原序限额，
 *       与关掉 rerank 的输出等价 —— 绝不是"退回 0.35 池的原序"（那会把垃圾带进 top5）；</li>
 *   <li><b>精排后限额</b>：顺序以重排为准，每篇 ≤2 与 rerankTopK 在<b>精排之后</b>套用。</li>
 * </ol>
 */
class RetrievalServiceRerankTest {

    /** 造一条召回候选：docId + 余弦分 + 可识别正文的标记 */
    private static Document hit(String docId, double score, String marker) {
        return Document.builder()
                .text(marker + "-" + docId)
                .metadata(new HashMap<>(Map.of("doc_id", docId, "title", "t" + docId,
                        "heading", "", "source", "s.md")))
                .score(score)
                .build();
    }

    /** 把一条候选克隆成"经过重排"的样子（带 rerank_score，顺序即重排后的顺序） */
    private static Document withRerank(Document document, double rerankScore) {
        Map<String, Object> metadata = new HashMap<>(document.getMetadata());
        metadata.put(RagRerankService.METADATA_RERANK_SCORE, rerankScore);
        return Document.builder()
                .id(document.getId())
                .text(document.getText())
                .metadata(metadata)
                .score(document.getScore())
                .build();
    }

    /**
     * 构造被测服务：mock 向量库返回 {@code recall}，把真正发出的 SearchRequest
     * 存进 {@code captured}（测试要同时断言"返回了什么"和"请求长什么样"）。
     * rerank 用外部传入的 mock（测"失败降级"时让它的 rerank() 返回 null）。
     */
    private static RetrievalService service(RagProperties properties, List<Document> recall,
                                            RagRerankService rerank, AtomicReference<SearchRequest> captured) {
        QdrantRagService rag = mock(QdrantRagService.class);
        VectorStore store = mock(VectorStore.class);
        when(rag.isEnabled()).thenReturn(true);
        when(rag.vectorStore()).thenReturn(store);
        when(store.similaritySearch(any(SearchRequest.class))).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return recall;
        });
        return new RetrievalService(rag, properties, null, rerank);
    }

    @Test
    @DisplayName("rerank 关：请求仍按基线（0.50@fetchK10），rerank 不被调用")
    void disabledPathStaysBaseline() {
        AtomicReference<SearchRequest> captured = new AtomicReference<>();
        RagRerankService rerank = mock(RagRerankService.class);
        RetrievalService service = service(new RagProperties(), List.of(hit("2", 0.81, "开馆")), rerank, captured);

        List<Document> result = service.search("图书馆几点开门", 1);

        assertThat(result).hasSize(1);
        assertThat(captured.get().getTopK()).isEqualTo(10);
        assertThat(captured.get().getSimilarityThreshold()).isEqualTo(0.50);
        verify(rerank, never()).rerank(any(), anyList());
    }

    @Test
    @DisplayName("rerank 开：召回调宽（0.35@20），候选全低于拒答线 → 整体拒答且不调 rerank")
    void enabledRefusesWhenNoCandidatePassesGate() {
        AtomicReference<SearchRequest> captured = new AtomicReference<>();
        RagProperties properties = new RagProperties();
        properties.getRerank().setEnabled(true);
        RagRerankService rerank = mock(RagRerankService.class);
        List<Document> recall = List.of(hit("5", 0.49, "垃圾1"), hit("2", 0.38, "垃圾2"));
        RetrievalService service = service(properties, recall, rerank, captured);

        assertThat(service.search("快速排序的原理是什么", 1)).isEmpty();
        // 召回段形状：宽阈值 0.35、召回 20 条
        assertThat(captured.get().getTopK()).isEqualTo(20);
        assertThat(captured.get().getSimilarityThreshold()).isEqualTo(0.35);
        // 拒答门在重排之前：垃圾问题零 rerank 成本
        verify(rerank, never()).rerank(any(), anyList());
    }

    @Test
    @DisplayName("rerank 失败降级：按拒答线重过滤 + 原序限额 —— 与基线行为等价")
    void rerankFailureFallsBackToBaseline() {
        RagProperties properties = new RagProperties();
        properties.getRerank().setEnabled(true);
        RagRerankService rerank = mock(RagRerankService.class);
        when(rerank.rerank(any(), anyList())).thenReturn(null);
        // 候选含一条 0.45 的（过不了拒答线），其余 6 条过线分属 3 篇
        List<Document> recall = List.of(
                hit("5", 0.66, "a"), hit("5", 0.62, "b"), hit("3", 0.61, "c"),
                hit("3", 0.60, "d"), hit("9", 0.45, "垃圾"), hit("2", 0.59, "e"), hit("2", 0.58, "f"));
        AtomicReference<SearchRequest> captured = new AtomicReference<>();
        RetrievalService service = service(properties, recall, rerank, captured);

        List<Document> result = service.search("图书馆几点开门", 1);

        // 0.45 被 0.50 挡掉；每篇 ≤2 取 topK=5：a(5) b(5) c(3) d(3) e(2)，f 因 topK 截断
        assertThat(result)
                .extracting(d -> d.getMetadata().get("doc_id"))
                .containsExactly("5", "5", "3", "3", "2");
        // 降级路径没有 rerank_score —— 这也是调试口区分"重排生效/降级"的依据
        assertThat(result.get(0).getMetadata()).doesNotContainKey(RagRerankService.METADATA_RERANK_SCORE);
    }

    @Test
    @DisplayName("rerank 生效：顺序以重排为准、每篇限额在精排后套用、取 rerankTopK 条")
    void rerankSuccessUsesReorderedList() {
        RagProperties properties = new RagProperties();
        properties.getRerank().setEnabled(true);
        properties.getRerank().setTopK(3);
        RagRerankService rerank = mock(RagRerankService.class);
        // 召回按余弦序：doc5×2 → doc2 → doc3×2
        List<Document> recall = List.of(
                hit("5", 0.70, "a"), hit("5", 0.68, "b"), hit("2", 0.66, "c"),
                hit("3", 0.64, "d"), hit("3", 0.63, "e"));
        // 重排把 doc3 d 顶到第一；若限额在精排前，doc3 只能贡献 1 条就轮不到它排第一
        List<Document> reranked = List.of(
                withRerank(recall.get(3), 0.9),   // doc3 d
                withRerank(recall.get(2), 0.8),   // doc2 c
                withRerank(recall.get(0), 0.7),   // doc5 a
                withRerank(recall.get(4), 0.6),   // doc3 e
                withRerank(recall.get(1), 0.5));  // doc5 b
        when(rerank.rerank(any(), anyList())).thenReturn(reranked);
        AtomicReference<SearchRequest> captured = new AtomicReference<>();
        RetrievalService service = service(properties, recall, rerank, captured);

        List<Document> result = service.search("图书馆几点开门", 1);

        // rerankTopK=3 + 每篇 ≤2 在精排后套用：d(3) c(2) a(5)；e、b 被 topK 截掉
        assertThat(result)
                .extracting(d -> d.getMetadata().get("doc_id"))
                .containsExactly("3", "2", "5");
        // 余弦分原样保留（第一条余弦 0.64，不是 rerank 的 0.9）
        assertThat(result.get(0).getScore()).isEqualTo(0.64);
        assertThat(result.get(0).getMetadata().get(RagRerankService.METADATA_RERANK_SCORE))
                .isEqualTo(0.9);
    }

    @Test
    @DisplayName("过门但候选只 1 条：直接进截取，rerank 原样回传即透传")
    void singleCandidatePassesThrough() {
        RagProperties properties = new RagProperties();
        properties.getRerank().setEnabled(true);
        List<Document> recall = List.of(hit("2", 0.81, "开馆"));
        RagRerankService rerank = mock(RagRerankService.class);
        when(rerank.rerank(any(), anyList())).thenReturn(recall);
        AtomicReference<SearchRequest> captured = new AtomicReference<>();
        RetrievalService service = service(properties, recall, rerank, captured);

        assertThat(service.search("图书馆几点开门", 1)).hasSize(1);
    }

    @Test
    @DisplayName("缓存键指纹：关=off；开=参数串；任一参数变化键必变（A/B 不串味）")
    void fingerprintSeparatesRerankStates() {
        assertThat(RetrievalService.rerankFingerprint(false, "gte-rerank-v2", 20, 0.35, 5))
                .as("关闭态固定为 off").isEqualTo("off");

        String on = RetrievalService.rerankFingerprint(true, "gte-rerank-v2", 20, 0.35, 5);
        assertThat(on).isEqualTo("gte-rerank-v2:20:0.35:5");
        assertThat(RetrievalService.rerankFingerprint(true, "qwen3-rerank", 20, 0.35, 5))
                .as("换模型 = 换键").isNotEqualTo(on);
        assertThat(RetrievalService.rerankFingerprint(true, "gte-rerank-v2", 10, 0.35, 5))
                .as("改召回条数 = 换键").isNotEqualTo(on);
        assertThat(RetrievalService.rerankFingerprint(true, "gte-rerank-v2", 20, 0.40, 5))
                .as("改召回阈值 = 换键").isNotEqualTo(on);
        assertThat(RetrievalService.rerankFingerprint(true, "gte-rerank-v2", 20, 0.35, 3))
                .as("改注入条数 = 换键").isNotEqualTo(on);
    }
}
