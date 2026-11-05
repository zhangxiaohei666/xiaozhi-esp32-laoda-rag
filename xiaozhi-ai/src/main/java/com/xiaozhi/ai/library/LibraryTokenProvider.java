package com.xiaozhi.ai.library;

import com.xiaozhi.utils.DateUtils;
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
 * 向登录守护进程索取图书馆 token。
 *
 * <p>为什么不自己登录：图书馆系统用 CAS 统一身份认证，登录页有图形验证码、浏览器指纹、
 * 多因素认证字段，纯 HTTP 无法自动化；逆向学校核心认证系统也不合规。
 * 因此把"登录"这件脏活隔离到独立的守护进程里，本类只做一件事：<b>要一个可用的 token</b>。
 *
 * <p>缓存策略：token 有效期 100 分钟，这里按"过期时间 - 2 分钟"缓存；
 * 守护进程自己会提前续期，所以正常情况下本类几乎不会触发真实请求。
 *
 * <p>失败语义：拿不到 token 时抛 {@link LibraryUnavailableException}，
 * 由工具层转成一句人话返回给模型，绝不把异常抛进对话链路。
 */
@Slf4j
@Component
public class LibraryTokenProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LibraryProperties props;
    private final HttpClient http;

    /** 缓存的 token */
    private volatile String token;
    /** 缓存 token 的失效时刻（毫秒），已留出安全余量 */
    private volatile long tokenValidUntil;

    public LibraryTokenProvider(LibraryProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    /**
     * 取一个可用 token。
     *
     * @return 不带 "bearer" 前缀的原始 token
     * @throws LibraryUnavailableException 守护进程没起、未登录、或超时
     */
    public String token() {
        long now = DateUtils.millis();
        String cached = token;
        if (cached != null && tokenValidUntil > now) {
            return cached;
        }
        return refresh();
    }

    private synchronized String refresh() {
        // 双检：并发的第二个线程可能已经刷新完了
        long now = DateUtils.millis();
        if (token != null && tokenValidUntil > now) {
            return token;
        }

        URI uri = URI.create(trimSlash(props.getSidecarUrl()) + "/token");
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(props.getTokenTimeoutMs()))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LibraryUnavailableException("图书馆登录服务被中断");
        } catch (Exception e) {
            log.warn("图书馆登录守护进程不可用: {}", e.getMessage());
            throw new LibraryUnavailableException("图书馆查询服务没启动");
        }

        if (resp.statusCode() != 200) {
            log.warn("登录守护进程返回 {}: {}", resp.statusCode(), brief(resp.body()));
            throw new LibraryUnavailableException("图书馆查询服务异常");
        }

        try {
            JsonNode node = MAPPER.readTree(resp.body());
            String t = text(node, "token");
            boolean loginRequired = node.path("loginRequired").asBoolean(false);
            if (t == null || t.isBlank()) {
                throw new LibraryUnavailableException(
                        loginRequired ? "图书馆系统需要重新登录" : "图书馆系统暂时取不到登录状态");
            }
            long expAt = node.path("expiresAt").asLong(0L);
            // 留 2 分钟余量；expiresAt 缺失时保守按 5 分钟缓存
            long validUntil = expAt > 0 ? expAt - 120_000L : DateUtils.millis() + 300_000L;
            token = t;
            tokenValidUntil = validUntil;
            return t;
        } catch (LibraryUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.warn("解析守护进程响应失败: {}", e.getMessage());
            throw new LibraryUnavailableException("图书馆查询服务返回异常");
        }
    }

    /** 供健康检查/调试用：当前是否持有可用 token（不会触发网络请求） */
    public boolean hasToken() {
        return token != null && tokenValidUntil > DateUtils.millis();
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