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
 * <p>只封装查询接口（都是 POST，请求体是 JSON，鉴权靠 {@code authorization: bearer<token>}）：
 * <ul>
 *   <li>{@code POST /v4/space/pick}   总览：楼层 / 区域的总数与空闲数</li>
 *   <li>{@code POST /v4/Space/seat}   明细：某区域每个座位的状态</li>
 *   <li>{@code POST /v4/Space/map}    区域预约方式：reserveType + 每天可约时段（2026-11-24 实测）</li>
 *   <li>{@code POST /v4/member/seat}  我的预约记录（2026-11-24 实测）</li>
 * </ul>
 *
 * <p><b>关于 AES</b>：预约类接口（{@code /v4/space/confirm}）请求体要 AES 加密，
 * 那属于写操作，在 {@link LibraryWriteClient} 里做，本类不碰。
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

    /**
     * 一个可约时段（{@code Space/map} 返回的 {@code date.list[].times[]}）。
     * reserveType=1（时段型）时，{@code id} 就是预约明文里的 segment 值。
     */
    public record TimeSeg(String id, String start, String end) {}

    /** 一天的可约时段列表（{@code Space/map} 返回的 {@code date.list[]} 一条） */
    public record DateSlot(String day, String showTime, List<TimeSeg> times) {

        /** reserveType=1 时一天通常只有一个时段，取它；多个时取第一个（实测未见多个） */
        public TimeSeg firstSeg() {
            return times == null || times.isEmpty() ? null : times.get(0);
        }
    }

    /**
     * 区域预约方式（{@code Space/map} 返回的 data）。
     *
     * @param type        座位类型：1=普通座位；其他（如考研座位）本期不碰
     * @param reserveType 预约参数形态：1=时段型（segment）、2=只结束时间、3=起止时间
     */
    public record AreaMap(String areaName, String type, String typeName,
                          String reserveType, List<DateSlot> dates) {

        /** 目标日期的时段列表；查不到返回 null（调用方提示该天不可约） */
        public DateSlot dateOf(String day) {
            for (DateSlot s : dates) {
                if (day.equals(s.day())) {
                    return s;
                }
            }
            return null;
        }
    }

    /**
     * 一条预约记录（{@code member/seat} 返回的 {@code data.data[]} 一条，2026-11-24 实测字段）。
     */
    public record Booking(
            String id,          // 预约记录 ID：取消/签退/临时离开都用它
            String status,      // ⚠️ 数字码不可靠（实测"预约成功"=9 而非文档早记的 2）；业务判断用 statusName
            String statusName,  // 中文状态名（渲染与业务判断都用它）：预约成功/用户取消/已结束
            String day,         // MM-DD
            String start,       // HH:mm
            String end,         // HH:mm
            String areaName,    // 区域名（nameMerge 里截出来）
            String nameMerge,   // 全名：图书馆-三楼-艺术沙龙
            String seatNo,      // 座位号
            String spaceId,     // 座位 ID
            boolean cancel,     // ⚠️ 2026-11-24 实测：该字段不可靠（标 0 时取消接口仍放行），不再用于业务判断，仅保留解析
            String signInTime,  // 签到时间，null=未签到
            String signOutTime, // 签离时间，null=未签离
            String cancelTime   // 取消时间，空=未取消
    ) {

        /** 系统返回的 cancel 原始标记（⚠️ 实测不可靠，业务判断请用 {@link LibraryBookingHelper#cancellable}） */
        public boolean canCancel() {
            return cancel;
        }

        /** 正在使用中（状态名判断；"使用中"字样待现场实测确认） */
        public boolean inUse() {
            return statusName != null && statusName.contains("使用中");
        }

        /** 临时离开中 */
        public boolean tempLeave() {
            return statusName != null && statusName.contains("临时离开");
        }
    }

    /**
     * 一条「我的常用/收藏座位」（{@code member/often} / {@code member/collect} 返回的 {@code data[]} 一条，
     * 2026-11-24 实测字段）。
     *
     * <p><b>为什么只保留座位身份</b>：接口还返回 {@code is_subscribe}、{@code segment}，但
     * 2026-11-24 实测二者都不能当「能不能约」的依据——同一时刻、同一时段，{@code often} 把
     * 公共阅览区 111 标成 {@code is_subscribe=0}，{@code collect} 却把 204 标成 1，而这两个座位
     * 在 {@code Space/seat} 里都明确是「空闲」。这和 {@code member/seat} 的 {@code cancel} 字段
     * 属于同一类「系统自带但自相矛盾」的提示字段。
     *
     * <p>所以这里只承担三件事：区域、座位号、座位 ID。可约与否一律以
     * {@code Space/seat} 的 {@code status} 为准（与 {@link #seats} 同源，也和下单前的重查一致）。
     */
    public record FavoriteSeat(
            String areaId,    // 区域 ID（用于查 Space/map 与 Space/seat）
            String areaName,  // 区域全名：图书馆-三楼-社科借阅区2
            String seatId,    // 座位 ID（Space/seat 返回的 id）
            String seatNo     // 座位号：040
    ) {}

    /** 「常用/收藏」的一个时段（{@code member/oftentime} 返回的 {@code data[].times[]}） */
    public record FavTime(String start, String end) {}

    /** 「常用/收藏」的一天（{@code member/oftentime} 返回的 {@code data[]} 一条） */
    public record FavDay(String day, List<FavTime> times) {

        /** 取第一个时段当「探针」（often/collect 必须带合法 day+begin+end 才能查到清单） */
        public FavTime firstTime() {
            return times == null || times.isEmpty() ? null : times.get(0);
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
        return seats(areaId, date, "08:00", "22:00");
    }

    /**
     * 查某区域某时段的具体座位（2026-11-24 新增重载，供预约选座用）。
     *
     * <p>老的两参数方法保留并委托到这里（全天 08:00-22:00），现有调用点不用改。
     * start_time / end_time 是必填项，缺一个后端就报 "Undefined index: start_time"；
     * 传空串则会触发 SQL datetime 转换错误。这里解析失败一律回落 08:00/22:00，
     * <b>绝不传空串</b>（评审 B-1）。
     */
    public List<Seat> seats(String areaId, String date, String startTime, String endTime) {
        String s = (startTime == null || startTime.isBlank()) ? "08:00" : startTime;
        String e = (endTime == null || endTime.isBlank()) ? "22:00" : endTime;
        String json = "{\"id\":\"" + areaId + "\",\"day\":\"" + date
                + "\",\"start_time\":\"" + s + "\",\"end_time\":\"" + e + "\"}";
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

    /**
     * 查区域的预约方式：reserveType + 每天可约时段（{@code POST /v4/Space/map}）。
     *
     * <p>预约前必须先调本方法拿 reserveType，再决定参数形态（评审 B-2：
     * 顺序不能反——先知道类型，再解析/反问时段，否则 reserveType=2 的区域会误反问开始时间）。
     *
     * @param areaId 区域 ID
     * @throws LibraryUnavailableException 接口异常或区域不是普通座位类型
     */
    public AreaMap areaMap(String areaId) {
        String json = "{\"id\":\"" + areaId + "\"}";
        JsonNode data = post("/v4/Space/map", json).path("data");
        if (data.isMissingNode() || data.isNull()) {
            throw new LibraryUnavailableException("图书馆没有返回这个区域的预约方式");
        }
        String type = str(data, "type");
        String typeName = str(data, "type_name");
        JsonNode date = data.path("date");
        String reserveType = str(date, "reserveType");

        List<DateSlot> dates = new ArrayList<>();
        for (JsonNode n : iterable(date.path("list"))) {
            List<TimeSeg> times = new ArrayList<>();
            for (JsonNode t : iterable(n.path("times"))) {
                times.add(new TimeSeg(str(t, "id"), str(t, "start"), str(t, "end")));
            }
            dates.add(new DateSlot(str(n, "day"), str(n, "show_time"), times));
        }
        return new AreaMap(str(data, "name"), type, typeName, reserveType, dates);
    }

    /**
     * 查我的预约记录（{@code POST /v4/member/seat}，type=1 座位预约，前 10 条）。
     *
     * <p>记录按时间倒序，最新的在前。返回的 {@code cancel} 字段是系统自带的
     * "当前可否取消"标记（实测：可取消=1、不可取消=0），取消工具优先信任它。
     */
    public List<Booking> myBookings() {
        String json = "{\"type\":\"1\",\"page\":1,\"limit\":10}";
        JsonNode data = post("/v4/member/seat", json).path("data");
        List<Booking> out = new ArrayList<>();
        for (JsonNode n : iterable(data.path("data"))) {
            String nameMerge = str(n, "nameMerge");
            String areaName = str(n, "name");
            if (areaName.isBlank() && !nameMerge.isBlank()) {
                // nameMerge 形如"图书馆-三楼-艺术沙龙"，取最后一段
                int idx = nameMerge.lastIndexOf('-');
                areaName = idx >= 0 ? nameMerge.substring(idx + 1) : nameMerge;
            }
            out.add(new Booking(
                    str(n, "id"),
                    str(n, "status"),
                    str(n, "status_name"),
                    str(n, "day"),
                    str(n, "start"),
                    str(n, "end"),
                    areaName,
                    nameMerge,
                    str(n, "no"),
                    str(n, "space"),
                    n.path("cancel").asInt(0) == 1,
                    text(n, "signInTime"),
                    text(n, "signOutTime"),
                    text(n, "cancelTime")));
        }
        return out;
    }

    /**
     * 我的「常用」时段（{@code POST /v4/member/oftentime}，type=1 座位，2026-11-24 实测）。
     *
     * <p>返回今天/明天各自的时段列表。注意 {@code times[].start} 是原始起点
     * （今天可能是 07:50），前端展示用的裁剪后起点在 {@code show_time}（形如「14:19~21:30」）——
     * 后者格式不稳（实测明天 08:00-11:50 被写成「14:19~11:50」），所以这里<b>只取 start/end</b>，
     * 真要判断某段时间是否合法，一律以 {@code Space/map} 的 {@code times[]} 为准。
     */
    public List<FavDay> oftenTimes() {
        return favoriteTimes("/v4/member/oftentime");
    }

    /** 我的「收藏」时段（{@code POST /v4/member/collecttime}，结构同 ofttime） */
    public List<FavDay> collectTimes() {
        return favoriteTimes("/v4/member/collecttime");
    }

    private List<FavDay> favoriteTimes(String path) {
        JsonNode data = post(path, "{\"type\":1}").path("data");
        List<FavDay> out = new ArrayList<>();
        for (JsonNode n : iterable(data)) {
            List<FavTime> times = new ArrayList<>();
            for (JsonNode t : iterable(n.path("times"))) {
                times.add(new FavTime(str(t, "start"), str(t, "end")));
            }
            out.add(new FavDay(str(n, "day"), times));
        }
        return out;
    }

    /**
     * 我的「常用」座位（{@code POST /v4/member/often}）。
     *
     * <p><b>必须带 day + begin_time + end_time</b>：只传 {@code {"type":1}} 实测返回
     * {@code code=201 缺少参数或参数错误}。所以调用方要先拿一个合法时段当探针
     * （{@link FavDay#firstTime()}）。
     *
     * <p>返回的座位身份与时段无关；随参数变化的 {@code is_subscribe}/{@code segment}
     * 不参与可约判断（原因见 {@link FavoriteSeat}）。
     */
    public List<FavoriteSeat> oftenSeats(String day, String begin, String end) {
        return favoriteSeats("/v4/member/often", day, begin, end);
    }

    /** 我的「收藏」座位（{@code POST /v4/member/collect}，参数同 often） */
    public List<FavoriteSeat> collectSeats(String day, String begin, String end) {
        return favoriteSeats("/v4/member/collect", day, begin, end);
    }

    private List<FavoriteSeat> favoriteSeats(String path, String day, String begin, String end) {
        String json = "{\"type\":1,\"day\":\"" + day + "\",\"begin_time\":\"" + begin
                + "\",\"end_time\":\"" + end + "\"}";
        JsonNode data = post(path, json).path("data");
        List<FavoriteSeat> out = new ArrayList<>();
        for (JsonNode n : iterable(data)) {
            out.add(new FavoriteSeat(
                    str(n, "area_id"),
                    str(n, "name_merge"),
                    str(n, "space_id"),
                    str(n, "space_name")));
        }
        return out;
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
            // 登录类错误：先让本地 token 缓存失效，再由外层重试拿新 token（F-08）。
            // 2026-11-24 实测补充：学校单点登录互踢返回"请注意,您的账号已从其他设备上登录"，
            // 同样意味着本地缓存 token 已失效——原来只认 NO_LOGIN，遇到互踢会拿着死 token
            // 重试到缓存过期（最长 100 分钟），期间所有查询/写操作全部失败。
            // 现在凡是消息含"登录"字样都失效重取，取不到再由工具层转成人话。
            if (msg != null && (msg.contains("NO_LOGIN") || msg.contains("登录"))) {
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