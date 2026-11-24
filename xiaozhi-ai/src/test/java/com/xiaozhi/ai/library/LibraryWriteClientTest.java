package com.xiaozhi.ai.library;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 钉住写客户端的核心纪律：只发一次、绝不重试；成功/失败/不确定三态语义。
 *
 * <p>用本地 {@link HttpServer} 当学校接口，超时场景把服务端卡住，
 * 断言请求只到了一次（写操作重试 = 重复下单，这是红线）。
 */
class LibraryWriteClientTest {

    private LibraryWriteClient client(HttpServer server) {
        LibraryProperties props = new LibraryProperties();
        props.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        props.setTimeoutMs(800);

        LibraryTokenProvider tokenProvider = mock(LibraryTokenProvider.class);
        when(tokenProvider.token()).thenReturn("test-token");
        return new LibraryWriteClient(props, tokenProvider);
    }

    private static HttpServer server(java.util.function.Consumer<String> onBody, String response, int delayMs)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            onBody.accept(body);
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ignored) {
                }
            }
            byte[] resp = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();
        return server;
    }

    @Test
    @DisplayName("code==0 → 成功")
    void successOnCodeZero() throws Exception {
        HttpServer server = server(b -> {
        }, "{\"code\":0,\"message\":\"取消成功\"}", 0);
        try {
            LibraryWriteClient.WriteResult r =
                    client(server).execute(LibraryWriteClient.Op.CANCEL, "{\"id\":\"123\"}");
            assertThat(r.success()).isTrue();
            assertThat(r.uncertain()).isFalse();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("code!=0 → 明确失败并带学校文案")
    void failWithSchoolMessage() throws Exception {
        HttpServer server = server(b -> {
        }, "{\"code\":500,\"message\":\"该座位已被预约\"}", 0);
        try {
            LibraryWriteClient.WriteResult r =
                    client(server).execute(LibraryWriteClient.Op.CONFIRM, "{\"aesjson\":\"xx\"}");
            assertThat(r.success()).isFalse();
            assertThat(r.uncertain()).isFalse();
            assertThat(r.message()).contains("已被预约");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("服务端卡住超时 → 结果不确定，且请求只发了 1 次（绝不重试）")
    void timeoutIsUncertainAndSentExactlyOnce() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = server(b -> hits.incrementAndGet(), "{}", 5000);
        try {
            LibraryWriteClient.WriteResult r =
                    client(server).execute(LibraryWriteClient.Op.CHECKOUT, "{\"id\":\"9\"}");
            assertThat(r.success()).isFalse();
            assertThat(r.uncertain()).isTrue();
            assertThat(r.message()).contains("不确定");
            assertThat(hits.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("请求体原样送达（cancel 的 id 结构）")
    void requestBodyMatches() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<String> got = new java.util.concurrent.atomic.AtomicReference<>();
        HttpServer server = server(b -> {
            got.set(b);
            hits.incrementAndGet();
        }, "{\"code\":0,\"message\":\"ok\"}", 0);
        try {
            client(server).execute(LibraryWriteClient.Op.CANCEL, "{\"id\":\"4214214\"}");
            assertThat(hits.get()).isEqualTo(1);
            assertThat(got.get()).isEqualTo("{\"id\":\"4214214\"}");
        } finally {
            server.stop(0);
        }
    }
}
