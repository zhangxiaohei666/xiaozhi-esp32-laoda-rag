package com.xiaozhi.knowledge.service;

import com.xiaozhi.common.model.PageResult;
import com.xiaozhi.knowledge.dal.mysql.dataobject.KnowledgeChunkDO;
import com.xiaozhi.knowledge.dal.mysql.dataobject.KnowledgeDocDO;

import java.util.List;
import java.util.Map;

/**
 * 知识库文档的**本地账本**操作（只管 MySQL，不碰向量库）。
 *
 * <p>为什么要有这本账：Qdrant 只记得"有哪些点"，它不知道"我传过哪些文件、处理成功没、几块"。
 * 删除一份资料也要先知道它对应哪个 id 才能去 Qdrant 精准删。
 *
 * <p>职责边界：
 * <ul>
 *   <li>本接口：MySQL 增删改查（文档 + 分块目录）</li>
 *   <li>{@code KnowledgeIngestAppService}（server 模块）：把"落盘文件 → 解析向量化 → 回写账本"串起来</li>
 *   <li>{@code RetrievalService}：只查 Qdrant，不查本接口</li>
 * </ul>
 *
 * <p>2026-11-01 重构：主键从"内容哈希字符串"改为自增 BIGINT，
 * 内容哈希降级为 {@code contentHash} 字段（唯一索引用于去重）。
 * 所有方法签名里的 {@code String docId} 因此改为 {@code Long id}。
 */
public interface KnowledgeDocService {

    /**
     * 插入一条 status=processing 的记录。
     *
     * <p>自增主键会回填到入参对象的 {@code id} 字段上，调用方插入后可直接读取。
     *
     * @throws org.springframework.dao.DuplicateKeyException 同一用户上传同一内容时
     *         （命中 uk_owner_hash），调用方应转成"更新已有记录"
     */
    void insertProcessing(KnowledgeDocDO doc);

    /** 入库成功：更新字数、块数、状态与原文路径。 */
    void markSuccess(Long id, int charCount, int chunkCount, String filePath);

    /** 入库失败：记录原因，便于排查（扫描件 PDF、embedding 401 等）。 */
    void markFailed(Long id, String errorMsg);

    /**
     * 重新入库已有文档：更新元信息并把状态重置为 processing，清空旧错误与旧统计。
     *
     * <p>用于「同一份内容再次上传」的场景 —— 此时复用已有的 id，
     * 而不是新建一条记录，否则文档会变成两条。
     *
     * @param doc 必须带 {@code id}；其余字段（标题/归类/可见性/链接等）会一并更新
     */
    void resetToProcessing(KnowledgeDocDO doc);

    /** 全量替换某文档的分块目录（先删后插，幂等）。 */
    void replaceChunks(Long docId, List<KnowledgeChunkDO> chunks);

    /** 按主键查；不存在时返回 null。 */
    KnowledgeDocDO get(Long id);

    /**
     * 按「所属用户 + 内容指纹」查，用于去重。
     *
     * <p><b>跨环境同步/合并时必须用这个方法定位文档，不能用 id</b>：
     * id 只在单库内有效，contentHash 才是内容决定的稳定标识。
     */
    KnowledgeDocDO findByOwnerAndHash(Integer userId, String contentHash);

    /** 分块目录，按 chunkIndex 升序（不含正文）。 */
    List<KnowledgeChunkDO> listChunks(Long docId);

    /**
     * 分页查询。
     *
     * @param ownerUserId 非空时按「自己的 或 共享的」过滤；为 null 表示不限（管理员）
     */
    PageResult<KnowledgeDocDO> page(int pageNo, int pageSize,
                                    String category, String status, String scope, String keyword,
                                    Integer ownerUserId);

    /** 删除账本（分块先删，文档后删）。 */
    void delete(Long id);

    /**
     * 统计。
     *
     * @param ownerUserId 非空时只统计「自己的 或 共享的」；为 null 表示全量
     */
    Map<String, Object> stats(Integer ownerUserId);

    /** 已有的归类取值（供前端下拉），只返回非空值。 */
    List<String> listCategories(Integer ownerUserId);
}