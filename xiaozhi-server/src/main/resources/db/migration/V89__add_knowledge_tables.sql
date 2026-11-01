-- 知识库模块：文档清单表 + 分块目录表 + 权限数据
--
-- 设计要点：
--   1. docId = sha256(userId|文件名|大小|内容md5) 前 16 位，**含 userId**。
--      不含 userId 时，两个用户上传同名同内容文件会算出同一个 docId 而互相覆盖。
--   2. 分块表**不存正文**：正文在 Qdrant 每个点的 payload.doc_content 里有一份，磁盘原文也有一份，
--      再存就是第三份且要维护一致性。将来做关键词召回时再加 text 列并从 Qdrant/原文回填。
--   3. scope 只有两级：private（仅本人）/ shared（所有人）。管理员上传选 shared 即人人可见，
--      因此检索层不需要判断"是不是管理员"，也就没有管理员 id 硬编码的问题。
--   4. status 用 varchar 而非 enum：状态机以后可能加值，enum 改动要 ALTER TABLE，且日志里数字状态不可读。
--
-- 全部语句幂等，可重复执行。

-- ============ 1. 文档清单表 ============
CREATE TABLE IF NOT EXISTS `sys_knowledge_doc` (
  `docId`       varchar(64)   NOT NULL COMMENT '文档ID = sha256(userId|文件名|大小|内容md5) 前16位',
  `title`       varchar(255)  NOT NULL COMMENT '标题',
  `category`    varchar(64)   NULL     COMMENT '归类：通知公告/图书馆/网页抓取等',
  `scope`       varchar(16)   NOT NULL DEFAULT 'private' COMMENT '可见性：private-仅本人 shared-所有人',
  `sourceType`  varchar(16)   NOT NULL DEFAULT 'upload' COMMENT '来源：upload-上传 scrape-抓取 import-批量导入',
  `fileName`    varchar(255)  NULL     COMMENT '原始文件名',
  `filePath`    varchar(500)  NULL     COMMENT '原文落盘路径',
  `url`         varchar(500)  NULL     COMMENT '原文链接',
  `publishDate` date          NULL     COMMENT '发文/发布日期',
  `charCount`   int unsigned  NOT NULL DEFAULT 0 COMMENT '正文字数',
  `chunkCount`  int unsigned  NOT NULL DEFAULT 0 COMMENT '切块数',
  `status`      varchar(16)   NOT NULL DEFAULT 'processing' COMMENT '处理状态：processing/success/failed',
  `errorMsg`    varchar(1000) NULL     COMMENT '失败原因',
  `userId`      int unsigned  NOT NULL COMMENT '归属用户（上传人）',
  `createTime`  datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updateTime`  datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`docId`),
  KEY `idx_kdoc_user_time` (`userId`, `createTime`),
  KEY `idx_kdoc_category` (`category`),
  KEY `idx_kdoc_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库文档表';

-- ============ 2. 分块目录表（不存正文） ============
CREATE TABLE IF NOT EXISTS `sys_knowledge_chunk` (
  `chunkId`    varchar(80)  NOT NULL COMMENT '分块ID = docId-序号',
  `docId`      varchar(64)  NOT NULL COMMENT '所属文档ID',
  `chunkIndex` int unsigned NOT NULL COMMENT '第几块（从 0 开始）',
  `heading`    varchar(500) NULL     COMMENT '标题路径，如「图书馆规则 > 开放时间」',
  `charCount`  int unsigned NOT NULL DEFAULT 0 COMMENT '该块字数',
  `createTime` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updateTime` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`chunkId`),
  KEY `idx_kchunk_doc` (`docId`, `chunkIndex`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库分块目录表（正文不在表内）';

-- ============ 3. 菜单权限 ============
INSERT INTO `sys_permission`
  (`parentId`, `name`, `permissionKey`, `permissionType`, `path`, `component`, `icon`, `sort`, `visible`, `status`)
SELECT NULL, '知识库', 'system:knowledge', 'menu', '/knowledge', 'page/Knowledge', 'folder-open', 10, '1', '1'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `sys_permission` WHERE `permissionKey` = 'system:knowledge');

-- ============ 4. 接口与按钮权限（挂在知识库菜单下） ============
INSERT INTO `sys_permission`
  (`parentId`, `name`, `permissionKey`, `permissionType`, `path`, `component`, `icon`, `sort`, `visible`, `status`)
SELECT parent.`permissionId`, src.`name`, src.`permissionKey`, src.`permissionType`, NULL, NULL, NULL, src.`sort`, '0', '1'
FROM (
    SELECT '文档列表接口'         AS name, 'system:knowledge:api:list'    AS permissionKey, 'api'    AS permissionType, 1 AS sort
    UNION ALL SELECT '文档详情接口',        'system:knowledge:api:detail',  'api',    2
    UNION ALL SELECT '上传文档接口',        'system:knowledge:api:upload',  'api',    3
    UNION ALL SELECT '删除文档接口',        'system:knowledge:api:delete',  'api',    4
    UNION ALL SELECT '检索调试接口',        'system:knowledge:api:search',  'api',    5
    UNION ALL SELECT '知识库统计接口',      'system:knowledge:api:stats',   'api',    6
    UNION ALL SELECT '批量导入接口',        'system:knowledge:api:import',  'api',    7
    UNION ALL SELECT '管理共享资料',        'system:knowledge:manage',      'button', 8
) src
JOIN `sys_permission` parent ON parent.`permissionKey` = 'system:knowledge'
LEFT JOIN `sys_permission` existing ON existing.`permissionKey` = src.`permissionKey`
WHERE existing.`permissionId` IS NULL;

-- ============ 5. 分配给角色 ============
-- 管理员：全部 9 项
-- 普通用户：菜单 + list/detail/upload/delete/search/stats（不给 import 与 manage，避免批量灌库和改共享）
INSERT INTO `sys_auth_role_permission` (`authRoleId`, `permissionId`)
SELECT authRole.`authRoleId`, permission.`permissionId`
FROM (
    SELECT 'admin' AS roleKey, 'system:knowledge' AS permissionKey
    UNION ALL SELECT 'admin', 'system:knowledge:api:list'
    UNION ALL SELECT 'admin', 'system:knowledge:api:detail'
    UNION ALL SELECT 'admin', 'system:knowledge:api:upload'
    UNION ALL SELECT 'admin', 'system:knowledge:api:delete'
    UNION ALL SELECT 'admin', 'system:knowledge:api:search'
    UNION ALL SELECT 'admin', 'system:knowledge:api:stats'
    UNION ALL SELECT 'admin', 'system:knowledge:api:import'
    UNION ALL SELECT 'admin', 'system:knowledge:manage'
    UNION ALL SELECT 'user',  'system:knowledge'
    UNION ALL SELECT 'user',  'system:knowledge:api:list'
    UNION ALL SELECT 'user',  'system:knowledge:api:detail'
    UNION ALL SELECT 'user',  'system:knowledge:api:upload'
    UNION ALL SELECT 'user',  'system:knowledge:api:delete'
    UNION ALL SELECT 'user',  'system:knowledge:api:search'
    UNION ALL SELECT 'user',  'system:knowledge:api:stats'
) src
JOIN `sys_auth_role` authRole ON authRole.`roleKey` = src.`roleKey`
JOIN `sys_permission` permission ON permission.`permissionKey` = src.`permissionKey`
LEFT JOIN `sys_auth_role_permission` existing
    ON existing.`authRoleId` = authRole.`authRoleId`
   AND existing.`permissionId` = permission.`permissionId`
WHERE existing.`id` IS NULL;
