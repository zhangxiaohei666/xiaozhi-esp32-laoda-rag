package com.xiaozhi.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xiaozhi.common.model.PageResult;
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
 */
@Slf4j
@Service
public class KnowledgeDocServiceImpl implements KnowledgeDocService {

    @Resource
    private KnowledgeDocMapper docMapper;

    @Resource
    private KnowledgeChunkMapper chunkMapper;

    @Override
    public void insertProcessing(KnowledgeDocDO doc) {
        if (doc.getStatus() == null) {
            doc.setStatus(KnowledgeDocDO.STATUS_PROCESSING);
        }
        if (doc.getHashVersion() == null) {
            doc.setHashVersion(KnowledgeDocDO.HASH_VERSION_CURRENT);
        }
        // 纯插入：自增主键会回填到 doc.id。
        // 同一用户上传同一内容会命中 uk_owner_hash 抛 DuplicateKeyException，
        // 调用方应捕获后改用 findByOwnerAndHash + resetToProcessing 的更新路径。
        docMapper.insert(doc);
        log.info("知识库落账: id={}, 文件={}, hash={}, status={}",
                doc.getId(), doc.getFileName(), brief(doc.getContentHash()), doc.getStatus());
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
    public void resetToProcessing(KnowledgeDocDO doc) {
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
    public void replaceChunks(Long docId, List<KnowledgeChunkDO> chunks) {
        chunkMapper.delete(new LambdaQueryWrapper<KnowledgeChunkDO>().eq(KnowledgeChunkDO::getDocId, docId));
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        for (KnowledgeChunkDO chunk : chunks) {
            // 主键自增，这里显式置空，避免调用方误传
            chunk.setId(null);
            chunk.setDocId(docId);
            chunkMapper.insert(chunk);
        }
    }

    @Override
    public KnowledgeDocDO get(Long id) {
        if (id == null) {
            return null;
        }
        return docMapper.selectById(id);
    }

    @Override
    public KnowledgeDocDO findByOwnerAndHash(Integer userId, String contentHash) {
        if (userId == null || !StringUtils.hasText(contentHash)) {
            return null;
        }
        return docMapper.selectOne(new LambdaQueryWrapper<KnowledgeDocDO>()
                .eq(KnowledgeDocDO::getUserId, userId)
                .eq(KnowledgeDocDO::getContentHash, contentHash)
                .last("LIMIT 1"));
    }

    @Override
    public List<KnowledgeChunkDO> listChunks(Long docId) {
        if (docId == null) {
            return List.of();
        }
        return chunkMapper.selectList(new LambdaQueryWrapper<KnowledgeChunkDO>()
                .eq(KnowledgeChunkDO::getDocId, docId)
                .orderByAsc(KnowledgeChunkDO::getChunkIndex));
    }

    @Override
    public PageResult<KnowledgeDocDO> page(int pageNo, int pageSize,
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
        // 非管理员：只看「自己的 或 共享的」
        if (ownerUserId != null) {
            qw.and(w -> w.eq(KnowledgeDocDO::getUserId, ownerUserId)
                    .or().eq(KnowledgeDocDO::getScope, KnowledgeDocDO.SCOPE_SHARED));
        }
        qw.orderByDesc(KnowledgeDocDO::getCreateTime);

        IPage<KnowledgeDocDO> page = docMapper.selectPage(new Page<>(pageNo, pageSize), qw);
        return new PageResult<>(page.getRecords(), page.getTotal(), pageNo, pageSize);
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