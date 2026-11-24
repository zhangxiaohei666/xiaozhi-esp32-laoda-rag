package com.xiaozhi.ai.library;

import com.xiaozhi.ai.llm.tool.ToolCallStringResultConverter;
import com.xiaozhi.ai.tool.ToolsGlobalRegistry;
import com.xiaozhi.ai.tool.session.ToolSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 查询「我的常用/收藏座位」在今天和明天的预约情况，并筛出当前可约的座位（只读，人人可调用）。
 *
 * <p><b>需求来源</b>（2026-11-24）：用户在 H5「常用预约」页（{@code #/common}）维护了常用与收藏座位，
 * 想直接问「我今天/明天这些座位哪些还被占着、哪些能约」。
 *
 * <p><b>数据来源与分工</b>：
 * <ul>
 *   <li>座位身份（区域 + 座位号）：{@code member/often}、{@code member/collect}。
 *       这两个接口必须带合法 {@code day+begin_time+end_time}（只传 type 会 201），
 *       所以先用 {@code member/oftentime}/{@code member/collecttime} 取一个真实时段当探针。
 *       座位清单本身与时段无关。</li>
 *   <li>能不能约：<b>一律以 {@code Space/seat} 的 {@code status} 为准</b>（1=空闲）。
 *       接口自带的 {@code is_subscribe} 实测自相矛盾（同一时刻 111 在 often 标 0、
 *       204 在 collect 标 1，而两者 status 都是空闲），与 {@code member/seat} 的
 *       {@code cancel} 字段同属不可信提示，不参与判断。</li>
 *   <li>时段范围：{@code Space/map} 的 {@code times[]}（每区域每天可能有多段，如社科借阅区2 明天两段）。</li>
 * </ul>
 *
 * <p><b>为什么可约判断要和下单链路同源</b>：{@link LibraryPrepareReserveFunction} 也是用
 * {@code Space/seat} 挑空位，所以这里判"可约"的座位，走预约时大概率仍空闲；
 * 真正下单前 {@code confirm} 还会重查一次（两段式确认的既有保障）。
 *
 * <p>注册条件：只要 {@code xiaozhi.library.enabled=true}（与 {@link LibraryMyBookingsFunction} 同口径）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "xiaozhi.library.enabled", havingValue = "true")
public class LibraryMyFavoritesFunction implements ToolsGlobalRegistry.GlobalFunction {

    public static final String TOOL_NAME = "library_my_favorites";

    private static final String TOOL_DESCRIPTION =
            "查询「我」在图书馆的常用座位和收藏座位，今天和明天各自哪些是空的、哪些已被预约或使用中。"
            + "当用户问「我常用的座位还能约吗」「我收藏的座位今天有空吗」「我常去的几个位置哪几个能约」"
            + "这类问题时调用。返回按日期、区域、时段列出可约与不可约的座位；"
            + "用户挑中某个座位后，用 library_prepare_reserve 指定区域+座位号+日期去生成待确认单。"
            + "如果用户问的是自己已经约到的记录，用 library_my_bookings，不要用本工具。"
            + "参数 date 可选（今天/明天，不传=两天都查）；kind 可选（常用/收藏，不传=都查）。";

    private final LibraryApiClient apiClient;

    public LibraryMyFavoritesFunction(LibraryApiClient apiClient) {
        this.apiClient = apiClient;
    }

    @Override
    public ToolCallback getFunctionCallTool(ToolSession toolSession) {
        return FunctionToolCallback
                .builder(TOOL_NAME, (Map<String, String> params, ToolContext toolContext) ->
                        execute(params.get("date"), params.get("kind")))
                .description(TOOL_DESCRIPTION)
                .inputSchema("""
                        {
                            "type": "object",
                            "properties": {
                                "date": {
                                    "type": "string",
                                    "description": "查哪天：「今天」或「明天」。不传则两天都查。"
                                },
                                "kind": {
                                    "type": "string",
                                    "description": "只查哪一类：「常用」或「收藏」。不传则两类都查。"
                                }
                            }
                        }
                        """)
                .inputType(Map.class)
                .toolCallResultConverter(ToolCallStringResultConverter.INSTANCE)
                .build();
    }

