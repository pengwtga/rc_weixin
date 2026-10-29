package com.rightcapital.notify.common.idgen;

import java.util.concurrent.ThreadLocalRandom;

/**
 * ULID 生成器（T3.4：notification_no / traceId 的来源）。
 *
 * <p>为什么不用 UUID：notification_no 要出现在 URL 路径、日志和 DB 唯一索引里，
 * ULID 有序（时间前缀）+ 26 字符定长 + Crockford Base32（无易混字符 0/O、1/I/L），
 * 对 B+ 树插入友好，也方便人眼按时间归组排查。</p>
 *
 * <p>实现说明：48bit 毫秒时间戳 + 80bit 随机数。单实例同毫秒内不保证严格单调
 * （V0 不做单机单调锁），跨实例排序更无从谈起 —— 好在这不是需求：
 * 唯一性由 uk_no 兜底，顺序语义已明确不提供（§3.5）。</p>
 */
public final class Ulid {

    private static final char[] ENCODING = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private Ulid() {
    }

    /** 生成 26 字符大写 ULID。 */
    public static String next() {
        return of(System.currentTimeMillis());
    }

    static String of(long timestampMillis) {
        if (timestampMillis < 0 || timestampMillis >= 1L << 48) {
            throw new IllegalArgumentException("时间戳超出 ULID 48bit 范围: " + timestampMillis);
        }
        // 80bit 随机数拆成 high(30bit) + low(50bit)
        long randomHigh = ThreadLocalRandom.current().nextLong(1L << 30);
        long randomLow = ThreadLocalRandom.current().nextLong(1L << 50);

        char[] out = new char[26];
        long ts = timestampMillis;
        for (int i = 9; i >= 0; i--) {          // 10 字符时间戳（48bit）
            out[i] = ENCODING[(int) (ts & 0x1F)];
            ts >>>= 5;
        }
        long high = randomHigh;
        long low = randomLow;
        for (int i = 25; i >= 16; i--) {        // 随机段低 50bit（10 字符）
            out[i] = ENCODING[(int) (low & 0x1F)];
            low >>>= 5;
        }
        for (int i = 15; i >= 10; i--) {        // 随机段高 30bit（6 字符）
            out[i] = ENCODING[(int) (high & 0x1F)];
            high >>>= 5;
        }
        return new String(out);
    }
}
