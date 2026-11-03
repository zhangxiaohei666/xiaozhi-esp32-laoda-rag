package com.xiaozhi.knowledge;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.xiaozhi.ai.rag.DocumentIngestService;
import com.xiaozhi.ai.rag.QdrantRagService;
import com.xiaozhi.ai.rag.RetrievalService;
import com.xiaozhi.common.model.PageResult;
import com.xiaozhi.common.model.bo.KnowledgeDocBO;
import com.xiaozhi.common.model.resp.KnowledgeCreateResultResp;
import com.xiaozhi.common.model.resp.KnowledgeDetailResp;
import com.xiaozhi.common.model.resp.KnowledgeDocResp;
import com.xiaozhi.common.model.resp.KnowledgePingResp;
import com.xiaozhi.common.model.resp.KnowledgeSearchResp;
import com.xiaozhi.common.model.resp.KnowledgeStatsResp;
import com.xiaozhi.common.web.ApiResponse;
import com.xiaozhi.knowledge.convert.KnowledgeConvert;
import com.xiaozhi.knowledge.service.KnowledgeAccessGuard;
import com.xiaozhi.knowledge.service.KnowledgeDocService;
import com.xiaozhi.server.web.BaseController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库（RAG）业务接口。
 *
 * <h3>接口分工</h3>
 * <pre>
 * 文件传输：POST /api/knowledge/files          （KnowledgeFileController，只落盘）
 * 入库：    POST /api/knowledge/documents      （本类，校验权限 → 解析向量化 → 回写账本）
 * 管理：    GET/DELETE /api/knowledge/documents[/{docId}]、/stats、/categories
 * 调试：    GET /api/knowledge/ping、/search
 * </pre>
 *
 * <h3>三层鉴权</h3>
 * <ol>
 *   <li>登录：Sa-Token 拦截器统一 {@code StpUtil.checkLogin()}</li>
 *   <li>功能：方法上的 {@code @SaCheckPermission("system:knowledge:api:*")}</li>
 *   <li>数据：{@link KnowledgeAccessGuard}（本人 / 共享 / 管理员），逻辑不写在本类里</li>
 * </ol>
 *
 * <p>归属人一律从会话取（{@code knowledgeAccessGuard.currentUserId()}），**不接受请求体传 userId**。
 */
@RestController
@RequestMapping("/api/knowledge")
@Tag(name = "知识库", description = "RAG 知识库：文档入库、列表、删除、检索调试")
public class KnowledgeController extends BaseController {

    @Resource
    private QdrantRagService qdrantRagService;

    @Resource
    private DocumentIngestService documentIngestService;

    @Resource
    private RetrievalService retrievalService;

    @Resource
    private KnowledgeDocService knowledgeDocService;

    @Resource
    private KnowledgeAccessGuard knowledgeAccessGuard;

    @Resource
    private KnowledgeIngestAppService knowledgeIngestAppService;

    /** BO → Resp 转换：Controller 出参一律是 Resp，DO/BO 不出 web 层。 */
    @Resource
    private KnowledgeConvert knowledgeConvert;

    @Resource
    private com.xiaozhi.knowledge.service.KnowledgePendingFiles knowledgePendingFiles;

    // ==================== 连通性自检 ====================

    /**
     * RAG 连通性自检。
     *
     * @return 各环节状态（Qdrant 可达性、集合是否存在、embedding 配置是否就绪）
     */
    @GetMapping("/ping")
    @ResponseBody
    @SaCheckLogin
    @Operation(summary = "RAG 连通性自检", description = "检查 Qdrant 是否可达、集合是否存在、embedding 配置是否就绪")
    public ApiResponse<KnowledgePingResp> ping() {
        return ApiResponse.success(toPingResp(qdrantRagService.ping()));
    }

    // ==================== 入库（两步式的第二步） ====================