    /**
     * 执行体：纯只读。
     */
    String execute(String dateText, String kindText) {
        try {
            // ① 目标日期（不传 = 今天 + 明天）
            List<LibraryDateSupport.Parsed> days = new ArrayList<>();
            if (dateText == null || dateText.isBlank()) {
                days.add(LibraryDateSupport.parse("今天"));
                days.add(LibraryDateSupport.parse("明天"));
            } else {
                days.add(LibraryDateSupport.parse(dateText));
            }

            // 2026-11-24 修正：原来用「不含某词」反推要查哪类，模型若传「常用和收藏」
            // 会同时被两个条件排除、导致两类都不查。改成先识别提到了哪些，都没提就两类都查。
            boolean hasOften = mentions(kindText, "常用", "often");
            boolean hasCollect = mentions(kindText, "收藏", "collect");
            boolean wantOften = hasOften || !hasCollect;
            boolean wantCollect = hasCollect || !hasOften;

            // ② 探针时段：often/collect 必须带合法 day+begin+end
            String probeDay = null;
            LibraryApiClient.FavTime probe = null;
            List<LibraryApiClient.FavDay> favDays = apiClient.oftenTimes();
            if (favDays.isEmpty()) {
                favDays = apiClient.collectTimes();
            }
            for (LibraryApiClient.FavDay fd : favDays) {
                if (!fd.day().isBlank() && fd.firstTime() != null) {
                    probeDay = fd.day();
                    probe = fd.firstTime();
                    break;
                }
            }
            if (probe == null) {
                return "图书馆系统这会儿没返回你的常用/收藏时段，暂时查不了，稍后再试。";
            }

            // ③ 座位清单（按 areaId+seatId 去重，来源取并集）
            Map<String, Owned> seatMap = new LinkedHashMap<>();
            if (wantOften) {
                for (LibraryApiClient.FavoriteSeat s : apiClient.oftenSeats(probeDay, probe.start(), probe.end())) {
                    merge(seatMap, s, true, false);
                }
            }
            if (wantCollect) {
                for (LibraryApiClient.FavoriteSeat s : apiClient.collectSeats(probeDay, probe.start(), probe.end())) {
                    merge(seatMap, s, false, true);
                }
            }
            if (seatMap.isEmpty()) {
                return wantOften && wantCollect
                        ? "没查到你的常用/收藏座位。可以在图书馆网页的「常用预约」页先收藏几个座位。"
                        : "没查到你的" + (wantOften ? "常用" : "收藏") + "座位。";
            }

            // ④ 按区域分组
            Map<String, List<Owned>> byArea = new LinkedHashMap<>();
            for (Owned o : seatMap.values()) {
                byArea.computeIfAbsent(o.seat().areaId(), k -> new ArrayList<>()).add(o);
            }

            // ⑤ 逐区域 × 逐天 × 逐时段查权威状态
            Map<String, LibraryApiClient.AreaMap> mapCache = new HashMap<>();
            List<SlotView> views = new ArrayList<>();
            List<String> notes = new ArrayList<>();
            for (Map.Entry<String, List<Owned>> e : byArea.entrySet()) {
                String areaId = e.getKey();
                String areaName = shortAreaName(e.getValue().get(0).seat().areaName());
                LibraryApiClient.AreaMap am;
                try {
                    am = mapCache.computeIfAbsent(areaId, apiClient::areaMap);
                } catch (Exception ex) {
                    notes.add(areaName + " 这会儿查不到开放时段");
                    continue;
                }
                for (LibraryDateSupport.Parsed d : days) {
                    LibraryApiClient.DateSlot slot = am.dateOf(d.date());
                    if (slot == null || slot.times() == null || slot.times().isEmpty()) {
                        notes.add(d.label() + "「" + areaName + "」没有开放预约（可能闭馆）");
                        continue;
                    }
                    for (LibraryApiClient.TimeSeg seg : slot.times()) {
                        List<LibraryApiClient.Seat> seats;
                        try {
                            seats = apiClient.seats(areaId, d.date(), seg.start(), seg.end());
                        } catch (Exception ex) {
                            notes.add(d.label() + "「" + areaName + "」" + seg.start() + "-" + seg.end()
                                    + " 的座位状态暂时查不到");
                            continue;
                        }
                        views.add(buildView(d.label(), areaName, seg, e.getValue(), seats));
                    }
                }
            }

            // ⑥ 渲染
            return render(seatMap.values(), views, notes);
        } catch (LibraryUnavailableException e) {
            return e.getMessage() + "。";
        } catch (Exception e) {
            log.warn("{} 执行失败: {}", TOOL_NAME, e.getMessage());
            return "这会儿查不到你的常用/收藏座位，稍后再问我一次。";
        }
    }

    // ==================== 组装与渲染 ====================

    /** 座位 + 来源标记 */
    record Owned(LibraryApiClient.FavoriteSeat seat, boolean often, boolean collect) {
    }

    /** 一个座位在某时段的状态；{@link #mark()} 只在「两处都有」时返回后缀，避免每行都挂来源 */
    record SeatView(String seatNo, boolean often, boolean collect, boolean free, String statusName) {

        String mark() {
            return often && collect ? "（常用+收藏）" : "";
        }
    }

    /** 某天、某区域、某时段的查询结果 */
    record SlotView(String dayLabel, String areaName,
                    String start, String end, List<SeatView> seats) {
    }

    private static void merge(Map<String, Owned> map, LibraryApiClient.FavoriteSeat s,
                              boolean often, boolean collect) {
        String key = s.areaId() + "#" + s.seatId();
        Owned old = map.get(key);
        if (old == null) {
            map.put(key, new Owned(s, often, collect));
        } else {
            map.put(key, new Owned(old.seat(), old.often() || often, old.collect() || collect));
        }
    }

