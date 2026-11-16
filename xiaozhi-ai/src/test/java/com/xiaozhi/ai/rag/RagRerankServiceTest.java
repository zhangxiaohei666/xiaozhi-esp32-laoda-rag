package com.xiaozhi.ai.rag;

import com.xiaozhi.common.config.RagProperties;
import com.xiaozhi.common.port.ConfigLookup;

import com.alibaba.dashscope.rerank.TextReRankOutput;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link RagRerankService} 的纯逻辑回归。
 *
 * <p><b>为什么单测不碰 HTTP</b>：SDK 调用链（端点/key/模型/响应 schema）已经用真 key 对真端点
 * 冒烟验证过（2026-11-17，4 次调用全通，证据留在 .run/tmp/rerank-smoke/），上线后由评估轮
 * 端到端覆盖 —— 单测只钉<b>本类自己写的逻辑</b>：重排映射的防御规则、超时包装、守门短路。
 *
 * <p>重点钉三条契约：
 * <ol>
 *   <li>{@code applyResults} 对畸形响应（越界/重复/缺 index/缺 score/漏条目）的消化规则；
 *       漏掉的候选必须按原序补尾 —— 一条都不许丢。</li>
 *   <li>{@code Document.score}（余弦）在重建后<b>原样保留</b> —— 它是评估 top1 均分口径的锚，
 *       被 rerank 分覆盖过一次就会让整个评估口径作废。</li>
 *   <li>守门短路：关→null；候选&lt;2→原样；key 缺失→null。全部不许抛异常。</li>
 * </ol>
 */
class RagRerankServiceTest {

    private static Document doc(String docId, String text, Double score) {
        Document.Builder builder = Document.builder()
                .text(text)
                .metadata(new HashMap<>(Map.of("doc_id", docId, "title", "t-" + docId)));
        if (score != null) {
            builder.score(score);
        }
        return builder.build();
    }

    /** 构造一条服务端 result（SDK 类有公有 setter，直接造，不需要外呼） */
    private static TextReRankOutput.Result result(int index, Double score) {
        TextReRankOutput.Result r = new TextReRankOutput.Result();
        r.setIndex(index);
        r.setRelevanceScore(score);
        return r;
    }

    private static RagRerankService service(boolean enabled) {
        RagProperties properties = new RagProperties();
        properties.getRerank().setEnabled(enabled);
        return new RagRerankService(properties, mock(ConfigLookup.class));
    }

    @Test
    @DisplayName("全量映射：按服务端序重排，rerank_score 进 metadata，余弦分保持")
    void applyResultsReordersAndKeepsCosineScore() {
        List<Document> candidates = List.of(
                doc("a", "甲", 0.61), doc("b", "乙", 0.60), doc("c", "丙", 0.59));
        // 服务端认为 丙 > 甲 > 乙
        List<Document> ordered = RagRerankService.applyResults(candidates, List.of(
                result(2, 0.9), result(0, 0.8), result(1, 0.7)));

        assertThat(ordered)
                .extracting(d -> d.getMetadata().get("doc_id"))
                .containsExactly("c", "a", "b");
        assertThat(ordered.get(0).getScore())
                .as("余弦分绝不能被 rerank 分覆盖（评估口径的锚）")
                .isEqualTo(0.59);
        assertThat(ordered.get(0).getMetadata().get(RagRerankService.METADATA_RERANK_SCORE))
                .isEqualTo(0.9);
        // id 与正文也要原样带过去
        assertThat(ordered.get(0).getText()).isEqualTo("丙");
    }

    @Test
    @DisplayName("缺项防御：没被服务端提到的候选按原序补尾，一条不丢")
    void applyResultsAppendsUnrankedAtTail() {
        List<Document> candidates = List.of(
                doc("a", "1", 0.6), doc("b", "2", 0.55), doc("c", "3", 0.5), doc("d", "4", 0.45));
        // 服务端只回了 c 和 a —— b、d 必须按原序补在尾部
        List<Document> ordered = RagRerankService.applyResults(candidates, List.of(
                result(2, 0.9), result(0, 0.8)));

        assertThat(ordered)
                .extracting(d -> d.getMetadata().get("doc_id"))
                .containsExactly("c", "a", "b", "d");
    }

