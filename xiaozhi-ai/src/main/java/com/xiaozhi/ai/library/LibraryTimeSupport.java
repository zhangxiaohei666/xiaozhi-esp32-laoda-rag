package com.xiaozhi.ai.library;

import com.xiaozhi.utils.DateUtils;

import java.time.Duration;
import java.time.LocalTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把用户嘴里的时段说法翻译成 {@code HH:mm} 区间。
 *
 * <p>规则（评审定稿）：
 * <ul>
 *   <li>关键词：上午 08:00-12:00、下午 14:00-18:00、晚上 18:00-22:00</li>
 *   <li>"现在/马上"：取整到下一个半点，结束 = 开始 + 2 小时（≤ 22:00）</li>
 *   <li>精确区间（"14:00-16:00"）与只给开始（"14点"，默认 +2h）都支持</li>
 *   <li><b>开始 ≥ 20:00 拒绝</b>：开放时间快结束，避免约出无法利用的时段</li>
 * </ul>
 *
 * <p>解析不出时不抛异常，而是返回空 {@link Slot}（start/end 都为 null），
 * 由调用方决定"缺什么反问什么"——因为 reserveType 不同，需要的时间字段也不同，
 * 反问逻辑归调用方。解析成功但违反规则才抛 {@link LibraryUnavailableException}。
 *
 * <p>取时一律走 {@link DateUtils}（全仓约定：业务代码不得自己调 {@code now()}）。
 */
final class LibraryTimeSupport {

    /** 匹配 "14:00-16:00"、"14:30到16:00"、"14点至16点半" 这类双端区间 */
    private static final Pattern RANGE = Pattern.compile(
            "^\\s*(\\d{1,2}|[一二三四五六七八九十]{1,3})\\s*(?:[:：]\\s*(\\d{1,2}))?\\s*[点]?\\s*"
                    + "(?:-|—|~|到|至|－)\\s*"
                    + "(\\d{1,2}|[一二三四五六七八九十]{1,3})\\s*(?:[:：]\\s*(\\d{1,2}))?\\s*[点半]?\\s*$");

    /** 匹配 "14:00"、"14点"、"下午3点"（关键词已在前面处理，这里只剩数字） */
    private static final Pattern SINGLE = Pattern.compile(
            "^\\s*(\\d{1,2}|[一二三四五六七八九十]{1,3})\\s*(?:[:：]\\s*(\\d{1,2}))?\\s*[点半]?\\s*$");

    private LibraryTimeSupport() {}

    /** 解析结果：start/end 是 {@code HH:mm}，可为 null（表示用户没给）；changedNotice 是人话提示 */
    record Slot(String start, String end, String changedNotice) {

        /** 两端都齐 */
        boolean complete() {
            return start != null && end != null;
        }

        /** 只有开始时间（结束未给） */
        boolean onlyStart() {
            return start != null && end == null;
        }

        /** 只有结束时间（reserveType=2 只要这个） */
        boolean onlyEnd() {
            return start == null && end != null;
        }
    }

    /**
     * @param text 用户/模型给的时段说法，可为空
     * @throws LibraryUnavailableException 说法能看懂但违反预约规则（开始太晚）
     */
    static Slot parse(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            return new Slot(null, null, null);
        }

        // ① 关键词（最常见，语音说"下午"就是这些）
        if (containsAny(t, "上午", "早上", "早晨")) {
            return new Slot("08:00", "12:00", null);
        }
        if (containsAny(t, "下午", "中午")) {
            return new Slot("14:00", "18:00", null);
        }
        if (containsAny(t, "晚上", "傍晚", "夜里")) {
            return new Slot("18:00", "22:00", null);
        }

