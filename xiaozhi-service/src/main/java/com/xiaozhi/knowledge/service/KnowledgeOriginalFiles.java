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
import java.nio.file.StandardOpenOption;

/**
 * 知识库<b>原文</b>的存储：只管"字节 ↔ 磁盘文件"，不认识上传流程、不认识账本、不认识向量库。
 *
 * <h3>为什么单独一个类（替代原来的 {@code KnowledgePendingFiles}）</h3>
 * 原来那个类把三件事混在一起：暂存目录（两步式上传的中间态）、原文落盘、文件名清洗。
 * 两步式合并之后暂存层没有存在意义了，剩下的只有"把原文存好、删好、找得到"，
 * 所以收成一个职责单一的存储类。
 *
 * <h3>为什么不复用项目的 {@code StorageService}</h3>
 * 那里已经有一套 upload/download/remove 抽象，看起来更省事，但 {@code LocalStorageService}
 * 的根目录是全局 {@code xiaozhi.storage.local.base-dir}（音频、头像等共用），
 * 而知识库原文档目录是独立的 {@code xiaozhi.rag.knowledge.upload-dir}，
 * 且库里 38 条 {@code filePath} 已经按后者写成相对路径。
 * 强行统一会把已入库的原文全部搬走。所以这里单独持有知识库自己的根目录，
 * 将来若要换成 OSS，把这个类的实现替换掉即可（调用方只依赖 save/delete/resolve）。
 *
 * <h3>目录布局</h3>
 * {@code <upload-dir>/<docId>-<安全文件名>}，与历史数据完全一致，因此**已入库的
 * 38 条 filePath 不需要迁移**。文件名规则见 {@link #safeFileName}。
 */
@Slf4j
@Component
public class KnowledgeOriginalFiles {

    /**
     * 单个文件名允许的最大字节数。
     *
     * <p>Linux 上限 255 字节；这里留到 180，因为磁盘上的名字还要加 {@code <docId>-} 前缀，
     * 且 UTF-8 中文一个字 3 字节，余量太小容易踩线。
     */
    private static final int MAX_FILE_NAME_BYTES = 180;

    /** 知识库原文档根目录（绝对路径）。 */
    private final Path root;

    public KnowledgeOriginalFiles(RuntimePathConfig runtimePath, RagProperties ragProperties) {
        // 目录解析统一交给 RuntimePathConfig：
        //   留空     → ${xiaozhi.runtime.data-dir}/knowledge（推荐，一处配置管全部落盘文件）
        //   相对路径 → 相对 data-dir
        //   绝对路径 → 原样使用
        //
        // ⚠️ 不要再写死 Windows 盘符：反斜杠在 Linux 上是合法文件名字符，
        //    会把整条路径当成单个目录名创建出来，数据落到谁也找不到的地方。
        String configured = ragProperties.getKnowledge().getUploadDir();
        this.root = (configured == null || configured.isBlank())
                ? runtimePath.resolveStorageKey("knowledge")
                : runtimePath.resolveStorageKey(configured);
        log.info("知识库原文目录: {}（xiaozhi.rag.knowledge.upload-dir={}）",
                root, (configured == null || configured.isBlank()) ? "留空 → data-dir/knowledge" : configured);
    }

    /** 原文档根目录（只读用途：排查、重建索引脚本）。 */
    public Path rootDir() {
        return root;
    }

    /**
     * 把原文写到 {@code <root>/<docId>-<安全文件名>}，返回<b>相对 root 的路径</b>
     * （形如 {@code 3-图书馆借阅规则.md}），直接写进 {@code sys_knowledge_doc.filePath}。
     *
     * <p>用 {@code REPLACE_EXISTING} 的语义：同一 docId 重复入库（改内容重传）时覆盖旧文件，
     * 不会在磁盘上留两份。
     *
     * @param docId 账本主键，落盘时必须已经存在
     * @throws IOException 写失败。调用方应视为"该文档没有原文副本"，
     *                     后续无法重建索引 —— 是否让整次入库失败由调用方决定
     */
    public String save(String fileName, byte[] content, Long docId) throws IOException {
        if (docId == null) {
            throw new IllegalArgumentException("落盘需要 docId");
        }
        Files.createDirectories(root);
        Path target = root.resolve(docId + "-" + safeFileName(fileName));
        Files.write(target, content,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        // 统一用正斜杠：Windows 上 relativize 不含分隔符时本来就是纯文件名，
        // 但一旦路径有多层就会带反斜杠，写进库后再跨平台读会出问题
        return root.relativize(target).toString().replace('\\', '/');
    }

    /**
     * 删掉一份原文。幂等：文件不存在、路径为空、路径非法都只记一条日志，不抛。
     *
     * <p>为什么不抛：删除文档的主流程是"向量 → 原文 → 账本"，原文删不掉不该阻断
     * 后面删账本（那才是用户看得见的"删不掉了"）。残留文件的危害远小于删不掉的记录。
     */
    public void delete(String storedPath) {
        Path path = resolve(storedPath);
        if (path == null) {
            return;
        }
        try {
            if (Files.deleteIfExists(path)) {
                log.info("已删除知识库原文: {}", storedPath);
            }
        } catch (IOException e) {
            log.warn("删除知识库原文失败（不影响账本删除）: {} ({})", storedPath, e.getMessage());
        }
    }

    /**
     * 把账本里的 {@code filePath} 解析成绝对路径。
     *
     * <p>兼容两种历史存法：新数据是相对 root 的路径，老数据可能是绝对路径。
     * 空/空白返回 {@code null}（表示这篇没有原文副本）。
     */
    public Path resolve(String storedPath) {
        if (storedPath == null || storedPath.isBlank()) {
            return null;
        }
        Path path = Paths.get(storedPath);
        return path.isAbsolute() ? path : root.resolve(path);
    }

    /**
     * 文件名清洗：去掉路径分隔符与 Windows 非法字符，并按<b>字节</b>从尾部截断。
     *
     * <p>为什么保留尾部：抓取来的文件名前缀高度重复（如「通知公告-2026-10-26-」），
     * 区分度都在后面；而且扩展名也在尾部，截尾才能保住 {@code .md}。
     */
    static String safeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "unnamed";
        }
        String cleaned = fileName.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        return truncateTailToBytes(cleaned, MAX_FILE_NAME_BYTES);
    }

    /** 从尾部按 UTF-8 字节数截断，避免切断多字节字符。 */
    static String truncateTailToBytes(String value, int maxBytes) {
        byte[] all = value.getBytes(StandardCharsets.UTF_8);
        if (all.length <= maxBytes) {
            return value;
        }
        int start = value.length();
        int bytes = 0;
        while (start > 0) {
            int codePoint = value.codePointBefore(start);
            int charCount = Character.charCount(codePoint);
            int byteCount = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + byteCount > maxBytes) {
                break;
            }
            bytes += byteCount;
            start -= charCount;
        }
        // 截断后以 '_' 或 '-' 开头会显得奇怪，去掉前导分隔符
        return value.substring(start).replaceAll("^[_-]+", "");
    }
}