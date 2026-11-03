package com.xiaozhi.common.model.bo;

import lombok.Data;

import java.util.List;

/**
 * 知识库文档详情（业务对象）：账本信息 + 分块目录。
 *
 * <p>单独成型是为了让编排层（AppService）能一次交付"文档 + 它的块"，
 * 而不是把两者塞进一个 Map 让调用方自己按 key 取。
 */
@Data
public class KnowledgeDetailBO {

    private KnowledgeDocBO doc;

    /** 分块目录（不含正文）。 */
    private List<KnowledgeChunkBO> chunks;
}