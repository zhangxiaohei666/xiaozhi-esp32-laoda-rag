package com.xiaozhi.ai.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索词改写三层的静态行为契约（RAG 升级轮 2026-11-17）。
 *
 * <p>钉住四条最容易被改坏的底线：
 * <ul>
 *   <li>门控必须"宁漏勿杀"：表外的任何句子（包括复合句"你好，图书馆几点开门"）都必须放行，
 *       误杀真问题的代价远大于多查一次库；</li>
 *   <li>改写只影响检索词：触发条件、拼接顺序（上轮在前）、截断长度都要有契约，
 *       喂给模型的原句不受影响（那部分在调用方，不在这里）；</li>
 *   <li>同义词只追加公认别名，一个都不多补；</li>
 *   <li>倒扫上一条问句必须跳过"与当前同文本"的消息（当前消息可能已入列）。</li>
 * </ul>
 */
class RagQueryRewriteTest {

    // ── 门控 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("整句寒暄、纯标点、空白/null 都被门控（标点被归一化剥掉）")
    void smallTalkIsGated() {
        assertThat(RagQueryRewrite.isSmallTalk("你好")).isTrue();
        assertThat(RagQueryRewrite.isSmallTalk("谢谢！")).isTrue();
        assertThat(RagQueryRewrite.isSmallTalk("好的。。。")).isTrue();
        assertThat(RagQueryRewrite.isSmallTalk("嗯嗯")).isTrue();
        assertThat(RagQueryRewrite.isSmallTalk("继续讲")).isTrue();
        // 纯标点/空白/空
        assertThat(RagQueryRewrite.isSmallTalk("？？？")).isTrue();
        assertThat(RagQueryRewrite.isSmallTalk("   ")).isTrue();
        assertThat(RagQueryRewrite.isSmallTalk("")).isTrue();
        assertThat(RagQueryRewrite.isSmallTalk(null)).isTrue();
    }

    @Test
    @DisplayName("宁漏勿杀：复合句和长应答句不放行就没法检索了")
    void compositeQuestionIsNotGated() {
        // 表里最危险的邻居：寒暄词 + 真问题的复合句
        assertThat(RagQueryRewrite.isSmallTalk("你好，图书馆几点开门")).isFalse();
        assertThat(RagQueryRewrite.isSmallTalk("谢谢，那再问下座位怎么预约")).isFalse();
        // 寒暄词后跟内容的"继续"
        assertThat(RagQueryRewrite.isSmallTalk("继续讲一下图书馆的预约规则")).isFalse();
        // 表外语气词（宁漏勿杀：宁可多查一次库）
        assertThat(RagQueryRewrite.isSmallTalk("嗯嗯嗯")).isFalse();
    }

    // ── 指代补全 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("短问句/指代开头且有上轮 → 上轮（截 80 字）拼在前")
    void shortOrAnaphoricQuestionIsRewritten() {
        // 指代开头（那/这/它/他/她）
        assertThat(RagQueryRewrite.rewriteForRetrieval("那周日呢", "图书馆周六几点开门"))
                .isEqualTo("图书馆周六几点开门 那周日呢");
        // ≤12 字的完整短句也拼上轮（语音场景省略追问是常态）
        assertThat(RagQueryRewrite.rewriteForRetrieval("几点闭馆", "图书馆周六几点开门"))
                .isEqualTo("图书馆周六几点开门 几点闭馆");
        // 指代开头、超过 12 字同样触发
        String longFollowUp = "那考试周的座位是不是也要重新预约一遍";
        assertThat(RagQueryRewrite.rewriteForRetrieval(longFollowUp, "座位怎么预约"))
                .isEqualTo("座位怎么预约 " + longFollowUp);
        // 上轮超过 80 字截断再拼
        String longPrev = "图".repeat(90);
        assertThat(RagQueryRewrite.rewriteForRetrieval("那周日呢", longPrev))
                .isEqualTo("图".repeat(80) + " 那周日呢");
    }

