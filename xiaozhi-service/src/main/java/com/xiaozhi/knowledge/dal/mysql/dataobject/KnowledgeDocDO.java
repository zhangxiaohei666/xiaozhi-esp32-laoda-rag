package com.xiaozhi.knowledge.dal.mysql.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xiaozhi.common.dal.mysql.dataobject.BaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 知识库文档（一份文件一行）。
 *
 * <p><b>身份与指纹分离</b>（2026-11-01 重构，原方案用内容哈希直接当主键）：
 * <ul>
 *   <li>{@link #id} —— <b>身份</b>。自增 BIGINT、8 字节、有序，永不变。
 *       账本、分块表、Qdrant 的 payload 全部用它关联。
 *       <b>⚠️ 仅在单个数据库实例内有效，不跨环境传输</b>：跨环境同步/合并一律用
 *       {@code (userId, contentHash)} 匹配，不能用 id。</li>
 *   <li>{@link #contentHash} —— <b>指纹</b>。{@code sha256(字节数|md5(内容))}，
 *       <b>不含 userId、不含文件名</b>：同一份内容在任何用户、任何文件名下都算出同一个值，
 *       所以它才是"跨环境的稳定标识"。算法要变时加 {@link #hashVersion} 分批重算即可，
 *       主键和关联关系完全不动。</li>
 * </ul>
 *
 * <p>去重靠 {@code UNIQUE KEY uk_owner_hash (userId, contentHash)}，
 * 不靠主键：同一用户重复上传同一内容 → 唯一键冲突 → 走"更新已有记录"而不是新增。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_knowledge_doc")
public class KnowledgeDocDO extends BaseDO {

    /** 可见性：仅本人可见 */
    public static final String SCOPE_PRIVATE = "private";
    /** 可见性：所有人可见 */
    public static final String SCOPE_SHARED = "shared";

    /** 处理状态：处理中 */
    public static final String STATUS_PROCESSING = "processing";
    /** 处理状态：成功 */
    public static final String STATUS_SUCCESS = "success";
    /** 处理状态：失败 */
    public static final String STATUS_FAILED = "failed";

    /** 来源：后台上传 */
    public static final String SOURCE_UPLOAD = "upload";
    /** 来源：网页抓取 */
    public static final String SOURCE_SCRAPE = "scrape";
    /** 来源：批量导入 */
    public static final String SOURCE_IMPORT = "import";

    /** 指纹算法版本：将来换算法时用它标记哪些行需要重算，默认 1。 */
    public static final int HASH_VERSION_CURRENT = 1;

    /** 主键（身份）。自增，永不变；仅单库内有效，不跨环境传输。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 内容指纹 = sha256(字节数|md5(内容))，不含 userId 与文件名。跨环境靠它匹配。 */
    private String contentHash;

    /** 指纹算法版本（预留，便于将来分批重算 contentHash）。 */
    private Integer hashVersion;

    private String title;
    private String category;
    private String scope;
    private String sourceType;
    private String fileName;

    /**
     * 原文相对路径（相对 {@code KNOWLEDGE_UPLOAD_DIR}），例如 {@code 3-图书馆规则.md}。
     *
     * <p>存相对路径而不是绝对路径：换机器、换操作系统、换上传目录时数据不用改。
     * 早期版本存的是 {@code D:\xiaozhi\...} 绝对路径，迁到 Linux 后会全部失效。
     */
    private String filePath;

    private String url;
    private LocalDate publishDate;
    private Integer charCount;
    private Integer chunkCount;
    private String status;
    private String errorMsg;
    private Integer userId;
}