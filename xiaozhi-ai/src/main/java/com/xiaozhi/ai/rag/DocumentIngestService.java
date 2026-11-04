package com.xiaozhi.ai.rag;

import com.xiaozhi.common.config.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import com.xiaozhi.utils.DateUtils;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档入库：解析 → 切块 → 向量化 → 写 Qdrant。
 *
 * <p><b>只负责 RAG 那一半</b>：本类不碰 MySQL 账本、不判断权限。
 * 账本与权限在 {@code KnowledgeIngestAppService}（server 模块），本类只拿一个已存在的
 * {@code docId} 就往向量库写。
 *
 * <p>2026-11-01 重构（身份与指纹分离）：
 * <ul>
 *   <li>向量库里的 {@code doc_id} 从「内容哈希字符串」改成「账本自增主键的数字字符串」。
 *       自增 id 永不变，所以改哈希算法时向量库完全不用动。</li>
 *   <li>{@link #computeContentHash} 只依赖内容（{@code sha256(字节数|md5(内容))}），
 *       <b>不含 userId、不含文件名</b> —— 它降级为"去重指纹"，不再是身份。</li>
 *   <li>payload 额外带一个 {@code content_hash}，方便排查"这条向量属于哪份内容"。</li>
 * </ul>
 *
 * <p>⚠️ Qdrant 里 {@code doc_id} 的 payload 索引类型是 {@code keyword}，
 * 所以必须存<b>字符串</b>（{@code String.valueOf(id)}），过滤时也必须传字符串，
 * 否则匹配不上。
 */
@Slf4j
@Component
public class DocumentIngestService {

    /** 切块目标长度（中文按字符算）：攒到"再加一行就超"才切，所以普通块略小于此值。 */
    private static final int CHUNK_TARGET = 600;

    /** 单块硬上限：只在单个超长段落（无法按行安全切）时才按字符位置硬切。 */
    private static final int CHUNK_HARD_MAX = 800;

    /** 硬切时的重叠长度：只在硬切时生效，按行聚合切块没有重叠。 */
    private static final int OVERLAP = 80;

    /**
     * 单次 embedding 的最大条数。
     *
     * <p>阿里云 text-embedding-v3 的硬限制是 10 条；超过会在整篇文档入库时返回
     * {@code 400 InvalidParameter: batch size is invalid, it should not be larger than 10}。
     * 切块后超过 10 块的文档必须分批写入。
     */
    private static final int EMBED_BATCH_SIZE = 10;

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");

    /**
     * 详细追踪开关（读代码调试用）。
     *
     * <p>开启方式：环境变量 {@code KNOWLEDGE_DEBUG_TRACE=true}（或 application.yml 里同名配置）。
     * 开启后会额外打印：
     * <ul>
     *   <li>Tika 解析出的原文前 300 字</li>
     *   <li>每一块的序号 / 字数 / 标题路径</li>
     *   <li>第一块的完整 payload（看清到底往向量库里存了什么）</li>
     *   <li>每一批 embedding 的进度</li>
     * </ul>
     *
     * <p>平时保持关闭：38 篇文档就 82 块，全打出来会刷屏。
     */
    private static volatile boolean traceEnabled = false;

    private final QdrantRagService ragService;

    public DocumentIngestService(QdrantRagService ragService, RagProperties properties) {
        this.ragService = ragService;
        traceEnabled = properties.getKnowledge().isDebugTrace();
        log.info("文档入库服务初始化: 切块目标 {} 字（硬上限 {}，硬切重叠 {}），embedding 每批 {} 条，详细追踪={}",
                CHUNK_TARGET, CHUNK_HARD_MAX, OVERLAP, EMBED_BATCH_SIZE, traceEnabled ? "开" : "关");
        if (traceEnabled) {
            log.info("  ⚠️ xiaozhi.rag.knowledge.debug-trace=true：入库时会打印切块明细与 payload，仅用于调试，记得关掉");
        }
    }

    /** 裁剪文本用于日志预览（避免刷屏）。 */
    private static String preview(String text, int max) {
        if (text == null) {
            return "null";
        }
        String oneLine = text.replace("\n", "⏎");
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…（共 " + text.length() + " 字）";
    }

    // ---------------------------------------------------------------- 记录类型

    /** 切块结果（块正文 + 标题路径）。 */
    public record Chunk(String text, String heading) {
    }

    /** 从 Markdown 头部解析出的元信息（没有对应行就为空串）。 */
    public record HeaderInfo(String category, String publishDate, String url) {
    }

    /**
     * 入库选项。
     *
     * @param docId       账本主键（<b>必填</b>）—— 向量库与分块表都用它关联
     * @param contentHash 内容指纹（可空，仅写进 payload 便于排查）
     * @param storedPath  原文保存位置（<b>相对上传根目录</b>的路径，如 {@code 3-图书馆规则.md}）。
     *                    由编排层负责落盘并把路径传进来 —— RAG 层不碰业务文件存储。
     *                    可空：抓取器/批量导入这类场景不落磁盘。
     * @param sourceUrl   原文链接（可空，会被 md 头部的「原文链接：」兜底）
     * @param userId      归属用户（null 视为 0）
     * @param scope       可见性 private / shared；空则 private
     * @param category    归类；空则用 md 头部「来源栏目：」
     * @param publishDate 发布日期 yyyy-MM-dd；空则用 md 头部「发布日期：」
     */
    public record IngestOptions(Long docId, String contentHash, String storedPath, String sourceUrl,
                                Integer userId, String scope, String category, String publishDate) {

        /** 抓取器/批量导入的便捷构造：无指纹、不落盘、共享可见。 */
        public static IngestOptions of(String sourceUrl, Integer userId) {
            return new IngestOptions(null, null, null, sourceUrl, userId, "shared", null, null);
        }
    }

    /**
     * 入库结果。
     *
     * @param chunks     每块的标题路径与正文（正文只进向量库，账本只记目录）
     * @param storedPath 原文落盘绝对路径；空串表示落盘失败（不影响入库）
     */
    public record IngestResult(Long docId, String contentHash, String title, int charCount,
                               int chunkCount, List<Chunk> chunks, String storedPath, long pointsAfter) {
    }

    // ---------------------------------------------------------------- 元信息解析

    /**
     * 解析 md 头部元信息；遇到第一行 {@code ---} 就停止（避免误伤正文）。
     *
     * <p>约定格式（抓取脚本生成的文件都带这几行）：
     * <pre>
     * - 来源栏目：通知公告
     * - 发布日期：2026-10-23
     * - 原文链接：https://xxx
     * </pre>
     */
    /**
     * 解析文件头部的元信息。
     *
     * <p><b>public 的原因</b>：编排层（{@code KnowledgeIngestAppService}）要在写账本之前
     * 就知道这三个值 —— 否则抓取脚本不传参数时，Qdrant payload 里有值、MySQL 账本里却是空，
     * 两边不一致。让两处共用同一套解析规则，就不会漂移。
     */
    public static HeaderInfo parseHeader(String text) {
        String category = "";
        String publishDate = "";
        String url = "";
        if (text != null) {
            for (String rawLine : text.split("\n")) {
                String line = rawLine.trim();
                if ("---".equals(line)) {
                    break;
                }
                if (line.startsWith("- 来源栏目：")) {
                    category = line.substring("- 来源栏目：".length()).trim();
                } else if (line.startsWith("- 发布日期：")) {
                    String value = line.substring("- 发布日期：".length()).trim();
                    // 只接受 yyyy-MM-dd，别的写法忽略
                    if (value.length() >= 10 && value.charAt(4) == '-' && value.charAt(7) == '-') {
                        publishDate = value.substring(0, 10);
                    }
                } else if (line.startsWith("- 原文链接：")) {
                    url = line.substring("- 原文链接：".length()).trim();
                }
            }
        }
        return new HeaderInfo(category, publishDate, url);
    }

    /** 取第一个非空白值；都为空则返回 fallback。 */
    private static String blankTo(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred.trim();
        }
        return fallback == null ? "" : fallback.trim();
    }

    // ---------------------------------------------------------------- 入库主流程

    /** 兼容旧签名。 */
    public IngestResult ingest(String fileName, byte[] content, String sourceUrl, Integer userId) throws Exception {
        return ingest(fileName, content, IngestOptions.of(sourceUrl, userId));
    }

    public IngestResult ingest(String fileName, byte[] content, IngestOptions options) throws Exception {
        IngestOptions opts = options == null ? IngestOptions.of(null, null) : options;
        Long docId = opts.docId();
        if (docId == null) {
            throw new IllegalStateException("缺少 docId：请先在账本里创建文档记录，再调用本方法入库");
        }

        // ① 解析
        String text = extractText(fileName, content);
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("解析后没有取到任何文本（可能是扫描版 PDF，需要 OCR 才能提取）");
        }
        log.info("① 解析: {} 字节 → {} 字纯文本（Tika，{}）", content.length, text.length(), fileName);
        if (traceEnabled) {
            log.info("   [trace] 原文前 300 字: {}", preview(text, 300));
        }

        // ② 切块
        List<Chunk> chunks = split(text);
        if (chunks.isEmpty()) {
            throw new IllegalStateException("切块结果为空");
        }
        int totalChars = chunks.stream().mapToInt(c -> c.text().length()).sum();
        log.info("② 切块: {} 字 → {} 块（平均 {} 字/块）",
                text.length(), chunks.size(), chunks.isEmpty() ? 0 : totalChars / chunks.size());
        if (traceEnabled) {
            for (int i = 0; i < chunks.size(); i++) {
                Chunk c = chunks.get(i);
                log.info("   [trace] 块{}/{}: {} 字, heading=[{}], 开头: {}",
                        i, chunks.size() - 1, c.text().length(), c.heading(), preview(c.text(), 60));
            }
        }

        // ③ 元信息
        int ownerId = opts.userId() == null ? 0 : opts.userId();
        String contentHash = opts.contentHash() != null ? opts.contentHash() : computeContentHash(content);
        String title = stripExtension(fileName);
        String updatedAt = DateUtils.instant().toString();
        HeaderInfo header = parseHeader(text);
        String scope = blankTo(opts.scope(), "private");
        String category = blankTo(opts.category(), header.category());
        String publishDate = blankTo(opts.publishDate(), header.publishDate());
        String effectiveUrl = blankTo(opts.sourceUrl(), header.url());

        // ④ 原文位置：由编排层落盘并传进来（RAG 层不碰业务文件存储，避免两处都写造成重复文件）
        String storedPath = opts.storedPath() == null ? "" : opts.storedPath();

        // ⑤ 幂等：同一份文档重新入库，先清掉旧向量
        String docIdText = String.valueOf(docId);
        try {
            ragService.vectorStore().delete(new FilterExpressionBuilder().eq("doc_id", docIdText).build());
            log.debug("已清理同 docId 的旧向量: {}", docIdText);
        } catch (Exception e) {
            log.debug("清理旧向量跳过（集合可能尚不存在）: {}", brief(e));
        }

        // ⑥ 组装 Document（正文 + payload）
        List<Document> documents = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            Map<String, Object> metadata = new LinkedHashMap<>();
            // ⚠️ 必须 String：Qdrant 里 doc_id 是 keyword 索引
            metadata.put("doc_id", docIdText);
            metadata.put("chunk_id", docIdText + "-" + i);
            metadata.put("content_hash", contentHash);
            metadata.put("source", fileName);
            metadata.put("title", title);
            metadata.put("heading", chunk.heading());
            metadata.put("url", effectiveUrl);
            metadata.put("category", category);
            metadata.put("publish_date", publishDate);
            // scope 是检索时的可见性开关：private 只给本人，shared 给所有人
            metadata.put("scope", scope);
            metadata.put("updated_at", updatedAt);
            metadata.put("user_id", ownerId);
            metadata.put("chunk_index", i);
            metadata.put("file_path", storedPath);
            documents.add(new Document(UUID.randomUUID().toString(), chunk.text(), metadata));
        }

        // ⑦ 分批写入（阿里云 embedding 单次最多 10 条）
        int batchCount = (documents.size() + EMBED_BATCH_SIZE - 1) / EMBED_BATCH_SIZE;
        log.info("③ 向量化: {} 块 → 分 {} 批调 embedding（每批最多 {} 条）", documents.size(), batchCount, EMBED_BATCH_SIZE);
        int batchNo = 0;
        for (int from = 0; from < documents.size(); from += EMBED_BATCH_SIZE) {
            int to = Math.min(documents.size(), from + EMBED_BATCH_SIZE);
            batchNo++;
            if (traceEnabled) {
                log.info("   [trace] 第 {}/{} 批: 写入块 {}~{}", batchNo, batchCount, from, to - 1);
            }
            ragService.vectorStore().add(documents.subList(from, to));
        }

        long points = 0L;
        try {
            Object pingPoints = ragService.ping().get("pointsCount");
            if (pingPoints instanceof Number number) {
                points = number.longValue();
            }
        } catch (Exception ignored) {
            // 统计失败不影响入库结果
        }
        log.info("④ 入库完成: id={}, 文件={}, 原文 {} 字 → {} 块 → Qdrant（当前总点数 {}）",
                docId, fileName, text.length(), chunks.size(), points);
        return new IngestResult(docId, contentHash, title, text.length(), chunks.size(),
                chunks, storedPath, points);
    }

    /** Tika 抽取纯文本。 */
    private String extractText(String fileName, byte[] content) throws Exception {
        BodyContentHandler handler = new BodyContentHandler(-1);
        Metadata metadata = new Metadata();
        metadata.set("resourceName", fileName == null ? "unknown" : fileName);
        try (ByteArrayInputStream in = new ByteArrayInputStream(content)) {
            new AutoDetectParser().parse(in, handler, metadata, new ParseContext());
        }
        return normalize(handler.toString());
    }

    /** 统一换行符、压缩多余空白。 */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String s = text.replace("\r\n", "\n").replace('\r', '\n').replace('\u00a0', ' ');
        s = s.replaceAll("[ \\t]+", " ");
        s = s.replaceAll("\n{3,}", "\n\n");
        return s.trim();
    }

    // ---------------------------------------------------------------- 切块

    /**
     * 切块：按标题层级优先，其次按行聚合。
     *
     * <p>两个判断信号（<b>不判断"段落结尾"</b>）：
     * <ol>
     *   <li>遇到 markdown 标题行（{@code #} 开头）→ 先切一刀，再更新章节路径</li>
     *   <li>加下一行会超过 {@link #CHUNK_TARGET} → 先切一刀</li>
     * </ol>
     * 所以切点只落在<b>行边界</b>上；这里不产生重叠（行边界不会切断句子）。
     * 重叠只在 {@link #flush} 里的超长段落硬切时才产生。
     */
    static List<Chunk> split(String text) {
        List<Chunk> out = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        List<String> headingStack = new ArrayList<>();
        String currentHeading = "";
        int lineNo = 0;

        for (String rawLine : text.split("\n")) {
            lineNo++;
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            Matcher matcher = HEADING.matcher(line);
            if (matcher.matches()) {
                // 标题前先收一刀，保证上一段归属正确的标题
                int before = out.size();
                flush(out, buffer, currentHeading);
                if (traceEnabled && out.size() > before) {
                    log.info("   [trace] 行{}: 遇到标题「{}」→ 先切一刀（生成 {} 块）",
                            lineNo, matcher.group(2).trim(), out.size() - before);
                }
                int level = matcher.group(1).length();
                while (headingStack.size() >= level) {
                    headingStack.remove(headingStack.size() - 1);
                }
                headingStack.add(matcher.group(2).trim());
                currentHeading = String.join(" > ", headingStack);
                if (traceEnabled) {
                    log.info("   [trace] 行{}: 章节路径更新为「{}」", lineNo, currentHeading);
                }
                // 标题文字本身不进正文，只作为 heading 前缀拼到每一块前面
                continue;
            }
            int bufLen = buffer.length();
            if (bufLen > 0 && bufLen + line.length() + 1 > CHUNK_TARGET) {
                if (traceEnabled) {
                    log.info("   [trace] 行{}: 缓冲区 {} 字 + 本行 {} 字 + 换行 = {} > {} → 切块",
                            lineNo, bufLen, line.length(), bufLen + line.length() + 1, CHUNK_TARGET);
                }
                flush(out, buffer, currentHeading);
            }
            buffer.append(line).append('\n');
        }
        if (traceEnabled && buffer.length() > 0) {
            log.info("   [trace] 文末: 缓冲区还剩 {} 字 → 最后切一刀", buffer.length());
        }
        flush(out, buffer, currentHeading);
        return out;
    }

    /**
     * 把缓冲区内容落成块；对超长段落按 {@link #CHUNK_HARD_MAX} 硬切并保留 {@link #OVERLAP} 重叠。
     *
     * <p>普通块（≤800 字）只会循环一次，所以没有重叠；
     * 只有单段超过 800 字、无法按行安全切开时才硬切，那时才需要重叠来补救被切断的句子。
     */
    private static void flush(List<Chunk> out, StringBuilder buffer, String heading) {
        String body = buffer.toString().trim();
        buffer.setLength(0);
        if (body.isEmpty()) {
            return;
        }
        if (traceEnabled) {
            log.info("   [trace]   └─ 落块: 正文 {} 字（加 heading 前缀后 {}, 循环次数 {}）",
                    body.length(), heading.isEmpty() ? body.length() : body.length() + heading.length() + 1,
                    (body.length() + CHUNK_HARD_MAX - 1) / CHUNK_HARD_MAX);
        }
        for (int start = 0; start < body.length(); ) {
            int end = Math.min(body.length(), start + CHUNK_HARD_MAX);
            String piece = body.substring(start, end).trim();
            if (!piece.isEmpty()) {
                String withHeading = heading.isEmpty() ? piece : heading + "\n" + piece;
                out.add(new Chunk(withHeading, heading));
            }
            if (end >= body.length()) {
                break;
            }
            start = Math.max(start + 1, end - OVERLAP);
            if (traceEnabled) {
                log.info("   [trace]   └─ 超长段落硬切: 下一片从 {} 开始（回退 {} 字重叠）", start, OVERLAP);
            }
        }
    }

    // ---------------------------------------------------------------- 指纹与工具

    /**
     * 内容指纹 = {@code sha256(字节数 | md5(内容))}，返回完整 64 位十六进制。
     *
     * <p><b>不含 userId、不含文件名</b>：
     * <ul>
     *   <li>不含 userId → 它才是"内容"的指纹；用户维度交给 {@code uk_owner_hash(userId, contentHash)}</li>
     *   <li>不含文件名 → 同一份内容改个名字不算新文档（符合"指纹"语义）</li>
     *   <li>带字节数 → 防 md5 碰撞（虽然概率极低，但让指纹更稳）</li>
     * </ul>
     *
     * <p>算法要变时：加字段或换实现，并把 {@code sys_knowledge_doc.hashVersion} 自增，
     * 存量数据可以分批重算 —— <b>主键与向量库关联完全不受影响</b>。
     */
    public static String computeContentHash(byte[] content) {
        byte[] safe = content == null ? new byte[0] : content;
        String head = safe.length + "|" + md5Hex(safe);
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(head.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("计算内容指纹失败: " + e.getMessage(), e);
        }
    }

    private static String stripExtension(String fileName) {
        if (fileName == null) {
            return "未命名";
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String md5Hex(byte[] content) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md5.digest(content));
        } catch (Exception e) {
            return String.valueOf(content.length);
        }
    }

    /** 日志里只打印异常消息的前 120 字。 */
    private static String brief(Throwable e) {
        String message = e.getMessage();
        if (message == null) {
            return e.getClass().getSimpleName();
        }
        return message.length() > 120 ? message.substring(0, 120) + "..." : message;
    }
}