    @Test
    @DisplayName("无上轮、或长问句且非指代开头 → 原样返回不拼接")
    void noPrevOrLongStandaloneQuestionUnchanged() {
        assertThat(RagQueryRewrite.rewriteForRetrieval("那周日呢", null)).isEqualTo("那周日呢");
        assertThat(RagQueryRewrite.rewriteForRetrieval("那周日呢", "   ")).isEqualTo("那周日呢");
        String longStandalone = "图书馆的开馆时间具体是怎么规定的需要注意什么";
        assertThat(RagQueryRewrite.rewriteForRetrieval(longStandalone, "图书馆周六几点开门"))
                .isEqualTo(longStandalone);
    }

    // ── 同义词 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("命中同组任一词追加其余成员；不命中原样返回")
    void synonymsAppendedOncePerGroup() {
        assertThat(RagQueryRewrite.expandSynonyms("饭卡丢了怎么办"))
                .isEqualTo("饭卡丢了怎么办 一卡通 校园卡");
        assertThat(RagQueryRewrite.expandSynonyms("校园卡怎么充值"))
                .isEqualTo("校园卡怎么充值 饭卡 一卡通");
        // 同组两个词都出现时只补缺的那个，不重复追加
        assertThat(RagQueryRewrite.expandSynonyms("一卡通和校园卡是一回事吗"))
                .isEqualTo("一卡通和校园卡是一回事吗 饭卡");
        // 不命中 → 原样
        assertThat(RagQueryRewrite.expandSynonyms("图书馆几点开门")).isEqualTo("图书馆几点开门");
        assertThat(RagQueryRewrite.expandSynonyms("")).isEqualTo("");
        assertThat(RagQueryRewrite.expandSynonyms(null)).isEqualTo("");
    }

    @Test
    @DisplayName("组合入口：先指代补全再同义词扩展")
    void retrievalQueryCombinesRewriteAndSynonyms() {
        assertThat(RagQueryRewrite.retrievalQuery("饭卡丢了怎么办", "图书馆几点开门"))
                .isEqualTo("图书馆几点开门 饭卡丢了怎么办 一卡通 校园卡");
        // 无上轮只做同义词扩展
        assertThat(RagQueryRewrite.retrievalQuery("饭卡丢了怎么办", null))
                .isEqualTo("饭卡丢了怎么办 一卡通 校园卡");
    }

    // ── 倒扫上一条问句 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("倒扫跳过与当前同文本的用户消息，返回上一条问句")
    void previousUserQuestionSkipsCurrentAndScansBack() {
        List<Message> messages = List.of(
                new SystemMessage("系统提示词"),
                new UserMessage("图书馆周六几点开门"),
                new AssistantMessage("周六 8 点开门"),
                new UserMessage("那周日呢"));
        // 当前问句已入列（TextChatService 先 add 再检索）：跳过同文本者，取上一条
        assertThat(RagQueryRewrite.previousUserQuestion(messages, "那周日呢"))
                .isEqualTo("图书馆周六几点开门");
        // 当前问句还没入列（Persona 打断路径）：照常取上一条
        assertThat(RagQueryRewrite.previousUserQuestion(
                List.of(new UserMessage("图书馆周六几点开门")), "那周日呢"))
                .isEqualTo("图书馆周六几点开门");
        // 文本带首尾空白 → trim 后参与比对与返回
        assertThat(RagQueryRewrite.previousUserQuestion(
                List.of(new UserMessage("  图书馆周六几点开门  ")), "那周日呢"))
                .isEqualTo("图书馆周六几点开门");
    }

    @Test
    @DisplayName("没有可返回的上一条问句时给 null")
    void previousUserQuestionReturnsNullWhenAbsent() {
        // 只剩与当前同文本的一条（不该把当前问句当上轮）
        assertThat(RagQueryRewrite.previousUserQuestion(
                List.of(new UserMessage("那周日呢")), "那周日呢")).isNull();
        assertThat(RagQueryRewrite.previousUserQuestion(List.of(), "那周日呢")).isNull();
        assertThat(RagQueryRewrite.previousUserQuestion(null, "那周日呢")).isNull();
        // 只有非用户消息
        assertThat(RagQueryRewrite.previousUserQuestion(
                List.of(new SystemMessage("系统提示词"), new AssistantMessage("回答")), "那周日呢")).isNull();
    }
}
