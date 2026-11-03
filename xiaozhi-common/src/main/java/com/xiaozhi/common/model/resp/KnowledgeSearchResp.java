package com.xiaozhi.common.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 检索调试结果（web 出参）。
 *
 * <p>这是排查 RAG 的主要手段：不依赖对话，直接问一句就能看到召回结果与分数。
 * 因此把 topK、阈值这些<b>本次实际生效</b>的参数也回出去 ——
 * 排查时最常问的是"为什么没命中"，答案往往就在"阈值配高了"或"topK 太小"。
 */
@Data
@Schema(description = "检索调试结果")
public class KnowledgeSearchResp {

    @Schema(description = "问题文本")
    private String query;

    @Schema(description = "本次生效的 topK")
    private Integer topK;

    @Schema(description = "本次生效的相似度阈值")
    private Double threshold;

    @Schema(description = "命中的块（按分数降序）")
    private List<Hit> hits;

    /** 单条命中。 */
    @Data
    @Schema(description = "检索命中的分块")
    public static class Hit {

        @Schema(description = "相似度分数（保留 4 位小数）")
        private Double score;

        @Schema(description = "文档标题")
        private String title;

        @Schema(description = "标题路径，如「图书馆规则 > 开放时间」")
        private String heading;

        @Schema(description = "来源文件名")
        private String source;

        @Schema(description = "文档ID（字符串，与向量库 payload 一致）")
        private String docId;

        @Schema(description = "正文片段（前 300 字）")
        private String snippet;
    }
}