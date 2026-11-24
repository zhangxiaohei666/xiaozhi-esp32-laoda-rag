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

import java.util.List;
import java.util.Map;

/**
 * 查询我的预约情况工具（只读，**人人可调用**，不做 owner 限制）。
 *
 * <p>数据源 {@code POST /v4/member/seat}（type=1 座位预约，前 10 条，按时间倒序）。
 * 返回中文摘要：日期、区域、座位号、时段、状态、签到/签离时间。
 * 取消/签退/临时离开等写工具从这里定位预约记录 ID。
 *
 * <p><b>为什么不限 owner</b>（2026-11-24 用户拍板）：查询不修改任何东西，
 * 任何登录用户都可以查；本项目是个人自用部署，账号只有本人，无需隔离。
 *
 * <p>注册条件：只要 {@code xiaozhi.library.enabled=true}（不要求 write-enabled）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "xiaozhi.library.enabled", havingValue = "true")
public class LibraryMyBookingsFunction implements ToolsGlobalRegistry.GlobalFunction {

    public static final String TOOL_NAME = "library_my_bookings";

    private static final String TOOL_DESCRIPTION =
            "查询「我」的图书馆座位预约记录：当前预约、历史记录、签到/签离时间。"
            + "当用户问「我的预约」「我约的座位」「预约情况」「签到时间」这类问题时调用。"
            + "只有返回里标着【未签到，可取消】的记录才能取消（交给 library_prepare_cancel）；"
            + "已签到、已结束、已取消的记录不能再取消，不要建议用户取消这些。"
            + "参数 date 可选：「今天」「明天」或不传（不传返回全部近期记录）。";

    private final LibraryApiClient apiClient;

    public LibraryMyBookingsFunction(LibraryApiClient apiClient) {
        this.apiClient = apiClient;
    }

    @Override
    public ToolCallback getFunctionCallTool(ToolSession toolSession) {
        return FunctionToolCallback
                .builder(TOOL_NAME, (Map<String, String> params, ToolContext toolContext) ->
                        execute(params.get("date")))
                .description(TOOL_DESCRIPTION)
                .inputSchema("""
                        {
                            "type": "object",
                            "properties": {
                                "date": {
                                    "type": "string",
                                    "description": "过滤日期：「今天」或「明天」。用户没指定就不传。"
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
    String execute(String dateText) {
        try {
            List<LibraryApiClient.Booking> all = apiClient.myBookings();

            List<LibraryApiClient.Booking> list = all;
            String scope = "";
            if (dateText != null && !dateText.isBlank()) {
                LibraryDateSupport.Parsed d = LibraryDateSupport.parse(dateText);
                String mmdd = d.date().length() >= 10 ? d.date().substring(5) : d.date();
                list = all.stream().filter(b -> mmdd.equals(b.day())).toList();
                scope = d.label();
            }

            if (list.isEmpty()) {
                return all.isEmpty()
                        ? "没查到你的座位预约记录。"
                        : (scope + "没有预约。" + "最近的记录是：\n" + renderOne(all.get(0)));
            }

            StringBuilder sb = new StringBuilder();
            sb.append(all.size() > list.size() ? scope + "有 " + list.size() + " 条预约：" : "你的座位预约：");
            for (int i = 0; i < Math.min(list.size(), 5); i++) {
                sb.append("\n\n").append(renderOne(list.get(i)));
            }
            if (list.size() > 5) {
                sb.append("\n\n（只列了最近 5 条，共 ").append(list.size()).append(" 条）");
            }
            return sb.toString();
        } catch (LibraryUnavailableException e) {
            return e.getMessage() + "。";
        } catch (Exception e) {
            log.warn("{} 执行失败: {}", TOOL_NAME, e.getMessage());
            return "这会儿查不到预约记录，稍后再问我一次。";
        }
    }

    private String renderOne(LibraryApiClient.Booking b) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(b.statusName() == null ? "状态未知" : b.statusName()).append("] ");
        sb.append(b.day()).append(" ").append(b.start()).append("-").append(b.end()).append("，");
        sb.append("「").append(b.areaName()).append("」").append(b.seatNo()).append(" 号");

        if (b.signInTime() != null && !b.signInTime().isBlank()) {
            sb.append("；已签到 ").append(b.signInTime());
        }
        if (b.signOutTime() != null && !b.signOutTime().isBlank()) {
            sb.append("；已签离 ").append(b.signOutTime());
        }
        if (b.cancelTime() != null && !b.cancelTime().isBlank()) {
            sb.append("；已取消 ").append(b.cancelTime());
        }
        if (LibraryBookingHelper.cancellable(b)) {
            sb.append("；【未签到，可取消】");
        }
        return sb.toString();
    }

    @Override
    public String getToolName() {
        return TOOL_NAME;
    }

    @Override
    public String getToolDescription() {
        return "查询我的图书馆座位预约记录（人人可调用）";
    }
}
