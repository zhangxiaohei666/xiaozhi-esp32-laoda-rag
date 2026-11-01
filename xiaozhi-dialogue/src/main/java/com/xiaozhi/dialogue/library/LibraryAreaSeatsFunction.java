package com.xiaozhi.dialogue.library;

import com.xiaozhi.ai.tool.ToolsGlobalRegistry;
import com.xiaozhi.ai.tool.session.ToolSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图书馆某区域具体座位工具（场景 2、4）。
 *
 * <p>回答的是"具体哪个位置能坐"：英语角哪些座位空着、自科借阅区1 还有几个空位。
 *
 * <p><b>为什么不一次把 22 个区域的座位全查出来</b>：一个区域一百多个座位，
 * 全馆两千多个，语音念不完也没意义。所以本工具只查**一个**区域；
 * 用户没指定区域时，让模型先用 {@link LibrarySeatOverviewFunction} 拿到区域的空闲数，
 * 再决定查哪个。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "library.enabled", havingValue = "true")
public class LibraryAreaSeatsFunction implements ToolsGlobalRegistry.GlobalFunction {

    public static final String TOOL_NAME = "library_area_seats";

    /** 一次最多列多少个空位：再多念出来用户也记不住 */
    private static final int MAX_FREE_LISTED = 30;
    /** 已占的一条最多列几个 */
    private static final int MAX_TAKEN_LISTED = 12;

    private final LibraryApiClient apiClient;
    private final LibraryAreaResolver areaResolver;

    public LibraryAreaSeatsFunction(LibraryApiClient apiClient, LibraryAreaResolver areaResolver) {
        this.apiClient = apiClient;
        this.areaResolver = areaResolver;
    }

    @Tool(name = TOOL_NAME,
            description = "查询图书馆某个区域内【每一个座位】的实时状态：哪些座位空着可以坐、哪些已被占用。"
                    + "当用户问到「英语角哪些座位空着」「自科借阅区1 还有位置吗」「XX 号座位有人吗」"
                    + "「帮我看看具体哪些位置」这类【要具体座位号】的问题时，调用本工具。"
                    + "如果用户只问「还有没有位置」这种数量问题，用 library_seat_overview 更合适。"
                    + "本工具一次只能查一个区域，area 必填。",
            returnDirect = false)
    public String areaSeats(
            @ToolParam(description = "区域名称，用用户原话即可，例如「英语角」「自科借阅区1」「四楼公共阅览区」。必填。")
            String area,
            @ToolParam(description = "日期。可传「今天」「明天」，或 yyyy-MM-dd。不传默认今天。", required = false)
            String date) {

        try {
            if (area == null || area.isBlank()) {
                return "要查具体座位，得先告诉我哪个区域，比如「英语角」「自科借阅区1」「四楼公共阅览区」。"
                        + "不确定的话可以先问「图书馆还有哪些位置」，我列一下各区域的情况。";
            }

            LibraryDateSupport.Parsed d = LibraryDateSupport.parse(date);
            LibraryAreaResolver.Resolved resolved = areaResolver.resolve(area, d.date());

            if (!resolved.matched()) {
                return "没找到「" + area + "」这个区域。图书馆的楼层和区域是这些——" + resolved.hint()
                        + "。想查哪个直接说名字。";
            }
            if (resolved.areas().isEmpty()) {
                return "「" + area + "」对应的区域有点多，说得再具体一点？"
                        + "比如「三楼公共阅览区」而不是只说「公共阅览区」。";
            }
            if (resolved.areas().size() > 1) {
                return ambiguous(area, resolved, d);
            }

            LibraryApiClient.Area target = resolved.areas().get(0);
            List<LibraryApiClient.Seat> seats = apiClient.seats(target.id(), d.date());
            if (seats.isEmpty()) {
                return floorPrefix(target) + target.name() + "（" + d.date() + " " + d.label()
                        + "）这会儿没有可查的座位数据，可能是这个区域今天不开放。";
            }
            return render(target, seats, d);
        } catch (LibraryUnavailableException e) {
            return e.getMessage() + "。";
        } catch (Exception e) {
            log.warn("{} 执行失败: {}", TOOL_NAME, e.getMessage());
            return "暂时查不到这个区域的座位，稍后再问我一次。";
        }
    }

