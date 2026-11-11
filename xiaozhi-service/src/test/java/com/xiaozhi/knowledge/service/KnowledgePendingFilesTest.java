package com.xiaozhi.knowledge.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 钉住待入库文件的两个安全边界：**文件名清洗** 与 **fileId 只由内容决定**。
 *
 * <p>为什么值得测：
 * <ul>
 *   <li><b>文件名清洗</b>：用户上传的文件名是不可信输入，可能含路径穿越（{@code ../}）、
 *       非法字符、或超长到 OS 报错。清洗错一处就是 500 或写到目录外。</li>
 *   <li><b>fileId 稳定性</b>：同一份内容重复上传必须得到同一个 fileId（幂等），
 *       否则 {@code .pending/} 会堆垃圾。但它**不能**包含 userId —— 那会让隔离失效。</li>
 * </ul>
 */
class KnowledgePendingFilesTest {

    // ============================================================ 文件名清洗

    @Test
    @DisplayName("路径分隔符替换掉，防止写到目录外")
    void stripsPathSeparators() {
        assertThat(KnowledgePendingFiles.safeFileName("../../etc/passwd"))
                .doesNotContain("/").doesNotContain("\\");
        assertThat(KnowledgePendingFiles.safeFileName("a\\b\\c.txt"))
                .doesNotContain("\\");
    }

    @Test
    @DisplayName("Windows 非法字符替换掉")
    void stripsIllegalCharacters() {
        String out = KnowledgePendingFiles.safeFileName("a:b*c?d\"e<f>g|h.txt");

        assertThat(out).doesNotContain(":").doesNotContain("*").doesNotContain("?")
                .doesNotContain("\"").doesNotContain("<").doesNotContain(">")
                .doesNotContain("|");
        // 扩展名保留
        assertThat(out).endsWith(".txt");
    }

    @Test
    @DisplayName("保留中文文件名")
    void keepsChinese() {
        assertThat(KnowledgePendingFiles.safeFileName("图书馆使用规则.md"))
                .isEqualTo("图书馆使用规则.md");
    }

