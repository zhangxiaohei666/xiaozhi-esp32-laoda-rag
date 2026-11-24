package com.xiaozhi.ai.library;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 图书馆座位功能配置（{@code xiaozhi.library.*}）。
 *
 * <p>所有环境差异都走这里，代码里不留任何硬编码地址/路径 —— 本项目最终要迁移到别的服务器，
 * 迁过去只需要改环境变量，不用改代码。
 *
 * <p>默认 <b>关闭</b>（{@code xiaozhi.library.enabled=false}）：此时两个工具都不注册，
 * 模型完全不知道有这功能，不影响任何现有能力。
 *
 * <p>⚠️ 前缀与其他扩展配置统一挂在 {@code xiaozhi} 下（早期是裸 {@code library}），
 * 这样全部可配置项都能在同一份 yml 的 {@code xiaozhi:} 段落里找到。
 */
@Data
@Component
@ConfigurationProperties(prefix = "xiaozhi.library")
public class LibraryProperties {

    /** 总开关。false 时两个查询工具都不注册。 */
    private boolean enabled = false;

    /**
     * 登录守护进程地址（只监听 127.0.0.1）。
     *
     * <p>该进程负责 CAS 登录与 token 续期，Java 侧只向它要 token，不碰登录细节。
     */
    private String sidecarUrl = "http://127.0.0.1:8790";

    /** 图书馆系统根地址。换学校/换域名只改这里。 */
    private String baseUrl = "https://zwyy.nyist.edu.cn";

    /** 单次 HTTP 超时（毫秒）。对话链路不能被拖住，所以给得比较短。 */
    private int timeoutMs = 5000;

    /** 向守护进程要 token 的超时（毫秒）。它在本机，应该很快。 */
    private int tokenTimeoutMs = 3000;

    /**
     * 写操作（预约/签退）允许执行的用户 id。
     *
     * <p><b>0 表示未配置，此时写工具一律拒绝执行</b>。宁可写明「没配置」，
     * 也不要给个默认值让写操作悄悄生效 —— 预约写错座位在图书馆系统里
     * 就是一次违约，直接影响信用。
     *
     * <p>只读工具不受这个限制（查询不会修改任何东西）；
     * 但写操作校验的是 {@code device.getUserId()}（设备归属人），
     * 不是前端传来的参数，所以伪造请求改不动这个值。
     *
     * <p>定稿口径（2026-11-24）：查询（含查询预约情况）人人可调用；
     * 预约/取消/临时离开/签退仅 admin（userId=1）可调用，即本项配置为 {@code 1}。
     */
    private long ownerUserId = 0L;

    /**
     * 写操作总开关。false 时所有写工具（prepare_* 与 confirm_action）不注册，
     * 只有查询工具可用。默认 false：写能力要等配置与测试都过了再显式打开。
     */
    private boolean writeEnabled = false;

    /** 写操作待确认（Redis）的存活秒数。默认 120 秒，够说一句"确认"，又不会留陈旧快照。 */
    private int pendingTtlSeconds = 120;
}