    @Test
    @DisplayName("畸形防御：越界/重复/缺 index 的 result 一律忽略")
    void applyResultsIgnoresMalformedEntries() {
        List<Document> candidates = List.of(doc("a", "1", 0.6), doc("b", "2", 0.55));
        // Arrays.asList 允许 null 元素（List.of 会 NPE）
        List<Document> ordered = RagRerankService.applyResults(candidates, Arrays.asList(
                result(5, 0.9),                        // 越界
                result(-1, 0.8),                       // 越界
                result(0, 0.7),
                result(0, 0.6),                        // 重复 index
                null,                                  // null 元素
                new TextReRankOutput.Result()));       // index 为 null

        assertThat(ordered)
                .extracting(d -> d.getMetadata().get("doc_id"))
                .containsExactly("a", "b");
    }

    @Test
    @DisplayName("relevance_score 为 null：顺序照用，metadata 不写 rerank_score")
    void applyResultsToleratesNullScore() {
        List<Document> candidates = List.of(doc("a", "1", 0.6), doc("b", "2", 0.55));
        List<Document> ordered = RagRerankService.applyResults(candidates, List.of(
                result(1, null), result(0, null)));

        assertThat(ordered)
                .extracting(d -> d.getMetadata().get("doc_id"))
                .containsExactly("b", "a");
        assertThat(ordered.get(0).getMetadata())
                .doesNotContainKey(RagRerankService.METADATA_RERANK_SCORE);
    }

    @Test
    @DisplayName("开关关闭：rerank 返回 null = 调用方走基线，且不碰 ConfigLookup")
    void rerankReturnsNullWhenDisabled() {
        RagRerankService service = service(false);
        assertThat(service.rerank("问题", List.of(doc("a", "1", 0.6), doc("b", "2", 0.5)))).isNull();
        assertThat(service.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("候选不足 2 条：原样返回不调 API（1 条不存在顺序）")
    void rerankReturnsCandidatesWhenFewerThanTwo() {
        RagRerankService service = service(true);
        List<Document> single = List.of(doc("a", "1", 0.6));
        assertThat(service.rerank("问题", single)).isSameAs(single);
        assertThat(service.rerank("问题", List.of())).isEmpty();
    }

    @Test
    @DisplayName("拿不到 key（embedding 配置缺失）：退基线返回 null，绝不抛")
    void rerankReturnsNullWhenKeyMissing() {
        RagProperties properties = new RagProperties();
        properties.getRerank().setEnabled(true);
        ConfigLookup lookup = mock(ConfigLookup.class);
        when(lookup.getDefaultConfig("llm", "embedding")).thenReturn(null);
        RagRerankService service = new RagRerankService(properties, lookup);

        assertThat(service.rerank("问题", List.of(doc("a", "1", 0.6), doc("b", "2", 0.5)))).isNull();
    }

    @Test
    @DisplayName("withTimeout：超时返回 null，调用方立即拿回控制权")
    void withTimeoutReturnsNullOnTimeout() {
        long start = System.nanoTime();
        String value = RagRerankService.withTimeout(80, () -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return "slow";
        });
        assertThat(value).as("超时即 null，走降级").isNull();
        // 上限取 1000ms（≈超时 80ms 的 12 倍余量）：功能坏了会等满 2000ms，
        // 之前用 500ms 睡眠 + 400ms 上限在构建后高负载机器上实测抖到 441ms 误报过
        assertThat(System.nanoTime() - start)
                .as("80ms 超时后必须立即返回，不能等满 2000ms")
                .isLessThan(1_000_000_000L);
    }

    @Test
    @DisplayName("withTimeout：正常路径原样返回值")
    void withTimeoutReturnsValue() {
        assertThat(RagRerankService.withTimeout(1000, () -> "ok")).isEqualTo("ok");
    }

    @Test
    @DisplayName("withTimeout：动作异常原样抛出（由 rerank 的 catch 统一降级）")
    void withTimeoutPropagatesActionFailure() {
        assertThatThrownBy(() -> RagRerankService.withTimeout(1000, () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
    }
}
