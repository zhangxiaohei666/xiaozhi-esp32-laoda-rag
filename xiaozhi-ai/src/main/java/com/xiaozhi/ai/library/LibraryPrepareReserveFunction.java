package com.xiaozhi.ai.library;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xiaozhi.ai.llm.tool.ToolCallStringResultConverter;
import com.xiaozhi.ai.tool.ToolsGlobalRegistry;
import com.xiaozhi.ai.tool.session.ToolSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 图书馆座位预约【准备】工具：查空位、选座、生成待确认单，但<b>不真正下单</b>。
 *
 * <p>两段式确认的第一段（评审定稿核心设计）：本工具只做只读的事——
 * 解析区域/日期/时段 → 读 {@code Space/map} 拿 reserveType → 查空位挑座 →
 * 把全量快照写进 Redis 待确认（{@link LibraryPendingActionStore}）。
 * 真正的写接口（AES + confirm）由 {@link LibraryConfirmActionFunction} 在用户说"确认"后执行。
 *
 * <p><b>为什么不让模型一步到位</b>：预约是写操作，写错座位在图书馆系统里就是违约。
 * 分离后，"确认"不再需要模型传任何参数，快照是什么就执行什么，多轮对话措辞变化也改不动它。
 *
 * <p>权限：写工具仅 admin（{@link LibraryOwnerGuard}，注册期从会话绑定 userId）。
 *
 * <p>注册条件：{@code xiaozhi.library.enabled=true} 且 {@code xiaozhi.library.write-enabled=true}
 * （@ConditionalOnProperty 多 name 为 AND 语义）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = {"xiaozhi.library.enabled", "xiaozhi.library.write-enabled"}, havingValue = "true")
public class LibraryPrepareReserveFunction implements ToolsGlobalRegistry.GlobalFunction {

    public static final String TOOL_NAME = "library_prepare_reserve";

    private static final String TOOL_DESCRIPTION =
            "图书馆座位预约的【准备】工具：查该区域在用户要的日期和时段还有没有空位，选好座位后"
            + "生成一条待确认的预约单，但【不真正下单】。"
            + "用户指定了座位号就按号找（该号不空闲会如实说明）；没指定就自动挑一个空位。"
            + "有的区域一天有多个时段（例如社科借阅区2 分上午和下午两段），要把用户说的时段传进 time；"
            + "不传就默认第一个时段。返回里会写明最终约的起止时间，念给用户听。"
            + "调用后把返回内容念给用户，等用户明确说「确认」「约吧」之后，"
            + "再调用 library_confirm_action 去真正提交。用户没明确同意时绝不能提交。"
            + "参数：area=区域名（必填）；date=今天或明天（不传默认今天）；seatNo=座位号（可选）；"
            + "time=时段（可选，「上午」「下午」「晚上」「14:00-16:00」这类）。"
            + "如果用户只是问「有没有空位」「还剩多少」，用 library_seat_overview 或 library_area_seats，不要用本工具。";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LibraryApiClient apiClient;
    private final LibraryAreaResolver areaResolver;
    private final LibraryPendingActionStore pendingStore;
    private final LibraryOwnerGuard ownerGuard;

    public LibraryPrepareReserveFunction(LibraryApiClient apiClient, LibraryAreaResolver areaResolver,
                                         LibraryPendingActionStore pendingStore, LibraryOwnerGuard ownerGuard) {
        this.apiClient = apiClient;
        this.areaResolver = areaResolver;
        this.pendingStore = pendingStore;
        this.ownerGuard = ownerGuard;
    }

    @Override
    public ToolCallback getFunctionCallTool(ToolSession toolSession) {
        // 注册期绑定会话身份（KnowledgeSearchFunction 同款模式）：
        // userId 从 ToolSession 取，绝不接受模型参数传入。
        Integer userId = toolSession.getUserId();
        return FunctionToolCallback
                .builder(TOOL_NAME, (Map<String, String> params, ToolContext toolContext) ->
                        execute(userId, params))
                .description(TOOL_DESCRIPTION)
                .inputSchema("""
                        {
                            "type": "object",
                            "properties": {
                                "area": {
                                    "type": "string",
                                    "description": "区域名称，用用户原话即可，例如「英语角」「艺术沙龙」「四楼公共阅览区」。必填。"
                                },
                                "date": {
                                    "type": "string",
                                    "description": "预约日期：只能「今天」或「明天」，不传默认今天。"
                                },
                                "seatNo": {
                                    "type": "string",
                                    "description": "用户指定的座位号（如「045」「12号」）。没指定就不传，让本工具自动选空位。"
                                },
                                "time": {
                                    "type": "string",
                                    "description": "时段。可传关键词「上午」「下午」「晚上」，或精确区间「14:00-16:00」，或「14点」。用户没提就不传，不要自己编。"
                                }
                            },
                            "required": ["area"]
                        }
                        """)
                .inputType(Map.class)
                .toolCallResultConverter(ToolCallStringResultConverter.INSTANCE)
                .build();
    }

