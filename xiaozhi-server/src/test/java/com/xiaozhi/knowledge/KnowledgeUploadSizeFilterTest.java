package com.xiaozhi.knowledge;

import com.xiaozhi.ai.rag.DocumentFormats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 钉住「请求体还没收完就拒」这道闸门的边界。
 *
 * <p>为什么值得测：这个过滤器<b>只允许误放，不允许误杀</b>。
 * 误放还有 Controller 的 {@code getSize()} 与编排层的
 * {@link DocumentFormats#assertAcceptable} 兜底；误杀则是正常文件直接被 413，
 * 用户重试多少次都没用。所以每个"不该拦"的分支都要有断言。
 *
 * <p><b>测试手法注意</b>：{@code MockHttpServletRequest.getContentLengthLong()} 取的是
 * {@code setContent()} 那个数组的长度，<b>不看 Content-Length 头</b>。
 * 所以这里必须覆写该方法 —— 用 {@code addHeader} 伪造长度是无效的，
 * 会让"超限应该被拒"的用例悄悄变成"长度 0，当然放行"。
 */
class KnowledgeUploadSizeFilterTest {

    private static final String TARGET = "/api/knowledge/documents";

    private final KnowledgeUploadSizeFilter filter = new KnowledgeUploadSizeFilter();
    private final AtomicBoolean chained = new AtomicBoolean(false);

    /** 造一个"声明长度为 declaredBytes"的请求（-1 表示拿不到长度） */
    private static MockHttpServletRequest request(String method, String path, long declaredBytes) {
        return new MockHttpServletRequest(method, path) {
            @Override
            public long getContentLengthLong() {
                return declaredBytes;
            }
        };
    }

    private void run(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        chained.set(false);
        filter.doFilter(request, response, (req, res) -> chained.set(true));
    }

    @Test
    @DisplayName("正好在上限内 → 放行，不写响应")
    void passesNormalUpload() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        run(request("POST", TARGET, DocumentFormats.MAX_UPLOAD_BYTES), response);

        assertThat(chained).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("超过上限（含 1MB multipart 余量）→ 413 且不再往下解析")
    void rejectsOversizeUpload() throws Exception {
        long tooBig = DocumentFormats.MAX_UPLOAD_BYTES + 2L * 1024 * 1024;
        MockHttpServletResponse response = new MockHttpServletResponse();

        run(request("POST", TARGET, tooBig), response);

        // 关键：chain 没被调用 = body 没有被解析
        assertThat(chained).isFalse();
        assertThat(response.getStatus()).isEqualTo(413);
        String body = response.getContentAsString();
        // 文案要告诉用户上限是多少，否则他只能反复试
        assertThat(body).contains("\"code\":413").contains("20MB").contains("文件过大");
    }

    @Test
    @DisplayName("1MB 余量是留给 multipart 头的：20MB 文件 + 几 KB 头不该被误杀")
    void toleratesMultipartOverhead() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        run(request("POST", TARGET, DocumentFormats.MAX_UPLOAD_BYTES + 4096), response);

        assertThat(chained).isTrue();
    }

    @Test
    @DisplayName("拿不到 Content-Length（分块传输）→ 放行，交给后面两层判断")
    void passesWhenContentLengthUnknown() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        run(request("POST", TARGET, -1L), response);

        assertThat(chained).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("不拦其他方法/其他路径：DELETE、GET、其他上传端点都不受影响")
    void ignoresOtherRequests() throws Exception {
        long tooBig = DocumentFormats.MAX_UPLOAD_BYTES + 2L * 1024 * 1024;
        String[][] combos = {
                {"GET", TARGET},
                {"DELETE", TARGET + "/42"},
                {"POST", "/api/file/upload"},
                {"POST", "/api/music/music"}
        };

        for (String[] combo : combos) {
            MockHttpServletResponse response = new MockHttpServletResponse();

            run(request(combo[0], combo[1], tooBig), response);

            assertThat(chained).as(combo[0] + " " + combo[1]).isTrue();
            assertThat(response.getStatus()).as(combo[0] + " " + combo[1]).isEqualTo(200);
        }
    }
}