package com.xiaozhi.ai.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 钉住切块规则：**标题优先、600 兜底、800 硬切、80 重叠（仅硬切时）**。
 *
 * <p>为什么值得测：这套规则是整个 RAG 检索质量的地基。
 * 改一个常量（600 → 400）会让所有已入库的块失效，而这一层以前一个测试都没有。
 *
 * <p>三个数字的准确含义（最容易误解的地方）：
 * <ul>
 *   <li>{@code 600} = <b>目标</b>，不是硬上限。加下一行会超过它就切。</li>
 *   <li>{@code 800} = <b>单段硬上限</b>。普通块根本到不了这个分支。</li>
 *   <li>{@code 80}  = <b>重叠</b>，只在单段超 800 被硬切时才出现；
 *       普通块 {@code for} 循环只跑一次就 break，<b>零重叠</b>。</li>
 * </ul>
 */
class DocumentIngestServiceSplitTest {

    @Test
    @DisplayName("按 markdown 标题切块，每个块带完整标题路径前缀")
    void splitsByHeading() {
        String md = String.join("\n",
                "# 图书馆使用规则",
                "",
                "## 开放时间",
                "",
                "- 周一至周五：8:00 - 22:00",
                "- 周六：9:00 - 17:00",
                "",
                "## 借阅规则",
                "",
                "- 本科生一次最多借 10 册");

        List<DocumentIngestService.Chunk> chunks = DocumentIngestService.split(md);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).heading()).isEqualTo("图书馆使用规则 > 开放时间");
        assertThat(chunks.get(0).text()).startsWith("图书馆使用规则 > 开放时间");
        assertThat(chunks.get(0).text()).contains("周一至周五");
        assertThat(chunks.get(1).heading()).isEqualTo("图书馆使用规则 > 借阅规则");
        assertThat(chunks.get(1).text()).contains("本科生");
    }

    @Test
    @DisplayName("标题文字本身不进正文（只作为 heading 前缀拼一次）")
    void headingTextNotDuplicatedInBody() {
        String md = String.join("\n", "## 开放时间", "周一至周五 8:00-22:00");

        List<DocumentIngestService.Chunk> chunks = DocumentIngestService.split(md);

        assertThat(chunks).hasSize(1);
        String text = chunks.get(0).text();
        // "开放时间" 只应在开头的 heading 前缀里出现一次
        assertThat(text.split("开放时间", -1)).hasSize(2);
    }

    @Test
    @DisplayName("多级标题：同级替换、上级保留")
    void multiLevelHeadingStack() {
        String md = String.join("\n",
                "# 图书馆规则",
                "## 开放时间",
                "### 寒暑假",
                "寒暑假 9:00-16:00",
                "### 节假日",
                "节假日闭馆",
                "## 借阅规则",
                "本科生 10 册");

        List<DocumentIngestService.Chunk> chunks = DocumentIngestService.split(md);

        assertThat(chunks).extracting(DocumentIngestService.Chunk::heading)
                .containsExactly(
                        "图书馆规则 > 开放时间 > 寒暑假",
                        "图书馆规则 > 开放时间 > 节假日",
                        "图书馆规则 > 借阅规则");
    }

    @Test
    @DisplayName("超过 600 字才切，正常长段落不会被拆")
    void aggregatesUntilTarget() {
        // 20 行 × 30 字 ≈ 600 字，应该只切 1 刀
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append("这是一行大约三十个字的内容用来测试聚合行为编号").append(i).append("\n");
        }

        List<DocumentIngestService.Chunk> chunks = DocumentIngestService.split(sb.toString());

        // 没有标题、总量刚过 600 → 切 1 块（不是 20 块）
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text().length()).isGreaterThan(500);
    }

    @Test
    @DisplayName("长文档无标题时会切成多块，每块 ≤ 600 左右")
    void splitsLongDocumentWithoutHeadings() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("第").append(i).append("行：这是一段用于测试长文档切块的文字内容，大约三十个字。\n");
        }

        List<DocumentIngestService.Chunk> chunks = DocumentIngestService.split(sb.toString());

        assertThat(chunks.size()).isGreaterThan(1);
        // 除了最后一块，前面每块不应该远超 600（硬上限 800 才是真上限）
        for (DocumentIngestService.Chunk c : chunks) {
            assertThat(c.text().length()).isLessThanOrEqualTo(800);
        }
    }

    @Test
    @DisplayName("单段超 800 硬切，且相邻两片有重叠")
    void hardCutsOverlongParagraphWithOverlap() {
        // 一整段 2000 字、无换行 → 必须硬切
        String longPara = "字".repeat(2000);

        List<DocumentIngestService.Chunk> chunks = DocumentIngestService.split(longPara);

        assertThat(chunks.size()).isGreaterThan(1);
        for (DocumentIngestService.Chunk c : chunks) {
            assertThat(c.text().length()).isLessThanOrEqualTo(800);
        }
        // 硬切才有重叠：后一片的前 80 字应与前一片末尾相同
        if (chunks.size() >= 2) {
            String first = chunks.get(0).text();
            String second = chunks.get(1).text();
            String tailOfFirst = first.substring(Math.max(0, first.length() - 80));
            assertThat(second.substring(0, 80)).isEqualTo(tailOfFirst);
        }
    }

    @Test
    @DisplayName("空内容 / 只有空行 → 0 块")
    void returnsEmptyForBlankInput() {
        assertThat(DocumentIngestService.split("")).isEmpty();
        assertThat(DocumentIngestService.split("   \n\n   ")).isEmpty();
    }

    @Test
    @DisplayName("只有标题没有正文 → 0 块（标题不当正文用）")
    void headingOnlyProducesNoChunk() {
        assertThat(DocumentIngestService.split("# 只有标题")).isEmpty();
    }

    @Test
    @DisplayName("文末不足 600 的残留也会落成一块")
    void flushesTailContent() {
        String md = String.join("\n", "## 小节", "一句话");

        List<DocumentIngestService.Chunk> chunks = DocumentIngestService.split(md);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).contains("一句话");
    }

    // ============================================================ 头部解析

    @Test
    @DisplayName("parseHeader 解析三个字段，遇到 --- 停止")
    void parsesHeaderFields() {
        String md = String.join("\n",
                "# 标题",
                "",
                "- 来源栏目：通知公告",
                "- 发文单位：人事处",
                "- 发布日期：2026-10-28",
                "- 原文链接：https://www.nyist.edu.cn/x",
                "---",
                "- 来源栏目：这是正文里的，不该被解析");

        DocumentIngestService.HeaderInfo header = DocumentIngestService.parseHeader(md);

        assertThat(header.category()).isEqualTo("通知公告");
        assertThat(header.publishDate()).isEqualTo("2026-10-28");
        assertThat(header.url()).isEqualTo("https://www.nyist.edu.cn/x");
    }

    @Test
    @DisplayName("parseHeader 只接受 yyyy-MM-dd 格式的日期，其他忽略")
    void rejectsNonStandardDate() {
        DocumentIngestService.HeaderInfo header = DocumentIngestService.parseHeader(
                "- 来源栏目：图书馆\n- 发布日期：2026年10月28日");

        assertThat(header.category()).isEqualTo("图书馆");
        assertThat(header.publishDate()).isEmpty();
    }

    @Test
    @DisplayName("parseHeader 对无头部的正文返回全空")
    void headerIsEmptyWhenMissing() {
        DocumentIngestService.HeaderInfo header =
                DocumentIngestService.parseHeader("# 标题\n\n正文内容，没有元信息");

        assertThat(header.category()).isEmpty();
        assertThat(header.publishDate()).isEmpty();
        assertThat(header.url()).isEmpty();
    }

    @Test
    @DisplayName("parseHeader null 安全")
    void headerNullSafe() {
        DocumentIngestService.HeaderInfo header = DocumentIngestService.parseHeader(null);

        assertThat(header.category()).isEmpty();
        assertThat(header.publishDate()).isEmpty();
        assertThat(header.url()).isEmpty();
    }
}