    /**
     * 执行体：只读准备，不碰任何写接口。
     */
    String execute(Integer userId, Map<String, String> params) {
        try {
            String reject = ownerGuard.rejectReason(userId);
            if (reject != null) {
                return reject;
            }

            String area = params.get("area");
            if (area == null || area.isBlank()) {
                return "要预约座位，得先告诉我哪个区域，比如「英语角」「艺术沙龙」。"
                        + "不确定的话可以先问「图书馆还有哪些位置」。";
            }

            LibraryDateSupport.Parsed d = LibraryDateSupport.parse(params.get("date"));
            LibraryAreaResolver.Resolved resolved = areaResolver.resolve(area, d.date());
            if (!resolved.matched()) {
                return "没找到「" + area + "」这个区域。图书馆的楼层和区域是这些——" + resolved.hint()
                        + "。想约哪个直接说名字。";
            }
            if (resolved.areas().isEmpty()) {
                return "「" + area + "」对应的区域有点多，说得再具体一点？"
                        + "比如「三楼公共阅览区」而不是只说「公共阅览区」。";
            }
            if (resolved.areas().size() > 1) {
                return ambiguousArea(area, resolved);
            }

            LibraryApiClient.Area target = resolved.areas().get(0);

            // ① 先读 Space/map 拿 reserveType（评审 B-2：先知道类型再解析时段，顺序不能反）
            LibraryApiClient.AreaMap map = apiClient.areaMap(target.id());
            if (!"1".equals(map.type())) {
                return "「" + target.name() + "」不是普通座位区域（类型：" + map.typeName()
                        + "），这个功能只支持普通座位。";
            }
            LibraryApiClient.DateSlot slot = map.dateOf(d.date());
            if (slot == null) {
                return d.label() + "「" + target.name() + "」没有开放预约，可能是闭馆日。";
            }

            // ② 按 reserveType 解析时段
            String reserveType = map.reserveType() == null ? "" : map.reserveType();
            String segment = "";
            String startTime;
            String endTime;
            String timeLabel;
            LibraryTimeSupport.Slot parsed = LibraryTimeSupport.parse(params.get("time"));

            switch (reserveType) {
                case "1" -> {
                    // 时段型：按用户说法在多段里选段（2026-11-24 修正）。
                    // 社科借阅区2 明天有 08:00-11:50 与 14:30-21:30 两段，
                    // 原来无脑取 firstSeg 会把「明天下午」悄悄约成上午，违背「不许悄悄改」。
                    LibraryApiClient.TimeSeg seg = LibraryTimeSupport.pickSegment(slot, parsed);
                    if (seg == null) {
                        return d.label() + "「" + target.name() + "」这会儿没有可约时段，稍后再试。";
                    }
                    segment = seg.id();
                    startTime = seg.start();
                    endTime = seg.end();
                    timeLabel = seg.start() + "-" + seg.end();
                }
                case "2" -> {
                    // 只要结束时间
                    if (parsed == null || parsed.end() == null) {
                        return "「" + target.name() + "」的预约方式需要你指定到几点结束，"
                                + "比如「到 16:00」。要哪个时段，再说一下？";
                    }
                    startTime = "";
                    endTime = parsed.end();
                    segment = "";
                    timeLabel = endTime + " 前";
                }
                case "3" -> {
                    // 起止时间都要
                    if (parsed == null || !parsed.complete()) {
                        return "预约需要知道时段，说「上午」「下午」「晚上」或「14:00-16:00」都行。"
                                + "你想约哪个时段？";
                    }
                    // 今天已过的时段改写为"现在起"（改动写进 notice，必须明说）
                    LibraryTimeSupport.Slot adjusted = "今天".equals(d.label())
                            ? LibraryTimeSupport.adjustIfPastToday(parsed) : parsed;
                    if (adjusted.changedNotice() != null) {
                        return "预约时段要改一下：你说的 " + parsed.start() + " 点已经过了，"
                                + "我按现在算就是 " + adjusted.start() + " 到 " + adjusted.end()
                                + "。按这个时段约吗？确认的话再跟我说一次。";
                    }
                    startTime = adjusted.start();
                    endTime = adjusted.end();
                    segment = "";
                    timeLabel = startTime + "-" + endTime;
                }
                default -> {
                    return "「" + target.name() + "」的预约方式（类型 " + reserveType
                            + "）还没摸清楚，暂时约不了。先在网页上约吧。";
                }
            }

            // ③ 查空位并选座（seatNo → Seat.id 映射在同一次返回里完成）
            List<LibraryApiClient.Seat> seats = apiClient.seats(target.id(), d.date(), startTime, endTime);
            LibraryApiClient.Seat chosen = chooseSeat(seats, params.get("seatNo"));
            if (chosen == null) {
                return renderNoSeat(target, d, timeLabel, params.get("seatNo"));
            }

            // ④ 同日冲突预检（best-effort：失败不阻塞）
            String conflict = sameDayConflict(d.date());

            // ⑤ 写待确认快照
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("seatId", chosen.id());
            payload.put("seatNo", chosen.no());
            payload.put("areaId", target.id());
            payload.put("areaName", target.name());
            payload.put("date", d.date());
            payload.put("startTime", startTime);
            payload.put("endTime", endTime);
            payload.put("segment", segment);
            payload.put("reserveType", reserveType);

            String summary = "「" + target.name() + "」" + d.label() + " " + timeLabel
                    + "，" + chosen.no() + " 号还空着。";
            if (conflict != null) {
                summary += "\n" + conflict;
            }
            summary += "\n确认的话我就下单。" + signReminder(d.label());

            LibraryPendingActionStore.Pending overwritten =
                    pendingStore.save(userId, LibraryPendingActionStore.Type.RESERVE, summary, payload);

            StringBuilder out = new StringBuilder();
            if (overwritten != null) {
                out.append("（注意：之前那条待确认的已作废。）\n\n");
            }
            out.append(summary);
            return out.toString();
        } catch (LibraryUnavailableException e) {
            return e.getMessage() + "。";
        } catch (Exception e) {
            log.warn("{} 执行失败: {}", TOOL_NAME, e.getMessage());
            return "这会儿没准备好预约信息，稍后再试一次。";
        }
    }

