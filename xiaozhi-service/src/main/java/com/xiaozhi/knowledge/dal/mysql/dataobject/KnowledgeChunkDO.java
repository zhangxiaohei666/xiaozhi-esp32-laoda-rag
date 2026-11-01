package com.xiaozhi.knowledge.dal.mysql.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xiaozhi.common.dal.mysql.dataobject.BaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 知识库分块目录（一份文档切成 N 块 → N 行）。
 *
 * <p><b>不存正文</b>：正文已在 Qdrant 每个点的 payload.doc_content 里有一份，磁盘原文也有一份。
 * 再存一份就是第三份，且要维护一致性。将来做关键词召回（方案 §8.5 的 ngram + RRF）时，
 * 再给这张表加 text 列，并从 Qdrant 或磁盘原文一次性回填。
 *
 * <p>2026-11-01 重构：主键由字符串 {@code docId-序号} 改为自增 BIGINT，
 * {@link #docId} 改为 {@code BIGINT} 指向 {@code sys_knowledge_doc.id}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_knowledge_chunk")
public class KnowledgeChunkDO extends BaseDO {

    /** 主键。自增。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属文档 id（= sys_knowledge_doc.id）。 */
    private Long docId;

    /** 第几块（从 0 开始）。 */
    private Integer chunkIndex;

    /** 标题路径，如「图书馆规则 > 开放时间」 */
    private String heading;

    /** 该块字数（含标题路径前缀）。 */
    private Integer charCount;
}