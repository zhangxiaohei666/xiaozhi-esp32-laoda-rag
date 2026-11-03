package com.xiaozhi.common.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 知识库统计（web 出参）。
 *
 * <p>把原先的 {@code Map<String,Object>} 收敛成明确字段：
 * Map 出参既过不了架构卡点，前端也没法从接口签名看出有哪些指标。
 */
@Data
@Schema(description = "知识库统计")
public class KnowledgeStatsResp {

    @Schema(description = "文档总数")
    private Integer docCount;

    @Schema(description = "入库成功的文档数")
    private Integer successCount;

    @Schema(description = "入库失败的文档数")
    private Integer failedCount;

    @Schema(description = "处理中的文档数")
    private Integer processingCount;

    @Schema(description = "分块总数")
    private Integer chunkCount;

    @Schema(description = "总字数")
    private Long charCount;
}