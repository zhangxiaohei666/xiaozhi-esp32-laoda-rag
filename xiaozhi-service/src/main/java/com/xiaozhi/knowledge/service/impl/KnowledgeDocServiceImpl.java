package com.xiaozhi.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xiaozhi.common.model.PageResult;
import com.xiaozhi.common.model.bo.KnowledgeChunkBO;
import com.xiaozhi.common.model.bo.KnowledgeDocBO;
import com.xiaozhi.knowledge.convert.KnowledgeConvert;
import com.xiaozhi.knowledge.dal.mysql.dataobject.KnowledgeChunkDO;
import com.xiaozhi.knowledge.dal.mysql.dataobject.KnowledgeDocDO;
import com.xiaozhi.knowledge.dal.mysql.mapper.KnowledgeChunkMapper;
import com.xiaozhi.knowledge.dal.mysql.mapper.KnowledgeDocMapper;
import com.xiaozhi.knowledge.service.KnowledgeDocService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库账本的 MySQL 实现。只做本地库，不涉及 Qdrant 与文件系统。
 *
 * <p>2026-11-01 重构要点：
 * <ul>
 *   <li>主键从内容哈希字符串改为自增 BIGINT，{@code insertProcessing} 不再"先删后插"</li>
 *   <li>去重交给唯一索引 {@code uk_owner_hash (userId, contentHash)}，
 *       重复插入会抛 {@code DuplicateKeyException}，由编排层转成"更新已有记录"</li>
 *   <li>新增 {@link #findByOwnerAndHash} 与 {@link #resetToProcessing}，
 *       支持"同一内容重新入库时复用同一个 id"</li>
 * </ul>
 *
 * <p>2026-11-03 分层修正：本实现是 <b>DO 的边界</b> —— 入参出参全部用 BO，
 * DO 只在本类内部出现。这样 server 层（Controller / AppService）看不到持久化形状，
 * 满足架构测试 {@code serverModuleDoesNotDependOnDal}。
 */
@Slf4j
@Service
public class KnowledgeDocServiceImpl implements KnowledgeDocService {

    @Resource
    private KnowledgeDocMapper docMapper;

    @Resource
    private KnowledgeChunkMapper chunkMapper;

    /** DO ↔ BO 转换（MapStruct 生成实现）。 */
    @Resource
    private KnowledgeConvert knowledgeConvert;

    @Override
    public void insertProcessing(KnowledgeDocBO doc) {
        KnowledgeDocDO row = toDO(doc);
        if (row.getStatus() == null) {
            row.setStatus(KnowledgeDocDO.STATUS_PROCESSING);
        }
        if (row.getHashVersion() == null) {
            row.setHashVersion(KnowledgeDocDO.HASH_VERSION_CURRENT);
        }
        // 纯插入：自增主键会回填到 row.id，再写回 BO
        docMapper.insert(row);
        doc.setId(row.getId());
        log.info("知识库落账: id={}, 文件={}, hash={}, status={}",
                row.getId(), row.getFileName(), brief(row.getContentHash()), row.getStatus());
    }

    @Override
    public void markSuccess(Long id, int charCount, int chunkCount, String filePath) {
        KnowledgeDocDO update = new KnowledgeDocDO();
        update.setId(id);
        update.setCharCount(charCount);
        update.setChunkCount(chunkCount);
        update.setStatus(KnowledgeDocDO.STATUS_SUCCESS);
        update.setErrorMsg("");
        if (StringUtils.hasText(filePath)) {
            update.setFilePath(filePath);
        }
        docMapper.updateById(update);
    }

    @Override
    public void markFailed(Long id, String errorMsg) {
        KnowledgeDocDO update = new KnowledgeDocDO();
        update.setId(id);
        update.setStatus(KnowledgeDocDO.STATUS_FAILED);
        update.setErrorMsg(truncate(errorMsg, 900));
        docMapper.updateById(update);
        log.warn("知识库入库失败: id={}, 原因={}", id, errorMsg);
    }

    @Override
    public void resetToProcessing(KnowledgeDocBO doc) {
        if (doc == null || doc.getId() == null) {
            throw new IllegalArgumentException("resetToProcessing 需要带 id");
        }
        KnowledgeDocDO update = new KnowledgeDocDO();
        update.setId(doc.getId());
        // 元信息可能被用户改过（归类 / 可见性 / 链接 / 发布日期），一并更新
        update.setTitle(doc.getTitle());
        update.setCategory(doc.getCategory());
        update.setScope(doc.getScope());
        update.setFileName(doc.getFileName());
        update.setUrl(doc.getUrl());
        update.setPublishDate(doc.getPublishDate());
        update.setStatus(KnowledgeDocDO.STATUS_PROCESSING);
        update.setErrorMsg("");
        update.setCharCount(0);
        update.setChunkCount(0);
        docMapper.updateById(update);
        log.info("知识库文档重置为处理中（重新入库）: id={}", doc.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replaceChunks(Long docId, List<KnowledgeChunkBO> chunks) {
        chunkMapper.delete(new LambdaQueryWrapper<KnowledgeChunkDO>().eq(KnowledgeChunkDO::getDocId, docId));
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        for (KnowledgeChunkBO chunk : chunks) {
            KnowledgeChunkDO row = new KnowledgeChunkDO();
            // 主键自增，显式置空避免调用方误传
            row.setId(null);
            row.setDocId(docId);
            row.setChunkIndex(chunk.getChunkIndex());
            row.setHeading(chunk.getHeading());
            row.setCharCount(chunk.getCharCount());
            chunkMapper.insert(row);
        }
    }

    @Override
    public KnowledgeDocBO get(Long id) {
        if (id == null) {
            return null;
        }
        return knowledgeConvert.toBO(docMapper.selectById(id));
    }

    @Override
    public KnowledgeDocBO findByOwnerAndHash(Integer userId, String contentHash) {
        if (userId == null || !StringUtils.hasText(contentHash)) {
            return null;
        }
        return knowledgeConvert.toBO(docMapper.selectOne(new LambdaQueryWrapper<KnowledgeDocDO>()
                .eq(KnowledgeDocDO::getUserId, userId)
                .eq(KnowledgeDocDO::getContentHash, contentHash)
                .last("LIMIT 1")));
    }

    @Override
    public List<KnowledgeChunkBO> listChunks(Long docId) {
        if (docId == null) {
            return List.of();
        }
        List<KnowledgeChunkDO> rows = chunkMapper.selectList(new LambdaQueryWrapper<KnowledgeChunkDO>()
                .eq(KnowledgeChunkDO::getDocId, docId)
                .orderByAsc(KnowledgeChunkDO::getChunkIndex));
        List<KnowledgeChunkBO> result = new ArrayList<>(rows.size());
        for (KnowledgeChunkDO row : rows) {
            result.add(knowledgeConvert.toBO(row));
        }
        return result;
    }

    @Override
    public PageResult<KnowledgeDocBO> page(int pageNo, int pageSize,
                                           String category, String status, String scope, String keyword,
                                           Integer ownerUserId) {
        LambdaQueryWrapper<KnowledgeDocDO> qw = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(category)) {
            qw.eq(KnowledgeDocDO::getCategory, category);
        }
        if (StringUtils.hasText(status)) {
            qw.eq(KnowledgeDocDO::getStatus, status);
        }
        if (StringUtils.hasText(scope)) {
            qw.eq(KnowledgeDocDO::getScope, scope);
        }
        if (StringUtils.hasText(keyword)) {
            qw.and(w -> w.like(KnowledgeDocDO::getTitle, keyword)
                    .or().like(KnowledgeDocDO::getFileName, keyword));
        }
        // 非管理员：只看「自己的」。共享文档属于别人上传的公共资产，不进「我的文档」列表；
        // 它仍会参与 RAG 检索（检索侧另有 scope==shared 的口径），这里只影响文档管理列表。
        if (ownerUserId != null) {
            qw.eq(KnowledgeDocDO::getUserId, ownerUserId);
        }
        qw.orderByDesc(KnowledgeDocDO::getCreateTime);

        IPage<KnowledgeDocDO> page = docMapper.selectPage(new Page<>(pageNo, pageSize), qw);
        List<KnowledgeDocBO> bos = new ArrayList<>(page.getRecords().size());
        for (KnowledgeDocDO row : page.getRecords()) {
            bos.add(knowledgeConvert.toBO(row));
        }
        return new PageResult<>(bos, page.getTotal(), pageNo, pageSize);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        chunkMapper.delete(new LambdaQueryWrapper<KnowledgeChunkDO>().eq(KnowledgeChunkDO::getDocId, id));
        docMapper.deleteById(id);
        log.info("知识库账本已删除: id={}", id);
    }

    @Override
    public Map<String, Object> stats(Integer ownerUserId) {
        LambdaQueryWrapper<KnowledgeDocDO> qw = new LambdaQueryWrapper<>();
        if (ownerUserId != null) {
            qw.and(w -> w.eq(KnowledgeDocDO::getUserId, ownerUserId)
                    .or().eq(KnowledgeDocDO::getScope, KnowledgeDocDO.SCOPE_SHARED));
        }
        List<KnowledgeDocDO> docs = docMapper.selectList(qw);

        int total = docs.size();
        int success = 0;
        int failed = 0;
        int processing = 0;
        int chunks = 0;
        long chars = 0;
        for (KnowledgeDocDO d : docs) {
            if (KnowledgeDocDO.STATUS_SUCCESS.equals(d.getStatus())) {
                success++;
            } else if (KnowledgeDocDO.STATUS_FAILED.equals(d.getStatus())) {
                failed++;
            } else {
                processing++;
            }
            chunks += d.getChunkCount() == null ? 0 : d.getChunkCount();
            chars += d.getCharCount() == null ? 0 : d.getCharCount();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("docCount", total);
        result.put("successCount", success);
        result.put("failedCount", failed);
        result.put("processingCount", processing);
        result.put("chunkCount", chunks);
        result.put("charCount", chars);
        return result;
    }

    @Override
    public List<String> listCategories(Integer ownerUserId) {
        LambdaQueryWrapper<KnowledgeDocDO> qw = new LambdaQueryWrapper<>();
        qw.select(KnowledgeDocDO::getCategory).isNotNull(KnowledgeDocDO::getCategory);
        if (ownerUserId != null) {
            qw.and(w -> w.eq(KnowledgeDocDO::getUserId, ownerUserId)
                    .or().eq(KnowledgeDocDO::getScope, KnowledgeDocDO.SCOPE_SHARED));
        }
        return docMapper.selectList(qw).stream()
                .map(KnowledgeDocDO::getCategory)
                .filter(StringUtils::hasText)
                .distinct()
                .sorted()
                .toList();
    }

    // ---------------------------------------------------------------- 内部工具

    /** BO → DO（只搬需要落库的字段；id 为空表示新增）。 */
    private static KnowledgeDocDO toDO(KnowledgeDocBO bo) {
        KnowledgeDocDO row = new KnowledgeDocDO();
        row.setId(bo.getId());
        row.setContentHash(bo.getContentHash());
        row.setHashVersion(bo.getHashVersion());
        row.setTitle(bo.getTitle());
        row.setCategory(bo.getCategory());
        row.setScope(bo.getScope());
        row.setSourceType(bo.getSourceType());
        row.setFileName(bo.getFileName());
        row.setFilePath(bo.getFilePath());
        row.setUrl(bo.getUrl());
        row.setPublishDate(bo.getPublishDate());
        row.setCharCount(bo.getCharCount() == null ? 0 : bo.getCharCount());
        row.setChunkCount(bo.getChunkCount() == null ? 0 : bo.getChunkCount());
        row.setStatus(bo.getStatus());
        row.setErrorMsg(bo.getErrorMsg());
        row.setUserId(bo.getUserId());
        return row;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** 日志里只打印哈希前 8 位，避免刷屏。 */
    private static String brief(String hash) {
        if (hash == null) {
            return "";
        }
        return hash.length() > 8 ? hash.substring(0, 8) : hash;
    }
}