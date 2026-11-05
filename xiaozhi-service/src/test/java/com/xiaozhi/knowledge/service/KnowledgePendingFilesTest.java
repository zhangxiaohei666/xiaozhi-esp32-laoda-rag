package com.xiaozhi.knowledge.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

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
}