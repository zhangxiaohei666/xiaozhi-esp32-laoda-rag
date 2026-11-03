package com.xiaozhi.common.model.resp;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * RAG 连通性自检结果（web 出参）。
 *
 * <p>排查 RAG 时最先看这个接口：Qdrant 通不通、集合在不在、embedding 配没配。
 * 这三个环节任一不通，检索就必然为空 —— 与其去翻日志，不如先看这里。
 *
 * <p>可选字段用 {@code @JsonInclude(NON_NULL)} 省略，前端拿到 null 反而要多写判空。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "RAG 连通性自检结果")
public class KnowledgePingResp {

    @Schema(description = "RAG 总开关是否开启")
    private Boolean enabled;

    @Schema(description = "Qdrant 地址，如 192.168.10.132:6334")
    private String qdrantAddress;

    @Schema(description = "集合名")
    private String collection;

    @Schema(description = "Qdrant 是否可达")
    private Boolean qdrantReachable;

    @Schema(description = "集合是否已存在（首次写入时自动创建）")
    private Boolean collectionExists;

    @Schema(description = "集合内向量点数")
    private Long pointsCount;

    @Schema(description = "向量维度")
    private Integer vectorSize;

    @Schema(description = "异常说明（仅在不通时出现）")
    private String note;

    @Schema(description = "embedding 配置是否就绪")
    private Boolean embeddingConfigured;

    @Schema(description = "配置指引（仅缺失时出现）")
    private String hint;
}