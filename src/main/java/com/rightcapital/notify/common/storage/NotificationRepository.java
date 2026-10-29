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

    /** notification 表的行映射（字段与 V2 DDL 一一对应）。 */
    public record NotificationRow(
            long id,
            String notificationNo,
            String callerId,
            String idempotencyKey,
            String targetUrl,
            String httpMethod,
            String headersEnc,
            byte[] bodyEnc,
            String bodySha256,
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
                   headers_enc, body_enc, body_sha256, status, max_attempts, next_attempt_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                """,
                row.notificationNo(), row.callerId(), row.idempotencyKey(), row.targetUrl(), row.httpMethod(),
                row.headersEnc(), row.bodyEnc(), row.bodySha256(), row.maxAttempts(), row.nextAttemptAt());
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
