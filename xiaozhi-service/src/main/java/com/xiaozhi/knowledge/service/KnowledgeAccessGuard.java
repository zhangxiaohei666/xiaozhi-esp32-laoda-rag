package com.xiaozhi.knowledge.service;

import cn.dev33.satoken.stp.StpUtil;
import com.xiaozhi.common.exception.ResourceNotFoundException;
import com.xiaozhi.common.exception.UnauthorizedException;
import com.xiaozhi.knowledge.dal.mysql.dataobject.KnowledgeDocDO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 知识库的数据级鉴权，**与业务逻辑解耦**：Controller / Service 只调用本类的方法下判断，
 * 不再各自散落写 if-else。
 *
 * <p>职责边界（三层里的第三层）：
 * <ul>
 *   <li>第 1 层「登录」由 Sa-Token 拦截器统一做（{@code StpUtil.checkLogin()}）</li>
 *   <li>第 2 层「能不能用这个功能」由接口上的 {@code @SaCheckPermission} 做</li>
 *   <li><b>第 3 层「能看/改哪几条数据」就是本类</b></li>
 * </ul>
 *
 * <p><b>管理员从不硬编码 userId</b>：走 Sa-Token 的 {@code StpUtil.hasRole("admin")}，
 * 而角色由 {@code StpInterfaceImpl} 从 {@code sys_user.isAdmin} + {@code sys_auth_role.roleKey}
 * 实时计算。以后新增管理员只需改一条数据（{@code UPDATE sys_user SET isAdmin='1' ...}），代码零改动。
 */
@Slf4j
@Component
public class KnowledgeAccessGuard {

    /** Sa-Token 里超级管理员的角色 key（{@code StpInterfaceImpl.ADMIN_ROLE_KEY}） */
    public static final String ADMIN_ROLE_KEY = "admin";

    /** 当前登录用户 id。**身份只从会话取，绝不接受前端传参**，否则可伪造。 */
    public Integer currentUserId() {
        return StpUtil.getLoginIdAsInt();
    }

    /** 当前登录用户是不是管理员。 */
    public boolean isAdmin() {
        return StpUtil.hasRole(ADMIN_ROLE_KEY);
    }

    /**
     * 创建文档时校验可见性：只有管理员能把资料设为共享。
     *
     * <p>普通用户一旦能把私人文件设成 shared，等于自己把资料公开给所有人，属于越权，必须拦。
     */
    public void assertCanCreateScope(String scope) {
        if (!KnowledgeDocDO.SCOPE_PRIVATE.equalsIgnoreCase(scope) && !isAdmin()) {
            throw new UnauthorizedException("只有管理员可以把资料设为共享");
        }
    }

    /**
     * 查看某份文档：本人上传的，或共享的，才允许。
     *
     * <p>无权查看时抛 {@link ResourceNotFoundException} 而不是「无权」——
     * 返回 404 不会暴露「这个 docId 存在」这一信息。
     */
    public void assertCanSee(KnowledgeDocDO doc, Integer userId) {
        if (doc == null) {
            throw new ResourceNotFoundException("文档不存在");
        }
        if (KnowledgeDocDO.SCOPE_SHARED.equalsIgnoreCase(doc.getScope())) {
            return;
        }
        if (userId != null && userId.equals(doc.getUserId())) {
            return;
        }
        throw new ResourceNotFoundException("文档不存在");
    }

    /**
     * 删除 / 重试 / 改归属等写操作：本人上传的，或管理员，才允许。
     */
    public void assertCanModify(KnowledgeDocDO doc, Integer userId) {
        if (doc == null) {
            throw new ResourceNotFoundException("文档不存在");
        }
        if (isAdmin()) {
            return;
        }
        if (userId != null && userId.equals(doc.getUserId())) {
            return;
        }
        throw new UnauthorizedException("只能操作自己上传的资料");
    }

    /**
     * 列表查询的过滤条件构造：非管理员只能看「自己的 + 共享的」，管理员不限。
     *
     * @return true 表示需要按 userId/scope 过滤；false 表示可看全部
     */
    public boolean needOwnerFilter() {
        return !isAdmin();
    }
}