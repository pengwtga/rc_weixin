package com.rightcapital.notify.common.storage;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;

/**
 * 逐次投递明细（T4.7）。
 *
 * <p>这张表只追加、不更新。它要回答的问题不是"现在什么状态"（那在 notification 表），
 * 而是"过去 10 分钟到底发生了什么"——供应商说没收到，我们说发了，靠它对齐时间线。</p>
 *
 * <p>{@code attempt_no} 是「本轮内第几次」，用 {@code MAX+1} 现算而不是从
 * {@code notification.attempt_count} 抄：后者的语义是「累计消耗的预算」，
 * 会因为熔断回退（{@link NotificationRepository#requeueAndRefund}）而减小，
 * 拿它当序号会产生重复键。**两个计数器的用途不同，不能互相替代**。</p>
 */
@Repository
public class DeliveryAttemptRepository {

    /** 与 notification.replay_epoch 对应；P4 阶段恒为 0，P5 重放后才会变化。 */
    private static final int DEFAULT_REPLAY_EPOCH = 0;

    private final JdbcTemplate jdbc;

    public DeliveryAttemptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记一次尝试，序号自动取「本轮最大值 + 1」。
     *
     * <p>理论上同一时刻只有一个 worker 持有该行的租约，不会并发写同一个
     * {@code (notification_id, replay_epoch)}。但租约过期后可能出现「僵尸 worker 与
     * 接手 worker 同时写」的窗口，因此对唯一键冲突做一次重算重试 —— 让明细表
     * 在异常路径下也不会丢记录。</p>
     */
    public void append(long notificationId, Timestamp startedAt, Long durationMs,
                       Integer statusCode, String errorClass, String responseSnippet) {
        for (int round = 0; round < 3; round++) {
            int attemptNo = nextAttemptNo(notificationId, DEFAULT_REPLAY_EPOCH);
            try {
                jdbc.update("""
                        INSERT INTO delivery_attempt
                          (notification_id, replay_epoch, attempt_no, started_at,
                           duration_ms, status_code, error_class, response_snippet)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        notificationId, DEFAULT_REPLAY_EPOCH, attemptNo, startedAt,
                        durationMs, statusCode, errorClass, responseSnippet);
                return;
            } catch (DuplicateKeyException e) {
                // 序号被并发抢走，重新算一次
            }
        }
        // 三次都撞键说明有别的东西在按同一 epoch 写这张表（数据异常）。
        // 明细缺失不该阻断投递主流程，但必须留下痕迹。
        throw new IllegalStateException(
                "delivery_attempt 连续 3 次序号冲突 notificationId=" + notificationId);
    }

    private int nextAttemptNo(long notificationId, int replayEpoch) {
        Integer max = jdbc.queryForObject("""
                SELECT COALESCE(MAX(attempt_no), 0) FROM delivery_attempt
                WHERE notification_id = ? AND replay_epoch = ?
                """, Integer.class, notificationId, replayEpoch);
        return (max == null ? 0 : max) + 1;
    }

    /** 某条通知的尝试次数（测试与状态详情接口用）。 */
    public int countByNotificationId(long notificationId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM delivery_attempt WHERE notification_id = ?", Integer.class, notificationId);
        return n == null ? 0 : n;
    }
}
