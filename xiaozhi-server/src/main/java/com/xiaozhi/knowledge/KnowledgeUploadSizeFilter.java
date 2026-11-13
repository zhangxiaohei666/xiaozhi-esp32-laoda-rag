package com.xiaozhi.knowledge;

import com.xiaozhi.ai.rag.DocumentFormats;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 知识库上传的「请求体还没收完就拒」闸门。
 *
 * <h3>为什么需要它（不只是一次重复校验）</h3>
 * 业务上限在 {@link DocumentFormats#MAX_UPLOAD_BYTES}（20MB），但那个校验发生在
 * <b>Spring 已经把整个 multipart 请求体收完并解析完</b>之后 —— 顺序是：
 * <pre>
 * Tomcat 收完 body（受 spring.servlet.multipart.max-file-size 约束，原本 100MB）
 *   → MultipartResolver 把各部分落到临时文件
 *   → Controller 拿到 MultipartFile，才轮到 getSize()/getBytes()
 * </pre>
 * 所以一个 30MB 的文件会先走完 30MB 的传输、落一次临时文件，然后才被告知「只能 20MB」。
 * 对本地调试无所谓，对走公网/手机热点传文件的场景就是几十秒白等。
 *
 * <p>本过滤器在 <b>Servlet 之前</b>只看请求头 {@code Content-Length}，超限直接回
 * 413，请求体不再往下解析 —— 这是唯一能在"收之前"判断的时机。
 *
 * <h3>为什么只拦这一个端点</h3>
 * 容器上限是全局的（{@code spring.servlet.multipart.max-file-size}），而各端点的业务上限
 * 并不一样：知识库 20MB（{@link DocumentFormats}），文件上传与音乐上传 50MB
 * （{@code StorageService.DEFAULT_MAX_SIZE}）。给知识库单独收紧，不能靠改全局配置 ——
 * 那会把另外两个端点一起卡到 20MB。
 *
 * <h3>与另外两层校验的分工</h3>
 * <ol>
 *   <li><b>这一层</b>：只看 Content-Length，拦住"一眼就知道太大"的请求，不解析 body</li>
 *   <li><b>Controller</b>：{@code getSize()} 再判一次 —— 分块传输没有 Content-Length 时，
 *       这层才是第一道（此时 body 已收完，但好在不复制进堆）</li>
 *   <li><b>编排层</b>：{@code DocumentFormats.assertAcceptable(fileName, content.length)}
 *       是最终权威，前两层漏了它也能兜住</li>
 * </ol>
 * 三层是有意重复的：前两层是性能与体验，第三层才是业务真相。
 */
@Slf4j
@Component
public class KnowledgeUploadSizeFilter extends OncePerRequestFilter {

    /** 只认这一个端点；其余上传端点各有自己的业务上限。 */
    private static final String TARGET_PATH = "/api/knowledge/documents";

    /**
     * Content-Length 是整个 multipart 请求体的长度（分隔符、字段名、Content-Disposition 头都算在内），
     * 比文件本身大几十到几百字节。留 1MB 余量，避免"正好 20MB 的文件"在边界上被误拒。
     */
    private static final long MULTIPART_OVERHEAD_BYTES = 1024 * 1024;

    private static final long MAX_REQUEST_BYTES =
            DocumentFormats.MAX_UPLOAD_BYTES + MULTIPART_OVERHEAD_BYTES;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (isTarget(request)) {
            long declared = request.getContentLengthLong();
            // -1 = 客户端没给 Content-Length（分块传输）。看不出来就放行，
            // 交给 Controller 的 getSize() 与编排层兜底 —— 宁可多收一次，也不误杀正常请求。
            if (declared > MAX_REQUEST_BYTES) {
                log.warn("知识库上传在接收阶段被拒: Content-Length={} 字节 > 允许 {} 字节, uri={}",
                        declared, MAX_REQUEST_BYTES, request.getRequestURI());
                writeTooLarge(response, declared);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static boolean isTarget(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod())
                && TARGET_PATH.equals(request.getRequestURI());
    }

    /**
     * 手写 JSON 而不是返回 {@code ApiResponse} 对象：这里还在 Servlet 链上，
     * 拿不到 Spring 的 HttpMessageConverter，也就没法靠 {@code @RestControllerAdvice}。
     * 字段名与 {@code ApiResponse} 对齐（code / message / data），前端拦截器才能照常解析。
     */
    private static void writeTooLarge(HttpServletResponse response, long declaredBytes) throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        long declaredMb = Math.max(1, declaredBytes / 1024 / 1024);
        long limitMb = DocumentFormats.MAX_UPLOAD_BYTES / 1024 / 1024;
        String message = "文件过大：约 " + declaredMb + "MB，上限 " + limitMb
                + "MB。请求在接收阶段就被拒绝，没有落盘也没有入库";
        response.getWriter().write("{\"code\":413,\"message\":\"" + message + "\",\"data\":null}");
    }
}