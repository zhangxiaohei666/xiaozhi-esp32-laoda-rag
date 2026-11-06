package com.xiaozhi.ai.library;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaozhi.utils.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 图书馆座位系统的只读接口客户端。
 *
 * <p>只封装两个查询接口（都是 POST，请求体是 JSON，鉴权靠 {@code authorization: bearer<token>}）：
 * <ul>
 *   <li>{@code POST /v4/space/pick}   总览：楼层 / 区域的总数与空闲数</li>
 *   <li>{@code POST /v4/Space/seat}   明细：某区域每个座位的状态</li>
 * </ul>
 *
 * <p><b>关于 AES</b>：预约类接口（{@code /v4/space/confirm}）请求体要 AES 加密，
 * 但本功能只做查询，用不到加密，所以这里没有引入任何加密代码。
 *
 * <p><b>关于重试</b>：只读查询最多重试 1 次（网络抖动很常见）；
 * 写操作不在本类范围内。
 */
@Slf4j
@Component
public class LibraryApiClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 座位状态码 → 中文。实测全量：1/2/3/6/7 */
    private static final String STATUS_FREE = "1";

    private final LibraryProperties props;
    private final LibraryTokenProvider tokenProvider;
    private final HttpClient http;

    public LibraryApiClient(LibraryProperties props, LibraryTokenProvider tokenProvider) {
        this.props = props;
        this.tokenProvider = tokenProvider;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    // ==================== 数据模型 ====================

    /** 楼层（二楼/三楼/四楼/五楼/其它） */
    public record Storey(String id, String name, int total, int free) {}

    /** 区域（一个具体的阅览室/借阅区） */
    public record Area(String id, String name, String floor, int total, int free) {}

    /** 座位 */
    public record Seat(String id, String no, String areaName, String status, String statusName) {
        public boolean isFree() {
            return STATUS_FREE.equals(status);
        }
    }

    /** 总览结果 */
    public record Overview(int totalSeats, int freeSeats, List<Storey> storeys, List<Area> areas) {

        /** 按空闲数从多到少排，供"哪个区域最空"这类问题使用 */
        public List<Area> areasByFreeDesc() {
            List<Area> copy = new ArrayList<>(areas);
            copy.sort((a, b) -> Integer.compare(b.free(), a.free()));
            return copy;
        }
    }

    // ==================== 接口 ====================

    /**
     * 总览结果的短缓存时长（2026-11-06 修 F-12 后半）。
     *
     * <p>为什么要缓存：网页聊天一轮请求可能连调两次 pick（先 resolve 区域再取总览），
     * 而工具描述原来还写着「每轮对话都要重新查，不要复用上一轮的结果」。
     * 一个用户连问几句「三楼还有位置吗」「四楼呢」就是连续三次打学校接口，
     * 用的是项目方唯一的 CAS 账号。
     *
     * <p>为什么是 30 秒而不是更长：座位数是会变的，30 秒内的缓存对「还剩多少座位」
     * 这个问题来说仍然是实时的；再长就会让人看到明显过期的数字。
     * 而且失效是惰性的（下次查询时判断），没有后台线程。
     */
    private static final long OVERVIEW_CACHE_MS = 30_000L;

    /**
     * 缓存项：date / at / overview 必须作为一个整体读写。
     *
     * <p>2026-11-06 修 P-02：最初写成三个独立 {@code volatile} 字段
     * （{@code cachedOverview} / {@code cachedOverviewDate} / {@code cachedOverviewAt}），
     * 注释还写着「单写者模式」—— 但工具执行在虚拟线程上，并发量在 F-12 之后反而更高，
     * 不是单写者。三个字段分三次写、读时分三次取，存在这样的交错：
     * <pre>
     * A 线程查「明天」：cachedOverview = 明天的结果  （还没写 date，仍是「今天」）
     * B 线程查「今天」：读到新 overview（明天数据）+ 旧 date（匹配「今天」）+ 旧 at（未过期）
     *                  → 把明天的座位数当成今天的返回
     * </pre>
     * 窗口很小，但方向恰好是「给出错误日期的实时数字」，正是这个工具最不该错的。
     *
     * <p>合成一个不可变 record 后，一次赋值 = 一次发布，
     * 读到的要么是旧的完整三元组，要么是新的完整三元组，不会半新半旧。
     */
    private record OverviewCache(String date, long at, Overview overview) {}

    /** 最近一次非空的 Overview 缓存；整条替换，不做字段级更新 */
    private volatile OverviewCache overviewCache;

    /**
     * 查总览。
     *
     * @param date yyyy-MM-dd，必须是系统支持的日期（一般只有今天和明天）
     */
    public Overview pick(String date) {
        long now = DateUtils.millis();
        OverviewCache cached = overviewCache;
        if (cached != null && date != null && date.equals(cached.date())
                && now - cached.at() < OVERVIEW_CACHE_MS) {
            return cached.overview();
        }

        Overview result = fetchOverview(date);

        // 只在拿到非空结果时写缓存：空结果不缓存（与 LibraryAreaResolver 的 F-07 修复一致），
        // 否则闭馆后的一次查询会把「全空」固化成 30 秒的事实。
        if (result != null && (!result.storeys().isEmpty() || !result.areas().isEmpty())) {
            overviewCache = new OverviewCache(date, now, result);
        }
        return result;
    }

    private Overview fetchOverview(String date) {
        String json = "{\"premisesIds\":[\"1\"],\"categoryIds\":[],\"storeyIds\":[],\"boutiqueIds\":[],\"date\":\"" + date + "\"}";
        JsonNode data = post("/v4/space/pick", json).path("data");
        if (data.isMissingNode() || data.isNull()) {
            throw new LibraryUnavailableException("图书馆系统没有返回座位数据");
        }

        int total = 0;
        int free = 0;
        List<Storey> storeys = new ArrayList<>();
        for (JsonNode n : iterable(data.path("storey"))) {
            Storey s = new Storey(
                    str(n, "id"), str(n, "name"),
                    n.path("total_num").asInt(0), n.path("free_num").asInt(0));
            storeys.add(s);
            total += s.total();
            free += s.free();
        }

        List<Area> areas = new ArrayList<>();
        for (JsonNode n : iterable(data.path("area"))) {
            areas.add(new Area(
                    str(n, "id"),
                    str(n, "name"),
                    str(n, "storeyName"),
                    n.path("total_num").asInt(0),
                    n.path("free_num").asInt(0)));
        }

        // 楼层合计与馆舍合计可能因"其它"分类略有出入，以楼层累加为准更贴近用户理解
        if (total == 0) {
            JsonNode premises = data.path("premises");
            if (premises.isArray() && !premises.isEmpty()) {
                JsonNode p = premises.get(0);
                total = p.path("total_num").asInt(0);
                free = p.path("free_num").asInt(0);
            }
        }

        return new Overview(total, free, storeys, areas);
    }

    /**
     * 查某区域的具体座位。
     *
     * @param areaId 区域 ID（来自 {@link Overview#areas()}）
     * @param date   yyyy-MM-dd
     */
    public List<Seat> seats(String areaId, String date) {
        // start_time / end_time 是必填项，缺一个后端就报 "Undefined index: start_time"；
        // 传空串则会触发 SQL datetime 转换错误。这里用全天区间作为默认值。
        String json = "{\"id\":\"" + areaId + "\",\"day\":\"" + date
                + "\",\"start_time\":\"08:00\",\"end_time\":\"22:00\"}";
        JsonNode data = post("/v4/Space/seat", json).path("data");
        List<Seat> seats = new ArrayList<>();
        for (JsonNode n : iterable(data.path("list"))) {
            seats.add(new Seat(
                    str(n, "id"),
                    str(n, "no"),
                    str(n, "area_name"),
                    str(n, "status"),
                    str(n, "status_name")));
        }
        return seats;
    }

    // ==================== 内部 ====================

    /** 发一次 POST，返回解析后的 JSON 根节点；只读接口失败重试 1 次 */
    private JsonNode post(String path, String body) {
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return postOnce(path, body);
            } catch (LibraryUnavailableException e) {
                boolean isLoginError = e.getMessage() != null && e.getMessage().contains("登录");
                // 登录类错误（2026-11-06 修 F-08）：
                //   第一次遇到 → postOnce 里已经 tokenProvider.invalidate()，
                //   再试一次就能拿到侧车刚换好的新 token，所以要继续循环；
                //   第二次还是登录错误 → 说明侧车那边也确实没登录（要去人工过验证码），
                //   这时候才直接抛，不要拿同一个错误反复打学校接口。
                if (isLoginError && attempt > 0) {
                    throw e;
                }
                if (isLoginError) {
                    log.debug("图书馆接口 {} 返回登录错误，本地 token 已失效，重试一次", path);
                }
                last = e;
            } catch (Exception e) {
                last = e;
            }
            if (attempt == 0) {
                log.debug("图书馆接口 {} 第一次失败，重试一次", path);
            }
        }
        log.warn("图书馆接口 {} 调用失败: {}", path, last == null ? "unknown" : last.getMessage());
        throw new LibraryUnavailableException("图书馆系统这会儿响应慢，稍等再问我一次");
    }

    private JsonNode postOnce(String path, String body) throws Exception {
        String token = tokenProvider.token();
        URI uri = URI.create(trimSlash(props.getBaseUrl()) + path);

        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(props.getTimeoutMs()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-Requested-With", "XMLHttpRequest")
                // 注意：bearer 后面没有空格，这是该系统前端的原始写法
                .header("authorization", "bearer" + token)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + resp.statusCode());
        }

        JsonNode root = MAPPER.readTree(resp.body());
        int code = root.path("code").asInt(-1);
        if (code != 0) {
            String msg = text(root, "message");
            // 未登录：先让本地 token 缓存失效，再由外层重试拿新 token（F-08）
            if (msg != null && msg.contains("NO_LOGIN")) {
                tokenProvider.invalidate();
                throw new LibraryUnavailableException("图书馆系统需要重新登录");
            }
            throw new LibraryUnavailableException("图书馆系统返回错误：" + (msg == null ? "未知原因" : msg));
        }
        return root;
    }

    private static Iterable<JsonNode> iterable(JsonNode arr) {
        return arr != null && arr.isArray() ? arr : List.<JsonNode>of();
    }

    private static String str(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText();
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
}