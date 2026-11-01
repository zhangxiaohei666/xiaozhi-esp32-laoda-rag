-- 调整「普通用户」角色的权限模型。
--
-- 背景：普通用户角色此前只给了 config / setting / chat / knowledge，
-- 结果是：进后台只能看到"配置管理"（模型与密钥是管理员的活，普通用户看到的是空表），
-- 而真正该有的「仪表盘」「设备管理（只看自己的设备）」反而没有 —— 权限模型与产品语义相反。
--
-- 本次调整：
--   授予  system:dashboard                      仪表盘（数据来自 device/role/message 三个列表，后端均按 userId 过滤）
--   授予  system:device + device:api:list       看自己的设备（service 层按 userId 过滤）
--   授予  system:device:api:create              扫码绑定设备（已有"设备已被其他用户绑定"校验）
--   授予  system:device:api:update              改自己设备（@CheckOwner(resource="device") + @CheckOwner(resource="role")）
--   授予  system:device:api:delete              删自己设备（@CheckOwner(resource="device")）
--   授予  system:setting:account:api:update     改自己的个人资料
--   授予  system:role:memory:chat:api:list      看自己的对话记录（仪表盘/记忆页要用）
--
--   收回  system:config 及其 config:api:*       模型/密钥/语音等系统配置属管理员职责；
--                                              普通用户看到的只是自己名下的空配置表，没有意义
--
-- 不动的：
--   system:device:api:batch-update  批量操作普通用户用不到，仍只给管理员
--   system:role / system:prompt-template / system:user / system:auth-role  仍只给管理员
--
-- ⚠️ 执行后必须清 Redis 权限缓存（TTL 约 5 天），否则要等缓存过期才生效：
--    DEL XiaoZhi:Permission::authRole:keys:<roleId>
--    DEL XiaoZhi:Permission::authRole:ids:<roleId>
--    DEL XiaoZhi:Permission::authRole:list:<roleId>
--    或走后台「权限角色」页面保存一次（那条路径带 @CacheEvict）。
--
-- 全部语句幂等，可重复执行。

-- ============ 1. 授予普通用户 ============
INSERT INTO `sys_auth_role_permission` (`authRoleId`, `permissionId`)
SELECT authRole.`authRoleId`, permission.`permissionId`
FROM (
    SELECT 'system:dashboard'                    AS permissionKey
    UNION ALL SELECT 'system:device'
    UNION ALL SELECT 'system:device:api:list'
    UNION ALL SELECT 'system:device:api:create'
    UNION ALL SELECT 'system:device:api:update'
    UNION ALL SELECT 'system:device:api:delete'
    UNION ALL SELECT 'system:setting:account:api:update'
    UNION ALL SELECT 'system:role:memory:chat:api:list'
) src
JOIN `sys_auth_role` authRole ON authRole.`roleKey` = 'user'
JOIN `sys_permission` permission ON permission.`permissionKey` = src.`permissionKey`
LEFT JOIN `sys_auth_role_permission` existing
       ON existing.`authRoleId` = authRole.`authRoleId`
      AND existing.`permissionId` = permission.`permissionId`
WHERE existing.`id` IS NULL;

-- ============ 2. 收回普通用户的配置管理 ============
DELETE rp FROM `sys_auth_role_permission` rp
JOIN `sys_auth_role` authRole ON authRole.`authRoleId` = rp.`authRoleId`
JOIN `sys_permission` permission ON permission.`permissionId` = rp.`permissionId`
WHERE authRole.`roleKey` = 'user'
  AND (permission.`permissionKey` = 'system:config'
       OR permission.`permissionKey` LIKE 'system:config:%');
