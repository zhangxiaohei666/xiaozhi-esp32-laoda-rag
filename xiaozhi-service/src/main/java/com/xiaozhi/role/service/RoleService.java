package com.xiaozhi.role.service;

import com.xiaozhi.common.model.bo.RoleBO;
import com.xiaozhi.common.model.PageResult;
import com.xiaozhi.role.model.RoleProjection;

import java.util.List;

public interface RoleService {

    // ===================== 查询操作 =====================

    PageResult<RoleProjection> page(int pageNo, int pageSize, Integer roleId, String roleName,
                                    String isDefault, String state, Integer userId);

    RoleBO getBO(Integer roleId);

    /** 用户的候选角色：自己的启用角色在前，公共开放角色在后（userId=1 跳过公共段，避免重复） */
    List<RoleBO> listBO(Integer userId, int limit);

    /** 用户自建的私人角色（userId 归属、isOpen=0；含停用行，供"我的角色"页展示与恢复） */
    List<RoleBO> listPrivateBO(Integer userId);

    /** 同一用户下是否已有同名角色；excludeRoleId 用于改名时排除自己 */
    boolean existsRoleName(Integer userId, String roleName, Integer excludeRoleId);

    /** 是否与 admin 已开放的公共角色同名；私人角色不允许叫公共角色的名字 */
    boolean existsPublicRoleName(String roleName, Integer excludeRoleId);

    /** 当前开放且启用的公共角色数量；管理员关闭开关前至少留一个 */
    long countOpenPublic();

    /**
     * 只查该用户名下的角色（isDefault 优先、roleId 次之），<b>不看 isOpen</b>。
     * <p>公共角色兜底请用 {@link #getPublicDefaultBO()}；该方法对没有私人角色的普通用户返回 null。
     */
    RoleBO getDefaultOrFirstBO(Integer userId);

    /** 公共默认角色：admin 名下、开放且启用的角色里 isDefault 优先、roleId 次之；一个都没有时返回 null */
    RoleBO getPublicDefaultBO();

    /** 绑定了该模型配置的角色数，跨用户统计 */
    long countByModelId(Integer modelId);

    // ===================== 写操作（待迁移到 RoleAppService） =====================

    Integer copyDefaultRole(Integer sourceUserId, Integer targetUserId);
}
