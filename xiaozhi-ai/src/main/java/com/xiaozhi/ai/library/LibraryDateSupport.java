package com.xiaozhi.ai.library;

import com.xiaozhi.utils.DateUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 把用户嘴里的日期说法翻译成 {@code yyyy-MM-dd}。
 *
 * <p>图书馆系统只开放**今天**和**明天**两个可预约日期（接口返回的 {@code date[]} 就两天），
 * 所以这里只认这两类说法；问"下周三"这类超范围的会明确告诉模型"只能查今明两天"。
 *
 * <p>取时一律走 {@link DateUtils}（全仓约定：业务代码不得自己调 {@code now()}）。
 */
final class LibraryDateSupport {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private LibraryDateSupport() {}

    /** 解析结果：日期 + 人类可读的中文说明（用于拼返回文本） */
    record Parsed(String date, String label) {}

    /**
     * @param text 用户/模型给的日期说法，可为空
     * @throws LibraryUnavailableException 说法无法识别或超出今明两天
     */
    static Parsed parse(String text) {
        LocalDate today = DateUtils.today();
        String t = text == null ? "" : text.trim();

        if (t.isEmpty() || t.contains("今天") || t.contains("今日")
                || t.equals("当天") || t.contains("现在")) {
            return new Parsed(today.format(ISO), "今天");
        }
        if (t.contains("明天") || t.contains("明日") || t.contains("次日")) {
            return new Parsed(today.plusDays(1).format(ISO), "明天");
        }
        if (t.contains("后天")) {
            throw new LibraryUnavailableException("图书馆只能预约今天和明天，后天还查不了");
        }

        // 纯日期：yyyy-MM-dd 或 yyyy/MM/dd
        String normalized = t.replace('/', '-').replace('.', '-');
        try {
            LocalDate d = LocalDate.parse(normalized, ISO);
            if (d.equals(today)) {
                return new Parsed(d.format(ISO), "今天");
            }
            if (d.equals(today.plusDays(1))) {
                return new Parsed(d.format(ISO), "明天");
            }
            throw new LibraryUnavailableException(
                    "图书馆只能查今天（" + today.format(ISO) + "）和明天（"
                            + today.plusDays(1).format(ISO) + "）");
        } catch (DateTimeParseException ignored) {
            // 继续往下猜
        }

        // "10月31日""10-31""10/31""31号"
        String s = t.replace("月", "-").replace("日", "").replace("号", "").replace("/", "-").replace(".", "-").trim();
        for (LocalDate candidate : new LocalDate[]{today, today.plusDays(1)}) {
            String m1 = candidate.getMonthValue() + "-" + candidate.getDayOfMonth();
            String m2 = String.format("%02d-%02d", candidate.getMonthValue(), candidate.getDayOfMonth());
            String d1 = candidate.getDayOfMonth() + "";
            if (s.equals(m1) || s.equals(m2) || s.equals(d1)) {
                return new Parsed(candidate.format(ISO),
                        candidate.equals(today) ? "今天" : "明天");
            }
        }

        throw new LibraryUnavailableException(
                "没听懂「" + t + "」是哪天。图书馆只能查今天和明天，你可以说「今天」或「明天」");
    }
}