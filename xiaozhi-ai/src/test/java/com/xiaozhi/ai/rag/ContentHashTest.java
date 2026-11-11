package com.xiaozhi.ai.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 钉住内容指纹 {@link DocumentIngestService#computeContentHash(byte[])} 的契约。
 *
 * <p>为什么值得测：这个值进的是 {@code sys_knowledge_doc.contentHash}，
 * 上面压着唯一键 {@code uk_owner_hash(userId, contentHash)} ——
 * <b>指纹错了不是报错，是静默丢文档</b>。
 *
 * <p>它原来在 {@code md5Hex} 的 catch 里写了 {@code return String.valueOf(content.length)}，
 * 即"MD5 拿不到就把字节数当哈希"，指纹会退化成"只取决于文件大小"，
 * 于是两份同字节数、不同内容的文档被判定为同一篇，后者覆盖前者。
 * MD5 是 JCA 规范强制提供的算法，这条分支实际不会走到，但语义是错的，已改成抛异常。
 *
 * <p>这里能守住的是"指纹必须真正由内容决定"，守不住的是"MD5 不可用时是否抛异常"
 * （正常 JVM 上无法复现）—— 后者只能靠代码里的注释与 review 拦。
 */
class ContentHashTest {

    private static final byte[] CONTENT = "图书馆借阅规则：每人最多借 10 本。".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("指纹是 64 位小写十六进制（sha256 的外层没变）")
    void is64LowerHex() {
        assertThat(DocumentIngestService.computeContentHash(CONTENT))
                .matches("^[0-9a-f]{64}$");
    }

    @Test
    @DisplayName("同字节数、不同内容 → 指纹必须不同（原 fallback bug 的回归测试）")
    void sameLengthDifferentContentProducesDifferentHash() {
        byte[] a = new byte[5120];
        byte[] b = new byte[5120];
        Arrays.fill(a, (byte) 'a');
        Arrays.fill(b, (byte) 'b');

        // 前提：两者字节数确实一样，这正是原来 fallback"只看长度"会撞上的形态
        assertThat(a.length).isEqualTo(b.length);

        assertThat(DocumentIngestService.computeContentHash(a))
                .as("同字节数不同内容的指纹必须不同，否则会被唯一键误判为重复")
                .isNotEqualTo(DocumentIngestService.computeContentHash(b));
    }

    @Test
    @DisplayName("确定性：同样输入两次得到同样指纹")
    void isDeterministic() {
        assertThat(DocumentIngestService.computeContentHash(CONTENT))
                .isEqualTo(DocumentIngestService.computeContentHash(CONTENT));
    }

    @Test
    @DisplayName("改一个字，指纹就变")
    void singleByteChangeMatters() {
        byte[] mutated = CONTENT.clone();
        mutated[0] = (byte) (mutated[0] ^ 1);

        assertThat(DocumentIngestService.computeContentHash(mutated))
                .isNotEqualTo(DocumentIngestService.computeContentHash(CONTENT));
    }

    @Test
    @DisplayName("null 与空数组等价，都不抛异常")
    void nullEqualsEmpty() {
        assertThat(DocumentIngestService.computeContentHash(null))
                .isEqualTo(DocumentIngestService.computeContentHash(new byte[0]));
    }

    @Test
    @DisplayName("空内容也能算出合法指纹（不能是空串或异常）")
    void emptyContentStillProducesHash() {
        assertThat(DocumentIngestService.computeContentHash(new byte[0]))
                .matches("^[0-9a-f]{64}$");
    }
}