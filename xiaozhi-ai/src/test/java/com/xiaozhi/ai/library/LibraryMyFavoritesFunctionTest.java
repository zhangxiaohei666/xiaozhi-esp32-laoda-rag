package com.xiaozhi.ai.library;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 钉住「常用/收藏座位」渲染的行为契约。
 *
 * <p>只测纯函数 {@link LibraryMyFavoritesFunction#render}（不碰网络）：
 * 头部汇总、按天分组、可约/不可约分组、两处都收藏的标注、空结果的兜底话术。
 *
 * <p>可约语义的唯一来源是 {@code Space/seat} 的 {@code status}（见设计方案 2.2③），
 * 所以这里直接构造 {@code SeatView.free} 来表达，不涉及 {@code is_subscribe}。
 */
class LibraryMyFavoritesFunctionTest {

    private static LibraryMyFavoritesFunction.Owned owned(String areaId, String areaName,
                                                           String seatId, String seatNo,
                                                           boolean often, boolean collect) {
        return new LibraryMyFavoritesFunction.Owned(
                new LibraryApiClient.FavoriteSeat(areaId, areaName, seatId, seatNo), often, collect);
    }

    private static LibraryMyFavoritesFunction.SeatView view(String no, boolean often, boolean collect,
                                                            boolean free, String status) {
        return new LibraryMyFavoritesFunction.SeatView(no, often, collect, free, status);
    }

    private static LibraryMyFavoritesFunction.SlotView slot(String day, String area, String start, String end,
                                                            LibraryMyFavoritesFunction.SeatView... seats) {
        return new LibraryMyFavoritesFunction.SlotView(day, area, start, end, List.of(seats));
    }

    @Test
    @DisplayName("混合场景：今天公共占着、社科可约；明天都空")
    void rendersMixed() {
        List<LibraryMyFavoritesFunction.Owned> seats = List.of(
                owned("13", "图书馆-三楼-公共阅览区", "3575", "111", true, false),
                owned("16", "图书馆-三楼-社科借阅区2", "3929", "045", true, true));

        String out = LibraryMyFavoritesFunction.render(seats, List.of(
                slot("今天", "公共阅览区", "14:20", "21:30",
                        view("111", true, false, false, "使用中")),
                slot("今天", "社科借阅区2", "18:00", "21:30",
                        view("045", true, true, true, "空闲")),
                slot("明天", "社科借阅区2", "08:00", "11:50",
                        view("045", true, true, true, "空闲"))
        ), List.of());

        assertThat(out).contains("你常用/收藏的座位共 2 个");
        // 区域名截取：去掉「图书馆-三楼-」前缀
        assertThat(out).contains("公共阅览区 111");
        assertThat(out).contains("社科借阅区2 045");
        assertThat(out).doesNotContain("图书馆-三楼-");
        // 可约 / 不可约分组
        assertThat(out).contains("⛔ 不可约：111（使用中）");
        assertThat(out).contains("✅ 可约：045（常用+收藏）");
        // 日期分组
        assertThat(out).contains("今天");
        assertThat(out).contains("明天");
        // 结尾给可复述的话术
        assertThat(out).contains("要哪个直接说");
    }

    @Test
    @DisplayName("全部被占：明确说都被占着，不给预约话术")
    void rendersAllBusy() {
        List<LibraryMyFavoritesFunction.Owned> seats = List.of(
                owned("13", "图书馆-三楼-公共阅览区", "3575", "111", true, false));

        String out = LibraryMyFavoritesFunction.render(seats, List.of(
                slot("今天", "公共阅览区", "14:20", "21:30",
                        view("111", true, false, false, "使用中"))
        ), List.of());

        assertThat(out).contains("⛔ 不可约：111（使用中）");
        assertThat(out).contains("都被占着");
        assertThat(out).doesNotContain("要哪个直接说");
    }

    @Test
    @DisplayName("某天该区域没有时段：写进提示，不静默跳过")
    void rendersNotes() {
        List<LibraryMyFavoritesFunction.Owned> seats = List.of(
                owned("16", "图书馆-三楼-社科借阅区2", "3929", "045", true, true));

        String out = LibraryMyFavoritesFunction.render(seats, List.of(),
                List.of("明天「社科借阅区2」没有开放预约（可能闭馆）"));

        assertThat(out).contains("明天「社科借阅区2」没有开放预约");
    }

    @Test
    @DisplayName("座位号归一：040 与 40 视为同一号，111 不受影响")
    void seatKeyNormalization() {
        assertThat(LibraryMyFavoritesFunction.seatKey("040"))
                .isEqualTo(LibraryMyFavoritesFunction.seatKey("40"));
        assertThat(LibraryMyFavoritesFunction.seatKey("111"))
                .isEqualTo(LibraryMyFavoritesFunction.seatKey("111号"));
        assertThat(LibraryMyFavoritesFunction.seatKey("0")).isEqualTo("0");
        assertThat(LibraryMyFavoritesFunction.seatKey("")).isEmpty();
    }
}
