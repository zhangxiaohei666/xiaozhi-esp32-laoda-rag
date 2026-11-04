package com.xiaozhi.knowledge.service;

import com.xiaozhi.common.config.RagProperties;
import com.xiaozhi.common.config.RuntimePathConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 「待入库文件」存储：两步式上传里，第一步把文件落在这里，第二步才解析入库。
 *
 * <p>为什么要有这一层：
 * <ul>
 *   <li><b>上传与鉴权解耦</b>：第一步只负责"收文件并落盘"，不认识知识库；
 *       真正的权限与归属校验都在第二步做。</li>
 *   <li><b>失败不用重传</b>：入库失败时文件还在，重新调第二步即可（大文件尤其重要）。</li>
 * </ul>
 *
 * <p>文件命名 {@code <fileId>-<安全文件名>}，fileId 取内容哈希，同一份文件重复上传天然幂等。
 * 注意 fileId 与 {@code sys_knowledge_doc.id} / {@code contentHash} <b>三者互不相干</b>：
 * fileId 只是"待入库文件"的临时句柄，入库完成即失效。
 *
 * <p>目录结构：
 * <pre>
 * KNOWLEDGE_UPLOAD_DIR/
 *   ├─ .pending/      第一步上传、尚未入库的文件（可被清理）
 *   └─ &lt;id&gt;-名称     已入库文档的原文副本（重建索引用）
 * </pre>
 *
 * <p>⚠️ 2026-11-01 两项跨平台改造（部署目标是 Linux）：
 * <ol>
 *   <li>{@link #promote} 返回<b>相对路径</b>（相对上传根目录）而不是绝对路径 ——
 *       换机器/换系统/换目录时数据库里的路径不用改；</li>
 *   <li>{@link #safeFileName} 按<b>字节</b>而不是字符截断 ——
 *       Linux 单文件名上限 255 字节，而一个中文字占 3 字节，
 *       原来"截到 120 字符"在纯中文长名下会算成 360 字节，直接报 File name too long。</li>
 * </ol>
 *
 * <p>⚠️ 孤儿文件（传了没入库）不会自动清理，v1 阶段靠人工清理 {@code .pending} 目录。
 */
@Slf4j
@Component
public class KnowledgePendingFiles {

    /** 允许的扩展名：只收纯文本与 Markdown，PDF/Word 的中文提取尚未验证，暂不开放 */
    public static final List<String> ALLOWED_EXTENSIONS = List.of("txt", "md", "markdown");

    /** 单文件大小上限 20MB */
    public static final long MAX_UPLOAD_BYTES = 20L * 1024 * 1024;

    /**
     * 单个文件名允许的最大字节数。
     *
     * <p>Linux 上限 255 字节；这里留到 180，是因为正式目录里的名字还要加 {@code <id>-} 前缀，
     * 而且 UTF-8 中文一个字 3 字节，余量太小容易踩线。
     */
    private static final int MAX_FILE_NAME_BYTES = 180;

    private static final String PENDING_DIR_NAME = ".pending";

    private final Path root;
    private final Path pendingDir;

    public KnowledgePendingFiles(RuntimePathConfig runtimePath, RagProperties ragProperties) {
        // 目录解析统一交给 RuntimePathConfig：
        //   留空     → ${xiaozhi.runtime.data-dir}/knowledge（推荐，一处配置管全部落盘文件）
        //   相对路径 → 相对 data-dir
        //   绝对路径 → 原样使用
        //
        // ⚠️ 早期实现把 "D:\xiaozhi\.run\knowledge" 写成默认值。Windows 的反斜杠在 Linux 上是
        //    合法文件名字符，Linux 上会创建出一个名为 `D:\xiaozhi\.run\knowledge` 的**单层目录**——
        //    功能看着正常，数据却落到一个谁也不会去找的地方。
        String configured = ragProperties.getKnowledge().getUploadDir();
        this.root = (configured == null || configured.isBlank())
                ? runtimePath.resolveStorageKey("knowledge")
                : runtimePath.resolveStorageKey(configured);
        this.pendingDir = root.resolve(PENDING_DIR_NAME);
        try {
            Files.createDirectories(pendingDir);
        } catch (IOException e) {
            log.warn("创建待入库目录失败: {}", e.getMessage());
        }
        log.info("知识库原文目录: {}（xiaozhi.rag.knowledge.upload-dir={}）",
                root, (configured == null || configured.isBlank()) ? "留空 → data-dir/knowledge" : configured);
    }

    /** 第一步的产物 */
    public record PendingFile(String fileId, String fileName, long size, String extension, Path path) {
    }

    public Path rootDir() {
        return root;
    }

    /**
     * 把账本里存的 {@code filePath} 解析成可操作的绝对路径。
     *
     * <p>兼容两种历史存法：新数据是相对路径（相对上传根），老数据是绝对路径。
     */
    public Path resolveStored(String storedPath) {
        if (storedPath == null || storedPath.isBlank()) {
            return null;
        }
        Path p = Paths.get(storedPath);
        return p.isAbsolute() ? p : root.resolve(p);
    }

    /** 校验扩展名并返回小写扩展名；不支持时抛 IllegalArgumentException。 */
    public String requireSupportedExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("缺少文件名");
        }
        int dot = fileName.lastIndexOf('.');
        String ext = dot > 0 ? fileName.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("不支持的文件类型：." + ext
                    + "，目前只支持 " + String.join(" / ", ALLOWED_EXTENSIONS));
        }
        return ext;
    }

    /**
     * 落盘（不分用户，等价于 {@code save(fileName, content, 0)}）。
     *
     * @deprecated 新代码请用 {@link #save(String, byte[], int)} 按归属人隔离目录，
     *             否则多人传同名同内容文件时会互相把对方的待入库文件移走。
     */
    @Deprecated
    public PendingFile save(String fileName, byte[] content) throws IOException {
        return save(fileName, content, 0);
    }

    /**
     * 落盘到该用户自己的待入库子目录；返回 fileId 等基本信息。
     *
     * <p><b>为什么要按 ownerId 分目录</b>：fileId 只由「文件名 + 内容」算出，不含归属人。
     * 以前所有用户共用一个 {@code .pending/} 目录，于是 A、B 传同名同内容文件时 fileId 相同，
     * B 入库调 {@link #promote} 会把 A 的那份文件移走，A 再入库就报"待入库文件不存在"。
     * 分开目录后两人各有一份，互不影响。
     *
     * @param ownerId 归属人，未登录传 0
     */
    public PendingFile save(String fileName, byte[] content, int ownerId) throws IOException {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("文件为空");
        }
        if (content.length > MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException("文件过大：" + (content.length / 1024) + " KB，上限 20MB");
        }
        String ext = requireSupportedExtension(fileName);
        String fileId = contentHash(fileName, content);
        Path dir = pendingDirOf(ownerId);
        Files.createDirectories(dir);
        Path target = dir.resolve(fileId + "-" + safeFileName(fileName));
        Files.write(target, content);
        log.info("待入库文件已保存: fileId={}, 文件={}, {} 字节, owner={}", fileId, fileName, content.length, ownerId);
        return new PendingFile(fileId, fileName, content.length, ext, target);
    }

    /** 某个归属人的待入库目录 */
    private Path pendingDirOf(int ownerId) {
        return pendingDir.resolve(String.valueOf(ownerId));
    }

    /**
     * 按 fileId 找到待入库文件（不分用户）。
     *
     * @deprecated 新代码请用 {@link #find(String, int)}。
     */
    @Deprecated
    public PendingFile find(String fileId) throws IOException {
        return find(fileId, 0);
    }

    /**
     * 按 fileId 找该归属人的待入库文件。
     *
     * <p>查找顺序：先 {@code .pending/<ownerId>/}，找不到再回退到 {@code .pending/} 根目录。
     * 回退是为了兼容本改造之前上传、还没入库的文件（它们还在根目录里）。
     *
     * @throws IOException 找不到时（消息里带 fileId，便于排查）
     */
    public PendingFile find(String fileId, int ownerId) throws IOException {
        if (fileId == null || fileId.isBlank()) {
            throw new IllegalArgumentException("缺少 fileId");
        }
        // fileId 是十六进制串，先做一次格式校验，避免拼出越界路径
        if (!fileId.matches("[0-9a-fA-F]{8,64}")) {
            throw new IllegalArgumentException("fileId 格式不正确");
        }
        Path hit = findIn(pendingDirOf(ownerId), fileId);
        if (hit == null) {
            // 向后兼容：改造前上传的文件在 .pending/ 根目录
            hit = findIn(pendingDir, fileId);
        }
        if (hit == null) {
            throw new IOException("待入库文件不存在或已被清理：fileId=" + fileId
                    + "，请重新上传");
        }
        String nameWithId = hit.getFileName().toString();
        String fileName = nameWithId.substring(nameWithId.indexOf('-') + 1);
        String ext = requireSupportedExtension(fileName);
        return new PendingFile(fileId, fileName, Files.size(hit), ext, hit);
    }

    /** 在指定目录里按 fileId 前缀找文件；目录不存在或没命中返回 null */
    private static Path findIn(Path dir, String fileId) throws IOException {
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.getFileName().toString().startsWith(fileId + "-"))
                    .findFirst()
                    .orElse(null);
        }
    }

    public byte[] read(PendingFile file) throws IOException {
        return Files.readAllBytes(file.path());
    }

    /**
     * 入库成功后把文件从 .pending 移到正式目录，命名 {@code <id>-<文件名>}。
     *
     * <p>用自增 id 做前缀（而不是内容哈希）有两个好处：id 全局唯一所以跨用户不会撞名；
     * id 永不变所以文件名稳定，重新入库不会在磁盘上留两份。
     *
     * @return <b>相对上传根目录的路径</b>（如 {@code 3-图书馆规则.md}）；
     *         移动失败返回空串表示"没有正式副本"，不影响入库结果
     */
    public String promote(PendingFile file, Long docId) {
        try {
            Files.createDirectories(root);
            Path target = root.resolve(docId + "-" + safeFileName(file.fileName()));
            Files.move(file.path(), target, StandardCopyOption.REPLACE_EXISTING);
            return root.relativize(target).toString();
        } catch (Exception e) {
            log.warn("原件从 .pending 移到正式目录失败（不影响入库）: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 入库失败或明确不要了，删掉待入库文件（不分用户）。
     *
     * @deprecated 新代码请用 {@link #discard(String, int)}。
     */
    @Deprecated
    public void discard(String fileId) {
        discard(fileId, 0);
    }

    /** 入库失败或明确不要了，删掉该归属人的待入库文件。幂等。 */
    public void discard(String fileId, int ownerId) {
        try {
            Files.deleteIfExists(find(fileId, ownerId).path());
        } catch (Exception e) {
            log.debug("清理待入库文件跳过: {}", e.getMessage());
        }
    }

    private static String contentHash(String fileName, byte[] content) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            String head = (fileName == null ? "" : fileName) + "|" + content.length + "|"
                    + HexFormat.of().formatHex(md5.digest(content));
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(head.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 16);
        } catch (Exception e) {
            // 理论上不会发生；退化为随机 id，保证功能可用
            return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
    }

    /**
     * 文件名安全化：去掉路径分隔符与特殊字符，并按<b>字节</b>从尾部截断。
     *
     * <p>为什么保留尾部：抓取来的文件名前缀高度重复（如「通知公告-2026-10-26-」），
     * 区分度都在后面；而且扩展名也在尾部，截尾才能保住 {@code .md}。
     */
    static String safeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "unnamed";
        }
        String s = fileName.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        return truncateTailToBytes(s, MAX_FILE_NAME_BYTES);
    }

    /** 从尾部按 UTF-8 字节数截断，避免切断多字节字符。 */
    static String truncateTailToBytes(String s, int maxBytes) {
        byte[] all = s.getBytes(StandardCharsets.UTF_8);
        if (all.length <= maxBytes) {
            return s;
        }
        int start = s.length();
        int bytes = 0;
        while (start > 0) {
            int cp = s.codePointBefore(start);
            int charCount = Character.charCount(cp);
            int byteCount = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + byteCount > maxBytes) {
                break;
            }
            bytes += byteCount;
            start -= charCount;
        }
        String cut = s.substring(start);
        // 截断后以 '_' 或 '-' 开头会显得奇怪，去掉前导分隔符
        return cut.replaceAll("^[_-]+", "");
    }
}