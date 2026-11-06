package com.xiaozhi.ai.library;

import com.xiaozhi.utils.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把用户嘴里的"三楼""英语角""四楼公共阅览区"翻译成接口要的区域 ID。
 *
 * <p><b>为什么需要这一层</b>：工具参数不能要求模型传 {@code areaId="11"} —— 模型不知道这个数字。
 * 用户说的是自然语言，所以这一层负责把自然语言映射成 ID；映射不到时还要给出候选，
 * 让模型能引导用户换个说法。
 *
 * <p><b>数据来源</b>：{@code POST /v4/space/pick} 返回的 {@code storey[]} 与 {@code area[]}。
 * 区域结构基本不变，缓存 1 小时。
 */
@Slf4j
@Component
public class LibraryAreaResolver {

    /** 区域结构缓存时长 */
    private static final long CACHE_TTL_MS = 60 * 60 * 1000L;

    private final LibraryApiClient apiClient;

    /** 缓存：楼层 + 区域的目录（与日期无关） */
    private volatile Snapshot snapshot;
    private volatile long snapshotAt;

    public LibraryAreaResolver(LibraryApiClient apiClient) {
        this.apiClient = apiClient;
    }

    /** 解析结果 */
    public record Resolved(
            List<LibraryApiClient.Area> areas,
            List<LibraryApiClient.Storey> storeys,
            boolean matched,
            String hint) {

        public boolean isWholeLibrary() {
            return areas.isEmpty() && storeys.isEmpty();
        }
    }

    /**
     * 解析用户输入。
     *
     * @param userText 用户/模型给的区域或楼层描述，可为空（表示全馆）
     * @param today    用于拉取目录的日期（接口需要日期参数）
     */
    public Resolved resolve(String userText, String today) {
        String text = userText == null ? "" : userText.trim();
        if (text.isEmpty()) {
            return new Resolved(List.of(), List.of(), true, null);
        }

        Snapshot snap = snapshot(today);

        // ① 楼层关键词优先：很多时候用户只说"三楼"
        Set<String> floorIds = new LinkedHashSet<>();
        List<LibraryApiClient.Storey> hitStoreys = new ArrayList<>();
        for (LibraryApiClient.Storey s : snap.storeys()) {
            if (matchesFloor(text, s.name())) {
                floorIds.add(s.id());
                hitStoreys.add(s);
            }
        }

        // ② 区域名匹配
        List<LibraryApiClient.Area> hitAreas = new ArrayList<>();
        for (LibraryApiClient.Area a : snap.areas()) {
            if (matchesArea(text, a)) {
                hitAreas.add(a);
            }
        }

        // ③ 组合场景："三楼公共阅览区" —— 楼层命中 + 区域名命中，取交集
        if (!floorIds.isEmpty() && !hitAreas.isEmpty()) {
            List<LibraryApiClient.Area> narrowed = hitAreas.stream()
                    .filter(a -> floorIds.contains(floorIdOf(snap, a)))
                    .toList();
            if (!narrowed.isEmpty()) {
                return new Resolved(narrowed, hitStoreys, true, null);
            }
        }

        if (!hitAreas.isEmpty()) {
            return new Resolved(hitAreas, List.of(), true, null);
        }
        if (!hitStoreys.isEmpty()) {
            // 只说楼层：把该层所有区域作为范围
            List<LibraryApiClient.Area> inFloors = snap.areas().stream()
                    .filter(a -> floorIds.contains(floorIdOf(snap, a)))
                    .toList();
            return new Resolved(inFloors, hitStoreys, true, null);
        }

        // ④ 认不出来：给候选，让模型引导用户
        return new Resolved(List.of(), List.of(), false, buildCandidates(snap));
    }

    // ==================== 匹配规则 ====================

    /** "三楼" / "3楼" / "三层" / "其它" 都能对上 */
    private static boolean matchesFloor(String text, String storeyName) {
        if (storeyName == null || storeyName.isBlank()) {
            return false;
        }
        String t = normalize(text);
        String s = normalize(storeyName);
        if (t.contains(s)) {
            return true;
        }
        // 数字形式：3楼 / 3层
        String digit = s.replace("楼", "").replace("层", "");
        String cn = toChineseNumber(digit);
        if (cn != null) {
            return t.contains(cn + "楼") || t.contains(cn + "层")
                    || t.contains(digit + "楼") || t.contains(digit + "层");
        }
        return false;
    }

