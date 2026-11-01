-- 普通用户需要能读角色列表。
--
-- 背景：/chat（Web 聊天页）启动时要调 GET /api/role/list 让用户选"跟哪个小智角色聊"，
-- 该接口要求 system:role:api:list，此前这条只分配给管理员，
-- 导致普通用户一进聊天页就拿不到角色列表（403），再叠加前端默认落地页的问题（已修）
-- 就会出现"登录后处处无权限"的观感。
--
-- 这里只补**读取**权限，不涉及增删改（system:role:api:create/update/delete 仍然只给管理员）。
--
-- ⚠️ 执行后必须清 Redis 权限缓存，否则生效时间会拖到缓存过期（TTL 约 5 天）：
--    DEL XiaoZhi:Permission::authRole:keys:<authRoleId>
--    DEL XiaoZhi:Permission::authRole:ids:<authRoleId>
--    DEL XiaoZhi:Permission::authRole:list:<authRoleId>
--    也可以走后台「权限角色」页面保存一次，那条路径带 @CacheEvict。

INSERT INTO `sys_auth_role_permission` (`authRoleId`, `permissionId`)
SELECT authRole.`authRoleId`, permission.`permissionId`
FROM `sys_auth_role` authRole
JOIN `sys_permission` permission ON permission.`permissionKey` = 'system:role:api:list'
LEFT JOIN `sys_auth_role_permission` existing
       ON existing.`authRoleId` = authRole.`authRoleId`
      AND existing.`permissionId` = permission.`permissionId`
WHERE authRole.`roleKey` = 'user'
  AND existing.`id` IS NULL;
