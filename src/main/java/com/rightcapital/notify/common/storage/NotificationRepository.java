package com.rightcapital.notify.common.storage;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * notification 表的数据访问层（T2.4/T2.5）。
 *
 * <p>所有状态变更 SQL 都必须带「WHERE status = 期望的当前状态」守卫：
 * 代码里的状态机校验（NotificationStatus）只是第一道闸，
 * 并发仲裁靠 DB 行级条件更新的影响行数。</p>
 *
 * <p>认领（claimDue）是整套系统的技术基石（D4），五个硬约束都体现在 SQL 里：
 * ① 派生表包一层 FOR UPDATE SKIP LOCKED（避开 MySQL 1093，且多实例零等待互不相交）；
 * ② 租约过期的 PROCESSING 与到期任务走同一个 WHERE（崩溃回收不是特殊路径）；
 * ③ 时间取 DB 的 NOW(3) 而非应用时钟；
 * ④ attempt_count 在认领时自增（毒消息保护）；
 * ⑤ 本方法 REQUIRES_NEW 独立短事务 —— 认领事务里绝不放 HTTP 调用。</p>
 */
@Repository
public class NotificationRepository {

    private static final RowMapper<NotificationRow> ROW_MAPPER = new DataClassRowMapper<>(NotificationRow.class);

    private final JdbcTemplate jdbc;

    public NotificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * notification 表的行映射（字段与 V4 迁移后的结构一致）。
     *
     * <p>{@code headersEnc} / {@code bodyEnc} 是 AES-256-GCM 密封后的二进制
     * （{@code [1B 版本][12B IV][密文+tag]}），不是明文 —— 名字里的 Enc 是承诺，不是修饰。
     * {@code bodyFingerprint} 是明文的 HMAC-SHA256（密钥化指纹），归档清空 body 后仍保留。</p>
     */
    public record NotificationRow(
            long id,
            String notificationNo,
            String callerId,
            String idempotencyKey,
            String targetUrl,
            String httpMethod,
            byte[] headersEnc,
            byte[] bodyEnc,
            String bodyFingerprint,
            String status,
            int attemptCount,
            int maxAttempts,
            int replayEpoch,
            Timestamp nextAttemptAt,
            String leaseOwner,
            Timestamp leaseUntil,
            String lastErrorClass,
            Integer lastStatusCode,
            Timestamp createdAt,
            Timestamp updatedAt) {
    }

    /** 插入一条 PENDING 通知。幂等冲突由 uk_idem 触发 DuplicateKeyException，由服务层查回（T3.3）。 */
    public void insertPending(NotificationRow row) {
        jdbc.update("""
                INSERT INTO notification
                  (notification_no, caller_id, idempotency_key, target_url, http_method,
                   headers_enc, body_enc, body_fingerprint, status, max_attempts, next_attempt_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                """,
                row.notificationNo(), row.callerId(), row.idempotencyKey(), row.targetUrl(), row.httpMethod(),
                row.headersEnc(), row.bodyEnc(), row.bodyFingerprint(), row.maxAttempts(), row.nextAttemptAt());
    }

    /** 按 (caller, idempotency_key) 查已有通知 —— 幂等冲突时返回原单号。 */
    public Optional<NotificationRow> findByIdempotencyKey(String callerId, String idempotencyKey) {
        return jdbc.query("""
                        SELECT * FROM notification WHERE caller_id = ? AND idempotency_key = ?
                        """, ROW_MAPPER, callerId, idempotencyKey)
                .stream().findFirst();
    }

    /** 按对外单号查询（管理/查询接口用）。 */
    public Optional<NotificationRow> findByNo(String notificationNo) {
        return jdbc.query("SELECT * FROM notification WHERE notification_no = ?", ROW_MAPPER, notificationNo)
                .stream().findFirst();
    }

    /**
     * 条件状态更新：只有当前状态与 expected 一致才生效（并发仲裁靠这里）。
     *
     * @return 实际更新的行数；0 表示状态已被并发修改，调用方必须放弃本次结果
     */
    public int updateStatusGuarded(long id, String expectedStatus, String targetStatus) {
        return jdbc.update("""
                UPDATE notification SET status = ? WHERE id = ? AND status = ?
                """, targetStatus, id, expectedStatus);
    }