    private SlotView buildView(String dayLabel, String areaName, LibraryApiClient.TimeSeg seg,
                               List<Owned> owned, List<LibraryApiClient.Seat> seats) {
        Map<String, LibraryApiClient.Seat> byNo = new HashMap<>();
        for (LibraryApiClient.Seat s : seats) {
            byNo.put(seatKey(s.no()), s);
        }
        List<SeatView> out = new ArrayList<>();
        for (Owned o : owned) {
            LibraryApiClient.Seat s = byNo.get(seatKey(o.seat().seatNo()));
            if (s == null) {
                // 该区域座位表里没有这个号（可能已停用），如实标注而不是当作可约
                out.add(new SeatView(o.seat().seatNo(), o.often(), o.collect(), false, "查无此座位"));
                continue;
            }
            out.add(new SeatView(o.seat().seatNo(), o.often(), o.collect(),
                    s.isFree(), s.statusName().isBlank() ? "状态未知" : s.statusName()));
        }
        return new SlotView(dayLabel, areaName, seg.start(), seg.end(), out);
    }

    /**
     * 渲染成可直接播报的文本（静态方法，便于单测）。
     */
    static String render(Collection<Owned> seats, List<SlotView> views, List<String> notes) {
        StringBuilder sb = new StringBuilder();

        // 头部：按区域汇总座位号
        Map<String, List<String>> headMap = new LinkedHashMap<>();
        for (Owned o : seats) {
            headMap.computeIfAbsent(shortAreaName(o.seat().areaName()), k -> new ArrayList<>())
                    .add(o.seat().seatNo());
        }
        int total = seats.size();
        sb.append("你常用/收藏的座位共 ").append(total).append(" 个：");
        boolean firstArea = true;
        for (Map.Entry<String, List<String>> e : headMap.entrySet()) {
            if (!firstArea) {
                sb.append("；");
            }
            sb.append(e.getKey()).append(" ").append(String.join("、", e.getValue()));
            firstArea = false;
        }

        // 按天分组
        Map<String, List<SlotView>> byDay = new LinkedHashMap<>();
        for (SlotView v : views) {
            byDay.computeIfAbsent(v.dayLabel(), k -> new ArrayList<>()).add(v);
        }

        int freeTotal = 0;
        for (Map.Entry<String, List<SlotView>> e : byDay.entrySet()) {
            sb.append("\n\n").append(e.getKey());
            for (SlotView v : e.getValue()) {
                sb.append("\n▸ ").append(v.areaName()).append(" ").append(v.start()).append("-").append(v.end());
                List<String> freeList = new ArrayList<>();
                List<String> busyList = new ArrayList<>();
                for (SeatView sv : v.seats()) {
                    if (sv.free()) {
                        freeTotal++;
                        freeList.add(sv.seatNo() + sv.mark());
                    } else {
                        busyList.add(sv.seatNo() + "（" + sv.statusName() + "）");
                    }
                }
                if (!freeList.isEmpty()) {
                    sb.append("\n   ✅ 可约：").append(String.join("、", freeList));
                }
                if (!busyList.isEmpty()) {
                    sb.append("\n   ⛔ 不可约：").append(String.join("、", busyList));
                }
            }
        }

        if (!notes.isEmpty()) {
            sb.append("\n\n");
            sb.append(String.join("；", notes)).append("。");
        }

        if (freeTotal == 0) {
            sb.append("\n\n你常用/收藏的座位在这些时段都被占着，换别的区域吧（可以先问「图书馆还有哪些位置空」）。");
        } else {
            sb.append("\n\n要哪个直接说，比如「约社科借阅区2 的 045，明天下午」。");
        }
        return sb.toString();
    }

    private static String shortAreaName(String nameMerge) {
        if (nameMerge == null || nameMerge.isBlank()) {
            return "某区域";
        }
        int idx = nameMerge.lastIndexOf('-');
        return idx >= 0 ? nameMerge.substring(idx + 1) : nameMerge;
    }

    /** 座位号归一：去「号」、空格，再去前导零（040 与 40 视为同一号） */
    static String seatKey(String no) {
        String s = no == null ? "" : no.replace("号", "").replace(" ", "").trim();
        int i = 0;
        while (i < s.length() - 1 && s.charAt(i) == '0') {
            i++;
        }
        return s.substring(i);
    }

    private static boolean mentions(String kind, String cn, String en) {
        if (kind == null) {
            return false;
        }
        return kind.contains(cn) || kind.toLowerCase().contains(en);
    }

    @Override
    public String getToolName() {
        return TOOL_NAME;
    }

    @Override
    public String getToolDescription() {
        return "查询我的常用/收藏座位今明两天的占用情况与可约座位（人人可调用）";
    }
}
