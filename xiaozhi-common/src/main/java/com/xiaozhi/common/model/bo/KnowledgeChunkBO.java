package com.xiaozhi.common.model.bo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库分块目录（业务对象，<b>不含正文</b>）。
 *
 * <p>正文只存在两处：Qdrant 每个点的 {@code payload.doc_content}，以及磁盘原文。
 * 这里只保留块级元信息（序号、标题路径、字数），用于详情页展示与排查。
 */
@Data
public class KnowledgeChunkBO {

    /** 分块主键。 */
    private Long id;

    /** 所属文档 id。 */
    private Long docId;

    /** 第几块（从 0 开始）。 */
    private Integer chunkIndex;

    /** 标题路径，如「图书馆规则 > 开放时间」。 */
    private String heading;

    /** 该块字数（含标题路径前缀）。 */
    private Integer charCount;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}