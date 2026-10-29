package com.rightcapital.notify.dispatcher;

/**
 * 本实例的调度身份，写入 {@code notification.lease_owner} 前缀。
 *
 * <p>由「主机名前 12 字符 + 进程号」组成，例如 {@code MacBook-Pro-3-4711}。
 * 为什么不是随机 UUID：租约字段是排查时的第一手线索，看到
 * {@code MacBook-Pro-3-4711#01JB...} 能直接知道是哪台机器的哪个进程；
 * 随机串只能证明"不是我"。</p>
 *
 * <p><b>长度受数据库约束</b>：{@code lease_owner} 是 VARCHAR(64)，
 * 而认领时写入的是 {@code workerId + "#" + ULID(26)}。因此 workerId 必须 ≤ 30，
 * 否则严格模式下会报 "Data too long" —— 一个启动时才发现、运行时才炸的坑，
 * 所以在这里直接断言。</p>
 */
public record WorkerIdentity(String value) {

    private static final int MAX_LENGTH = 30;

    public WorkerIdentity {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("workerId 不能为空");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "workerId 不得超过 %d 字符（lease_owner = workerId + '#' + ULID(26) ≤ 64），实际 %d: %s"
                            .formatted(MAX_LENGTH, value.length(), value));
        }
    }
}
