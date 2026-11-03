package com.xiaozhi.knowledge;

import com.xiaozhi.ai.rag.DocumentIngestService;
import com.xiaozhi.ai.rag.QdrantRagService;
import com.xiaozhi.common.exception.OperationFailedException;
import com.xiaozhi.common.exception.ResourceNotFoundException;
import com.xiaozhi.common.model.bo.KnowledgeChunkBO;
import com.xiaozhi.common.model.bo.KnowledgeDetailBO;
import com.xiaozhi.common.model.bo.KnowledgeDocBO;
import com.xiaozhi.knowledge.service.KnowledgeAccessGuard;
import com.xiaozhi.knowledge.service.KnowledgeDocService;
import com.xiaozhi.knowledge.service.KnowledgePendingFiles;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库应用服务：把「待入库文件 → 解析切块向量化 → 回写账本（+ 失败补偿）」串成一条链。
 *
 * <h3>为什么编排放在 server 模块</h3>
 * 它同时依赖 xiaozhi-ai（{@link DocumentIngestService}、{@link QdrantRagService}）与
 * xiaozhi-service（账本、鉴权、待入库文件），只有 server 模块能同时看到这两边。
 *
 * <h3>一致性：状态机 + 幂等补偿，不是数据库事务</h3>
 * Qdrant 是外部系统，数据库事务回滚不了它，所以：
 * <pre>
 * ① 找到或创建账本记录（status=processing）
 * ② 原文落盘（promote 返回相对路径）
 * ③ 解析 / 切块 / embedding / 写 Qdrant
 * ④ 成功：写分块目录 + 更新 status=success
 *    失败：status=failed + errorMsg，并按 id 删掉 Qdrant 里可能写入的残留（幂等）
 * </pre>
 *
 * <h3>2026-11-01 重构：身份与指纹分离</h3>
 * <ul>
 *   <li><b>id</b>（自增）是身份，向量库与分块表都用它关联，永不变</li>
 *   <li><b>contentHash</b> 是内容指纹，只依赖内容，用于去重</li>
 *   <li>同一用户重复上传同一内容 → 复用同一个 id（{@link KnowledgeDocService#resetToProcessing}），
 *       不会变成两条文档</li>
 *   <li>成功落盘路径以<b>相对路径</b>写进账本，换机器/换系统不用改数据</li>
 * </ul>
 *
 * <h3>2026-11-03 分层修正</h3>
 * 本类<b>不再引用任何 DO</b>（{@code KnowledgeDocDO} / {@code KnowledgeChunkDO}），只与 BO 打交道。
 * 持久化形状（表字段、自增策略）由 service 模块内部负责；
 * 第三方 SDK（{@code org.springframework.ai.vectorstore}）也收进了 {@link QdrantRagService}。
 * 这样本类只剩"编排"，不掺表结构与 SDK 细节。
 */
@Slf4j
@Service
public class KnowledgeIngestAppService {

    @Resource
    private KnowledgePendingFiles pendingFileStore;

    @Resource
    private DocumentIngestService documentIngestService;

    @Resource
    private KnowledgeDocService knowledgeDocService;

    @Resource
    private KnowledgeAccessGuard knowledgeAccessGuard;

    @Resource
    private QdrantRagService qdrantRagService;

    /**
     * 第二步：把待入库文件变成一条知识库文档。
     *
     * @param req    文件 id + 归类 + 可见性等
     * @param userId 归属人（**调用方从会话取，不能来自请求体**）
     * @return id、标题、字数、块数、状态
     */
    public Map<String, Object> create(KnowledgeCreateReq req, Integer userId) {
        if (req == null || !StringUtils.hasText(req.getFileId())) {
            throw new IllegalArgumentException("缺少 fileId，请先调用 POST /api/knowledge/files 上传文件");
        }
        String scope = StringUtils.hasText(req.getScope())
                ? req.getScope().trim()
                : KnowledgeDocBO.SCOPE_PRIVATE;
        // 第 3 层鉴权：非管理员不允许把资料设为共享
        knowledgeAccessGuard.assertCanCreateScope(scope);

        KnowledgePendingFiles.PendingFile pending = readPending(req.getFileId());
        byte[] content = readBytes(pending);
        String fileName = pending.fileName();
        int ownerId = userId == null ? 0 : userId;

        // ① 内容指纹（与身份无关，只由内容决定）
        String contentHash = DocumentIngestService.computeContentHash(content);

        // ② 找到或创建账本记录，拿到 id
        Long docId = resolveDocId(ownerId, contentHash, fileName, req, scope);

        // ③ 原文落盘：把 .pending 的文件移到正式目录，返回相对路径
        String storedPath = pendingFileStore.promote(pending, docId);

        // ④ 解析 → 切块 → 向量化 → 写 Qdrant
        DocumentIngestService.IngestOptions options = new DocumentIngestService.IngestOptions(
                docId, contentHash, storedPath,
                req.getUrl(), userId, scope, req.getCategory(), req.getPublishDate());
        DocumentIngestService.IngestResult result;
        try {
            result = documentIngestService.ingest(fileName, content, options);
        } catch (Exception e) {
            // 失败：记原因 + 补偿删 Qdrant 残留（幂等，失败也不掩盖原始错误）
            knowledgeDocService.markFailed(docId, e.getMessage());
            qdrantRagService.deleteByDocId(docId);
            throw new OperationFailedException("入库失败：" + e.getMessage(), e);
        }

        // ⑤ 成功：写分块目录 + 更新账本
        knowledgeDocService.replaceChunks(docId, buildChunkRows(result, docId));
        knowledgeDocService.markSuccess(docId, result.charCount(), result.chunkCount(), storedPath);

        // 待入库文件已经 move 走了，这里只是兜底清理（幂等）
        pendingFileStore.discard(req.getFileId());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", docId);
        data.put("contentHash", contentHash);
        data.put("title", result.title());
        data.put("charCount", result.charCount());
        data.put("chunkCount", result.chunkCount());
        data.put("scope", scope);
        data.put("status", KnowledgeDocBO.STATUS_SUCCESS);
        return data;
    }

    /**
     * 找到或创建账本记录，返回它的 id。
     *
     * <p>三种情况：
     * <ol>
     *   <li>该用户已有同内容文档 → 复用 id，重置为 processing（支持"改完内容重新上传"）</li>
     *   <li>没有 → 新建（自增 id 回填到入参对象）</li>
     *   <li>插入时撞唯一键（并发上传同一文件）→ 重新查一次再复用</li>
     * </ol>
     */
    private Long resolveDocId(int ownerId, String contentHash, String fileName,
                              KnowledgeCreateReq req, String scope) {
        KnowledgeDocBO existing = knowledgeDocService.findByOwnerAndHash(ownerId, contentHash);
        if (existing != null) {
            knowledgeDocService.resetToProcessing(withMeta(existing.getId(), contentHash, ownerId,
                    fileName, req, scope));
            return existing.getId();
        }

        KnowledgeDocBO doc = withMeta(null, contentHash, ownerId, fileName, req, scope);
        try {
            knowledgeDocService.insertProcessing(doc);
            return doc.getId();
        } catch (DuplicateKeyException e) {
            // 并发：另一个请求刚插进去同一份内容 → 重查并复用
            KnowledgeDocBO again = knowledgeDocService.findByOwnerAndHash(ownerId, contentHash);
            if (again == null) {
                throw new OperationFailedException("入库时发生并发冲突且无法定位已有记录，请重试", e);
            }
            log.info("并发重复上传，复用已有记录: id={}", again.getId());
            knowledgeDocService.resetToProcessing(withMeta(again.getId(), contentHash, ownerId,
                    fileName, req, scope));
            return again.getId();
        }
    }

    /**
     * 组装「要落账的文档元信息」。
     *
     * <p>只填用户这次提交能决定的字段；status / 字数 / 块数这些由 service 层按流程自己维护，
     * 编排层不去猜。
     */
    private static KnowledgeDocBO withMeta(Long id, String contentHash, int ownerId,
                                           String fileName, KnowledgeCreateReq req, String scope) {
        KnowledgeDocBO doc = new KnowledgeDocBO();
        doc.setId(id);
        doc.setContentHash(contentHash);
        doc.setHashVersion(KnowledgeDocBO.HASH_VERSION_CURRENT);
        doc.setTitle(stripExtension(fileName));
        doc.setCategory(StringUtils.hasText(req.getCategory()) ? req.getCategory().trim() : null);
        doc.setScope(scope);
        doc.setSourceType(KnowledgeDocBO.SOURCE_UPLOAD);
        doc.setFileName(fileName);
        doc.setUrl(req.getUrl());
        doc.setPublishDate(parseDate(req.getPublishDate()));
        doc.setUserId(ownerId);
        return doc;
    }

    /**
     * 删除一份资料：Qdrant → 磁盘原文 → 账本。
     *
     * <p>三步都幂等，中途失败可以再点一次删除，不会残留半截状态。
     */
    public void delete(Long id, Integer userId) {
        KnowledgeDocBO doc = knowledgeDocService.get(id);
        if (doc == null) {
            throw new ResourceNotFoundException("文档不存在");
        }
        // 第 3 层鉴权：本人或管理员
        knowledgeAccessGuard.assertCanModify(doc, userId);

        // ② Qdrant（先做可重试的外部动作）
        qdrantRagService.deleteByDocId(id);
        // ③ 磁盘原文（文件不在也不算失败）
        deleteStoredFile(doc.getFilePath());
        // ④ 账本
        knowledgeDocService.delete(id);
        log.info("知识库文档已删除: id={}, 标题={}", id, doc.getTitle());
    }

    /** 文档详情（含分块目录，不含正文）。 */
    public KnowledgeDetailBO detail(Long id, Integer userId) {
        KnowledgeDocBO doc = knowledgeDocService.get(id);
        knowledgeAccessGuard.assertCanSee(doc, userId);

        KnowledgeDetailBO detail = new KnowledgeDetailBO();
        detail.setDoc(doc);
        detail.setChunks(knowledgeDocService.listChunks(id));
        return detail;
    }

    // ---------- 内部工具 ----------

    private KnowledgePendingFiles.PendingFile readPending(String fileId) {
        try {
            return pendingFileStore.find(fileId);
        } catch (Exception e) {
            throw new ResourceNotFoundException(e.getMessage());
        }
    }

    private byte[] readBytes(KnowledgePendingFiles.PendingFile pending) {
        try {
            return pendingFileStore.read(pending);
        } catch (Exception e) {
            throw new OperationFailedException("读取待入库文件失败：" + e.getMessage(), e);
        }
    }

    /** 删磁盘原文。账本里存的是相对路径（老数据可能是绝对路径，resolveStored 两种都兼容）。 */
    private void deleteStoredFile(String filePath) {
        if (!StringUtils.hasText(filePath)) {
            return;
        }
        try {
            java.nio.file.Path path = pendingFileStore.resolveStored(filePath);
            if (path != null) {
                java.nio.file.Files.deleteIfExists(path);
            }
        } catch (Exception e) {
            log.warn("删除原文文件失败（不影响账本删除）: {}", e.getMessage());
        }
    }

    /** 由入库结果生成分块目录（**不含正文**，正文只存在 Qdrant 与磁盘原文里）。 */
    private List<KnowledgeChunkBO> buildChunkRows(DocumentIngestService.IngestResult result, Long docId) {
        List<DocumentIngestService.Chunk> chunks = result.chunks();
        List<KnowledgeChunkBO> rows = new ArrayList<>(chunks == null ? 0 : chunks.size());
        if (chunks == null) {
            return rows;
        }
        for (int i = 0; i < chunks.size(); i++) {
            DocumentIngestService.Chunk chunk = chunks.get(i);
            KnowledgeChunkBO row = new KnowledgeChunkBO();
            row.setDocId(docId);
            row.setChunkIndex(i);
            // 标题路径与字数都取切块时的真实值，不填占位符
            row.setHeading(chunk.heading());
            row.setCharCount(chunk.text() == null ? 0 : chunk.text().length());
            rows.add(row);
        }
        return rows;
    }

    private static LocalDate parseDate(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            log.debug("publishDate 格式不对，忽略: {}", value);
            return null;
        }
    }

    private static String stripExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}