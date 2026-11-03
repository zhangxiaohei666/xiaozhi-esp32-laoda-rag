package com.xiaozhi.common.model.resp;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库分块目录（web 出参，<b>不含正文</b>）。
 *
 * <p>正文在向量库与磁盘原文里，这里只回目录信息，避免详情页把整篇正文再传一遍。
 */
@Data
@Schema(description = "知识库分块目录")
public class KnowledgeChunkResp {

    @Schema(description = "分块ID")
    private Long id;

    @Schema(description = "所属文档ID")
    private Long docId;

    @Schema(description = "第几块（从 0 开始）")
    private Integer chunkIndex;

    @Schema(description = "标题路径，如「图书馆规则 > 开放时间」")
    private String heading;

    @Schema(description = "该块字数（含标题路径前缀）")
    private Integer charCount;

    @Schema(description = "创建时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;
}