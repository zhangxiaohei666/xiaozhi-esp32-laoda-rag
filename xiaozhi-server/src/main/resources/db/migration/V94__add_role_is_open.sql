-- 角色公共化：sys_role 增加「开放」标记。
-- 公共角色 = userId=1 且 isOpen=1：所有用户直接加载使用，不复制；用户私人角色恒 isOpen=0。
-- 只加列 + 把 admin 当前默认角色标为开放，不写死任何 roleId（roleId 是各库自增生成的，
-- 39 个迁移里没有一条 sys_role 种子数据）；其余公共角色由管理员在管理端开关上打开。
-- 结构变更按 V55 / V81 惯例做存在性守卫：手工加过列的库重复执行不报 1060。

SET @has_column = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_role' AND COLUMN_NAME = 'isOpen'
);
SET @ddl = IF(@has_column = 0,
    'ALTER TABLE `sys_role` ADD COLUMN `isOpen` TINYINT NOT NULL DEFAULT 0 COMMENT ''公共角色开放标记：1-全员可见，0-仅本人'' AFTER `isDefault`',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 兜底：只把 admin 名下「当前默认角色」标为开放（本机即牢大），不按名字、不按 roleId
UPDATE `sys_role` SET `isOpen` = 1 WHERE `userId` = 1 AND `isDefault` = '1';
