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
 *
 * <p>2026-11-17 rerank 上线后补回读 {@code rerankEnabled / rerankModel / rerankRecallK /
 * rerankRecallThreshold / rerankTopK} 与每条命中的 {@code rerankScore} ——
 * 沿用"读回实际值、不信日志不信推测"红线：env 名写错静默回退这类问题只有回读能抓到。
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

    @Schema(description = "本次 rerank 是否启用（读回实际生效值，不信日志）")
    private Boolean rerankEnabled;

    @Schema(description = "本次生效的重排模型；rerank 关闭时仍回显配置值")
    private String rerankModel;

    @Schema(description = "本次生效的召回条数（仅 rerank 开时使用）")
    private Integer rerankRecallK;

    @Schema(description = "本次生效的召回阈值（仅 rerank 开时使用；拒答线仍是上面的 threshold）")
    private Double rerankRecallThreshold;

    @Schema(description = "本次生效的重排后注入条数（仅 rerank 开时使用）")
    private Integer rerankTopK;

    @Schema(description = "命中的块（rerank 开时按重排序；关时按分数降序）")
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

        @Schema(description = "重排分数（rerank 开且该条经过重排时才有；请求内相对分数，不可跨请求比较）")
        private Double rerankScore;
    }
}