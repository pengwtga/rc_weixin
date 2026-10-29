package com.rightcapital.notify.common.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * 请求体指纹（HMAC-SHA256，十六进制 64 字符）。
 *
 * <p><b>为什么是 HMAC 而不是裸 SHA-256</b>：这里对外暴露的明文熵经常很低——
 * 库存回调可能只是 {@code {"sku":"A-100","delta":-10}}，付款回调可能只有
 * {@code {"status":"paid"}}。如果指纹是裸 SHA-256，任何拿到数据库的人都能
 * 对候选明文做离线字典/暴力比对，反推出业务内容（裸摘要对低熵输入等价于可逆）。
 * 加一把只有应用知道的密钥后，攻击者没有密钥就无法构造候选摘要 ——
 * 这是"盐"想解决的问题，但用 <b>keyed hash</b> 解决，代价只有一次 HMAC。</p>
 *
 * <p><b>为什么不用 PBKDF2 + 随机盐</b>：盐是为了让相同口令产生不同摘要（防预计算表）。
 * 而本字段的用途恰恰是<b>相同明文必须得到相同指纹</b>——归档清除 body 之后，
 * 要靠它回答"当时发出去的到底是不是这份内容"。随机盐直接破坏这个用途；
 * 210k 次迭代则把每次受理的 CPU 从 0.001ms 拉到 30ms（本机实测 28803 倍），
 * 150 QPS 下纯哈希要吃掉 4.6 个核。慢哈希适合"验证一个人输入的口令"，
 * 不适合"每条消息都算一次的可比对指纹"。</p>
 *
 * <p>职责分工：{@link PayloadCipher} 负责机密性（可逆），本类负责可比对性（不可逆、密钥化）。
 * 两者用不同密钥，避免一处泄漏同时打到两个属性。</p>
 */
public final class ContentFingerprint {

    private final SecretKeySpec key;

    public ContentFingerprint(byte[] keyBytes) {
        if (keyBytes == null || keyBytes.length != 32) {
            throw new IllegalArgumentException("指纹密钥需要 32 字节，实际 " +
                    (keyBytes == null ? 0 : keyBytes.length) + " 字节");
        }
        this.key = new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    /** 计算指纹；null / 空体也返回稳定值（空体的指纹是 HMAC("")，不是 null）。 */
    public String of(byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(data == null ? new byte[0] : data));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 不可用", e);
        }
    }
}
