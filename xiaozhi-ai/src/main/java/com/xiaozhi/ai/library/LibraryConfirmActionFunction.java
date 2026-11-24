package com.xiaozhi.ai.library;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaozhi.ai.llm.tool.ToolCallStringResultConverter;
import com.xiaozhi.ai.tool.ToolsGlobalRegistry;
import com.xiaozhi.ai.tool.session.ToolSession;
import com.xiaozhi.common.model.bo.OperationLogBO;
import com.xiaozhi.operationlog.service.OperationLogService;
import com.xiaozhi.utils.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * 统一确认执行工具：图书馆座位写操作的<b>唯一写入口</b>。
 *
 * <p>两段式确认的第二段：读 Redis 待确认快照 → 校验 admin → 按类型执行真正的写接口。
 * 所有写请求都从这里发出（{@link LibraryWriteClient}，单发不重试），
 * prepare_* 工具没有任何写接口调用。模型看到 {@code confirm} 才执行、别的名字都不执行，
 * 这个区分体现在工具名上（评审定稿）。
 *
 * <p>流程：
 * <ol>
 *   <li>admin 校验（{@link LibraryOwnerGuard}）</li>
 *   <li>读待确认：没有/已过期 → 提示</li>
 *   <li>幂等：Redis SETNX（RESERVE 按 用户+日期+座位+时段；CANCEL/LEAVE/CHECKOUT 按记录 id）</li>
 *   <li>RESERVE 提交前用快照里的 seatId <b>重查一次座位</b>：从"看到空位"到"点确认"之间
 *       可能已被别人约走，不空闲就拒绝，而不是把学校的报错原样转给用户</li>
 *   <li>写接口单发：超时返回"结果不确定"，提示查记录，绝不自动重发</li>
 *   <li>写审计（{@link OperationLogService}，异步）；审计失败不影响写结果</li>
 * </ol>
 *
 * <p>权限：仅 admin。注册条件：enabled + write-enabled。
 *
 * <p>⚠️ 依赖边界说明：{@code xiaozhi-ai} 首次引用 service 层（OperationLogService），
 * 理由：写操作必须进现有审计表（sys_operation_log），复用既有异步写入而非另建一套。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = {"xiaozhi.library.enabled", "xiaozhi.library.write-enabled"}, havingValue = "true")
public class LibraryConfirmActionFunction implements ToolsGlobalRegistry.GlobalFunction {

    public static final String TOOL_NAME = "library_confirm_action";

    private static final String TOOL_DESCRIPTION =
            "执行图书馆座位写操作（预约/取消/临时离开/签退）的唯一工具。"
            + "只有在前一个工具（library_prepare_reserve / library_prepare_cancel / "
            + "library_prepare_leave / library_prepare_checkout）返回了待确认单、"
            + "且用户明确说「确认」「好的，执行」之后才调用本工具。"
            + "没有待确认时不要调用；用户没同意时不要调用。没有参数。";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String IDEMPOTENT_PREFIX = "xiaozhi:library:done:";
    private static final Duration IDEMPOTENT_TTL = Duration.ofMinutes(10);

    private final LibraryApiClient apiClient;
    private final LibraryWriteClient writeClient;
    private final LibraryPendingActionStore pendingStore;
    private final LibraryOwnerGuard ownerGuard;
    private final StringRedisTemplate redis;
    private final ObjectProvider<OperationLogService> operationLogService;