    /**
     * 把已上传到待入库区的文件变成一条知识库文档。
     *
     * <p>流程：校验 scope 权限 → 读待入库文件 → 落账 processing → 解析/切块/向量化/写 Qdrant
     * → 写分块目录 → 账本置 success（失败则 failed + 补偿删向量）。
     *
     * @param req 文件 id + 归类 + 可见性 + 原文链接 + 发布日期
     * @return docId、标题、字数、块数、可见性、状态
     */
    @PostMapping("/documents")
    @ResponseBody
    @SaCheckPermission("system:knowledge:api:upload")
    @Operation(summary = "创建知识库文档",
            description = "传入第一步返回的 fileId。走权限校验后解析、切块、向量化并回写账本")
    public ApiResponse<KnowledgeCreateResultResp> createDocument(@RequestBody KnowledgeCreateReq req) {
        Integer userId = knowledgeAccessGuard.currentUserId();
        return ApiResponse.success(toCreateResult(knowledgeIngestAppService.create(req, userId)));
    }

    // ==================== 查询 ====================

    /**
     * 文档列表。
     *
     * <p>非管理员只能看到「自己上传的 + 所有共享的」，管理员看全部。
     *
     * @param pageNo   页码，从 1 开始
     * @param pageSize 每页条数
     * @param category 归类筛选（可空）
     * @param status   状态筛选 processing/success/failed（可空）
     * @param scope    可见性筛选 private/shared（可空）
     * @param keyword  标题或文件名模糊匹配（可空）
     */
    @GetMapping("/documents")
    @ResponseBody
    @SaCheckPermission("system:knowledge:api:list")
    @Operation(summary = "文档列表", description = "非管理员只返回自己的与共享的文档")
    public ApiResponse<PageResult<KnowledgeDocResp>> listDocuments(
            @Parameter(description = "页码") @RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
            @Parameter(description = "每页条数") @RequestParam(value = "pageSize", defaultValue = "20") int pageSize,
            @Parameter(description = "归类") @RequestParam(value = "category", required = false) String category,
            @Parameter(description = "状态") @RequestParam(value = "status", required = false) String status,
            @Parameter(description = "可见性") @RequestParam(value = "scope", required = false) String scope,
            @Parameter(description = "关键词") @RequestParam(value = "keyword", required = false) String keyword) {

        Integer ownerFilter = knowledgeAccessGuard.needOwnerFilter()
                ? knowledgeAccessGuard.currentUserId() : null;
        return ApiResponse.success(knowledgeDocService.page(
                Math.max(1, pageNo), Math.min(Math.max(1, pageSize), 200),
                category, status, scope, keyword, ownerFilter)
                .map(knowledgeConvert::toResp));
    }

    /**
     * 文档详情：账本信息 + 分块目录（不含正文，正文在向量库与原文文件里）。
     *
     * <p>无权查看时返回 404 而不是 403 —— 不暴露「这个 id 存在」。
     */
    @GetMapping("/documents/{id}")
    @ResponseBody
    @SaCheckPermission("system:knowledge:api:detail")
    @Operation(summary = "文档详情", description = "含分块目录，不含分块正文")
    public ApiResponse<KnowledgeDetailResp> detailDocument(
            @Parameter(description = "文档ID（账本自增主键）") @PathVariable("id") Long id) {
        Integer userId = knowledgeAccessGuard.currentUserId();
        return ApiResponse.success(knowledgeConvert.toResp(knowledgeIngestAppService.detail(id, userId)));
    }

    /** 统计：文档数、成功/失败/处理中、总块数、总字数。非管理员只统计自己有权限看的。 */
    @GetMapping("/stats")
    @ResponseBody
    @SaCheckPermission("system:knowledge:api:stats")
    @Operation(summary = "知识库统计")
    public ApiResponse<KnowledgeStatsResp> stats() {
        Integer ownerFilter = knowledgeAccessGuard.needOwnerFilter()
                ? knowledgeAccessGuard.currentUserId() : null;
        return ApiResponse.success(toStatsResp(knowledgeDocService.stats(ownerFilter)));
    }

