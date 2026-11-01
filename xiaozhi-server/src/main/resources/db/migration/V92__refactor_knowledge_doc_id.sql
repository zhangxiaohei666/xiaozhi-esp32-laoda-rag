-- ============================================================
-- 知识库表重构：身份与指纹分离
--
-- 背景：原设计用「内容哈希字符串」直接当主键（docId varchar，如 5f079255f629d885），
-- 带来三个问题：
--   ① InnoDB 聚簇索引下随机主键插入引起页分裂；varchar(64) 的索引键长 258 字节，
--      而每个二级索引都内嵌主键 → 实测索引占用是数据占用的 3 倍；
--   ② 内容一改哈希就变，同一篇文档会变成两条独立记录，无法追踪"同一份文档"；
--   ③ 最严重：一旦哈希算法调整，所有历史 id 全变，账本与向量库直接失联
--      （本项目已真实发生过：早期入库的 39 份资料在账本表建出来之前进库，
--      至今仍是"向量有、账本没有"的状态）。
--
-- 新设计：
--   id           BIGINT AUTO_INCREMENT  —— 身份。永不变，账本/分块/向量库全用它关联
--   contentHash  CHAR(64)               —— 指纹。sha256(字节数|md5(内容))，只依赖内容
--   hashVersion  TINYINT                —— 指纹算法版本，将来换算法时可分批重算
--   去重靠 UNIQUE KEY uk_owner_hash (userId, contentHash)，不靠主键
--
-- ⚠️ 两个必须遵守的约定（写在这里，防将来踩坑）：
--   1. id 只在【单个数据库实例】内有效，不跨环境传输。
--      跨环境同步/合并一律用 (userId, contentHash) 匹配，不能用 id。
--   2. filePath 存【相对路径】（相对 KNOWLEDGE_UPLOAD_DIR）。
--      早期版本存的是 D:\xiaozhi\... 绝对路径，迁到 Linux 后会全部失效。
--
-- 数据迁移：本脚本是 DROP + CREATE（数据量小，重新入库最干净）。
--   预期流程：备份 → 本脚本 → 清空 Qdrant → 从磁盘原文重新入库。
-- ============================================================

DROP TABLE IF EXISTS sys_knowledge_chunk;
DROP TABLE IF EXISTS sys_knowledge_doc;

CREATE TABLE sys_knowledge_doc
(
    id            BIGINT UNSIGNED    NOT NULL AUTO_INCREMENT
        COMMENT '主键：身份，永不变。⚠️仅在单个数据库实例内有效，不跨环境传输',
    contentHash   CHAR(64)           NOT NULL
        COMMENT '内容指纹 = sha256(字节数|md5(内容))，不含 userId 与文件名。⚠️跨环境同步/合并用它匹配',
    hashVersion   TINYINT UNSIGNED   NOT NULL DEFAULT 1
        COMMENT '指纹算法版本（预留：换算法时用于分批重算 contentHash）',

    title         VARCHAR(255)       NOT NULL COMMENT '标题（默认取文件名去后缀）',
    category      VARCHAR(64)        DEFAULT NULL COMMENT '归类：通知公告/图书馆/网页抓取等',
    scope         VARCHAR(16)        NOT NULL DEFAULT 'private' COMMENT '可见性：private-仅本人 shared-所有人',
    sourceType    VARCHAR(16)        NOT NULL DEFAULT 'upload' COMMENT '来源：upload-上传 scrape-抓取 import-批量导入',
    fileName      VARCHAR(255)       DEFAULT NULL COMMENT '原始文件名',
    filePath      VARCHAR(500)       DEFAULT NULL
        COMMENT '原文相对路径（相对 KNOWLEDGE_UPLOAD_DIR），如 3-图书馆规则.md。存相对路径是为了换机器/换系统不用改数据',
    url           VARCHAR(500)       DEFAULT NULL COMMENT '原文链接',
    publishDate   DATE               DEFAULT NULL COMMENT '发文/发布日期',
    charCount     INT UNSIGNED       NOT NULL DEFAULT 0 COMMENT '正文字数',
    chunkCount    INT UNSIGNED       NOT NULL DEFAULT 0 COMMENT '切块数',
    status        VARCHAR(16)        NOT NULL DEFAULT 'processing'
        COMMENT '处理状态：processing/success/failed',
    errorMsg      VARCHAR(1000)      DEFAULT NULL COMMENT '失败原因',
    userId        INT UNSIGNED       NOT NULL COMMENT '归属用户（上传人）',
    createTime    DATETIME           DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updateTime    DATETIME           DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    PRIMARY KEY (id),
    -- 去重靠这个唯一索引：同一用户 + 同一内容 = 同一份文档
    UNIQUE KEY uk_owner_hash (userId, contentHash),
    KEY idx_owner_time (userId, createTime),
    KEY idx_category (category),
    KEY idx_status (status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='知识库文档表';

CREATE TABLE sys_knowledge_chunk
(
    id         BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '主键',
    docId      BIGINT UNSIGNED  NOT NULL COMMENT '所属文档 id（= sys_knowledge_doc.id）',
    chunkIndex INT UNSIGNED     NOT NULL COMMENT '第几块（从 0 开始）',
    heading    VARCHAR(500)     DEFAULT NULL COMMENT '标题路径，如「图书馆规则 > 开放时间」',
    charCount  INT UNSIGNED     NOT NULL DEFAULT 0 COMMENT '该块字数（含标题路径前缀）',
    createTime DATETIME         DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updateTime DATETIME         DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    PRIMARY KEY (id),
    -- 同一文档的同一序号只能有一条；重新入库是先删后插，不会冲突
    UNIQUE KEY uk_doc_chunk (docId, chunkIndex),
    KEY idx_doc (docId)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='知识库分块目录表（正文不在表内）';