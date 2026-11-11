package com.xiaozhi.knowledge;

import com.xiaozhi.ai.rag.DocumentFormats;
import com.xiaozhi.ai.rag.DocumentIngestService;
import com.xiaozhi.ai.rag.QdrantRagService;
import com.xiaozhi.common.exception.OperationFailedException;
import com.xiaozhi.common.exception.ResourceNotFoundException;
import com.xiaozhi.common.model.bo.KnowledgeChunkBO;
import com.xiaozhi.common.model.bo.KnowledgeDetailBO;
import com.xiaozhi.common.model.bo.KnowledgeDocBO;
import com.xiaozhi.knowledge.service.KnowledgeAccessGuard;
import com.xiaozhi.knowledge.service.KnowledgeDocService;
import com.xiaozhi.knowledge.service.KnowledgeOriginalFiles;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
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
    private KnowledgeOriginalFiles originalFiles;

    @Resource
    private DocumentIngestService documentIngestService;

    @Resource
    private KnowledgeDocService knowledgeDocService;

    @Resource
    private KnowledgeAccessGuard knowledgeAccessGuard;

    @Resource
    private QdrantRagService qdrantRagService;

    /**
     * 上传并入库：一次请求完成"校验 → 落账 → 原文落盘 → 解析切块向量化 → 回写账本"。
     *
     * <p><b>执行顺序以方法体里的行内标号 ①~⑦ 为准，这里不再复述一遍。</b>
     * 以前 javadoc 自列过一份步骤清单、代码里另有一套标号，改代码时只动一处，
     * 两套编号就会指不同的事 —— 上次重写时确实踩到了：javadoc 列的项数和代码标号对不上。
     * 只有两处顺序上的取舍需要单独讲清楚：
     * <ul>
     *   <li><b>scope 权限校验要早</b>：它在 ① 之后、② 之前 —— 没权限的请求
     *       不该在磁盘上留下任何东西</li>
     *   <li><b>原文落盘（⑤）放在解析（③）之后</b>：那时已确认文件能解析，
     *       落盘失败不会留下垃圾</li>
     * </ul>
     *
     * <p><b>失败语义</b>：⑥ 起任一步失败（包含 ⑦ 写分块目录与置 success），都执行
     * {@code markFailed} + 按 docId 删向量 + 删刚落盘的原文，然后把原始异常抛出去，
     * 不留半截状态。
     *
     * <p><b>唯一被允许降级的是 ⑤ 落盘失败</b>：记 error 后继续入库 ——
     * 用户能查能用，只是这篇以后无法重建索引（换 embedding 模型、改切块规则时会缺原文）。
     *
     * <p><b>历史说明</b>：这里原本写着一大段"失败后能不能用原 fileId 重试"的推演。
     * 那个机制（两步式上传 + {@code .pending} 暂存 + {@code promote()}）已在
     * 2026-11-11 的一步式重构中<b>整体删除</b>（提交 {@code e65d0b9}），
     * fileId 与 promote 都不再存在，现在传进来的就是文件字节本身。
     *
     * @param fileName 原始文件名（展示与落盘都用它）
     * @param content  文件字节（≤ 20MB，调用方已在内存里读好）
     * @param req      归类 / 可见性 / 原文链接 / 发布日期（可空）
     * @param userId   归属人（<b>调用方从会话取，不能来自请求体</b>）
     * @return id、标题、字数、块数、状态
     */
    public Map<String, Object> create(String fileName, byte[] content, KnowledgeCreateReq req, Integer userId) {
        KnowledgeCreateReq request = req == null ? new KnowledgeCreateReq() : req;

        // ① 校验内容与格式。纯内存判断，不产生任何副作用 ——
        //    格式白名单来自 ai 层的 DocumentFormats（全项目唯一事实来源），
        //    上传侧不再自己维护一份拷贝：以后加 PDF/Word 只改那里，这里自动放开。
        DocumentFormats.assertAcceptable(fileName, content == null ? 0 : content.length);

        String scope = StringUtils.hasText(request.getScope())
                ? request.getScope().trim()
                : KnowledgeDocBO.SCOPE_PRIVATE;
        // 第 3 层鉴权（位于 ① 与 ② 之间：必须早于任何落盘动作 —— 没权限的请求
        // 不该在磁盘上留下任何东西；javadoc 里用"① 之后、② 之前"来锚定这一步）
        knowledgeAccessGuard.assertCanCreateScope(scope);

        int ownerId = userId == null ? 0 : userId;

        // ② 内容指纹（与身份无关，只由内容决定）
        String contentHash = DocumentIngestService.computeContentHash(content);

        // ③ 解析文件头部元信息（抓取脚本会把「来源栏目/发布日期/原文链接」写在 md 头部）。
        //    必须在写账本之前解析：否则抓取脚本不传参数时，Qdrant payload 有值、
        //    MySQL 账本却是空，两边对不上。
        DocumentIngestService.HeaderInfo header = parseHeaderOf(content);

        // ④ 找到或创建账本记录，拿到 docId（磁盘文件名要用它做前缀）
        Long docId = resolveDocId(ownerId, contentHash, fileName, request, scope, header);

        // ⑤ 原文落盘。失败不阻断入库：用户能查能用，只是这篇以后无法重建索引
        //    （换 embedding 模型、改切块规则时会缺原文）。所以日志必须是 error 级别，
        //    别让"看起来成功"掩盖了降级。
        String storedPath = "";
        try {
            storedPath = originalFiles.save(fileName, content, docId);
        } catch (Exception e) {
            log.error("原文落盘失败，该文档将无法重建索引: docId={}, 文件={}, 原因={}",
                    docId, fileName, e.getMessage());
        }

        // ⑥ 解析 → 切块 → 向量化 → 写 Qdrant
        DocumentIngestService.IngestOptions options = new DocumentIngestService.IngestOptions(
                docId, contentHash, storedPath,
                request.getUrl(), userId, scope, request.getCategory(), request.getPublishDate());
        DocumentIngestService.IngestResult result;
        try {
            result = documentIngestService.ingest(fileName, content, options);
        } catch (Exception e) {
            // 失败：记原因 + 删 Qdrant 残留 + 删刚落盘的原文（都幂等，且不掩盖原始错误）
            knowledgeDocService.markFailed(docId, e.getMessage());
            qdrantRagService.deleteByDocId(docId);
            originalFiles.delete(storedPath);
            throw new OperationFailedException("入库失败：" + e.getMessage(), e);
        }

        // ⑦ 成功：写分块目录 + 更新账本
        knowledgeDocService.replaceChunks(docId, buildChunkRows(result, docId));
        knowledgeDocService.markSuccess(docId, result.charCount(), result.chunkCount(), storedPath);

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
                              KnowledgeCreateReq req, String scope,
                              DocumentIngestService.HeaderInfo header) {
        KnowledgeDocBO existing = knowledgeDocService.findByOwnerAndHash(ownerId, contentHash);
        if (existing != null) {
            knowledgeDocService.resetToProcessing(withMeta(existing.getId(), contentHash, ownerId,
                    fileName, req, scope, header));
            return existing.getId();
        }

        KnowledgeDocBO doc = withMeta(null, contentHash, ownerId, fileName, req, scope, header);
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
                    fileName, req, scope, header));
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
                                           String fileName, KnowledgeCreateReq req, String scope,
                                           DocumentIngestService.HeaderInfo header) {
        KnowledgeDocBO doc = new KnowledgeDocBO();
        doc.setId(id);
        doc.setContentHash(contentHash);
        doc.setHashVersion(KnowledgeDocBO.HASH_VERSION_CURRENT);
        doc.setTitle(stripExtension(fileName));
        doc.setCategory(blankTo(req.getCategory(), header.category()));
        doc.setScope(scope);
        doc.setSourceType(resolveSourceType(req.getSourceType()));
        doc.setFileName(fileName);
        doc.setUrl(blankTo(req.getUrl(), header.url()));
        doc.setPublishDate(parseDate(blankTo(req.getPublishDate(), header.publishDate())));
        doc.setUserId(ownerId);
        return doc;
    }

    /**
     * 删除一份资料：Qdrant → 磁盘原文 → 账本。
     *
     * <p>三步都幂等，中途失败可以再点一次删除，不会残留半截状态。
     *
     * <p><b>Qdrant 这一步必须检查结果</b>：向量没删掉就把账本删了，
     * 那份资料还会被检索命中 —— 等于"删了但没删干净"，属于隐私事故。
     * 此时直接报错让用户重试，比静默成功安全。
     */
    public void delete(Long id, Integer userId) {
        KnowledgeDocBO doc = knowledgeDocService.get(id);
        if (doc == null) {
            throw new ResourceNotFoundException("文档不存在");
        }
        // 第 3 层鉴权：本人或管理员
        knowledgeAccessGuard.assertCanModify(doc, userId);

        // ② Qdrant（先做可重试的外部动作）
        // ⚠️ RAG 未启用时本来就没有向量可删，deleteByDocId 也返回 false，
        //    这种情况不能当成删除失败，所以先用 isEnabled() 区分开。
        if (qdrantRagService.isEnabled() && !qdrantRagService.deleteByDocId(id)) {
            throw new OperationFailedException(
                    "向量库删除失败，该资料仍可能被检索到。请稍后重试（Qdrant 可能暂时不可用）");
        }
        // ③ 磁盘原文（文件不在也不算失败）
        originalFiles.delete(doc.getFilePath());
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

    /**
     * 从原始字节解析文件头部元信息。
     *
     * <p>用 UTF-8 解码；解码异常就当作没有头部（返回全空），不让编码问题挡住入库。
     * 抓取脚本产出的都是 UTF-8 md，这条路覆盖绝大多数情况。
     */
    private static DocumentIngestService.HeaderInfo parseHeaderOf(byte[] content) {
        try {
            return DocumentIngestService.parseHeader(new String(content, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new DocumentIngestService.HeaderInfo("", "", "");
        }
    }

    /** 取第一个非空白值；都为空返回 null（账本里该列可空，不要塞空串）。 */
    private static String blankTo(String preferred, String fallback) {
        if (StringUtils.hasText(preferred)) {
            return preferred.trim();
        }
        if (StringUtils.hasText(fallback)) {
            return fallback.trim();
        }
        return null;
    }

    /**
     * 归一化来源类型：只认 upload / scrape / import 三个值，其他（含空）一律按 upload。
     *
     * <p>为什么不直接信前端传的值：这列进了统计与筛选，
     * 允许任意字符串会让前端一个拼写错误就产生一个新的"来源"分类。
     */
    private static String resolveSourceType(String raw) {
        if (!StringUtils.hasText(raw)) {
            return KnowledgeDocBO.SOURCE_UPLOAD;
        }
        String value = raw.trim().toLowerCase();
        return switch (value) {
            case KnowledgeDocBO.SOURCE_SCRAPE -> KnowledgeDocBO.SOURCE_SCRAPE;
            case KnowledgeDocBO.SOURCE_IMPORT -> KnowledgeDocBO.SOURCE_IMPORT;
            default -> KnowledgeDocBO.SOURCE_UPLOAD;
        };
    }

    private static String stripExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}