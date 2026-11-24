package com.xiaozhi.ai.library;

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
import java.util.List;

/**
 * 图书馆座位总览工具（场景 1、3）。
 *
 * <p>回答的是"数量"类问题：图书馆现在人多吗、三楼还有位置吗、明天好约吗。
 *
 * <p>数据来源 {@code POST /v4/space/pick}，一次请求同时拿到楼层和区域的统计。
 *
 * <p>返回**纯中文文本**而不是 JSON：本工具注册为 {@code returnDirect = false}，
 * 模型要基于结果组织语言，给结构化中文比给 JSON 更容易说对。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "xiaozhi.library.enabled", havingValue = "true")
public class LibrarySeatOverviewFunction implements ToolsGlobalRegistry.GlobalFunction {

    public static final String TOOL_NAME = "library_seat_overview";

    /** 区域榜单最多列几个：太多会超出语音播报的耐心 */
    private static final int TOP_AREAS = 6;

    private final LibraryApiClient apiClient;
    private final LibraryAreaResolver areaResolver;

    public LibrarySeatOverviewFunction(LibraryApiClient apiClient, LibraryAreaResolver areaResolver) {
        this.apiClient = apiClient;
        this.areaResolver = areaResolver;
    }

    @Tool(name = TOOL_NAME,
            description = "查询图书馆座位的总体情况（可按日期查今天或明天）：全馆座位总数与空闲数、"
                    + "各楼层的空闲数、各区域的空闲数。"
                    + "当用户问到「图书馆现在人多吗」「还有没有位置」「还剩多少座位」「三楼人多不多」"
                    + "「哪个区域最空」「今天/明天好不好约」这类【问数量、问整体】的问题时，调用本工具。"
                    + "同一轮里不要为了「确认一下」立刻重复调用同一个楼层和日期："
                    + "服务端有 30 秒短缓存，重复调用拿到的还是同一份数字。"
                    + "如果用户问的是「具体哪几个座位空着」，改用 library_area_seats。",
            returnDirect = false)
    public String overview(
            @ToolParam(description = "楼层或区域名称，用用户原话即可，例如「三楼」「英语角」「四楼公共阅览区」。"
                    + "不传表示查全馆。", required = false)
            String area,
            @ToolParam(description = "日期：只能「今天」或「明天」，不传默认今天。", required = false)
            String date) {

        try {
            LibraryDateSupport.Parsed d = LibraryDateSupport.parse(date);
            LibraryAreaResolver.Resolved resolved = areaResolver.resolve(area, d.date());

            if (!resolved.matched()) {
                return "没找到「" + area + "」这个区域。图书馆的楼层和区域是这些——" + resolved.hint()
                        + "。想查哪个直接说名字。";
            }

            LibraryApiClient.Overview ov = apiClient.pick(d.date());

            if (resolved.isWholeLibrary()) {
                return renderWhole(ov, d);
            }
            return renderScoped(ov, resolved, d, area);
        } catch (LibraryUnavailableException e) {
            // 技术失败一律转成人话，绝不把异常抛回对话链路
            return e.getMessage() + "。";
        } catch (Exception e) {
            log.warn("{} 执行失败: {}", TOOL_NAME, e.getMessage());
            return "暂时查不到图书馆座位情况，稍后再问我一次。";
        }
    }

    // ==================== 文本渲染 ====================

    private String renderWhole(LibraryApiClient.Overview ov, LibraryDateSupport.Parsed d) {
        StringBuilder sb = new StringBuilder();
        sb.append("图书馆座位（").append(d.date()).append(" ").append(d.label()).append("）：");
        sb.append("总座位 ").append(ov.totalSeats()).append(" 个，空闲 ").append(ov.freeSeats()).append(" 个。");

        if (!ov.storeys().isEmpty()) {
            sb.append("\n\n各楼层：");
            for (LibraryApiClient.Storey s : ov.storeys()) {
                sb.append("\n  ").append(s.name()).append(" 空闲 ").append(s.free())
                        .append(" / ").append(s.total());
            }
        }

        List<LibraryApiClient.Area> top = ov.areasByFreeDesc();
        if (!top.isEmpty()) {
            sb.append("\n\n空闲最多的").append(Math.min(TOP_AREAS, top.size())).append("个区域：");
            for (int i = 0; i < Math.min(TOP_AREAS, top.size()); i++) {
                LibraryApiClient.Area a = top.get(i);
                sb.append("\n  ").append(floorPrefix(a)).append(a.name())
                        .append(" 空闲 ").append(a.free()).append(" / ").append(a.total());
            }
        }
        return sb.toString();
    }

    private String renderScoped(LibraryApiClient.Overview ov, LibraryAreaResolver.Resolved resolved,
                               LibraryDateSupport.Parsed d, String rawArea) {
        StringBuilder sb = new StringBuilder();

        // 单区域：给数字 + 一句判断
        if (resolved.areas().size() == 1) {
            LibraryApiClient.Area target = resolved.areas().get(0);
            // 用最新统计重新取一次，避免目录缓存里的数字过期
            LibraryApiClient.Area fresh = findFresh(ov, target.id());
            LibraryApiClient.Area a = fresh != null ? fresh : target;

            sb.append(floorPrefix(a)).append(a.name()).append("（").append(d.date())
                    .append(" ").append(d.label()).append("）：");
            sb.append("共 ").append(a.total()).append(" 个座位，空闲 ").append(a.free()).append(" 个。");
            sb.append(judge(a.free(), a.total()));
            return sb.toString();
        }

        // 多区域（例如只说"三楼"，或说了"公共阅览区"命中好几层）
        List<LibraryApiClient.Area> list = new ArrayList<>();
        for (LibraryApiClient.Area a : resolved.areas()) {
            LibraryApiClient.Area fresh = findFresh(ov, a.id());
            list.add(fresh != null ? fresh : a);
        }
        list.sort((x, y) -> Integer.compare(y.free(), x.free()));

        int total = 0;
        int free = 0;
        for (LibraryApiClient.Area a : list) {
            total += a.total();
            free += a.free();
        }

        String scope = rawArea == null || rawArea.isBlank() ? "你问的范围" : rawArea;
        sb.append(scope).append("（").append(d.date()).append(" ").append(d.label()).append("）：");
        sb.append("共 ").append(total).append(" 个座位，空闲 ").append(free).append(" 个。");
        sb.append("\n\n其中：");
        for (LibraryApiClient.Area a : list) {
            sb.append("\n  ").append(floorPrefix(a)).append(a.name())
                    .append(" 空闲 ").append(a.free()).append(" / ").append(a.total());
        }
        return sb.toString();
    }

    private static LibraryApiClient.Area findFresh(LibraryApiClient.Overview ov, String areaId) {
        for (LibraryApiClient.Area a : ov.areas()) {
            if (a.id().equals(areaId)) {
                return a;
            }
        }
        return null;
    }

    private static String floorPrefix(LibraryApiClient.Area a) {
        return a.floor() == null || a.floor().isBlank() ? "" : a.floor() + "-";
    }

    /** 用一句话说出"紧不紧张"，比让模型自己判断更稳 */
    private static String judge(int free, int total) {
        if (total <= 0) {
            return "";
        }
        double ratio = (double) free / total;
        if (free == 0) {
            return "这个位置已经满了，得等有人离开或换别的区域。";
        }
        if (ratio < 0.1) {
            return "剩得不多了，比较紧张。";
        }
        if (ratio < 0.4) {
            return "还有一定余量，但热门时段要抓紧。";
        }
        return "空位很充裕，基本随便挑。";
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
        return "查询图书馆座位总体情况（按日期，各楼层/各区域的空闲座位数）";
    }
}