    // ==================== 文本渲染 ====================

    private String render(LibraryApiClient.Area area, List<LibraryApiClient.Seat> seats,
                          LibraryDateSupport.Parsed d) {
        List<String> free = new ArrayList<>();
        // 已占的按状态分组，念出来更像人话
        Map<String, List<String>> takenByStatus = new LinkedHashMap<>();

        for (LibraryApiClient.Seat s : seats) {
            if (s.isFree()) {
                free.add(s.no());
            } else {
                String status = s.statusName() == null || s.statusName().isBlank() ? "已被占用" : s.statusName();
                takenByStatus.computeIfAbsent(status, k -> new ArrayList<>()).add(s.no());
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append(floorPrefix(area)).append(area.name())
                .append("（").append(d.date()).append(" ").append(d.label()).append("）：");
        sb.append("共 ").append(seats.size()).append(" 个座位，空闲 ").append(free.size()).append(" 个。");

        if (free.isEmpty()) {
            sb.append("\n\n现在没有空座位了，全都被占或锁定了。");
        } else {
            sb.append("\n\n空闲座位：");
            sb.append(joinLimited(free, MAX_FREE_LISTED));
            if (free.size() > MAX_FREE_LISTED) {
                sb.append(" 等共 ").append(free.size()).append(" 个");
            }
        }

        if (!takenByStatus.isEmpty()) {
            sb.append("\n已占用：");
            List<String> parts = new ArrayList<>();
            int budget = MAX_TAKEN_LISTED;
            for (Map.Entry<String, List<String>> e : takenByStatus.entrySet()) {
                if (budget <= 0) {
                    break;
                }
                List<String> nos = e.getValue();
                List<String> show = nos.size() > budget ? nos.subList(0, budget) : nos;
                parts.add(joinLimited(show, budget) + "（" + e.getKey() + "）");
                budget -= show.size();
            }
            sb.append(String.join("、", parts));
            int takenTotal = seats.size() - free.size();
            if (takenTotal > MAX_TAKEN_LISTED) {
                sb.append(" 等共 ").append(takenTotal).append(" 个");
            }
        }

        return sb.toString();
    }

    /** 命中多个区域时，把候选报给模型让它追问 */
    private String ambiguous(String rawArea, LibraryAreaResolver.Resolved resolved,
                             LibraryDateSupport.Parsed d) {
        StringBuilder sb = new StringBuilder();
        sb.append("「").append(rawArea).append("」对应了 ").append(resolved.areas().size()).append(" 个区域：");
        for (LibraryApiClient.Area a : resolved.areas()) {
            sb.append("\n  ").append(floorPrefix(a)).append(a.name());
        }
        sb.append("\n\n想查哪一个？说得具体点就行，比如「").append(floorPrefix(resolved.areas().get(0)))
                .append(resolved.areas().get(0).name()).append("」。");
        return sb.toString();
    }

    private static String joinLimited(List<String> items, int limit) {
        if (items.size() <= limit) {
            return String.join(" ", items);
        }
        return String.join(" ", items.subList(0, limit));
    }

    private static String floorPrefix(LibraryApiClient.Area a) {
        return a.floor() == null || a.floor().isBlank() ? "" : a.floor() + "-";
    }

    // ==================== 注册 ====================

    @Override
    public ToolCallback getFunctionCallTool(ToolSession toolSession) {
        ToolCallback[] tools = ToolCallbacks.from(this);
        return tools.length == 0 ? null : tools[0];
    }

    @Override
    public String getToolName() {
        return TOOL_NAME;
    }

    @Override
    public String getToolDescription() {
        return "查询图书馆某个区域的具体座位状态（哪些座位空着、哪些已被占用）";
    }
}