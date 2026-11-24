package com.xiaozhi.ai.library;

import com.xiaozhi.utils.DateUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 钉住预约准备工具的核心流程（reserveType=1 时段型，艺术沙龙实测类型）：
 * 不调写接口、segment 取 Space/map 的 times[].id、非 admin 拒绝、非普通座位拒绝。
 */
class LibraryPrepareReserveFunctionTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @BeforeEach
    void fixedClock() {
        DateUtils.use(Clock.fixed(Instant.parse("2026-11-24T06:10:00Z"), ZONE));
    }

    @AfterEach
    void resetClock() {
        DateUtils.reset();
    }

    private LibraryPrepareReserveFunction fn(LibraryApiClient api, LibraryAreaResolver resolver,
                                             LibraryPendingActionStore store, LibraryOwnerGuard guard) {
        return new LibraryPrepareReserveFunction(api, resolver, store, guard);
    }

    private static LibraryOwnerGuard guardWith(long owner) {
        LibraryProperties props = new LibraryProperties();
        props.setOwnerUserId(owner);
        return new LibraryOwnerGuard(props);
    }

    private static LibraryApiClient.AreaMap reserveTypeOneMap() {
        return new LibraryApiClient.AreaMap(
                "艺术沙龙", "1", "普通座位", "1",
                List.of(new LibraryApiClient.DateSlot("2026-11-25", "2026-11-25 07:50~21:30",
                        List.of(new LibraryApiClient.TimeSeg("1457482", "07:50", "21:30")))));
    }

    private static LibraryApiClient apiWith(LibraryApiClient.AreaMap map, List<LibraryApiClient.Seat> seats) {
        LibraryApiClient api = mock(LibraryApiClient.class);
        when(api.areaMap("17")).thenReturn(map);
        when(api.seats(eq("17"), eq("2026-11-25"), any(), any())).thenReturn(seats);
        when(api.myBookings()).thenReturn(List.of());
        return api;
    }

    private static LibraryAreaResolver resolverFor(String areaId, String name) {
        LibraryAreaResolver resolver = mock(LibraryAreaResolver.class);
        LibraryApiClient.Area area = new LibraryApiClient.Area(areaId, name, "三楼", 20, 20);
        when(resolver.resolve(eq(name), any()))
                .thenReturn(new LibraryAreaResolver.Resolved(List.of(area), List.of(), true, null));
        return resolver;
    }

    @Test
    @DisplayName("reserveType=1：选最小空闲座、segment 用 times[].id、不调写接口、文案含确认与签到提醒")
    void reserveTypeOnePreparesSegmentReservation() {
        LibraryApiClient api = apiWith(reserveTypeOneMap(), List.of(
                new LibraryApiClient.Seat("4025", "001", "艺术沙龙", "1", "空闲"),
                new LibraryApiClient.Seat("4026", "002", "艺术沙龙", "2", "已预约")));
        LibraryPendingActionStore store = mock(LibraryPendingActionStore.class);
        when(store.save(eq(1), eq(LibraryPendingActionStore.Type.RESERVE), any(), any()))
                .thenReturn(null);

        LibraryPrepareReserveFunction fn = fn(api, resolverFor("17", "艺术沙龙"), store, guardWith(1));
        String result = fn.execute(1, Map.of("area", "艺术沙龙", "date", "明天"));

        assertThat(result).contains("001");
        assertThat(result).contains("07:50-21:30");
        assertThat(result).contains("确认");
        assertThat(result).contains("自动签到");
        assertThat(result).doesNotContain("002"); // 已预约的不会选
    }

    @Test
    @DisplayName("非 admin：直接拒绝，不查任何接口")
    void nonAdminRejectedWithoutIo() {
        LibraryApiClient api = mock(LibraryApiClient.class);
        LibraryAreaResolver resolver = mock(LibraryAreaResolver.class);
        LibraryPendingActionStore store = mock(LibraryPendingActionStore.class);

        LibraryPrepareReserveFunction fn = fn(api, resolver, store, guardWith(1));
        String result = fn.execute(2, Map.of("area", "艺术沙龙"));

        assertThat(result).contains("只有管理员");
        org.mockito.Mockito.verifyNoInteractions(api, resolver, store);
    }

    @Test
    @DisplayName("非普通座位类型：明确拒绝")
    void nonNormalAreaRejected() {
        LibraryApiClient api = apiWith(
                new LibraryApiClient.AreaMap("考研自习室", "2", "考研座位", "3", List.of()),
                List.of());
        LibraryPendingActionStore store = mock(LibraryPendingActionStore.class);

        LibraryPrepareReserveFunction fn = fn(api, resolverFor("17", "考研自习室"), store, guardWith(1));
        String result = fn.execute(1, Map.of("area", "考研自习室", "date", "明天"));

        assertThat(result).contains("不是普通座位");
    }

    @Test
    @DisplayName("reserveType=3 没给时段：反问，不写待确认")
    void reserveTypeThreeWithoutTimeAsks() {
        LibraryApiClient api = apiWith(
                new LibraryApiClient.AreaMap("艺术沙龙", "1", "普通座位", "3",
                        List.of(new LibraryApiClient.DateSlot("2026-11-25", "全天", List.of()))),
                List.of());
        LibraryPendingActionStore store = mock(LibraryPendingActionStore.class);

        LibraryPrepareReserveFunction fn = fn(api, resolverFor("17", "艺术沙龙"), store, guardWith(1));
        String result = fn.execute(1, Map.of("area", "艺术沙龙", "date", "明天"));

        assertThat(result).contains("时段");
        org.mockito.Mockito.verifyNoInteractions(store);
    }

    @Test
    @DisplayName("无空位：返回可操作的引导文案")
    void noFreeSeatGuides() {
        LibraryApiClient api = apiWith(reserveTypeOneMap(), List.of(
                new LibraryApiClient.Seat("4025", "001", "艺术沙龙", "2", "已预约")));
        LibraryPendingActionStore store = mock(LibraryPendingActionStore.class);

        LibraryPrepareReserveFunction fn = fn(api, resolverFor("17", "艺术沙龙"), store, guardWith(1));
        String result = fn.execute(1, Map.of("area", "艺术沙龙", "date", "明天"));

        assertThat(result).contains("没有空位");
        org.mockito.Mockito.verifyNoInteractions(store);
    }
}
