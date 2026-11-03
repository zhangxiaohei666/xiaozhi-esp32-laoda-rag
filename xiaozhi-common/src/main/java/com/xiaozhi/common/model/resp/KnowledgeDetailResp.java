package com.xiaozhi.common.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 知识库文档详情（web 出参）：账本信息 + 分块目录。
 *
 * <p>不含分块正文 —— 正文在向量库与磁盘原文里，详情页只看目录。
 */
@Data
@Schema(description = "知识库文档详情")
public class KnowledgeDetailResp {

    @Schema(description = "文档信息")
    private KnowledgeDocResp doc;

    @Schema(description = "分块目录（不含正文）")
    private List<KnowledgeChunkResp> chunks;
}