    /** 选座：指定座位号则按号找（必须空闲）；否则选编号最小的空闲座 */
    private LibraryApiClient.Seat chooseSeat(List<LibraryApiClient.Seat> seats, String seatNo) {
        LibraryApiClient.Seat firstFree = null;
        for (LibraryApiClient.Seat s : seats) {
            if (!s.isFree()) {
                continue;
            }
            if (firstFree == null) {
                firstFree = s;
            }
            if (seatNo != null && !seatNo.isBlank() && normalizeNo(s.no()).equals(normalizeNo(seatNo))) {
                return s;
            }
        }
        if (seatNo == null || seatNo.isBlank()) {
            return firstFree;
        }
        return null;
    }

    private static String normalizeNo(String no) {
        return no == null ? "" : no.replace("号", "").replace(" ", "").trim();
    }

    private String renderNoSeat(LibraryApiClient.Area target, LibraryDateSupport.Parsed d,
                                String timeLabel, String seatNo) {
        if (seatNo != null && !seatNo.isBlank()) {
            return "「" + target.name() + "」" + d.label() + " " + timeLabel
                    + "，" + seatNo + " 号不空闲或没找到这个座位号。换个座位号，或让我自动挑一个空位？";
        }
        return "「" + target.name() + "」" + d.label() + " " + timeLabel + " 没有空位了。"
                + "可以换个区域，或先问「图书馆还有哪些位置」看看哪里空。";
    }

    /** 同日冲突预检：已有同天未结束的预约时提醒（best-effort，查不到就算了） */
    private String sameDayConflict(String date) {
        try {
            String mmdd = date.length() >= 10 ? date.substring(5) : date;
            for (LibraryApiClient.Booking b : apiClient.myBookings()) {
                if (mmdd.equals(b.day())
                        && !"已结束".equals(b.statusName())
                        && !"用户取消".equals(b.statusName())) {
                    return "注意：你" + b.day() + "在「" + b.areaName() + "」" + b.seatNo()
                            + " 号已经有一条预约（" + b.start() + "-" + b.end() + "），再约会重叠。";
                }
            }
        } catch (Exception ignored) {
            // 冲突预检失败不阻塞主流程
        }
        return null;
    }

    private static String signReminder(String dayLabel) {
        return "预约后不用手动签到，到馆走门禁闸机刷卡就自动签到；"
                + ("今天".equals(dayLabel) ? "记得在预约时段开始后 60 分钟内入馆。" : "明天在时段开始后 60 分钟内入馆。");
    }

    private static String ambiguousArea(String rawArea, LibraryAreaResolver.Resolved resolved) {
        StringBuilder sb = new StringBuilder();
        sb.append("「").append(rawArea).append("」对应了 ").append(resolved.areas().size()).append(" 个区域：");
        for (LibraryApiClient.Area a : resolved.areas()) {
            sb.append("\n  ").append(floorPrefix(a)).append(a.name());
        }
        sb.append("\n\n想约哪一个？说得具体点，比如「").append(floorPrefix(resolved.areas().get(0)))
                .append(resolved.areas().get(0).name()).append("」。");
        return sb.toString();
    }

    private static String floorPrefix(LibraryApiClient.Area a) {
        return a.floor() == null || a.floor().isBlank() ? "" : a.floor() + "-";
    }

    @Override
    public String getToolName() {
        return TOOL_NAME;
    }

    @Override
    public String getToolDescription() {
        return "图书馆座位预约准备（查空位选座，生成待确认单，不真正下单）";
    }
}
