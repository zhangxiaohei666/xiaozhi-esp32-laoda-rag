package com.xiaozhi.ai.library;

import org.springframework.stereotype.Component;

/**
 * 写操作 owner 校验：预约/取消/临时离开/签退只允许配置的用户（admin）执行。
 *
 * <p>校验逻辑集中在这一个类里，所有写工具（prepare_* 与 confirm_action）共用，
 * 避免每个工具各写一遍口径漂移。
 *
 * <p>fail-closed 三档：
 * <ol>
 *   <li>{@code owner-user-id} 未配置（0）→ 拒绝："没配置允许的用户"，绝不悄悄放行</li>
 *   <li>userId 为 null（取不到身份）→ 拒绝，绝不按"没校验"处理</li>
 *   <li>userId ≠ owner-user-id → 拒绝（查询工具不受此限制，见各工具注册说明）</li>
 * </ol>
 *
 * <p>userId 来源是 {@code ToolSession.getUserId()}（设备归属人/登录用户），
 * 在工具注册期从会话取、闭包绑定，绝不接受模型参数传入——伪造参数改不动校验结果。
 */
@Component
public class LibraryOwnerGuard {

    private final LibraryProperties props;

    public LibraryOwnerGuard(LibraryProperties props) {
        this.props = props;
    }

    /**
     * @param userId 会话归属用户 id（可能为 null）
     * @return null 表示放行；否则是给用户看的拒绝文案
     */
    public String rejectReason(Integer userId) {
        long owner = props.getOwnerUserId();
        if (owner == 0L) {
            return "图书馆的预约、取消这些写操作还没有配置允许的用户，找管理员把 owner-user-id 配上再用。";
        }
        if (userId == null) {
            return "识别不到你的身份，写操作没法执行。";
        }
        if (owner != userId.longValue()) {
            return "预约、取消、临时离开、签退这些操作只有管理员能用，你可以查座位和查预约。";
        }
        return null;
    }
}
