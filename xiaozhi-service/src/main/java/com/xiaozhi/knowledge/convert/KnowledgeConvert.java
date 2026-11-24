package com.xiaozhi.knowledge.convert;

import com.xiaozhi.common.model.bo.KnowledgeChunkBO;
import com.xiaozhi.common.model.bo.KnowledgeDetailBO;
import com.xiaozhi.common.model.bo.KnowledgeDocBO;
import com.xiaozhi.common.model.resp.KnowledgeChunkResp;
import com.xiaozhi.common.model.resp.KnowledgeDetailResp;
import com.xiaozhi.common.model.resp.KnowledgeDocResp;
import com.xiaozhi.knowledge.dal.mysql.dataobject.KnowledgeChunkDO;
import com.xiaozhi.knowledge.dal.mysql.dataobject.KnowledgeDocDO;
import org.mapstruct.Mapper;

/**
 * 知识库对象转换：DO ↔ BO ↔ Resp。
 *
 * <p>为什么要走这一层（项目分层规范）：
 * <ul>
 *   <li><b>DO 不外泄</b>：{@code sys_knowledge_doc} 的列结构只在 service 模块内可见，
 *       server 层与前端都拿不到它；改表结构不会波及 web 契约。</li>
 *   <li><b>Resp 收口</b>：前端能看到的字段由 {@link KnowledgeDocResp} 明确列出，
 *       不像 DO 那样"加一列就自动多一个字段"。</li>
 * </ul>
 *
 * <p>字段同名，所以不需要 {@code @Mapping} 逐个声明。
 * {@code filePath} 属于内部存储细节，本就不进 Resp（BO 有、Resp 没有，MapStruct 自动忽略）。
 */
@Mapper(componentModel = "spring")
public interface KnowledgeConvert {

    /** DO → BO（service 模块内部使用）。 */
    KnowledgeDocBO toBO(KnowledgeDocDO docDO);

    /** DO → BO（service 模块内部使用）。 */
    KnowledgeChunkBO toBO(KnowledgeChunkDO chunkDO);

    /** BO → Resp（server 层出参）。{@code ownerName} 由 Controller 手动填充（要查用户表），这里忽略。 */
    @org.mapstruct.Mapping(target = "ownerName", ignore = true)
    KnowledgeDocResp toResp(KnowledgeDocBO docBO);

    /** BO → Resp（server 层出参）。 */
    KnowledgeChunkResp toResp(KnowledgeChunkBO chunkBO);

    /** 详情：文档 + 分块目录一起转。 */
    KnowledgeDetailResp toResp(KnowledgeDetailBO detailBO);
}