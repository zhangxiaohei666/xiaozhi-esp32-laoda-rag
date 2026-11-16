package com.xiaozhi.ai.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Set;

/**
 * 检索词改写（RAG 升级轮 2026-11-17）：寒暄门控、多轮指代补全、同义词扩展。
 *
 * <p>★ 设计红线：<b>只改"送 embedding 的检索词"，不改喂给模型的原句。</b>
 * 模型看到的永远是用户原话，改写只影响检索这一侧 —— 这样改写永远不可能污染对话，
 * 最坏情况只是"这一轮检索没帮上忙"。
 *
 * <p>四个静态能力，供 {@link RetrievalService#buildInjection(String, Integer, String)} 组合调用：
 * <ul>
 *   <li>{@link #isSmallTalk}：寒暄/纯标点门控（宁漏勿杀：拿不准就检索）</li>
 *   <li>{@link #rewriteForRetrieval}：指代短句拼上轮问句（"那周日呢" → "图书馆周六几点开门 那周日呢"）</li>
 *   <li>{@link #expandSynonyms}：同义词组命中追加（饭卡=一卡通=校园卡）</li>
 *   <li>{@link #previousUserQuestion}：从裸消息列表倒扫上一条用户问句</li>
 * </ul>
 *
 * <p>为什么不用 LLM 做改写：语音链路每轮多一次 LLM 调用 = 多 1~2 秒首字延迟，
 * 而校园场景的指代模式高度固定（时间/地点/主题的省略追问），规则改写已够用。
 */
@Slf4j
public final class RagQueryRewrite {

    private RagQueryRewrite() {
    }

    /**
     * 寒暄白名单（归一化后精确匹配，{@link #normalize}）。
     *
     * <p>收录标准：<b>整句说完就没有检索意义</b>的应答/告别/确认词。
     * "你好，图书馆几点开门"这类复合句不会命中（归一化后是长串，不在表里），
     * 所以表可以放心收长一点 —— 漏收的代价只是多查一次库，误收的代价才是跳过真问题。
     */
    private static final Set<String> SMALL_TALK = Set.of(
            // 打招呼
            "你好", "您好", "早", "早上好", "中午好", "下午好", "晚上好", "晚安",
            "在吗", "在不在", "在不",
            // 应答确认
            "嗯", "嗯嗯", "哦", "哦哦", "噢", "啊", "好的", "好呀", "好嘞", "好滴",
            "行", "行吧", "行了", "可以", "收到", "明白了", "知道了", "了解", "懂了", "清楚",
            "没事", "没问题", "不用了",
            // 感谢
            "谢谢", "多谢", "谢啦", "感谢", "辛苦了",
            // 告别
            "再见", "拜拜", "回头见", "回见", "明天见",
            // 让模型继续（上轮内容在对话历史里，不需要新检索）
            "继续", "继续讲", "继续说", "继续吧", "接着讲", "接着说", "接着吧", "讲下去", "说下去",
            // 笑声/语气
            "哈哈", "哈哈哈", "哈哈哈哈", "呵呵", "嘿", "嘿嘿", "呢", "嗯呢");

    /**
     * 同义词组：只收"同一事物的公认别名"。
     * 追加错词会把检索带偏，宁可不加 —— 不收"可能是也可能不是"的近义词。
     */
    private static final String[][] SYNONYM_GROUPS = {
            { "饭卡", "一卡通", "校园卡" },
    };

    /** 改写触发上限：问句不超过这个字数且存在上轮问句时，拼上轮补全指代 */
    private static final int SHORT_QUESTION_CHARS = 12;

    /** 指代开头：这些词起头的问句几乎必然省略了主语/主题 */
    private static final String[] ANAPHORA_PREFIXES = { "那", "这", "它", "他", "她" };

    /** 上轮问句参与拼接前的截断长度 */
    private static final int PREV_QUESTION_CHARS = 80;

    /**
     * 门控：整句是寒暄/纯标点/空白时返回 true（调用方据此跳过检索）。
     *
     * <p>归一化 = 去掉全部字母数字以外的字符后精确匹配。"你好！！！"归一化后是"你好"，命中；
     * "你好，图书馆几点开门"归一化后是"你好图书馆几点开门"，不命中 —— 照常检索。
     */
    public static boolean isSmallTalk(String question) {
        if (question == null || question.isBlank()) {
            return true;
        }
        String normalized = normalize(question);
        return normalized.isEmpty() || SMALL_TALK.contains(normalized);
    }