        // ② "现在/马上"：取整到下一个半点，+2 小时封顶 22:00
        if (containsAny(t, "现在", "马上", "立刻")) {
            LocalTime now = DateUtils.now().toLocalTime();
            LocalTime start = roundUpToHalfHour(now);
            rejectTooLate(start, "现在太晚了，图书馆 22:00 闭馆，要约的话约明天吧");
            LocalTime end = capAt22(start.plusHours(2));
            return new Slot(fmt(start), fmt(end), null);
        }

        // ③ 双端区间："14:00-16:00"
        Slot range = parseRange(t);
        if (range != null) {
            rejectTooLate(LocalTime.parse(range.start()), "开始时间太晚了，图书馆 22:00 闭馆，约 20:00 前的时段吧");
            return range;
        }

        // ④ 只有开始："14点" → 默认 +2h
        LocalTime start = parseSingle(t);
        if (start != null) {
            rejectTooLate(start, "开始时间太晚了，图书馆 22:00 闭馆，约 20:00 前的时段吧");
            LocalTime end = capAt22(start.plusHours(2));
            return new Slot(fmt(start), fmt(end), null);
        }

        // ⑤ 看不懂：不抛，让调用方反问
        return new Slot(null, null, null);
    }

    /**
     * 今天预约时，若用户给的时段已经过去，改成"从下一个半点开始 +2h"，
     * 并把改动写进 {@code changedNotice}（评审 B-2 铁律：不许悄悄改，必须明说）。
     */
    static Slot adjustIfPastToday(Slot slot) {
        if (slot == null || !slot.complete()) {
            return slot;
        }
        LocalTime start = LocalTime.parse(slot.start());
        LocalTime now = DateUtils.now().toLocalTime();
        if (!start.isAfter(now)) {
            LocalTime newStart = roundUpToHalfHour(now);
            rejectTooLate(newStart, "现在太晚了，图书馆 22:00 闭馆，要约的话约明天吧");
            LocalTime newEnd = capAt22(newStart.plusHours(2));
            String notice = "你说的 " + slot.start() + " 点已经过了，我按现在算："
                    + fmt(newStart) + " 到 " + fmt(newEnd) + "。";
            return new Slot(fmt(newStart), fmt(newEnd), notice);
        }
        return slot;
    }

    // ==================== 解析细节 ====================

    /**
     * 多时段区域按用户说法选段（2026-11-24 新增）。
     *
     * <p><b>为什么需要</b>：{@code reserveType=1} 原来假设「一天只有一个时段」，无脑取
     * {@link LibraryApiClient.DateSlot#firstSeg()}。但 2026-11-24 实测社科借阅区2 明天有
     * 08:00-11:50、14:30-21:30 两段——用户说「明天下午」会被悄悄约成上午段，违背
     * 「不许悄悄改」铁律。
     *
     * <p><b>规则</b>（按优先级）：
     * <ol>
     *   <li>该天没有时段 → {@code null}（调用方提示不可约）</li>
     *   <li>只有一段 → 直接用它（绝大多数区域如此，行为与原来完全一致）</li>
     *   <li>用户没给时段（parsed 为空或没 start）→ 第一段（行为不变）</li>
     *   <li>给了时段 → 选与 {@code [start, end)} 重叠分钟数最大的段；全不重叠回落第一段</li>
     * </ol>
     */
    static LibraryApiClient.TimeSeg pickSegment(LibraryApiClient.DateSlot slot, Slot parsed) {
        if (slot == null || slot.times() == null || slot.times().isEmpty()) {
            return null;
        }
        LibraryApiClient.TimeSeg first = slot.times().get(0);
        if (slot.times().size() == 1) {
            return first;
        }
        if (parsed == null || parsed.start() == null) {
            return first;
        }

        LocalTime wantStart = LocalTime.parse(parsed.start());
        LocalTime wantEnd = parsed.end() == null ? wantStart.plusHours(2) : LocalTime.parse(parsed.end());

        LibraryApiClient.TimeSeg best = first;
        long bestOverlap = 0;
        for (LibraryApiClient.TimeSeg seg : slot.times()) {
            LocalTime segStart = LocalTime.parse(seg.start());
            LocalTime segEnd = LocalTime.parse(seg.end());
            LocalTime overlapStart = wantStart.isAfter(segStart) ? wantStart : segStart;
            LocalTime overlapEnd = wantEnd.isBefore(segEnd) ? wantEnd : segEnd;
            long overlap = overlapEnd.isAfter(overlapStart)
                    ? Duration.between(overlapStart, overlapEnd).toMinutes() : 0;
            if (overlap > bestOverlap) {
                bestOverlap = overlap;
                best = seg;
            }
        }
        return best;
    }

    private static Slot parseRange(String t) {
        Matcher m = RANGE.matcher(t);
        if (!m.matches()) {
            return null;
        }
        Integer h1 = toInt(m.group(1));
        Integer m1 = m.group(2) == null ? 0 : toInt(m.group(2));
        Integer h2 = toInt(m.group(3));
        Integer m2 = m.group(4) == null ? 0 : toInt(m.group(4));
        if (h1 == null || h2 == null || m1 == null || m2 == null) {
            return null;
        }
        LocalTime start = timeOf(h1, m1);
        LocalTime end = timeOf(h2, m2);
        if (start == null || end == null || !end.isAfter(start)) {
            return null;
        }
        return new Slot(fmt(start), fmt(end), null);
    }

    private static LocalTime parseSingle(String t) {
        Matcher m = SINGLE.matcher(t);
        if (!m.matches()) {
            return null;
        }
        Integer h = toInt(m.group(1));
        Integer min = m.group(2) == null ? 0 : toInt(m.group(2));
        if (h == null || min == null) {
            return null;
        }
        return timeOf(h, min);
    }

    private static LocalTime timeOf(int hour, int minute) {
        if (hour > 23 || minute > 59) {
            return null;
        }
        try {
            return LocalTime.of(hour, minute);
        } catch (Exception e) {
            return null;
        }
    }

    /** 阿拉伯数字或中文数字 → int；解析不了返回 null */
    private static Integer toInt(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ignored) {
            // 中文数字，只支持到 23 点够用的范围
        }
        return switch (s) {
            case "一" -> 1;
            case "二", "两" -> 2;
            case "三" -> 3;
            case "四" -> 4;
            case "五" -> 5;
            case "六" -> 6;
            case "七" -> 7;
            case "八" -> 8;
            case "九" -> 9;
            case "十" -> 10;
            case "十一" -> 11;
            case "十二" -> 12;
            case "十三" -> 13;
            case "十四" -> 14;
            case "十五" -> 15;
            case "十六" -> 16;
            case "十七" -> 17;
            case "十八" -> 18;
            case "十九" -> 19;
            case "二十" -> 20;
            case "二十一" -> 21;
            case "二十二" -> 22;
            default -> null;
        };
    }

    /** 向上取整到下一个半点：14:10→14:30、14:45→15:00、14:30→14:30 */
    private static LocalTime roundUpToHalfHour(LocalTime t) {
        if (t.getMinute() == 0 || t.getMinute() == 30) {
            return t.withSecond(0).withNano(0);
        }
        LocalTime base = t.withSecond(0).withNano(0);
        if (t.getMinute() < 30) {
            return base.withMinute(30);
        }
        return base.plusHours(1).withMinute(0);
    }

    private static void rejectTooLate(LocalTime start, String message) {
        if (start.getHour() >= 20) {
            throw new LibraryUnavailableException(message);
        }
    }

    private static LocalTime capAt22(LocalTime t) {
        LocalTime close = LocalTime.of(22, 0);
        return t.isAfter(close) ? close : t;
    }

    private static String fmt(LocalTime t) {
        return String.format("%02d:%02d", t.getHour(), t.getMinute());
    }

    private static boolean containsAny(String text, String... words) {
        for (String w : words) {
            if (text.contains(w)) {
                return true;
            }
        }
        return false;
    }
}
