package com.xiaozhi.common.model.bo;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 知识库文档（业务对象）。
 *
 * <p><b>为什么需要这一层</b>：项目分层规范要求 DO 只在 service 模块内部流转，
 * server 层（Controller / AppService）拿到 DO 就等于绕开了 Service 与 Convert，
 * 表结构会直接泄到 web 层。所以 service 对外只交付 BO。
 *
 * <p>本 BO 与 {@code KnowledgeDocDO} 字段基本一一对应 —— 这不是冗余，
 * 而是把"持久化形状"和"业务形状"解耦：将来 DO 加内部字段（如软删除标记、
 * 重试次数）不需要动对外契约，Resp 也不会跟着变。
 *
 * <p>身份与指纹的语义见 {@code KnowledgeDocDO}：{@code id} 是身份（永不变），
 * {@code contentHash} 是指纹（只依赖内容，用于去重与跨环境匹配）。
 */
@Data
public class KnowledgeDocBO {

    /** 可见性：仅本人可见。 */
    public static final String SCOPE_PRIVATE = "private";

    /** 可见性：所有人可见（管理员才能创建）。 */
    public static final String SCOPE_SHARED = "shared";

    /** 状态：正在处理。 */
    public static final String STATUS_PROCESSING = "processing";

    /** 状态：入库成功。 */
    public static final String STATUS_SUCCESS = "success";

    /** 状态：入库失败。 */
    public static final String STATUS_FAILED = "failed";

    /** 来源：用户上传。 */
    public static final String SOURCE_UPLOAD = "upload";

    /** 来源：网页抓取。 */
    public static final String SOURCE_SCRAPE = "scrape";

    /** 来源：批量导入。 */
    public static final String SOURCE_IMPORT = "import";

    /** 当前内容指纹算法版本。算法变更时自增，存量数据可分批重算。 */
    public static final int HASH_VERSION_CURRENT = 1;

    /** 主键（身份，永不变）。 */
    private Long id;

    /** 内容指纹 = sha256(字节数|md5(内容))，只依赖内容。 */
    private String contentHash;

    /** 指纹算法版本（预留）。 */
    private Integer hashVersion;

    private String title;
    private String category;

    /** 可见性：private / shared */
    private String scope;

    /** 来源：upload / scrape / import */
    private String sourceType;

    private String fileName;

    /** 原文相对路径（相对 KNOWLEDGE_UPLOAD_DIR） */
    private String filePath;

    private String url;
    private LocalDate publishDate;
    private Integer charCount;
    private Integer chunkCount;

    /** 处理状态：processing / success / failed */
    private String status;

    private String errorMsg;
    private Integer userId;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}