package com.xiaozhi.ai.library;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 图书馆预约接口的 AES 加密。
 *
 * <p>学校前端对 {@code /v4/space/confirm} 的请求体做了对称加密：
 * {@code {"aesjson":"Base64(AES-CBC(明文))"}}，参数：
 * <ul>
 *   <li>算法：AES/CBC/PKCS5Padding</li>
 *   <li>key = 当天日期 {@code yyyyMMdd} + 它的反转串（16 字节），<b>用的是发起请求当天，不是预约目标日</b></li>
 *   <li>iv = 固定串 {@code ZZWBKJ_ZHIHUAWEI}（前端源码里的常量，已实测验证）</li>
 * </ul>
 *
 * <p>明文（按区域 reserveType 不同，字段不同，见 {@link LibraryApiClient.AreaMap}）：
 * <pre>{@code
 * reserveType=1 → {"seat_id":"4025","segment":"1457482","day":"2026-11-25","start_time":"","end_time":""}
 * reserveType=2 → start_time 空串，只填 end_time
 * reserveType=3 → start_time 与 end_time 都填
 * }</pre>
 *
 * <p>算法已用真实预约验证（2026-11-24 03:34 实测预约成功）。
 * 明文里<b>不含任何敏感信息</b>（只有座位与时段），加密只是学校前端的防盗用手段，
 * 不是安全边界——安全边界在 {@link LibraryOwnerGuard}（只允许 admin 触发写操作）。
 */
public final class LibraryCrypto {

    private static final String IV = "ZZWBKJ_ZHIHUAWEI";

    private LibraryCrypto() {}

    /**
     * @param plain 明文 JSON
     * @param day   发起请求当天（yyyy-MM-dd），决定加密 key
     * @return Base64 密文，可直接塞进 {@code {"aesjson":"..."}}
     * @throws LibraryUnavailableException 加密失败（理论上不会发生，防御性收敛）
     */
    public static String encrypt(String plain, String day) {
        try {
            String compact = day.replace("-", "");
            String key = compact + new StringBuilder(compact).reverse();
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"),
                    new IvParameterSpec(IV.getBytes(StandardCharsets.UTF_8)));
            byte[] out = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new LibraryUnavailableException("预约请求加密失败，稍后再试一次");
        }
    }
}
