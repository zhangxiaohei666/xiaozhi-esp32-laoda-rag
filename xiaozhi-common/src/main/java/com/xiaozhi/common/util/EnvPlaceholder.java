package com.xiaozhi.common.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 配置值里的「环境变量占位符」解析。
 *
 * <p>用途：让密钥只存在于环境变量中，数据库里只落占位符。例如
 * {@code sys_config.apiKey} 填 {@code ${deepseek-api}}，真实 Key 放在名为
 * {@code deepseek-api} 的环境变量里。
 *
 * <p>支持两种写法：
 * <ul>
 *   <li>{@code ${NAME}} —— 取环境变量；没有则取同名 JVM 系统属性；都没有则视为「没填」（返回 null）</li>
 *   <li>{@code ${NAME:默认值}} —— 取不到时用默认值（默认值里可以含冒号）</li>
 * </ul>
 *
 * <p>只有「整个值就是一个占位符」时才替换；其它情况原样返回，避免误伤正常配置，
 * 例如签名串、正则之类含 {@code ${}} 的值不会被改动。
 *
 * <p>注意：解析结果会随配置一起被缓存（见 CacheHelper），因此**改了环境变量的值
 * 需要重启进程或清缓存**才生效。
 */
public final class EnvPlaceholder {

    /** 形如 ${NAME} 或 ${NAME:default}，整体匹配（前后不允许有其它字符）。 */
    private static final Pattern FULL = Pattern.compile("^\\$\\{([^}:]+)(?::([^}]*))?}$");

    private EnvPlaceholder() {
    }

    /**
     * 解析单个配置值。
     *
     * @param value 原始配置值，允许为 null
     * @return 解析后的值；未定义且无默认值时返回 null（与「没填」语义一致）
     */
    public static String resolve(String value) {
        if (value == null) {
            return null;
        }
        Matcher m = FULL.matcher(value.trim());
        if (!m.matches()) {
            return value;
        }

        String name = m.group(1).trim();

        String fromEnv = System.getenv(name);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }

        String fromProp = System.getProperty(name);
        if (fromProp != null && !fromProp.isBlank()) {
            return fromProp.trim();
        }

        String defaultValue = m.group(2);
        if (defaultValue != null && !defaultValue.isBlank()) {
            return defaultValue.trim();
        }

        // 占位符存在但来源没配：当作「没填」，交由调用方走无密钥分支，
        // 不要把 ${...} 字面量当密钥发出去。
        return null;
    }

    /**
     * 批量解析，便于转换器里一行处理多个密钥字段。
     */
    public static String[] resolveAll(String... values) {
        String[] out = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = resolve(values[i]);
        }
        return out;
    }
}