package com.xiaozhi.ai.library;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 钉住预约 AES 的行为契约：key/iv 拼法 + 可被同参数解密（roundtrip）。
 *
 * <p>key = 当天 yyyyMMdd + 反转（2026-11-24 实测预约成功验证过的算法）；
 * iv = ZZWBKJ_ZHIHUAWEI（前端常量）。这里的"基准"就是独立解密实现，
 * 与 encrypt 走同一套标准 AES/CBC/PKCS5，互为校验。
 */
class LibraryCryptoTest {

    private static final String PLAIN =
            "{\"seat_id\":\"4025\",\"segment\":\"1457482\",\"day\":\"2026-11-25\",\"start_time\":\"\",\"end_time\":\"\"}";

    @Test
    @DisplayName("加密结果可被同参数独立解密还原")
    void roundtripWithIndependentDecrypt() throws Exception {
        String b64 = LibraryCrypto.encrypt(PLAIN, "2026-11-24");

        byte[] cipherBytes = Base64.getDecoder().decode(b64);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec("2026112442116202".getBytes(StandardCharsets.UTF_8), "AES"),
                new IvParameterSpec("ZZWBKJ_ZHIHUAWEI".getBytes(StandardCharsets.UTF_8)));
        String decrypted = new String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8);

        assertThat(decrypted).isEqualTo(PLAIN);
    }

    @Test
    @DisplayName("不同日期产生不同密文（key 含日期）")
    void differentDayDifferentCipher() {
        String a = LibraryCrypto.encrypt(PLAIN, "2026-11-24");
        String b = LibraryCrypto.encrypt(PLAIN, "2026-11-25");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("密文是合法 Base64 且非明文原样")
    void cipherIsBase64NotPlain() {
        String b64 = LibraryCrypto.encrypt(PLAIN, "2026-11-24");
        assertThat(b64).isNotEqualTo(PLAIN);
        assertThat(Base64.getDecoder().decode(b64)).isNotEmpty();
    }
}
