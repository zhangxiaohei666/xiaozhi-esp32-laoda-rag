package com.xiaozhi.ai.llm.listener;

import com.xiaozhi.common.model.bo.OperationLogBO;
import com.xiaozhi.event.LlmModelFallbackEvent;
import com.xiaozhi.operationlog.service.OperationLogService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 降级监听器的三条底线：服务可用时写一次操作日志（字段语义化）；
 * 服务未装配时静默跳过；写日志抛异常时不炸（降级的兜底目的就是让对话继续）。
 */
class LlmModelFallbackListenerTest {

    private static LlmModelFallbackEvent event() {
        return new LlmModelFallbackEvent("小助手", 7, 11, 99, "openai", "绑定的模型配置已停用（modelId=11）");
    }

    @Test
    void fallbackWritesExactlyOneOperationLogWithSemanticFields() {
        OperationLogService service = mock(OperationLogService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<OperationLogService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);

        new LlmModelFallbackListener(provider).onFallback(event());

        ArgumentCaptor<OperationLogBO> captor = ArgumentCaptor.forClass(OperationLogBO.class);
        verify(service, times(1)).saveAsync(captor.capture());
        OperationLogBO bo = captor.getValue();
        assertThat(bo.getModule()).isEqualTo("llm");
        assertThat(bo.getOperation()).isEqualTo("llm_model_fallback");
        assertThat(bo.getHandler()).isEqualTo("ChatModelFactory.resolveConfig");
        assertThat(bo.getParams()).contains("小助手", "from=11", "to=99", "已停用");
    }

    @Test
    void missingServiceIsSkippedSilently() {
        @SuppressWarnings("unchecked")
        ObjectProvider<OperationLogService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        assertThatCode(() -> new LlmModelFallbackListener(provider).onFallback(event()))
                .doesNotThrowAnyException();
    }

    @Test
    void failingLogWriteDoesNotBreakTheListener() {
        OperationLogService service = mock(OperationLogService.class);
        doThrow(new IllegalStateException("审计链路炸了")).when(service).saveAsync(any());
        @SuppressWarnings("unchecked")
        ObjectProvider<OperationLogService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);

        assertThatCode(() -> new LlmModelFallbackListener(provider).onFallback(event()))
                .doesNotThrowAnyException();
    }
}
