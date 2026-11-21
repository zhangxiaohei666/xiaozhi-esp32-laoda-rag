package com.xiaozhi.ai.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 钉住标题路径的两级截断规则（2026-11-21 补，阈值 45/62 按实测语料标定）：
 * <ul>
 *   <li>单标题 ≤45 字，超长保留末尾 + "…" 标记（公文标题实义词常在后）；</li>
 *   <li>整条路径 ≤62 字（= 45 + 17，保证实测最长的 43 字标题挂树里不触发路径截断），
 *       超长从最外层开始丢（外层是泛目录词）。</li>
 * </ul>
 *
 * <p>实测基线：38 篇语料 56 个标题，最长单标题 43 字、最坏路径 43 字 → 当前语料全库零截断。
 * 两级缺一不可：只做单标题，深层叠加漏；只做路径，单点爆长漏。
 */
class DocumentIngestServiceHeadingCapTest {

    /** 实测语料里真实存在的 43 字长标题（应原样保留，不截） */
    private static final String REAL_LONG_TITLE =
            "关于南阳理工学院2025年7月1日至2026年6月30日本科班级学业导师考核结果的公示";

    @Test
    @DisplayName("实测最长的 43 字标题：零截断，原样保留（45 上限的标定依据）")
    void realLongestTitleUntouched() {
        assertThat(REAL_LONG_TITLE.length()).isEqualTo(43);   // 确认构造没走样

        String md = "# " + REAL_LONG_TITLE + "\n\n正文内容。";
        List<DocumentIngestService.Chunk> chunks = DocumentIngestService.split(md);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).heading()).isEqualTo(REAL_LONG_TITLE);   // 原样，无 …
        assertThat(chunks.get(0).text()).startsWith(REAL_LONG_TITLE);
    }

    @Test
    @DisplayName("43 字标题挂在两层短标题下（4+4）：路径 54 字仍不触发截断（62 上限的意义）")
    void longTitleUnderTreeUntouched() {
        // 4 + 3 + 4 + 3 + 43 = 57 ≤ 62 → 不触发路径截断
        String md = String.join("\n",
                "# 校务公开",
                "## 通知公告",
                "### " + REAL_LONG_TITLE,
                "",
                "正文内容。");

        List<DocumentIngestService.Chunk> chunks = DocumentIngestService.split(md);

        assertThat(chunks).hasSize(1);
        String heading = chunks.get(0).heading();
        assertThat(heading.length()).isLessThanOrEqualTo(62);
        assertThat(heading).startsWith("校务公开 > 通知公告 > ");    // 三层全保留
        assertThat(heading).doesNotContain("…");
    }

    @Test
    @DisplayName("单标题超 45（病态长）：保留末尾 + … 标记，总长 ≤45")
    void capsPathologicalHeading() {
        // 60 字病态标题（把整段话写成标题）
        String pathological = "关于开展2026年度图书馆古籍文献数字化保护与整理工作暨志愿者招募活动的实施方案讨论稿征求意见稿";
        assertThat(pathological.length()).isGreaterThan(45);

        String md = "# " + pathological + "\n\n正文内容。";
        List<DocumentIngestService.Chunk> chunks = DocumentIngestService.split(md);

        assertThat(chunks).hasSize(1);
        String heading = chunks.get(0).heading();
        assertThat(heading.length()).isLessThanOrEqualTo(45);
        assertThat(heading).startsWith("…");                       // 截头保尾
        assertThat(heading).endsWith("征求意见稿");                 // 类别词（尾部）保住
    }

    @Test
    @DisplayName("路径累积超 62（三层中长标题叠加）：从最外层丢，最内层保住")
    void capsAccumulatedPath() {
        // 8 + 3 + 26 + 3 + 16 = 56？不够。用更长的三层：20+3+26+3+16 = 68 > 62 → 触发
        String md = String.join("\n",
                "# 关于开展2026年度图书馆古籍文献保护工作实施方案的公告",   // 26 字
                "## 关于印发古籍文献数字化整理技术规范与操作指南的通知",     // 25 字
                "### 古籍修复室开放时间调整",                               // 13 字
                "",
                "正文内容。");

        // 路径 = 26 + 3 + 25 + 3 + 13 = 70 > 62 → 触发路径截断
        List<DocumentIngestService.Chunk> chunks = DocumentIngestService.split(md);

        assertThat(chunks).hasSize(1);
        String heading = chunks.get(0).heading();
        assertThat(heading.length()).isLessThanOrEqualTo(62);
        assertThat(heading).doesNotContain("关于开展2026年度");     // 最外层被丢
        assertThat(heading).contains("古籍修复室开放时间调整");      // 最内层保住
    }

    @Test
    @DisplayName("正常长度标题零截断：路径原样保留，不加 …")
    void normalHeadingsUntouched() {
        String md = String.join("\n",
                "# 图书馆规则",
                "## 开放时间",
                "",
                "周一至周五 8:00 - 22:00");

        List<DocumentIngestService.Chunk> chunks = DocumentIngestService.split(md);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).heading()).isEqualTo("图书馆规则 > 开放时间");
        assertThat(chunks.get(0).heading()).doesNotStartWith("…");
    }
}