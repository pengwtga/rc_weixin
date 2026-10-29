package com.rightcapital.notify.common.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D10 载荷密封：可逆、随机 IV、密文与行绑定（AAD）。
 */
class PayloadCipherTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final PayloadCipher CIPHER = new PayloadCipher(KEY);

    @Test
    void 密封后可还原() {
        byte[] plain = "{\"phone\":\"13800138000\"}".getBytes(StandardCharsets.UTF_8);
        byte[] aad = PayloadCipher.aadFor("01JNO", "body");

        assertThat(CIPHER.open(CIPHER.seal(plain, aad), aad)).isEqualTo(plain);
    }

    @Test
    void 相同明文两次密封_密文不同_但都能还原() {
        byte[] plain = "same payload".getBytes(StandardCharsets.UTF_8);
        byte[] aad = PayloadCipher.aadFor("01JNO", "body");

        byte[] first = CIPHER.seal(plain, aad);
        byte[] second = CIPHER.seal(plain, aad);

        // 随机 IV：同一内容不会产生相同密文（否则密文相等本身就在泄漏"内容相同"这个事实）
        assertThat(first).isNotEqualTo(second);
        assertThat(CIPHER.open(first, aad)).isEqualTo(plain);
        assertThat(CIPHER.open(second, aad)).isEqualTo(plain);
    }

    @Test
    void 密文被改一个字节_解密失败() {
        byte[] plain = "payload".getBytes(StandardCharsets.UTF_8);
        byte[] aad = PayloadCipher.aadFor("01JNO", "body");
        byte[] sealed = CIPHER.seal(plain, aad);

        byte[] tampered = Arrays.copyOf(sealed, sealed.length);
        tampered[tampered.length - 1] ^= 0x01;

        assertThatThrownBy(() -> CIPHER.open(tampered, aad))
                .isInstanceOf(PayloadCipher.PayloadIntegrityException.class);
    }

    @Test
    void 换行搬运密文_AAD不匹配则解密失败() {
        byte[] plain = "payload".getBytes(StandardCharsets.UTF_8);
        byte[] sealed = CIPHER.seal(plain, PayloadCipher.aadFor("01ORDER_A", "body"));

        // 把 A 行的密文贴到 B 行：GCM 认证失败（这是 AAD 相对普通校验和多出来的保护）
        assertThatThrownBy(() -> CIPHER.open(sealed, PayloadCipher.aadFor("01ORDER_B", "body")))
                .isInstanceOf(PayloadCipher.PayloadIntegrityException.class);
        // 同一行但字段串了（body 的密文塞进 headers）同样失败
        assertThatThrownBy(() -> CIPHER.open(sealed, PayloadCipher.aadFor("01ORDER_A", "headers")))
                .isInstanceOf(PayloadCipher.PayloadIntegrityException.class);
    }

    @Test
    void 空体与null的区分() {
        byte[] aad = PayloadCipher.aadFor("01JNO", "body");

        assertThat(CIPHER.seal(null, aad)).isNull();                                        // 没有 body
        byte[] empty = CIPHER.seal(new byte[0], aad);
        assertThat(empty).isNotNull();                                                       // 空 body 是合法值
        assertThat(CIPHER.open(empty, aad)).isEmpty();
    }

    @Test
    void 密钥长度不是32字节_构造即失败() {
        assertThatThrownBy(() -> new PayloadCipher(new byte[16]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 字节");
    }
}
