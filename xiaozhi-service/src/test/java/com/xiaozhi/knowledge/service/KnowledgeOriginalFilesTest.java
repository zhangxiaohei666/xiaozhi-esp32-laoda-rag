package com.xiaozhi.knowledge.service;

import com.xiaozhi.common.config.RagProperties;
import com.xiaozhi.common.config.RuntimePathConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 钉住原文存储的两个边界：<b>文件名清洗</b> 与 <b>落盘/删除的路径语义</b>。
 *
 * <p>为什么值得测：
 * <ul>
 *   <li><b>文件名清洗</b>：用户上传的文件名是不可信输入，可能含路径穿越（{@code ../}）、
 *       非法字符、或超长到 OS 报错。清洗错一处就是 500 或写到目录外。</li>
 *   <li><b>落盘命名</b>：磁盘上是 {@code <docId>-<安全文件名>}，而 {@code filePath}
 *       是被 MySQL 长期保存的相对路径 —— 两者格式一旦不一致，已入库的 38 篇就找不到原文。</li>
 *   <li><b>删除幂等</b>：删文档的主流程是"向量 → 原文 → 账本"，原文删两次不该抛。</li>
 * </ul>
 */
class KnowledgeOriginalFilesTest {

    private static KnowledgeOriginalFiles storeAt(Path dataDir) {
        RuntimePathConfig runtime = new RuntimePathConfig();
        runtime.setDataDir(dataDir.toString());
        RagProperties props = new RagProperties();   // uploadDir 留空 → dataDir/knowledge
        return new KnowledgeOriginalFiles(runtime, props);
    }

    // ============================================================ 文件名清洗

    @Test
    @DisplayName("路径分隔符替换掉，防止写到目录外")
    void stripsPathSeparators() {
        assertThat(KnowledgeOriginalFiles.safeFileName("../../etc/passwd"))
                .doesNotContain("/").doesNotContain("\\");
        assertThat(KnowledgeOriginalFiles.safeFileName("a\\b\\c.txt")).doesNotContain("\\");
    }

    @Test
    @DisplayName("Windows 非法字符替换掉，扩展名保留")
    void stripsIllegalCharacters() {
        String out = KnowledgeOriginalFiles.safeFileName("a:b*c?d\"e<f>g|h.txt");

        assertThat(out).doesNotContain(":").doesNotContain("*").doesNotContain("?")
                .doesNotContain("\"").doesNotContain("<").doesNotContain(">")
                .doesNotContain("|");
        assertThat(out).endsWith(".txt");
    }

    @Test
    @DisplayName("保留中文文件名")
    void keepsChinese() {
        assertThat(KnowledgeOriginalFiles.safeFileName("图书馆使用规则.md"))
                .isEqualTo("图书馆使用规则.md");
    }

    @Test
    @DisplayName("超长文件名按字节从尾部截断，不超 255 字节限制")
    void truncatesLongName() {
        String longName = "很长的文件名".repeat(100) + ".md";

        String out = KnowledgeOriginalFiles.safeFileName(longName);

        assertThat(out.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(255);
        assertThat(out).endsWith(".md");
    }

    @Test
    @DisplayName("空文件名返回兜底值，不返回 null")
    void handlesBlankName() {
        assertThat(KnowledgeOriginalFiles.safeFileName(null)).isNotEmpty();
        assertThat(KnowledgeOriginalFiles.safeFileName("")).isNotEmpty();
        assertThat(KnowledgeOriginalFiles.safeFileName("   ")).isNotEmpty();
    }

    @Test
    @DisplayName("纯点号文件名拼上 docId 前缀后不会穿越目录")
    void dotNamesCannotEscapeAfterPrefixing() {
        // safeFileName("..") 本身会返回 ".."，但调用方一律是 "<docId>-<safeName>"，
        // 所以真实路径是 "42-.." 而不是 ".." —— 不会穿越。这里钉住的正是「必须带前缀」。
        String target = 42L + "-" + KnowledgeOriginalFiles.safeFileName("..");

        assertThat(target).isNotEqualTo("..");
        assertThat(target).startsWith("42-");
        assertThat(target).doesNotContain("/").doesNotContain("\\");
    }

    // ============================================================ 落盘 / 读取 / 删除

    @Test
    @DisplayName("落盘路径是 <docId>-<安全文件名>，内容一致，返回相对路径")
    void savesUnderDocIdPrefix(@TempDir Path dataDir) throws Exception {
        KnowledgeOriginalFiles store = storeAt(dataDir);
        byte[] content = "图书馆借阅规则".getBytes(StandardCharsets.UTF_8);

        String storedPath = store.save("图书馆使用规则.md", content, 42L);

        // filePath 是要进 MySQL 的相对路径：正斜杠 + <docId>- 前缀，格式必须稳定
        assertThat(storedPath).isEqualTo("42-图书馆使用规则.md");
        Path onDisk = store.resolve(storedPath);
        assertThat(onDisk).exists();
        assertThat(Files.readAllBytes(onDisk)).isEqualTo(content);
        // 落在 <data-dir>/knowledge/ 下
        assertThat(onDisk.getParent().getFileName().toString()).isEqualTo("knowledge");
    }

    @Test
    @DisplayName("同一 docId 重复落盘会覆盖，不产生第二份（重传改内容时靠这条）")
    void saveOverwritesForSameDocId(@TempDir Path dataDir) throws Exception {
        KnowledgeOriginalFiles store = storeAt(dataDir);
        String first = store.save("a.md", "第一版".getBytes(StandardCharsets.UTF_8), 7L);
        String second = store.save("a.md", "第二版内容更长".getBytes(StandardCharsets.UTF_8), 7L);

        assertThat(second).isEqualTo(first);
        assertThat(Files.readString(store.resolve(first), StandardCharsets.UTF_8)).isEqualTo("第二版内容更长");
    }

    @Test
    @DisplayName("删除幂等：删两次、删不存在的路径、传 null 都不抛")
    void deleteIsIdempotent(@TempDir Path dataDir) throws Exception {
        KnowledgeOriginalFiles store = storeAt(dataDir);
        String storedPath = store.save("a.md", "x".getBytes(StandardCharsets.UTF_8), 1L);

        store.delete(storedPath);
        store.delete(storedPath);          // 第二次：文件已不在
        store.delete("never-existed.md");
        store.delete(null);
        store.delete("   ");

        assertThat(store.resolve(storedPath)).doesNotExist();
    }

    @Test
    @DisplayName("resolve 兼容老数据的绝对路径；空值返回 null")
    void resolveHandlesAbsoluteAndBlank(@TempDir Path dataDir) throws Exception {
        KnowledgeOriginalFiles store = storeAt(dataDir);

        assertThat(store.resolve("")).isNull();
        assertThat(store.resolve(null)).isNull();

        Path absolute = dataDir.resolve("legacy.md");
        assertThat(store.resolve(absolute.toString())).isEqualTo(absolute);
    }
}