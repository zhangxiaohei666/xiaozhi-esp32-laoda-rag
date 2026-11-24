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
 * 取消预约【准备】工具：定位可取消的预约记录，生成待确认单，不真取消。
 *
 * <p>适用场景（实测边界）：约了座位但去不了，在<b>还没签到</b>时取消。
 * 官方文案说"预约后 60 分钟内可取消、每天限一次"，但 2026-11-24 实测：
 * 当天第二次取消系统照样放行、member/seat 的 cancel 字段标 0 也不拦写接口、
 * "预约成功"的状态码是 9 不是 2。所以本工具按"status_name 含预约成功 + 未签到"
 * 进确认，最终由学校取消接口裁决。
 *
 * <p>真正执行在 {@link LibraryConfirmActionFunction}（{@code /v4/space/cancel}，已实测）。
 * 权限：仅 admin。注册条件：enabled + write-enabled。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = {"xiaozhi.library.enabled", "xiaozhi.library.write-enabled"}, havingValue = "true")
public class LibraryPrepareCancelFunction implements ToolsGlobalRegistry.GlobalFunction {

    public static final String TOOL_NAME = "library_prepare_cancel";

    private static final String TOOL_DESCRIPTION =
            "取消图书馆座位预约的【准备】工具：找到当前可取消的预约记录并生成待确认单，但【不真正取消】。"
            + "调用后把返回内容念给用户，用户明确同意后再调用 library_confirm_action 真正取消。"
            + "只对「预约成功且未签到」的记录进确认（最终能否取消由学校系统裁决）。"
            + "参数 date、seatNo 都可选：用户指定了哪条就传，没指定就不传。";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LibraryApiClient apiClient;
    private final LibraryPendingActionStore pendingStore;
    private final LibraryOwnerGuard ownerGuard;

    public LibraryPrepareCancelFunction(LibraryApiClient apiClient,
                                        LibraryPendingActionStore pendingStore,
                                        LibraryOwnerGuard ownerGuard) {
        this.apiClient = apiClient;
        this.pendingStore = pendingStore;
        this.ownerGuard = ownerGuard;
    }

    @Override
    public ToolCallback getFunctionCallTool(ToolSession toolSession) {
        Integer userId = toolSession.getUserId();
        return FunctionToolCallback
                .builder(TOOL_NAME, (Map<String, String> params, ToolContext toolContext) ->
                        execute(userId, params.get("date"), params.get("seatNo")))
                .description(TOOL_DESCRIPTION)
                .inputSchema("""
                        {
                            "type": "object",
                            "properties": {
                                "date": {
                                    "type": "string",
                                    "description": "预约日期「今天」或「明天」。用户没指定就不传。"
                                },
                                "seatNo": {
                                    "type": "string",
                                    "description": "座位号。用户没指定就不传。"
                                }
                            }
                        }
                        """)
                .inputType(Map.class)
                .toolCallResultConverter(ToolCallStringResultConverter.INSTANCE)
                .build();
    }

    String execute(Integer userId, String dateText, String seatNo) {
        try {
            String reject = ownerGuard.rejectReason(userId);
            if (reject != null) {
                return reject;
            }

            List<LibraryApiClient.Booking> all = apiClient.myBookings();
            List<LibraryApiClient.Booking> candidates = LibraryBookingHelper.cancellable(all)
                    .stream()
                    .filter(b -> matchDate(b, dateText))
                    .filter(b -> matchSeatNo(b, seatNo))
                    .toList();

            if (candidates.isEmpty()) {
                // 2026-11-24 修正：原来用 member/seat 的 cancel 字段判断「有没有可取消的」，
                // 但该字段已实测不可信（标 0 仍可取消）。改按统一口径：
                // status_name 含「预约成功」且未签到。
                boolean anyCancellable = !LibraryBookingHelper.cancellable(all).isEmpty();
                if (anyCancellable) {
                    return "按你给的条件没找到可取消的预约。可以问「我的预约」看看当前有哪些记录。";
                }
                return "没有当前可取消的预约（要「预约成功」且还没签到才能取消）。问「我的预约」可以看当前有哪些记录，已经过点的就去图书馆现场处理。";
            }
            if (candidates.size() > 1) {
                return LibraryBookingHelper.listForPick(candidates);
            }

            LibraryApiClient.Booking b = candidates.get(0);

            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("recordId", b.id());
            payload.put("areaName", b.areaName());
            payload.put("seatNo", b.seatNo());
            payload.put("day", b.day());

            String summary = "要对 " + LibraryBookingHelper.describe(b) + " 取消预约吗？"
                    + "取消不计违约。确认的话我就取消。";

            LibraryPendingActionStore.Pending overwritten =
                    pendingStore.save(userId, LibraryPendingActionStore.Type.CANCEL, summary, payload);

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
            return "这会儿没准备好取消操作，稍后再试一次。";
        }
    }

    private static boolean matchDate(LibraryApiClient.Booking b, String dateText) {
        if (dateText == null || dateText.isBlank()) {
            return true;
        }
        try {
            LibraryDateSupport.Parsed d = LibraryDateSupport.parse(dateText);
            String mmdd = d.date().length() >= 10 ? d.date().substring(5) : d.date();
            return mmdd.equals(b.day());
        } catch (Exception ignored) {
            return true; // 日期说法解析不了就不按日期过滤
        }
    }

    private static boolean matchSeatNo(LibraryApiClient.Booking b, String seatNo) {
        if (seatNo == null || seatNo.isBlank()) {
            return true;
        }
        String n = seatNo.replace("号", "").replace(" ", "").trim();
        return n.equals(b.seatNo() == null ? "" : b.seatNo().replace("号", "").replace(" ", "").trim());
    }

    @Override
    public String getToolName() {
        return TOOL_NAME;
    }

    @Override
    public String getToolDescription() {
        return "取消图书馆座位预约准备（定位可取消记录，生成待确认单）";
    }
}
