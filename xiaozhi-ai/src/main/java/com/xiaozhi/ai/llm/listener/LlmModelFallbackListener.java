package com.xiaozhi.ai.llm.listener;

import com.xiaozhi.common.model.bo.OperationLogBO;
import com.xiaozhi.event.LlmModelFallbackEvent;
import com.xiaozhi.operationlog.service.OperationLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * LLM 模型降级事件监听器：把降级事实落进操作日志，管理员在后台就能看到"谁降级了、从哪到哪、为什么"。
 * <p>模块归属：xiaozhi-ai——server 与 dialogue 两个进程都扫描 com.xiaozhi.ai，两边发布的降级事件都能被监听。
 * <p>依赖边界：与 {@code LibraryConfirmActionFunction} 同款弱依赖——OperationLogService 用 ObjectProvider 注入，
 * 拿不到时静默跳过，不拖累对话主流程；saveAsync 本身是 @Async，跨 Bean 调用走代理提交线程池即返回，
 * 本监听器不阻塞事件分发。
 */
@Slf4j
@Component
public class LlmModelFallbackListener {

    private final ObjectProvider<OperationLogService> operationLogService;

    public LlmModelFallbackListener(ObjectProvider<OperationLogService> operationLogService) {
        this.operationLogService = operationLogService;
    }

    @EventListener
    public void onFallback(LlmModelFallbackEvent event) {
        OperationLogService service = operationLogService.getIfAvailable();
        if (service == null) {
            log.debug("OperationLogService 未装配，降级事件不入操作日志 - 角色: {}", event.roleName());
            return;
        }
        OperationLogBO bo = new OperationLogBO();
        bo.setModule("llm");
        bo.setOperation("llm_model_fallback");
        // 降级事件不是 HTTP 请求触发的，没有 ip/url/method 语境，留空
        bo.setHandler("ChatModelFactory.resolveConfig");
        bo.setParams("role=" + event.roleName()
                + ",from=" + event.fromConfigId()
                + ",to=" + event.toConfigId()
                + ",reason=" + event.reason());
        try {
            service.saveAsync(bo);
        } catch (RuntimeException e) {
            log.warn("降级事件写操作日志失败（不影响对话） - 角色: {}", event.roleName(), e);
        }
    }
}
