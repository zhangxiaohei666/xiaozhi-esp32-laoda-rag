package com.xiaozhi.common.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 知识库入库结果（web 出参）。
 *
 * <p>只回必要的确认信息：文档 id（后续查看/删除要用）、指纹（排查重复上传用）、
 * 字数与块数（确认切块是否符合预期）。正文不回 —— 前端拿到也没用。
 */
@Data
@Schema(description = "知识库入库结果")
public class KnowledgeCreateResultResp {

    @Schema(description = "文档ID（账本自增主键，后续操作都用它）")
    private Long id;

    @Schema(description = "内容指纹")
    private String contentHash;

    @Schema(description = "标题（文件名去扩展名）")
    private String title;

    @Schema(description = "正文字数")
    private Integer charCount;

    @Schema(description = "切块数")
    private Integer chunkCount;

    @Schema(description = "可见性：private / shared")
    private String scope;

    @Schema(description = "处理状态")
    private String status;
}