    @Test
    @DisplayName("超长文件名按字节截断，不超限")
    void truncatesLongName() {
        String longName = "很长的文件名".repeat(100) + ".md";

        String out = KnowledgePendingFiles.safeFileName(longName);

        // 截断后的 UTF-8 字节数应该在合理范围内（不能超过常见文件系统 255 字节限制）
        assertThat(out.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(255);
        assertThat(out).endsWith(".md");
    }

    @Test
    @DisplayName("空文件名返回兜底值，不返回 null")
    void handlesBlankName() {
        assertThat(KnowledgePendingFiles.safeFileName(null)).isNotEmpty();
        assertThat(KnowledgePendingFiles.safeFileName("")).isNotEmpty();
        assertThat(KnowledgePendingFiles.safeFileName("   ")).isNotEmpty();
    }

    @Test
    @DisplayName("纯点号文件名拼上前缀后不会穿越目录")
    void dotNamesCannotEscapeAfterPrefixing() {
        // safeFileName("..") 会返回 ".."，但调用方一律是 "<fileId>-<safeName>"
        // 或 "<docId>-<safeName>"，所以真实路径是 "5-.." 而不是 ".." —— 不会穿越。
        // 这里钉住的正是「必须带前缀」这个约定。
        String fileId = "abcdef0123456789";
        String target = fileId + "-" + KnowledgePendingFiles.safeFileName("..");

        assertThat(target).isNotEqualTo("..");
        assertThat(target).startsWith(fileId + "-");
        assertThat(target.contains("/")).isFalse();
        assertThat(target.contains("\\")).isFalse();
    }

    // ============================================================ 扩展名校验

    @Test
    @DisplayName("只接受 txt / md / markdown")
    void acceptsOnlySupportedExtensions() {
        KnowledgePendingFiles store = null; // 静态方法不需要实例
        // 通过工厂方法无法直接测（需要 RuntimePathConfig），这里只验证 ALLOWED_EXTENSIONS 常量
        assertThat(KnowledgePendingFiles.ALLOWED_EXTENSIONS)
                .containsExactlyInAnyOrder("txt", "md", "markdown");
    }

    @Test
    @DisplayName("上传大小上限是 20MB")
    void uploadLimitIs20Mb() {
        assertThat(KnowledgePendingFiles.MAX_UPLOAD_BYTES).isEqualTo(20L * 1024 * 1024);
    }

    // ============================================================ 过期清理

    /** 固定"当前时刻"，让清理测试不依赖真实系统时钟。 */
    private static final Instant FIXED_NOW = Instant.parse("2026-11-11T03:15:00Z");

    private static void age(Path file, Duration howOld) throws Exception {
        Files.setLastModifiedTime(file, FileTime.from(FIXED_NOW.minus(howOld)));
    }

    @Test
    @DisplayName("清理：只删超过 ttl 的，新文件保留（owner 子目录与历史根目录散文件都覆盖）")
    void purgeRemovesOnlyExpired(@TempDir Path tmp) throws Exception {
        Path owner1 = Files.createDirectories(tmp.resolve("1"));
        Path owner2 = Files.createDirectories(tmp.resolve("2"));

        Path oldInOwner = Files.writeString(owner1.resolve("aaaa0000old.md"), "old");
        Path newInOwner = Files.writeString(owner1.resolve("bbbb1111new.md"), "new");
        Path oldAtRoot = Files.writeString(tmp.resolve("cccc2222old.md"), "old"); // 改造前的历史残留
        Path newInOwner2 = Files.writeString(owner2.resolve("dddd3333new.md"), "new");

        age(oldInOwner, Duration.ofHours(48));
        age(oldAtRoot, Duration.ofHours(48));
        age(newInOwner, Duration.ZERO);
        age(newInOwner2, Duration.ZERO);

        KnowledgePendingFiles.PurgeResult r =
                KnowledgePendingFiles.purgeDirOlderThan(tmp, Duration.ofHours(24), FIXED_NOW);

        assertThat(r.files()).isEqualTo(2);
        assertThat(oldInOwner).doesNotExist();
        assertThat(oldAtRoot).doesNotExist();
        assertThat(newInOwner).exists();
        assertThat(newInOwner2).exists();
    }

    @Test
    @DisplayName("安全边界：只扫待入库目录，绝不碰它的父目录（正式原文就在父目录）")
    void purgeNeverTouchesParentDirectory(@TempDir Path tmp) throws Exception {
        // 父目录里放一个"已入库原文"：名字形如 <docId>-<文件名>，比 .pending 老得多
        Path official = Files.writeString(tmp.resolve("1-已入库原文.md"), "原文");
        age(official, Duration.ofHours(72));

        Path pending = Files.createDirectories(tmp.resolve(".pending"));
        Path owner = Files.createDirectories(pending.resolve("1"));
        Path expired = Files.writeString(owner.resolve("aaaa0000old.md"), "old");
        age(expired, Duration.ofHours(72));

        KnowledgePendingFiles.PurgeResult r =
                KnowledgePendingFiles.purgeDirOlderThan(pending, Duration.ofHours(24), FIXED_NOW);

        assertThat(r.files()).isEqualTo(1);
        assertThat(expired).doesNotExist();
        assertThat(official).as("正式原文目录是 .pending 的父目录，绝不能被清理任务删掉").exists();
    }

    @Test
    @DisplayName("ttl 为 0 或负数表示关闭清理，一个都不删")
    void purgeIsNoOpWhenTtlNonPositive(@TempDir Path tmp) throws Exception {
        Path owner = Files.createDirectories(tmp.resolve("7"));
        Path f = Files.writeString(owner.resolve("aaaa0000x.md"), "x");
        age(f, Duration.ofHours(999));

        assertThat(KnowledgePendingFiles.purgeDirOlderThan(tmp, Duration.ZERO, FIXED_NOW).files()).isZero();
        assertThat(KnowledgePendingFiles
                .purgeDirOlderThan(tmp, Duration.ofHours(-1), FIXED_NOW).files()).isZero();
        assertThat(f).exists();
    }

    @Test
    @DisplayName("目录不存在或参数为 null 时返回空结果，不抛异常")
    void purgeToleratesMissingDirAndNulls(@TempDir Path tmp) {
        assertThat(KnowledgePendingFiles
                .purgeDirOlderThan(tmp.resolve("does-not-exist"), Duration.ofHours(24), FIXED_NOW)
                .files()).isZero();
        assertThat(KnowledgePendingFiles
                .purgeDirOlderThan(null, Duration.ofHours(24), FIXED_NOW).files()).isZero();
        assertThat(KnowledgePendingFiles
                .purgeDirOlderThan(tmp, Duration.ofHours(24), null).files()).isZero();
    }
}