package com.xiaozhi.common.model.resp;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 知识库文档（web 出参）。
 *
 * <p>按项目规范，Controller 出参只能是 Resp / PageResult&lt;Resp&gt; / 原语 / Void。
 * 直接把 DO 或 BO 抛给前端会绕开这一层字段收口 ——
 * 比如 {@code contentHash} 属于内部去重标识，本不该出现在列表里，
 * 但 DO 有它就会被一起序列化出去。
 */
@Data
@Schema(description = "知识库文档")
public class KnowledgeDocResp {

    @Schema(description = "文档ID（账本自增主键）")
    private Long id;

    @Schema(description = "内容指纹，用于识别同一份内容")
    private String contentHash;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "归类")
    private String category;

    @Schema(description = "可见性：private-仅本人 / shared-所有人")
    private String scope;

    @Schema(description = "来源：upload-上传 / scrape-抓取 / import-批量导入")
    private String sourceType;

    @Schema(description = "原始文件名")
    private String fileName;

    @Schema(description = "原文链接")
    private String url;

    @Schema(description = "发文/发布日期")
    private LocalDate publishDate;

    @Schema(description = "正文字数")
    private Integer charCount;

    @Schema(description = "切块数")
    private Integer chunkCount;

    @Schema(description = "处理状态：processing / success / failed")
    private String status;

    @Schema(description = "失败原因（仅失败时非空）")
    private String errorMsg;

    @Schema(description = "归属用户ID")
    private Integer userId;

    @Schema(description = "创建时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;
}