    /** 已有的归类取值，供前端下拉。 */
    @GetMapping("/categories")
    @ResponseBody
    @SaCheckPermission("system:knowledge:api:list")
    @Operation(summary = "归类列表", description = "已入库文档出现过的归类取值")
    public ApiResponse<java.util.List<String>> categories() {
        Integer ownerFilter = knowledgeAccessGuard.needOwnerFilter()
                ? knowledgeAccessGuard.currentUserId() : null;
        return ApiResponse.success(knowledgeDocService.listCategories(ownerFilter));
    }

    // ==================== 删除 ====================

    /**
     * 删除一份资料：向量库 → 磁盘原文 → 账本，三步幂等。
     *
     * <p>非管理员只能删自己上传的。
     */
    @DeleteMapping("/documents/{id}")
    @ResponseBody
    @SaCheckPermission("system:knowledge:api:delete")
    @Operation(summary = "删除文档", description = "同步删除 Qdrant 向量、磁盘原文与账本记录")
    public ApiResponse<Void> deleteDocument(
            @Parameter(description = "文档ID（账本自增主键）") @PathVariable("id") Long id) {
        Integer userId = knowledgeAccessGuard.currentUserId();
        knowledgeIngestAppService.delete(id, userId);
        return ApiResponse.success();
    }

    // ==================== 检索调试 ====================

    /**
     * 检索调试接口：直接看 RAG 命中了什么、分数多少。
     *
     * <p>这是排查 RAG 的主要手段：不依赖对话，直接问一句就能看到召回结果。
     *
     * @param q 问题文本
     * @return 命中的块（分数、标题、章节、片段）
     */
    @GetMapping("/search")
    @ResponseBody
    @SaCheckPermission("system:knowledge:api:search")
    @Operation(summary = "检索调试", description = "直接查看 RAG 命中结果与相似度分数")
    public ApiResponse<KnowledgeSearchResp> search(
            @Parameter(description = "问题文本") @RequestParam("q") String q) {
        // 与对话链路用同一套过滤（user_id / scope），否则调试结果会误导
        return ApiResponse.success(toSearchResp(q,
                retrievalService.searchForDebug(q, knowledgeAccessGuard.currentUserId())));
    }

    // ==================== 旧接口（保留兼容，已废弃） ====================