    /** 区域名匹配：输入包含区域名，或区域名包含输入 */
    private static boolean matchesArea(String text, LibraryApiClient.Area area) {
        String t = normalize(text);
        String name = normalize(area.name());
        if (name.isBlank()) {
            return false;
        }
        return t.contains(name) || name.contains(t);
    }

    private static String floorIdOf(Snapshot snap, LibraryApiClient.Area area) {
        // area 里带了 floor 名，用名字反查 ID
        for (LibraryApiClient.Storey s : snap.storeys()) {
            if (s.name().equals(area.floor())) {
                return s.id();
            }
        }
        return "";
    }

    private static String normalize(String s) {
        return s == null ? "" : s.replace(" ", "").replace("（", "(").replace("）", ")").trim();
    }

    /** 阿拉伯数字 → 中文数字（只处理 1~9，楼层够用） */
    private static String toChineseNumber(String digit) {
        return switch (digit) {
            case "1" -> "一";
            case "2" -> "二";
            case "3" -> "三";
            case "4" -> "四";
            case "5" -> "五";
            case "6" -> "六";
            case "7" -> "七";
            case "8" -> "八";
            case "9" -> "九";
            default -> null;
        };
    }

    private static String buildCandidates(Snapshot snap) {
        // 按楼层分组列出区域名，避免一次抛二十多个名字
        Map<String, List<String>> byFloor = new LinkedHashMap<>();
        for (LibraryApiClient.Area a : snap.areas()) {
            byFloor.computeIfAbsent(a.floor() == null || a.floor().isBlank() ? "其它" : a.floor(),
                    k -> new ArrayList<>()).add(a.name());
        }
        StringBuilder sb = new StringBuilder();
        byFloor.forEach((floor, names) -> {
            if (!sb.isEmpty()) {
                sb.append("；");
            }
            sb.append(floor).append("：").append(String.join("、", dedup(names)));
        });

        // ⚠️ 空目录要给兜底文案（2026-11-06 修 F-27）。
        //
        // 两个 Function 在 matched=false 时都拼：
        //   "没找到「X」这个区域。图书馆的楼层和区域是这些——" + hint
        // 若 hint 是空串，句子直接断在破折号上。
        // 闭馆后 API 返回空目录（F-07 同类根因）就会走到这里，
        // 而此时真实原因不是"用户说错了区域名"，而是"目录取不到"。
        if (sb.isEmpty()) {
            return "（区域目录这会儿取不到，多半是当天已闭馆，稍后再问一次）";
        }
        return sb.toString();
    }

    private static List<String> dedup(List<String> names) {
        return new ArrayList<>(new LinkedHashSet<>(names));
    }

    // ==================== 目录缓存 ====================

    private record Snapshot(List<LibraryApiClient.Storey> storeys, List<LibraryApiClient.Area> areas) {}

    private Snapshot snapshot(String today) {
        Snapshot cached = snapshot;
        if (cached != null && DateUtils.millis() - snapshotAt < CACHE_TTL_MS) {
            return cached;
        }
        synchronized (this) {
            if (snapshot != null && DateUtils.millis() - snapshotAt < CACHE_TTL_MS) {
                return snapshot;
            }
            LibraryApiClient.Overview ov = apiClient.pick(today);
            Snapshot fresh = new Snapshot(ov.storeys(), ov.areas());

            // ⚠️ 空目录不写缓存（2026-11-06 修 F-07）。
            //
            // 背景：22:00 闭馆后 LibraryApiClient.pick() 对「今天」返回全空
            // （实测 date=今天 → 115 字节 storey=0；date=明天 → 26756 字节 storey=5），
            // 这个空结果原来会被缓存 60 分钟。后果是一旦有人闭馆后问过一次
            // 「今天三楼还有位置吗」，接下来一小时内所有人问「明天英语角哪些座位空着」
            // 都会命中这份空目录 → 报「没找到『英语角』这个区域」，完全误导方向。
            //
            // 为什么不缓存但仍然返回：调用方需要知道「这次就是空的」，
            // 好走它自己的兜底文案；只是不把空结果固化成后续 60 分钟的事实。
            boolean empty = fresh.storeys().isEmpty() && fresh.areas().isEmpty();
            if (empty) {
                log.debug("图书馆目录为空（多半是当天已闭馆），本次不写入缓存");
                return fresh;
            }

            snapshot = fresh;
            snapshotAt = DateUtils.millis();
            log.debug("图书馆目录已刷新：{} 个楼层，{} 个区域", fresh.storeys().size(), fresh.areas().size());
            return fresh;
        }
    }
}