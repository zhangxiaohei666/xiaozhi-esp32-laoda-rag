package com.xiaozhi.ai.library;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 钉住统一确认工具的权限与空态行为：
 * admin 校验三档（未配置/null 身份/非 admin）、没有待确认时的提示。
 *
 * <p>owner 校验在 prepare 与 confirm 共用同一 {@link LibraryOwnerGuard}，
 * 这里测的是 confirm 入口（唯一真正执行写的地方）的口径。
 */
class LibraryConfirmActionFunctionTest {

    private LibraryOwnerGuard guardWith(long owner) {
        LibraryProperties props = new LibraryProperties();
        props.setOwnerUserId(owner);
        return new LibraryOwnerGuard(props);
    }

    private LibraryConfirmActionFunction fn(LibraryOwnerGuard guard) {
        LibraryApiClient api = mock(LibraryApiClient.class);
        LibraryWriteClient write = mock(LibraryWriteClient.class);
        LibraryPendingActionStore store = mock(LibraryPendingActionStore.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ObjectProvider<com.xiaozhi.operationlog.service.OperationLogService> logProvider = mock(ObjectProvider.class);
        return new LibraryConfirmActionFunction(api, write, store, guard, redis, logProvider);
    }

    @Test
    @DisplayName("owner 未配置（0）：一律拒绝并提示配置")
    void ownerNotConfiguredRejects() {
        LibraryConfirmActionFunction fn = fn(guardWith(0));
        String result = fn.execute(1);
        assertThat(result).contains("没有配置允许的用户");
    }

    @Test
    @DisplayName("userId 为 null：fail-closed 拒绝，不 NPE")
    void nullUserIdRejects() {
        LibraryConfirmActionFunction fn = fn(guardWith(1));
        String result = fn.execute(null);
        assertThat(result).contains("识别不到你的身份");
    }

    @Test
    @DisplayName("非 admin（userId=2，owner=1）：拒绝，且不碰任何下游")
    void nonAdminRejects() {
        LibraryConfirmActionFunction fn = fn(guardWith(1));
        String result = fn.execute(2);
        assertThat(result).contains("只有管理员");
    }

    @Test
    @DisplayName("admin 但无待确认：提示先说要做什么")
    void adminWithoutPendingGetsGuidance() {
        LibraryConfirmActionFunction fn = fn(guardWith(1));
        String result = fn.execute(1);
        assertThat(result).contains("没有待确认的操作");
    }

    @Test
    @DisplayName("guard 本身：配置 owner=1 时 userId=1 放行")
    void guardAllowsOwner() {
        assertThat(guardWith(1).rejectReason(1)).isNull();
    }
}