    /**
     * 批量认领到期任务（T2.5，D4 核心实现）。
     *
     * <p>SQL 语义：
     * <ul>
     *   <li>分支一：PENDING / RETRY_WAIT 且 next_attempt_at 已到期 —— 正常调度；</li>
     *   <li>分支二：PROCESSING 且 lease_until 已过 —— 崩溃回收，与分支一同权认领（D4.4）；</li>
     *   <li>派生表 + FOR UPDATE SKIP LOCKED：多实例并发执行时行集互不相交、零等待；</li>
     *   <li>attempt_count 在认领时 +1：认领即消耗预算，卡死 worker 的任务不会无限循环。</li>
     * </ul></p>
     *
     * <p>lease_owner 写入「workerId#批次token」：UPDATE 之后需要一个 SELECT 取回本次认领的行，
     * 如果按 workerId 查会把该 worker 之前轮次仍持租约的行一起带回来 —— 并发下就重复投递了。
     * 批次 token 让 SELECT 精确圈定本次认领的行。</p>
     *
     * @param workerId 认领者标识（写入 lease_owner 前缀，供排查；不参与 CAS 判断）
     * @param leaseSeconds 租约时长；启动断言保证它 > HTTP 总超时 + 余量（D4.5）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<NotificationRow> claimDue(String workerId, int leaseSeconds, int batchSize) {
        String claimToken = workerId + "#" + com.rightcapital.notify.common.idgen.Ulid.next();
        jdbc.update("""
                UPDATE notification
                SET status = 'PROCESSING',
                    lease_owner = ?,
                    lease_until = DATE_ADD(UTC_TIMESTAMP(3), INTERVAL ? SECOND),
                    attempt_count = attempt_count + 1
                WHERE id IN (
                  SELECT id FROM (
                    SELECT id FROM notification
                    WHERE (status IN ('PENDING','RETRY_WAIT') AND next_attempt_at <= UTC_TIMESTAMP(3))
                       OR (status = 'PROCESSING' AND lease_until < UTC_TIMESTAMP(3))
                    ORDER BY next_attempt_at
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                  ) due
                )
                """, claimToken, leaseSeconds, batchSize);
        return jdbc.query("""
                SELECT * FROM notification
                WHERE status = 'PROCESSING' AND lease_owner = ?
                  AND lease_until >= UTC_TIMESTAMP(3)
                ORDER BY next_attempt_at
                """, ROW_MAPPER, claimToken);
    }

    /** 队列积压量：PENDING + RETRY_WAIT 行数（T3.6 背压守卫用）。 */
    public long countPending() {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE status IN ('PENDING','RETRY_WAIT')", Long.class);
        return n == null ? 0 : n;
    }

    // ───────────────────────── 投递侧（P4） ─────────────────────────

    /**
     * 按单号精确认领一条（T4.12 insert-then-hint 专用）。
     *
     * <p>与 {@link #claimDue} 的区别：不做批量扫描，直接命中主键/唯一键。
     * 受理成功后把单号塞进内存交接队列，调度器用它立刻认领 —— 省掉最长一个轮询间隔的延迟。
     * 状态守卫与租约写法必须和 claimDue 完全一致，否则两条路径会打架：
     * 同一条任务既可能被 hint 认领、也可能被轮询认领，靠的都是
     * {@code WHERE status IN ('PENDING','RETRY_WAIT') AND next_attempt_at <= now}。</p>
     *
     * <p>返回 empty 是正常情况（已被别的实例认领、或还在退避期），不是错误。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<NotificationRow> claimByNo(String notificationNo, String workerId, int leaseSeconds) {
        String claimToken = workerId + "#" + com.rightcapital.notify.common.idgen.Ulid.next();
        int updated = jdbc.update("""
                UPDATE notification
                SET status = 'PROCESSING',
                    lease_owner = ?,
                    lease_until = DATE_ADD(UTC_TIMESTAMP(3), INTERVAL ? SECOND),
                    attempt_count = attempt_count + 1
                WHERE notification_no = ?
                  AND status IN ('PENDING','RETRY_WAIT')
                  AND next_attempt_at <= UTC_TIMESTAMP(3)
                """, claimToken, leaseSeconds, notificationNo);
        if (updated == 0) {
            return Optional.empty();
        }
        return jdbc.query("""
                        SELECT * FROM notification WHERE notification_no = ? AND lease_owner = ?
                        """, ROW_MAPPER, notificationNo, claimToken)
                .stream().findFirst();
    }

    /**
     * 投递成功落定：SUCCEEDED + 清租约。
     *
     * <p><b>为什么要带 {@code lease_owner} 守卫</b>：如果一次投递耗时超过了租约时长
     * （GC 长停顿、网络黑洞），租约会被别的实例回收并重新投递，此时本 worker 手里的
     * 结果已经过期。不带守卫地写状态，就会出现"旧结果覆盖新结果"——
     * 比如新 worker 已经判定 DEAD，旧 worker 慢悠悠地写回 SUCCEEDED。
     * 返回 0 表示"我的租约已经不属于我了"，调用方必须丢弃本次结果并记一笔僵尸投递。</p>
     *
     * @return 影响行数；0 表示租约已失效，本次结果不得采信
     */
    public int finishSucceeded(long id, String leaseOwner, Integer statusCode) {
        return jdbc.update("""
                UPDATE notification
                SET status = 'SUCCEEDED',
                    lease_owner = NULL,
                    lease_until = NULL,
                    last_error_class = NULL,
                    last_status_code = ?
                WHERE id = ? AND status = 'PROCESSING' AND lease_owner = ?
                """, statusCode, id, leaseOwner);
    }

