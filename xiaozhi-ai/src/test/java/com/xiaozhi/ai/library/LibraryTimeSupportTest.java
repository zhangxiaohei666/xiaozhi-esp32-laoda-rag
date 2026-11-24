package com.xiaozhi.ai.library;

import com.xiaozhi.utils.DateUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 钉住时段解析的行为契约（评审定稿规则）。
 *
 * <p>测试时钟固定在北京时间 14:10，覆盖：关键词、精确区间、只给开始、
 * "现在"取整、≥20:00 拒绝、过期改写（明说不动悄悄改）、解析不出返回空。
 */
class LibraryTimeSupportTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @BeforeEach
    void fixedClock() {
        // 2026-11-24 14:10 北京时间
        DateUtils.use(Clock.fixed(Instant.parse("2026-11-24T06:10:00Z"), ZONE));
    }

    @AfterEach
    void resetClock() {
        DateUtils.reset();
    }

    @Test
    @DisplayName("关键词：上午/下午/晚上")
    void keywordSlots() {
        assertThat(LibraryTimeSupport.parse("下午").start()).isEqualTo("14:00");
        assertThat(LibraryTimeSupport.parse("下午").end()).isEqualTo("18:00");
        assertThat(LibraryTimeSupport.parse("上午").start()).isEqualTo("08:00");
        assertThat(LibraryTimeSupport.parse("上午").end()).isEqualTo("12:00");
        assertThat(LibraryTimeSupport.parse("晚上").start()).isEqualTo("18:00");
        assertThat(LibraryTimeSupport.parse("晚上").end()).isEqualTo("22:00");
    }

    @Test
    @DisplayName("精确区间 14:00-16:00")
    void exactRange() {
        LibraryTimeSupport.Slot s = LibraryTimeSupport.parse("14:00-16:00");
        assertThat(s.start()).isEqualTo("14:00");
        assertThat(s.end()).isEqualTo("16:00");
    }

    @Test
    @DisplayName("只给开始：默认 +2 小时")
    void startOnlyPlusTwoHours() {
        LibraryTimeSupport.Slot s = LibraryTimeSupport.parse("14点");
        assertThat(s.start()).isEqualTo("14:00");
        assertThat(s.end()).isEqualTo("16:00");
    }

    @Test
    @DisplayName("只给开始 19:00：+2h = 21:00，未越闭馆线")
    void startOnlyStaysBelowClose() {
        LibraryTimeSupport.Slot s = LibraryTimeSupport.parse("19:00");
        assertThat(s.start()).isEqualTo("19:00");
        assertThat(s.end()).isEqualTo("21:00");
    }

    @Test
    @DisplayName("开始 ≥ 20:00 拒绝")
    void rejectTooLate() {
        assertThatThrownBy(() -> LibraryTimeSupport.parse("20点"))
                .isInstanceOf(LibraryUnavailableException.class)
                .hasMessageContaining("太晚");
        assertThatThrownBy(() -> LibraryTimeSupport.parse("20:30-22:00"))
                .isInstanceOf(LibraryUnavailableException.class);
    }

    @Test
    @DisplayName("现在：14:10 → 14:30-16:30（取整到下一个半点）")
    void nowRoundsUpToHalfHour() {
        LibraryTimeSupport.Slot s = LibraryTimeSupport.parse("现在");
        assertThat(s.start()).isEqualTo("14:30");
        assertThat(s.end()).isEqualTo("16:30");
    }

    @Test
    @DisplayName("解析不出的说法返回空 slot（调用方反问）")
    void unparseableReturnsEmpty() {
        LibraryTimeSupport.Slot s = LibraryTimeSupport.parse("随便什么时候");
        assertThat(s.start()).isNull();
        assertThat(s.end()).isNull();
        assertThat(s.complete()).isFalse();
    }

    @Test
    @DisplayName("今天已过时段：改写为现在起，且 notice 明说改了什么")
    void adjustPastTodayWithNotice() {
        LibraryTimeSupport.Slot old = LibraryTimeSupport.parse("08:00-12:00");
        LibraryTimeSupport.Slot adjusted = LibraryTimeSupport.adjustIfPastToday(old);

        assertThat(adjusted.start()).isEqualTo("14:30");
        assertThat(adjusted.end()).isEqualTo("16:30");
        assertThat(adjusted.changedNotice()).contains("已经过了");
        assertThat(adjusted.changedNotice()).contains("14:30");
    }

    @Test
    @DisplayName("今天未过时段：原样返回，无 notice")
    void adjustFutureSlotUntouched() {
        LibraryTimeSupport.Slot slot = LibraryTimeSupport.parse("16:00-18:00");
        LibraryTimeSupport.Slot adjusted = LibraryTimeSupport.adjustIfPastToday(slot);

        assertThat(adjusted.start()).isEqualTo("16:00");
        assertThat(adjusted.end()).isEqualTo("18:00");
        assertThat(adjusted.changedNotice()).isNull();
    }

    // ==================== 多时段选段（2026-11-24 新增） ====================

    /** 社科借阅区2 明天的真实两段（Space/map 实测） */
    private static LibraryApiClient.DateSlot twoSegments() {
        return new LibraryApiClient.DateSlot("2026-11-25", "",
                java.util.List.of(
                        new LibraryApiClient.TimeSeg("1458542", "08:00", "11:50"),
                        new LibraryApiClient.TimeSeg("1458614", "14:30", "21:30")));
    }

    @Test
    @DisplayName("两段区域 + 用户说「下午」→ 选下午段（不再无脑取第一段）")
    void pickSegmentAfternoon() {
        LibraryApiClient.TimeSeg seg = LibraryTimeSupport.pickSegment(twoSegments(),
                LibraryTimeSupport.parse("下午"));
        assertThat(seg.id()).isEqualTo("1458614");
        assertThat(seg.start()).isEqualTo("14:30");
    }

    @Test
    @DisplayName("两段区域 + 用户说「上午」→ 选上午段")
    void pickSegmentMorning() {
        LibraryApiClient.TimeSeg seg = LibraryTimeSupport.pickSegment(twoSegments(),
                LibraryTimeSupport.parse("上午"));
        assertThat(seg.id()).isEqualTo("1458542");
        assertThat(seg.start()).isEqualTo("08:00");
    }

    @Test
    @DisplayName("两段区域 + 用户给精确区间 14:00-16:00 → 选与之重叠的下午段")
    void pickSegmentExactRange() {
        LibraryApiClient.TimeSeg seg = LibraryTimeSupport.pickSegment(twoSegments(),
                LibraryTimeSupport.parse("14:00-16:00"));
        assertThat(seg.id()).isEqualTo("1458614");
    }

    @Test
    @DisplayName("两段区域 + 用户没提时段 → 第一段（行为与修正前一致）")
    void pickSegmentNoTime() {
        LibraryApiClient.TimeSeg seg = LibraryTimeSupport.pickSegment(twoSegments(), null);
        assertThat(seg.id()).isEqualTo("1458542");
    }

    @Test
    @DisplayName("单段区域：直接取该段（绝大多数区域，行为不变）")
    void pickSegmentSingleSegment() {
        LibraryApiClient.DateSlot slot = new LibraryApiClient.DateSlot("2026-11-24", "",
                java.util.List.of(new LibraryApiClient.TimeSeg("1457281", "14:20", "21:30")));
        LibraryApiClient.TimeSeg seg = LibraryTimeSupport.pickSegment(slot,
                LibraryTimeSupport.parse("下午"));
        assertThat(seg.id()).isEqualTo("1457281");
    }

    @Test
    @DisplayName("没有可约时段：返回 null（调用方提示不可约）")
    void pickSegmentEmpty() {
        LibraryApiClient.DateSlot slot = new LibraryApiClient.DateSlot("2026-11-24", "", java.util.List.of());
        assertThat(LibraryTimeSupport.pickSegment(slot, null)).isNull();
        assertThat(LibraryTimeSupport.pickSegment(null, null)).isNull();
    }
}