    /**
     * 一步式上传并入库（给抓取脚本用的便捷入口）。
     *
     * <p>内部走的还是两步式的同一套编排：先落 .pending，再入库。
     * 保留这个接口是为了不让已有的抓取脚本（{@code .run\抓取*.ps1}）改动。
     *
     * <p>可见性固定为 shared —— 抓取的都是公开网页内容，与早期行为一致。
     *
     * @deprecated 新代码请用两步式 {@code POST /api/knowledge/files} + {@code POST /api/knowledge/documents}
     */
    @Deprecated
    @PostMapping("/upload")
    @ResponseBody
    @SaCheckPermission("system:knowledge:api:upload")
    @Operation(summary = "【已废弃】一步式上传并入库", description = "请改用 /files + /documents 两步式")
    public ApiResponse<KnowledgeCreateResultResp> upload(
            @Parameter(description = "文档文件") @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @Parameter(description = "来源链接（可选）") @RequestParam(value = "url", required = false) String url)
            throws Exception {

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件为空");
        }
        String fileName = file.getOriginalFilename();
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("缺少文件名");
        }

        // ① 先落到 .pending（与两步式的第一步等价）
        com.xiaozhi.knowledge.service.KnowledgePendingFiles.PendingFile pending =
                knowledgePendingFiles.save(fileName, file.getBytes());

        // ② 走统一编排入库
        KnowledgeCreateReq req = new KnowledgeCreateReq();
        req.setFileId(pending.fileId());
        req.setScope(KnowledgeDocBO.SCOPE_SHARED);
        req.setUrl(url);

        return ApiResponse.success(toCreateResult(
                knowledgeIngestAppService.create(req, knowledgeAccessGuard.currentUserId())));
    }

    // ==================== Map → Resp 转换 ====================

    /**
     * 把入库编排返回的 Map 转成明确出参。
     *
     * <p>编排层用 Map 是因为它还要带出"临时算出来的 contentHash"这类非持久化字段；
     * 到了 web 边界就换成 {@link KnowledgeCreateResultResp}，让前端能拿到哪些字段一目了然。
     */
    private static KnowledgeCreateResultResp toCreateResult(Map<String, Object> data) {
        KnowledgeCreateResultResp resp = new KnowledgeCreateResultResp();
        resp.setId(asLong(data.get("id")));
        resp.setContentHash((String) data.get("contentHash"));
        resp.setTitle((String) data.get("title"));
        resp.setCharCount(asInt(data.get("charCount")));
        resp.setChunkCount(asInt(data.get("chunkCount")));
        resp.setScope((String) data.get("scope"));
        resp.setStatus((String) data.get("status"));
        return resp;
    }

    /** Map → 连通性自检出参。 */
    private static KnowledgePingResp toPingResp(Map<String, Object> data) {
        KnowledgePingResp resp = new KnowledgePingResp();
        resp.setEnabled(asBool(data.get("enabled")));
        resp.setQdrantAddress((String) data.get("qdrantAddress"));
        resp.setCollection((String) data.get("collection"));
        resp.setQdrantReachable(asBool(data.get("qdrantReachable")));
        resp.setCollectionExists(asBool(data.get("collectionExists")));
        resp.setPointsCount(asLong(data.get("pointsCount")));
        resp.setVectorSize(asInt(data.get("vectorSize")));
        resp.setNote((String) data.get("note"));
        resp.setEmbeddingConfigured(asBool(data.get("embeddingConfigured")));
        resp.setHint((String) data.get("hint"));
        return resp;
    }

    /** Map → 统计出参。 */
    private static KnowledgeStatsResp toStatsResp(Map<String, Object> data) {
        KnowledgeStatsResp resp = new KnowledgeStatsResp();
        resp.setDocCount(asInt(data.get("docCount")));
        resp.setSuccessCount(asInt(data.get("successCount")));
        resp.setFailedCount(asInt(data.get("failedCount")));
        resp.setProcessingCount(asInt(data.get("processingCount")));
        resp.setChunkCount(asInt(data.get("chunkCount")));
        resp.setCharCount(asLong(data.get("charCount")));
        return resp;
    }

    /** 检索结果 → 出参（把实际生效的 topK / 阈值一并带出，便于判断"为什么没命中"）。 */
    private KnowledgeSearchResp toSearchResp(String query, List<Map<String, Object>> hits) {
        KnowledgeSearchResp resp = new KnowledgeSearchResp();
        resp.setQuery(query);
        resp.setTopK(retrievalService.getTopK());
        resp.setThreshold(retrievalService.getThreshold());
        List<KnowledgeSearchResp.Hit> list = new java.util.ArrayList<>(hits == null ? 0 : hits.size());
        if (hits != null) {
            for (Map<String, Object> h : hits) {
                KnowledgeSearchResp.Hit hit = new KnowledgeSearchResp.Hit();
                hit.setScore(h.get("score") instanceof Number n ? n.doubleValue() : null);
                hit.setTitle((String) h.get("title"));
                hit.setHeading((String) h.get("heading"));
                hit.setSource((String) h.get("source"));
                hit.setDocId((String) h.get("docId"));
                hit.setSnippet((String) h.get("snippet"));
                list.add(hit);
            }
        }
        resp.setHits(list);
        return resp;
    }

    private static Boolean asBool(Object value) {
        return value instanceof Boolean b ? b : null;
    }

    private static Long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }

    private static Integer asInt(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }
}