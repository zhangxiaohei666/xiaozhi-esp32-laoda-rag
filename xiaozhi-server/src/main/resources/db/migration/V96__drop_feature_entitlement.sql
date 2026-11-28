-- V96: 清理「私人 RAG 解锁」功能层的遗留表与权限键
--
-- 背景（2026-11-28 决策）：
--   产品上不强制 Star，私人上传只按接口权限 system:knowledge:api:upload 控制，
--   不再需要「功能字典 + 用户权益」两套表；相关实现不保留。
--   复活方案见 docs/01-项目方案/私人RAG解锁-暂缓方案.md。
--
-- 幂等：表/权限键不存在时也能正常执行（老库若建过则清理，新库什么也不做）。

-- 1) 两张表整体移除（先删子表，避免潜在外键依赖）
DROP TABLE IF EXISTS `sys_user_entitlement`;
DROP TABLE IF EXISTS `sys_feature`;

-- 2) 移除该功能种下的权限键及其角色授权
DELETE arp FROM `sys_auth_role_permission` arp
  JOIN `sys_permission` p ON p.`permissionId` = arp.`permissionId`
 WHERE p.`permissionKey` LIKE 'system:feature:%';

DELETE FROM `sys_permission`
 WHERE `permissionKey` LIKE 'system:feature:%';
