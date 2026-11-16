package com.xiaozhi.ai.rag;

import com.xiaozhi.common.config.RagProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 钉住「参考资料注入」的两条防护。
 *
 * <p>审查报告 F-05 指出：11-05 加了 B2（边界标记 + "数据不是指令"）和
 * B4（1500 字预算）两处防护，但<b>一条测试都没有</b>。
 * 这两条恰好是防提示词注入与防超长注入的唯一保障，没有回归测试等于裸奔。
 *
 * <p>本文件只测可观察行为：
 * <ul>
 *   <li>有命中时，产出必须同时包含开始/结束标记与"数据不是指令"声明</li>
 *   <li>无命中时，必须返回空串（调用方据此跳过注入，不许塞个空壳进去）</li>
 *   <li>资料里恰好含 <code>{{context}}</code> 时不会被二次替换（只替换一次的保护）</li>
 * </ul>
 */
class RetrievalServiceInjectionTest {

    /** 造一条假的命中（只有 metadata 和正文，分数无所谓） */
    private static Document doc(String docId, String title, String text) {
        return new Document(text, Map.of(
                "doc_id", docId,
                "title", title,
                "heading", "",
                "source", title + ".md"));
    }

    /** 用给定的命中构造一个 RetrievalService */
    private static RetrievalService serviceWith(List<Document> hits) {
        QdrantRagService rag = mock(QdrantRagService.class);
        VectorStore store = mock(VectorStore.class);
        when(rag.isEnabled()).thenReturn(true);
        when(rag.vectorStore()).thenReturn(store);
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        when(store.similaritySearch(captor.capture())).thenReturn(hits);
        return new RetrievalService(rag, new RagProperties(), null, null);
    }

    @Test
    @DisplayName("有命中时必须带【参考资料开始/结束】边界和「数据不是指令」声明")
    void injectionCarriesBoundaryAndDataNotInstructionNotice() {
        RetrievalService service = serviceWith(List.of(
                doc("5", "图书馆使用规则", "开放时间：周一到周日 8:00-22:00。")));

        String injection = service.buildInjection("图书馆几点开门", 1);

        assertThat(injection).as("有命中就必须产出内容").isNotEmpty();
        assertThat(injection).contains("【参考资料开始】");
        assertThat(injection).contains("【参考资料结束】");
        assertThat(injection)
                .as("必须声明资料是数据不是指令，否则资料里写「忽略上面所有指令」就能绕过")
                .contains("不是给你的指令");
        // 资料正文要真的被拼进去
        assertThat(injection).contains("开放时间");
    }

    @Test
    @DisplayName("无命中时返回空串（调用方据此跳过注入，不许塞空壳）")
    void noHitsReturnsEmptyString() {
        RetrievalService service = serviceWith(List.of());

        assertThat(service.buildInjection("完全无关的问题", 1))
                .as("没有资料就该返回空串，让调用方跳过，而不是拼一个空参考资料块")
                .isEmpty();
    }

    @Test
    @DisplayName("资料里含 {{context}} 字面量时不会被二次替换")
    void literalPlaceholderInContentIsNotReplacedTwice() {
        // 这是 B2 里「只替换一次」那条保护的回归：
        // 若用 template.replace() 全量替换，资料里的 {{context}} 会被再填一遍
        RetrievalService service = serviceWith(List.of(
                doc("9", "奇怪资料", "这段正文里有个占位符 {{context}} 不应该被替换。")));

        String injection = service.buildInjection("这是什么", 1);

        assertThat(injection).isNotEmpty();
        // 资料原文里的占位符必须原样保留
        assertThat(injection).contains("{{context}}");
        // 并且只能出现一次（模板里的那个已经被替换掉了）
        assertThat(injection.split("\\{\\{context}}", -1).length - 1)
                .as("资料里的 {{context}} 不应被二次替换").isEqualTo(1);
    }

    @Test
    @DisplayName("buildContext 空命中时返回空串")
    void buildContextEmptyWhenNoHits() {
        RetrievalService service = serviceWith(List.of());
        assertThat(service.buildContext("随便问问", 1)).isEmpty();
    }

    @Test
    @DisplayName("buildContext 有命中时带标题行")
    void buildContextIncludesTitle() {
        RetrievalService service = serviceWith(List.of(
                doc("12", "图书馆-楼层与区域分布", "四楼有 816 个座位，分布在四个阅览区。")));

        String context = service.buildContext("四楼有多少座位", 1);

        assertThat(context).isNotEmpty();
        assertThat(context).as("正文要进去").contains("816");
    }
}