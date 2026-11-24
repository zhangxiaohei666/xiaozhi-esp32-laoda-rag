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
 * 签退【准备】工具：定位"使用中/临时离开"的记录，生成待确认单，不真执行。
 *
 * <p>语义（与临时离开严格区分，模型靠描述选工具）：
 * <ul>
 *   <li><b>签退</b>：今天用完了、离开图书馆，座位立即释放；不签退会记"未签离"违规</li>
 *   <li><b>临时离开</b>：暂时出馆，座位保留 60 分钟</li>
 * </ul>
 *
 * <p>真正执行在 {@link LibraryConfirmActionFunction}（{@code /v4/space/checkout}，未实测）。
 * 权限：仅 admin。注册条件：enabled + write-enabled。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = {"xiaozhi.library.enabled", "xiaozhi.library.write-enabled"}, havingValue = "true")
public class LibraryPrepareCheckoutFunction implements ToolsGlobalRegistry.GlobalFunction {

    public static final String TOOL_NAME = "library_prepare_checkout";

    private static final String TOOL_DESCRIPTION =
            "图书馆座位【签退】的【准备】工具：找到正在使用（或临时离开中）的座位记录并生成待确认单，"
            + "但【不真正执行】。用户今天用完了、要离开图书馆时用本工具（座位立即释放）。"
            + "只是暂时出馆、座位还想保留时用 library_prepare_leave（临时离开），不要用本工具。"
            + "只对已签到（使用中或临时离开中）的记录有效；只是预约了还没签到、不打算去的情况是取消，用 library_prepare_cancel。"
            + "调用后把返回内容念给用户，用户明确同意后再调用 library_confirm_action 真正执行。"
            + "没有参数。";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LibraryApiClient apiClient;
    private final LibraryPendingActionStore pendingStore;
    private final LibraryOwnerGuard ownerGuard;

    public LibraryPrepareCheckoutFunction(LibraryApiClient apiClient,
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
                        execute(userId))
                .description(TOOL_DESCRIPTION)
                .inputSchema("""
                        {
                            "type": "object",
                            "properties": {}
                        }
                        """)
                .inputType(Map.class)
                .toolCallResultConverter(ToolCallStringResultConverter.INSTANCE)
                .build();
    }

    String execute(Integer userId) {
        try {
            String reject = ownerGuard.rejectReason(userId);
            if (reject != null) {
                return reject;
            }

            List<LibraryApiClient.Booking> all = apiClient.myBookings();
            List<LibraryApiClient.Booking> active = LibraryBookingHelper.active(all);

            if (active.isEmpty()) {
                return "你现在没有正在使用的座位，没有可签退的记录。"
                        + "预约还没到时间的记录用「取消」处理。";
            }
            if (active.size() > 1) {
                return LibraryBookingHelper.listForPick(active);
            }

            LibraryApiClient.Booking b = active.get(0);

            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("recordId", b.id());
            payload.put("areaName", b.areaName());
            payload.put("seatNo", b.seatNo());
            payload.put("day", b.day());

            String summary = "要对 " + LibraryBookingHelper.describe(b) + " 签退吗？"
                    + "签退后座位立即释放；不签退直接走会记一次违规。确认吗？";

            LibraryPendingActionStore.Pending overwritten =
                    pendingStore.save(userId, LibraryPendingActionStore.Type.CHECKOUT, summary, payload);

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
            return "这会儿没准备好签退操作，稍后再试一次。";
        }
    }

    @Override
    public String getToolName() {
        return TOOL_NAME;
    }

    @Override
    public String getToolDescription() {
        return "图书馆座位签退准备（定位使用中记录，生成待确认单）";
    }
}
