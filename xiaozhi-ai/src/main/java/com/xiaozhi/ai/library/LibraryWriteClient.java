package com.xiaozhi.ai.library;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 图书馆写接口客户端：预约（confirm）/ 取消（cancel）/ 临时离开（leave）/ 签退（checkout）。
 *
 * <p><b>与只读客户端（{@link LibraryApiClient}）分开的核心纪律：写操作只发一次、绝不重试。</b>
 * 只读查询重试无害（大不了拿到的数字旧一点）；写操作重试有真实的业务风险——
 * 第一次请求可能已经在学校系统里生效了，只是响应超时没回来，重试就变成重复下单/重复取消。
 * 所以写操作遇网络超时返回"结果不确定"，让用户自己去查记录，绝不自动补一发。
 *
 * <p>成功判定：HTTP 200 且 {@code code == 0}（实测预约/取消都返回这个）。
 * 其余情况分两类：
 * <ul>
 *   <li>明确失败（HTTP 非 200、code!=0）：把学校返回的 message 转成人话</li>
 *   <li>结果不确定（连接失败、读超时）：提示用户"先查一下我的预约，别重复提交"</li>
 * </ul>
 */
@Slf4j
@Component
public class LibraryWriteClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 写操作类型 → 接口路径 */
    public enum Op {
        CONFIRM("/v4/space/confirm"),
        CANCEL("/v4/space/cancel"),
        LEAVE("/v4/space/leave"),
        CHECKOUT("/v4/space/checkout");

        private final String path;

        Op(String path) {
            this.path = path;
        }
    }

    /**
     * 一次写操作的结果。
     *
     * @param success   确定成功（code==0）
     * @param message   给用户看的中文人话
     * @param uncertain 结果不确定（网络超时），提示用户查记录而不是重试
     */
    public record WriteResult(boolean success, boolean uncertain, String message) {

        public static WriteResult ok(String message) {
            return new WriteResult(true, false, message);
        }

        public static WriteResult failed(String message) {
            return new WriteResult(false, false, message);
        }

        public static WriteResult uncertain(String message) {
            return new WriteResult(false, true, message);
        }
    }

    private final LibraryProperties props;
    private final LibraryTokenProvider tokenProvider;
    private final HttpClient http;

    public LibraryWriteClient(LibraryProperties props, LibraryTokenProvider tokenProvider) {
        this.props = props;
        this.tokenProvider = tokenProvider;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    /**
     * 执行一次写操作。只发一次，任何失败都不重试。
     *
     * @param op       操作类型
     * @param bodyJson 请求体（confirm 是 {@code {"aesjson":"..."}}；其余是 {@code {"id":"记录ID"}}）
     */
    public WriteResult execute(Op op, String bodyJson) {
        String token = tokenProvider.token();
        URI uri = URI.create(trimSlash(props.getBaseUrl()) + op.path);

        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(props.getTimeoutMs()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-Requested-With", "XMLHttpRequest")
                // 注意：bearer 后面没有空格，这是该系统前端的原始写法
                .header("authorization", "bearer" + token)
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return WriteResult.failed("操作被打断了，没有执行");
        } catch (Exception e) {
            // 连接失败 / 超时：不重试，明确告诉用户结果不确定
            log.warn("图书馆写操作 {} 网络失败（不重试）: {}", op, e.getMessage());
            return WriteResult.uncertain("这次结果不确定，学校系统可能已受理。先问我「我的预约」确认一下，别重复提交。");
        }

        if (resp.statusCode() != 200) {
            log.warn("图书馆写操作 {} HTTP {}: {}", op, resp.statusCode(), brief(resp.body()));
            return WriteResult.failed("学校系统返回异常（HTTP " + resp.statusCode() + "），这次没有执行成功。");
        }

        try {
            JsonNode root = MAPPER.readTree(resp.body());
            int code = root.path("code").asInt(-1);
            if (code == 0) {
                return WriteResult.ok("操作成功");
            }
            String msg = text(root, "message");
            if (msg != null && msg.contains("NO_LOGIN")) {
                tokenProvider.invalidate();
                return WriteResult.failed("图书馆登录状态过期了，稍等片刻再试一次");
            }
            return WriteResult.failed(msg == null || msg.isBlank() ? "学校系统没有接受这次操作" : msg);
        } catch (Exception e) {
            log.warn("图书馆写操作 {} 解析响应失败: {}", op, e.getMessage());
            return WriteResult.uncertain("这次结果不确定，先问我「我的预约」确认一下，别重复提交。");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String trimSlash(String s) {
        if (s == null || s.isBlank()) {
            return "";
        }
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String brief(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