    /**
     * 投递失败但可重试：RETRY_WAIT + 安排下次时间 + 清租约。
     *
     * @param delayMs 距下次尝试的延迟（P4 为固定值，P5 换成指数退避 + 抖动）
     */
    public int finishRetry(long id, String leaseOwner, long delayMs, String errorClass, Integer statusCode) {
        return jdbc.update("""
                UPDATE notification
                SET status = 'RETRY_WAIT',
                    lease_owner = NULL,
                    lease_until = NULL,
                    last_error_class = ?,
                    last_status_code = ?,
                    next_attempt_at = DATE_ADD(UTC_TIMESTAMP(3), INTERVAL ? MICROSECOND)
                WHERE id = ? AND status = 'PROCESSING' AND lease_owner = ?
                """, errorClass, statusCode, delayMs * 1000L, id, leaseOwner);
    }

    /**
     * 投递失败且不可重试：DEAD + 清租约。
     *
     * <p>DEAD 不是"丢弃"，是"交给人的队列"：失败清单 API 可查、可单条/批量重放（P5）。
     * 系统不假装自己能处理 401（凭证过期）这类问题 —— 它只能尽快停止烧预算并通知人。</p>
     */
    public int finishDead(long id, String leaseOwner, String errorClass, Integer statusCode) {
        return jdbc.update("""
                UPDATE notification
                SET status = 'DEAD',
                    lease_owner = NULL,
                    lease_until = NULL,
                    last_error_class = ?,
                    last_status_code = ?
                WHERE id = ? AND status = 'PROCESSING' AND lease_owner = ?
                """, errorClass, statusCode, id, leaseOwner);
    }

    /**
     * 退回队列并<b>回退 attempt_count</b>（T4.9 / T5.7 共用）。
     *
     * <p>适用场景：抢不到 per-host 并发许可、或 host 处于熔断打开状态。
     * 这类"没发出去"的跳过<b>不是一次尝试</b> —— 如果照常计数，供应商挂 30 分钟，
     * 健康任务会被自己的调度逻辑耗成 DEAD，等对方恢复时任务已经死了。
     * 这是整个投递引擎里最容易写错的一处，所以单独一个方法、单独的测试。</p>
     *
     * <p>{@code GREATEST(attempt_count, 1) - 1} 而不是 {@code attempt_count - 1}：
     * 列是 SMALLINT <b>UNSIGNED</b>，减到 -1 会直接报 "Out of range value"，
     * 让一次本该无害的回退变成一条投递异常。</p>
     */
    public int requeueAndRefund(long id, String leaseOwner, long delayMs) {
        return jdbc.update("""
                UPDATE notification
                SET status = 'RETRY_WAIT',
                    attempt_count = GREATEST(attempt_count, 1) - 1,
                    lease_owner = NULL,
                    lease_until = NULL,
                    next_attempt_at = DATE_ADD(UTC_TIMESTAMP(3), INTERVAL ? MICROSECOND)
                WHERE id = ? AND status = 'PROCESSING' AND lease_owner = ?
                """, delayMs * 1000L, id, leaseOwner);
    }

    /** 测试辅助：清表（Testcontainers IT 用）。生产代码不得调用。 */
    void deleteAllForTest() {
        jdbc.update("DELETE FROM notification");
    }

    /** 测试辅助：把某条的租约拨到过去（崩溃回收用例）。 */
    void expireLeaseForTest(String notificationNo) {
        jdbc.update("UPDATE notification SET lease_until = DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 1 SECOND) "
                + "WHERE notification_no = ?", notificationNo);
    }
}
