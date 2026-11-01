package com.xiaozhi.dialogue.llm.tool.function;

import com.xiaozhi.ai.tool.ToolsGlobalRegistry;
import com.xiaozhi.ai.tool.session.ToolSession;
import com.xiaozhi.utils.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 系统全局工具：获取当前日期与时间（默认北京时间 UTC+8）。
 *
 * <p><b>为什么要这个工具</b>：模型本身不知道"今天几号"。没有它，
 * "今天几号""最近有什么通知""医保缴费还有几天截止""下周三是几号"这类问题都只能瞎猜。
 *
 * <p><b>和系统提示词的关系</b>：系统提示词末尾会附带「当前日期」（只到"日"，
 * 一天只变一次，不破坏前缀 KV cache）；本工具负责**精确到分**的时间，
 * 以及**任意偏移的日期推算**（昨天/明天/下周/30 天后）。
 *
 * <p><b>取时纪律</b>：全仓规定业务代码不得自己调 {@code LocalDateTime.now()}，
 * 必须走 {@link DateUtils}，这里遵守该约定（也便于测试固定时间）。
 *
 * <p>本工具只读、无副作用、不涉及用户隐私，因此不需要二次确认。
 */
@Slf4j
@Component
public class GetCurrentDatetimeFunction implements ToolsGlobalRegistry.GlobalFunction {

    public static final String TOOL_NAME = "get_current_datetime";

    /** 偏移天数的合理上限：再大就不是"算日期"而是乱猜了 */
    private static final int MAX_OFFSET_DAYS = 3650;

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.SIMPLIFIED_CHINESE);
    private static final DateTimeFormatter DATE_ONLY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.SIMPLIFIED_CHINESE);
    private static final DateTimeFormatter WEEKDAY =
            DateTimeFormatter.ofPattern("EEEE", Locale.SIMPLIFIED_CHINESE);

    /**
     * 取当前（或相对今天偏移若干天的）日期时间。
     *
     * @param offsetDays 相对今天偏移的天数，可为负数：-1=昨天，0=今天，1=明天，7=下周同一天。
     *                   不传按 0 处理。
     * @return 可直接念给用户听的一行中文文本
     */
    @Tool(name = TOOL_NAME,
            description = "获取当前日期和时间（北京时间 UTC+8）。"
                    + "当用户问到「今天」「现在」「几号」「星期几」「这个月」「今年」「最近」"
                    + "「还有几天」「什么时候到期」等与当前时间相关的问题时，必须先调用本工具拿到真实日期，"
                    + "不要凭对话历史或记忆猜测。"
                    + "若需要推算其他日期（昨天、明天、后天、下周、N 天后等），传入 offsetDays 一次算准。",
            returnDirect = false)
    public String getCurrentDatetime(
            @ToolParam(description = "相对今天偏移的天数，可为负数。"
                    + "-1=昨天，0=今天，1=明天，7=下周同一天。不传默认 0（今天）。",
                    required = false)
            Integer offsetDays) {

        try {
            int offset = offsetDays == null ? 0 : offsetDays;
            if (offset > MAX_OFFSET_DAYS) {
                offset = MAX_OFFSET_DAYS;
            } else if (offset < -MAX_OFFSET_DAYS) {
                offset = -MAX_OFFSET_DAYS;
            }

            // 统一走 DateUtils：时区、时钟都由它决定（默认系统时区，本机为 UTC+8 北京时间）
            var now = DateUtils.now();
            var targetDate = now.toLocalDate().plusDays(offset);
            var target = offset == 0
                    ? now
                    : targetDate.atStartOfDay();

            String zone = describeZone();
            String weekday = WEEKDAY.format(targetDate);

            if (offset == 0) {
                return "当前时间：" + now.format(DATE_TIME) + " " + weekday + "（" + zone + "）";
            }
            // 偏移时只给到"日"，不编造时间
            String sign = offset > 0 ? "+" : "";
            return "目标日期：" + targetDate.format(DATE_ONLY) + " " + weekday + "（" + zone + "）"
                    + "，相对今天 " + sign + offset + " 天"
                    + "（今天是 " + DateUtils.today().format(DATE_ONLY) + " " + weekdayOfToday() + "）";
        } catch (Exception e) {
            // 工具绝不能把异常抛回对话链路
            log.warn("{} 执行失败: {}", TOOL_NAME, e.getMessage());
            return "暂时取不到当前时间，请让用户稍后再试。";
        }
    }

    private static String weekdayOfToday() {
        return WEEKDAY.format(DateUtils.today());
    }

    /**
     * 把时区说成人能听懂的话。
     *
     * <p>注意：不依赖 zoneId 的具体写法。本机 JVM 未指定 user.timezone 时，
     * {@code ZoneId.systemDefault()} 返回的是 {@code GMT+08:00} 而不是 {@code Asia/Shanghai}，
     * 若只按 id 匹配就会拼出「GMT+08:00 UTC+08:00」这种重复文本。
     * 所以这里**先看偏移量**：+08:00 一律称「北京时间」。
     */
    private static String describeZone() {
        try {
            var offset = DateUtils.offset();
            int seconds = offset.getTotalSeconds();
            if (seconds == 8 * 3600) {
                return "北京时间 UTC+08:00";
            }
            var zoneId = DateUtils.zone();
            return zoneId.getId() + " UTC" + offset.getId();
        } catch (Exception e) {
            return "本机时区";
        }
    }

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
        return "获取当前日期时间（北京时间），支持按天数偏移推算任意日期";
    }
}