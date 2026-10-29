package com.rightcapital.notify.common.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D10.3 指纹的三条硬性质：确定性、密钥相关、不等于裸摘要。
 *
 * <p>这三条正是"能不能用 PBKDF2 + 随机盐"的判断依据 —— 盐会让第一条失效，
 * 迭代次数会让热路径成本失控，而第二、三条用 HMAC 就能拿到。</p>
 */
class ContentFingerprintTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final ContentFingerprint FP = new ContentFingerprint(KEY);

    @Test
    void 相同内容_指纹稳定_这是归档清空body后仍可比对的前提() {
        byte[] body = "{\"status\":\"paid\"}".getBytes(StandardCharsets.UTF_8);

        assertThat(FP.of(body)).isEqualTo(FP.of(body));
        // 随机盐会破坏这一条 —— 同一份内容两次得到不同值，指纹就失去"比对"的意义
    }

    @Test
    void 不同内容_指纹不同() {
        assertThat(FP.of("a".getBytes(StandardCharsets.UTF_8)))
                .isNotEqualTo(FP.of("b".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void 换密钥_同一内容得到不同指纹_低熵内容也推不出来() {
        byte[] lowEntropy = "{\"status\":\"paid\"}".getBytes(StandardCharsets.UTF_8);
        ContentFingerprint other = new ContentFingerprint(
                "ffffffffffffffffffffffffffffffff".getBytes(StandardCharsets.UTF_8));

        // 拿到数据库但没有密钥的人，无法用"猜候选明文 + 算摘要"来还原低熵 body
        assertThat(FP.of(lowEntropy)).isNotEqualTo(other.of(lowEntropy));
    }

    @Test
    void 指纹不等于裸SHA256_这正是本次修正的漏洞() throws Exception {
        byte[] body = "{\"status\":\"paid\"}".getBytes(StandardCharsets.UTF_8);
        String bare = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));

        assertThat(FP.of(body)).isNotEqualTo(bare);
    }

    @Test
    void 输出恒为64位小写十六进制() {
        assertThat(FP.of("x".getBytes(StandardCharsets.UTF_8))).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void 空体与null得到同一个稳定指纹() {
        // 语义上"没有 body"与"空 body"在投递时都发空载荷，指纹保持一致可避免两种记录
        assertThat(FP.of(null)).isEqualTo(FP.of(new byte[0]));
    }
}