    /**
     * 多轮指代补全：触发条件满足且有上轮问句时，把上轮（截 {@value #PREV_QUESTION_CHARS} 字）拼在当前问句前面。
     *
     * <p>触发条件（满足其一）：
     * <ul>
     *   <li>问句 ≤ {@value #SHORT_QUESTION_CHARS} 字 —— 语音场景短问句几乎都是省略追问</li>
     *   <li>以 那/这/它/他/她 开头 —— 明确的指代句式</li>
     * </ul>
     * embedding 对拼接文本算相似度时两侧语义都会被吸收：
     * "图书馆周六几点开门 那周日呢"能正确命中开馆时间相关的块，
     * 而单查"那周日呢"只会命中一堆零碎。
     */
    public static String rewriteForRetrieval(String question, String prevQuestion) {
        if (question == null) {
            return "";
        }
        String q = question.strip();
        if (prevQuestion == null || prevQuestion.isBlank()) {
            return q;
        }
        String prev = prevQuestion.strip();
        boolean shortEnough = q.length() <= SHORT_QUESTION_CHARS;
        boolean anaphora = startsWithAny(q, ANAPHORA_PREFIXES);
        if (!shortEnough && !anaphora) {
            return q;
        }
        String truncated = prev.length() > PREV_QUESTION_CHARS ? prev.substring(0, PREV_QUESTION_CHARS) : prev;
        String rewritten = truncated + " " + q;
        log.info("RAG 改写：'{}' + 上轮 '{}' → '{}'", brief(q), brief(truncated), brief(rewritten));
        return rewritten;
    }

    /**
     * 同义词扩展：检索词命中某组的任一成员时，把同组其他成员追加到末尾。
     */
    public static String expandSynonyms(String query) {
        if (query == null || query.isBlank()) {
            return query == null ? "" : query;
        }
        String expanded = query;
        for (String[] group : SYNONYM_GROUPS) {
            boolean hit = false;
            StringBuilder append = new StringBuilder();
            for (String word : group) {
                if (expanded.contains(word)) {
                    hit = true;
                } else {
                    append.append(' ').append(word);
                }
            }
            if (hit && append.length() > 0) {
                expanded = expanded + append;
            }
        }
        return expanded;
    }

    /**
     * 组合入口：改写（指代补全）→ 同义词扩展。
     * 门控不在这里做 —— 由 {@link RetrievalService} 在<b>改写之前</b>对原句判断
     * （否则"嗯"这类寒暄会先被拼上上轮问句、变成看起来有效的检索词）。
     */
    public static String retrievalQuery(String question, String prevQuestion) {
        return expandSynonyms(rewriteForRetrieval(question, prevQuestion));
    }

    /**
     * 从消息列表倒扫上一条用户问句。
     *
     * <p>⚠️ 必须传 {@code conversation.rawMessages()}（裸文本快照）——
     * {@code conversation.messages()} 返回的是拼了 {@code [时间戳]} 前缀的装配副本，
     * 拿来比对会全部对不上。
     *
     * @param currentQuestion 当前问句；与当前同文本的用户消息会被跳过（当前消息可能已入列）
     * @return 上一条用户问句；没有返回 null
     */
    public static String previousUserQuestion(List<Message> messages, String currentQuestion) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        String current = currentQuestion == null ? "" : currentQuestion.strip();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (!(messages.get(i) instanceof UserMessage userMessage)) {
                continue;
            }
            String text = userMessage.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            String stripped = text.strip();
            if (stripped.equals(current)) {
                continue;
            }
            return stripped;
        }
        return null;
    }

    /** 归一化：只留字母与数字（去标点/空白/符号），寒暄表用它做精确匹配 */
    private static String normalize(String question) {
        return question.replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static boolean startsWithAny(String text, String[] prefixes) {
        for (String prefix : prefixes) {
            if (text.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String brief(String text) {
        return text.length() > 40 ? text.substring(0, 40) + "…" : text;
    }
}
