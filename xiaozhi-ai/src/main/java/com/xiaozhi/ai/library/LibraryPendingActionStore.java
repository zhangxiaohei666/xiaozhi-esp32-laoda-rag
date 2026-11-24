package com.xiaozhi.ai.library;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaozhi.utils.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 写操作"待确认"暂存（两段式确认的第二段入口）。
 *
 * <p>流程：prepare 工具查好座位/记录后，把全量快照写进 Redis；
 * 用户说"确认"时 {@code library_confirm_action} 读出来直接执行，不再重新解析。
 * 这样"确认"阶段不需要模型再传任何参数，也不会因为多轮对话里的措辞变化而订错座位。
 *
 * <p><b>为什么 key 用 userId 而不是 sessionId</b>（评审 C-4）：语音端网络抖动重连后
 * sessionId 会变，待确认会丢；userId 才是稳定的操作人标识。同一用户同一时刻只有一条
 * 待确认，新 prepare 覆盖旧条目，并把被覆盖的旧摘要交还给调用方提示"已作废"。
 *
 * <p>TTL 默认 120 秒：够用户说一句"确认"，又不会让陈旧的座位快照一直躺在 Redis 里
 * 等执行——毕竟从"看到空位"到"真下单"之间座位可能已经没了，confirm 阶段还要重查。
 *
 * <p>Redis 失败按不可用处理：存不进去就明说"稍后再试"，绝不退化成内存兜底——
 * 内存兜底在多实例/重启后是静默丢单，Redis 是两端进程共用的唯一事实。
 */
@Slf4j
@Component
public class LibraryPendingActionStore {

    private static final String KEY_PREFIX = "xiaozhi:library:pending:";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 待确认操作类型：与写接口一一对应 */
    public enum Type {
        RESERVE, CANCEL, LEAVE, CHECKOUT
    }

    /**
     * 一条待确认：summary 是给用户看的话；payload 是 confirm 阶段执行要用的全量快照。
     * payload 结构因 type 而异（见各 prepare 工具的 javadoc），confirm 只读不改。
     */
    public record Pending(Type type, String summary, JsonNode payload, long createdAt) {}

    private final LibraryProperties props;
    private final StringRedisTemplate redis;

    public LibraryPendingActionStore(LibraryProperties props, StringRedisTemplate redis) {
        this.props = props;
        this.redis = redis;
    }

    /**
     * 写入（或覆盖）一条待确认。
     *
     * @return 被覆盖的旧条目；没有旧条目返回 null。调用方据此提示"之前那条已作废"
     * @throws LibraryUnavailableException Redis 不可用
     */
    public Pending save(Integer userId, Type type, String summary, JsonNode payload) {
        if (userId == null) {
            throw new LibraryUnavailableException("识别不到你的身份，暂时没法记待确认");
        }
        Pending old = get(userId);
        try {
            Pending fresh = new Pending(type, summary, payload, DateUtils.millis());
            redis.opsForValue().set(key(userId), MAPPER.writeValueAsString(fresh),
                    Duration.ofSeconds(Math.max(10, props.getPendingTtlSeconds())));
            return old;
        } catch (Exception e) {
            log.warn("图书馆待确认写入失败: {}", e.getMessage());
            throw new LibraryUnavailableException("确认服务这会儿不可用，稍后再试一次");
        }
    }

    /**
     * 读当前用户的待确认；没有或已过期返回 null。
     *
     * @throws LibraryUnavailableException Redis 不可用（与"没有待确认"要区分开）
     */
    public Pending get(Integer userId) {
        if (userId == null) {
            return null;
        }
        try {
            String raw = redis.opsForValue().get(key(userId));
            if (raw == null || raw.isBlank()) {
                return null;
            }
            return MAPPER.readValue(raw, Pending.class);
        } catch (Exception e) {
            log.warn("图书馆待确认读取失败: {}", e.getMessage());
            throw new LibraryUnavailableException("确认服务这会儿不可用，稍后再试一次");
        }
    }

    /** 删掉当前用户的待确认（confirm 执行完或明确放弃后调用）。失败不抛：残留只会自然过期 */
    public void delete(Integer userId) {
        if (userId == null) {
            return;
        }
        try {
            redis.delete(key(userId));
        } catch (Exception e) {
            log.warn("图书馆待确认删除失败: {}", e.getMessage());
        }
    }

    private static String key(Integer userId) {
        return KEY_PREFIX + userId;
    }
}
