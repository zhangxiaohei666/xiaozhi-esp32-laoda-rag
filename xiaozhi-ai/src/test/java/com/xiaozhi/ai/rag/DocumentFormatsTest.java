package com.xiaozhi.ai.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 钉住格式白名单这一层的契约。
 *
 * <p>为什么值得测：白名单是"能不能入库"的唯一闸门，而且它是<b>给人看的错误提示来源</b>。
 * 放错了有两种坏法：<b>放太宽</b> → 用户传 PDF 进来，Tika 抽不出中文，
 * 结果是"入库成功但 0 块"；<b>放太窄</b> → 明明能解析的格式被拒。
 *
 * <p>更重要的是这层以后要扩（PDF / Word / 图片）。加格式时必须同步改
 * {@link DocumentFormats#VERIFIED} 的注释（写明验证日期与样本），
 * 这份测试会跟着自动覆盖新格式的"接受"路径，不需要再改测试。
 */
class DocumentFormatsTest {

    @Test
    @DisplayName("已验证的三种格式都能过")
    void acceptsVerifiedFormats() {
        assertThat(DocumentFormats.isSupported("a.txt")).isTrue();
        assertThat(DocumentFormats.isSupported("a.md")).isTrue();
        assertThat(DocumentFormats.isSupported("a.markdown")).isTrue();
        assertThatCode(() -> DocumentFormats.assertAcceptable("a.md", 10)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("扩展名大小写不敏感")
    void extensionIsCaseInsensitive() {
        assertThat(DocumentFormats.extensionOf("A.MD")).isEqualTo("md");
        assertThat(DocumentFormats.isSupported("A.TXT")).isTrue();
    }

    @Test
    @DisplayName("PDF / Word 目前明确拒绝（中文提取未验证，不能放进来）")
    void rejectsUnverifiedFormats() {
        assertThat(DocumentFormats.isSupported("a.pdf")).isFalse();
        assertThat(DocumentFormats.isSupported("a.docx")).isFalse();
        assertThatThrownBy(() -> DocumentFormats.assertAcceptable("a.pdf", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的文件类型")
                // 提示里要列出现在支持什么，否则用户不知道该转成什么格式
                .hasMessageContaining("txt");
    }

    @Test
    @DisplayName("没有扩展名 / 以点结尾 → 空扩展名，按不支持处理")
    void handlesMissingExtension() {
        assertThat(DocumentFormats.extensionOf("README")).isEmpty();
        assertThat(DocumentFormats.extensionOf("a.")).isEmpty();
        assertThat(DocumentFormats.extensionOf(null)).isEmpty();
        assertThatThrownBy(() -> DocumentFormats.assertAcceptable("README", 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("空文件名先报（比格式错更容易被用户改对）")
    void rejectsBlankFileName() {
        assertThatThrownBy(() -> DocumentFormats.assertAcceptable(null, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件名");
        assertThatThrownBy(() -> DocumentFormats.assertAcceptable("  ", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件名");
    }

    @Test
    @DisplayName("空内容与超过 20MB 都拒绝")
    void rejectsEmptyAndOversize() {
        assertThatThrownBy(() -> DocumentFormats.assertAcceptable("a.md", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件为空");

        assertThatThrownBy(() -> DocumentFormats.assertAcceptable("a.md", DocumentFormats.MAX_UPLOAD_BYTES + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件过大");

        // 边界值本身要放行
        assertThatCode(() -> DocumentFormats.assertAcceptable("a.md", DocumentFormats.MAX_UPLOAD_BYTES))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("上限就是 20MB，与前端提示一致")
    void uploadLimitIs20Mb() {
        assertThat(DocumentFormats.MAX_UPLOAD_BYTES).isEqualTo(20L * 1024 * 1024);
    }
}