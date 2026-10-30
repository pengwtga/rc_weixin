package com.rightcapital.notify.common.crypto;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

/**
 * 请求体 / 请求头的对称密封（D10：凭证与 PII 加密存储）。
 *
 * <p>选 AES-256-GCM 而不是"存明文 + 加密钥字段"的理由：GCM 是 AEAD，
 * 一次运算同时给机密性与完整性 —— 密文被改一个 bit，解密直接失败，
 * 不需要额外校验和字段。随机 IV 由每次密封自己生成，密文自带版本号前缀，
 * 为将来轮换密钥留位。</p>
 *
 * <p><b>为什么不用哈希（更不用 PBKDF2/加盐）来"保护"请求体</b>：这里要做的是<b>可逆解密</b>——
 * 投递时必须还原出原始字节才能发出去，任何哈希都不可逆，从原理上不满足。
 * 慢哈希（PBKDF2/bcrypt）是口令验证的工具：低熵输入、低频、一次性；本字段是
 * 每条消息都要解一次的高频可逆数据 —— 工具用错地方，比不加密更有害。</p>
 *
 * <p>存储格式：{@code [1B 版本][12B 随机IV][密文 || 16B GCM tag]}。</p>
 */
public final class PayloadCipher {

    /** 密文格式版本，用于将来无痛轮换算法/密钥。 */
    private static final byte FORMAT_VERSION = 1;
    /** GCM 推荐 96-bit IV —— 随机生成，命中重复的概率可忽略，无需计数器协调。 */
    private static final int IV_LENGTH = 12;
    /** 认证标签长度（bit）。 */
    private static final int TAG_LENGTH_BITS = 128;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;

    public PayloadCipher(byte[] keyBytes) {
        if (keyBytes == null || keyBytes.length != 32) {
            throw new IllegalArgumentException("AES-256 需要 32 字节密钥，实际 " +
                    (keyBytes == null ? 0 : keyBytes.length) + " 字节");
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * 构造附加认证数据（AAD）：「单号 + 字段名」。
     *
     * <p>把 AAD 绑定到行与字段，意味着把 A 行的密文搬到 B 行、或把 headers 的密文
     * 塞进 body 字段，解密都会失败 —— 这是 GCM 相对"明文 + 校验和"多出来的那层保护。
     * 受理与投递两侧都必须用同一个方法构造，否则解密失败。</p>
     */
    public static byte[] aadFor(String notificationNo, String field) {
        return (notificationNo + ":" + field).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 密封一段明文。
     *
     * @param plaintext 明文；null 原样返回 null（表示"没有这段数据"，与"空串"区分）
     * @param aad       附加认证数据。本项目传「单号 + 字段名」，
     *                  这样把 A 行的密文搬到 B 行会在解密时直接失败 —— 密文与行绑定
     * @return 可直接落 BLOB 的密封字节
     */
    public byte[] seal(byte[] plaintext, byte[] aad) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }
            byte[] ciphertext = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(1 + IV_LENGTH + ciphertext.length)
                    .put(FORMAT_VERSION)
                    .put(iv)
                    .put(ciphertext)
                    .array();
        } catch (GeneralSecurityException e) {
            // 密钥/算法在启动期已校验，走到这里说明 JVM 密码学提供者异常 —— 只能失败
            throw new IllegalStateException("AES-GCM 密封失败", e);
        }
    }

    /**
     * 还原明文。
     *
     * @throws PayloadIntegrityException 密文被篡改、AAD 不匹配（例如跨行搬运）、或密钥已轮换
     */
    public byte[] open(byte[] sealed, byte[] aad) {
        if (sealed == null) {
            return null;
        }
        if (sealed.length < 1 + IV_LENGTH + 16) {
            throw new PayloadIntegrityException("密文长度不足，无法包含 IV 与认证标签");
        }
        ByteBuffer buf = ByteBuffer.wrap(sealed);
        byte version = buf.get();
        if (version != FORMAT_VERSION) {
            throw new PayloadIntegrityException("密文格式版本不支持: " + version);
        }
        byte[] iv = new byte[IV_LENGTH];
        buf.get(iv);
        byte[] ciphertext = new byte[buf.remaining()];
        buf.get(ciphertext);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new PayloadIntegrityException("密文认证失败（被篡改 / AAD 不匹配 / 密钥不对）", e);
        }
    }

    /** 密文完整性校验失败。 */
    public static final class PayloadIntegrityException extends RuntimeException {
        public PayloadIntegrityException(String message) {
            super(message);
        }

        public PayloadIntegrityException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