    public LibraryConfirmActionFunction(LibraryApiClient apiClient,
                                        LibraryWriteClient writeClient,
                                        LibraryPendingActionStore pendingStore,
                                        LibraryOwnerGuard ownerGuard,
                                        StringRedisTemplate redis,
                                        ObjectProvider<OperationLogService> operationLogService) {
        this.apiClient = apiClient;
        this.writeClient = writeClient;
        this.pendingStore = pendingStore;
        this.ownerGuard = ownerGuard;
        this.redis = redis;
        this.operationLogService = operationLogService;
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

    /**
     * 执行体：唯一写入口。
     */
    String execute(Integer userId) {
        long startNanos = System.nanoTime();
        try {
            String reject = ownerGuard.rejectReason(userId);
            if (reject != null) {
                return reject;
            }

            LibraryPendingActionStore.Pending pending = pendingStore.get(userId);
            if (pending == null) {
                return "没有待确认的操作。想做什么先说一遍，比如「帮我约明天下午英语角的座位」。";
            }

            LibraryPendingActionStore.Type type = pending.type();
            JsonNode p = pending.payload();

            // 幂等：同一操作只允许成功执行一次
            String idemKey = idempotentKey(userId, type, p);
            Boolean first = redis.opsForValue().setIfAbsent(idemKey, "1", IDEMPOTENT_TTL);
            if (first == null || !first) {
                pendingStore.delete(userId);
                return "这条操作刚才已经提交过了，别再重复提交。问「我的预约」可以确认结果。";
            }

            // 预约提交前重查座位（提交前复核，评审定稿：不用 prepare 时的旧快照）
            if (type == LibraryPendingActionStore.Type.RESERVE) {
                String stillFree = recheckSeatFree(p);
                if (stillFree != null) {
                    pendingStore.delete(userId);
                    redis.delete(idemKey);
                    return stillFree;
                }
            }

            LibraryWriteClient.Op op = toOp(type);
            String body = buildBody(type, p);

            long callNanos = System.nanoTime();
            LibraryWriteClient.WriteResult result = writeClient.execute(op, body);
            long costMs = DateUtils.elapsedMillis(callNanos);

            if (result.uncertain()) {
                // 结果不确定：删掉待确认，绝不让用户再"确认"一次变成重复提交
                pendingStore.delete(userId);
                audit(userId, type, p, false, "uncertain", costMs);
                return result.message();
            }

            if (result.success()) {
                pendingStore.delete(userId);
                audit(userId, type, p, true, null, costMs);
                return successText(type, p);
            }

            // 明确失败：删待确认 + 记录审计
            pendingStore.delete(userId);
            redis.delete(idemKey);
            audit(userId, type, p, false, result.message(), costMs);
            return "没成功：" + result.message()
                    + "。规则上说不清的话，问我「图书馆规则」或「我的预约」。";
        } catch (LibraryUnavailableException e) {
            return e.getMessage() + "。";
        } catch (Exception e) {
            log.warn("{} 执行失败: {}", TOOL_NAME, e.getMessage());
            return "确认服务这会儿不可用，稍后再试一次。";
        }
    }

    /** 预约提交前重查座位；返回 null 表示仍空闲，否则是给用户的拒绝文案 */
    private String recheckSeatFree(JsonNode p) {
        String areaId = p.path("areaId").asText("");
        String date = p.path("date").asText("");
        String seatId = p.path("seatId").asText("");
        String start = p.path("startTime").asText("08:00");
        String end = p.path("endTime").asText("22:00");

        for (LibraryApiClient.Seat s : apiClient.seats(areaId, date, start, end)) {
            if (seatId.equals(s.id())) {
                if (s.isFree()) {
                    return null;
                }
                return "慢了一步：这个座位刚被别人约走了。重新说一次「帮我约 X 区域」，我换个座位。";
            }
        }
        return "这个座位现在查不到了（可能已释放或被锁定），先问「我的预约」确认一下，别急着再约。";
    }

    private String buildBody(LibraryPendingActionStore.Type type, JsonNode p) throws Exception {
        if (type == LibraryPendingActionStore.Type.RESERVE) {
            String day = DateUtils.today().toString();
            Map<String, String> plain = Map.of(
                    "seat_id", p.path("seatId").asText(""),
                    "segment", p.path("segment").asText(""),
                    "day", p.path("date").asText(""),
                    "start_time", p.path("startTime").asText(""),
                    "end_time", p.path("endTime").asText(""));
            String aes = LibraryCrypto.encrypt(MAPPER.writeValueAsString(plain), day);
            return "{\"aesjson\":\"" + aes + "\"}";
        }
        return "{\"id\":\"" + p.path("recordId").asText("") + "\"}";
    }

    private static LibraryWriteClient.Op toOp(LibraryPendingActionStore.Type type) {
        return switch (type) {
            case RESERVE -> LibraryWriteClient.Op.CONFIRM;
            case CANCEL -> LibraryWriteClient.Op.CANCEL;
            case LEAVE -> LibraryWriteClient.Op.LEAVE;
            case CHECKOUT -> LibraryWriteClient.Op.CHECKOUT;
        };
    }

    private static String idempotentKey(Integer userId, LibraryPendingActionStore.Type type, JsonNode p) {
        if (type == LibraryPendingActionStore.Type.RESERVE) {
            return IDEMPOTENT_PREFIX + "reserve:" + userId + ":" + p.path("date").asText("")
                    + ":" + p.path("seatId").asText("") + ":" + p.path("startTime").asText("")
                    + "-" + p.path("endTime").asText("");
        }
        return IDEMPOTENT_PREFIX + type.name().toLowerCase() + ":" + p.path("recordId").asText("");
    }

    private String successText(LibraryPendingActionStore.Type type, JsonNode p) {
        String area = p.path("areaName").asText("?");
        String seatNo = p.path("seatNo").asText("?");
        String day = p.path("day").asText("");
        String start = p.path("startTime").asText("");
        String end = p.path("endTime").asText("");
        String slotText = start.isBlank() ? "全天" : start + "-" + end;
        return switch (type) {
            case RESERVE -> "约好了：" + day + "，「" + area + "」" + seatNo + " 号（" + slotText
                    + "）。到馆走门禁闸机刷卡自动签到，记得在时段开始后 60 分钟内入馆。";
            case CANCEL -> "已取消：" + day + "「" + area + "」" + seatNo + " 号的预约。";
            case LEAVE -> "已登记临时离开，「" + area + "」" + seatNo + " 号保留 60 分钟，"
                    + "超时没回来会自动释放并记一次违规。";
            case CHECKOUT -> "已签退，「" + area + "」" + seatNo + " 号的座位已释放。";
        };
    }

    /** 写审计（异步）。审计服务拿不到（如某进程未扫到 service 包）时记 warn，不影响写结果 */
    private void audit(Integer userId, LibraryPendingActionStore.Type type, JsonNode p,
                       boolean success, String errorMsg, long costMs) {
        try {
            OperationLogService service = operationLogService.getIfAvailable();
            if (service == null) {
                log.warn("OperationLogService 不可用，本次图书馆写操作未入审计: type={}", type);
                return;
            }
            OperationLogBO bo = new OperationLogBO();
            bo.setUserId(userId);
            bo.setModule("library");
            bo.setOperation("library_" + type.name().toLowerCase());
            bo.setMethod(toOp(type).name());
            bo.setUrl("/v4/" + switch (type) {
                case RESERVE -> "space/confirm";
                case CANCEL -> "space/cancel";
                case LEAVE -> "space/leave";
                case CHECKOUT -> "space/checkout";
            });
            bo.setHandler("LibraryConfirmAction");
            bo.setParams(briefParams(type, p));
            bo.setSuccess(success);
            bo.setErrorMsg(errorMsg == null ? null : (errorMsg.length() > 200 ? errorMsg.substring(0, 200) : errorMsg));
            bo.setCostMs((int) costMs);
            service.saveAsync(bo);
        } catch (Exception e) {
            log.warn("图书馆写操作审计写入失败: {}", e.getMessage());
        }
    }

    /** 审计参数：只记座位/记录信息，不记 aesjson 密文与 token */
    private static String briefParams(LibraryPendingActionStore.Type type, JsonNode p) {
        if (type == LibraryPendingActionStore.Type.RESERVE) {
            return p.path("areaName").asText("") + " " + p.path("seatNo").asText("")
                    + " " + p.path("date").asText("") + " " + p.path("startTime").asText("")
                    + "-" + p.path("endTime").asText("");
        }
        return "recordId=" + p.path("recordId").asText("");
    }

    @Override
    public String getToolName() {
        return TOOL_NAME;
    }

    @Override
    public String getToolDescription() {
        return "执行图书馆座位写操作（预约/取消/临时离开/签退）的唯一确认入口";
    }
}
