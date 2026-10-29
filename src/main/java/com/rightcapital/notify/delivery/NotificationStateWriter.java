package com.rightcapital.notify.delivery;

import com.rightcapital.notify.common.storage.NotificationRepository;
import com.rightcapital.notify.common.storage.NotificationRepository.NotificationRow;
import com.rightcapital.notify.config.NotificationProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 状态落定（T4.8）：把一次投递结果写回 notification 表。
 *
 * <p>这是"投递"与"调度"之间唯一的写入口。所有状态变更都必须经过这里，
 * 原因不是洁癖，而是三个不变量只能在一个地方保证：</p>
 * <ol>
 *   <li><b>租约守卫</b>：写入必须带 {@code lease_owner}，否则僵尸 worker 会覆盖接手者的结果；</li>
 *   <li><b>预算判定</b>：什么情况叫"用完了"（可重试但 attempt_count 已达 max_attempts → DEAD）
 *       必须与认领时的 +1 配对，散在别处一定会算错；</li>
 *   <li><b>清租约</b>：任何终态/等待态都必须把 lease 置空，否则这一行要等租约自然过期才被回收，
 *       白白多等 60 秒。</li>
 * </ol>
 */
public class NotificationStateWriter {

    private static final Logger log = LoggerFactory.getLogger(NotificationStateWriter.class);

    private final NotificationRepository repository;
    private final NotificationProperties properties;
    private final MeterRegistry meterRegistry;

    public NotificationStateWriter(NotificationRepository repository,
                                   NotificationProperties properties,
                                   MeterRegistry meterRegistry) {
        this.repository = repository;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    /** 成功落定。 */
    public void settleSucceeded(NotificationRow row, DeliveryResult result) {
        int updated = repository.finishSucceeded(row.id(), row.leaseOwner(), result.statusCode());
        finish(row, "SUCCEEDED", updated);
    }

    /**
     * 失败落定：按「可重试性 + 剩余预算」决定进 RETRY_WAIT 还是 DEAD。
     *
     * @param attemptCount 认领时自增后的值（本次尝试已计入）
     */
    public void settleFailure(NotificationRow row, DeliveryResult result, int attemptCount) {
        boolean budgetExhausted = attemptCount >= row.maxAttempts();
        if (!result.retryable() || budgetExhausted) {
            int updated = repository.finishDead(row.id(), row.leaseOwner(),
                    result.errorClass() == null ? null : result.errorClass().name(), result.statusCode());
            if (updated > 0 && result.retryable()) {
                // 预算耗尽而不是"永久失败"——这两者都进 DEAD，但原因不同，
                // 排查时一个看 max_attempts，一个看 4xx，日志里必须能分开
                log.warn("重试预算耗尽转入死信 no={} attempts={}/{} lastClass={}",
                        row.notificationNo(), attemptCount, row.maxAttempts(), result.errorClass());
            }
            finish(row, "DEAD", updated);
            return;
        }

        long delayMs = properties.retry().fixedDelaySeconds() * 1000L; // TODO(P5): 换成指数退避 + 抖动 + Retry-After 下界
        int updated = repository.finishRetry(row.id(), row.leaseOwner(), delayMs,
                result.errorClass() == null ? null : result.errorClass().name(), result.statusCode());
        finish(row, "RETRY_WAIT", updated);
    }

    /**
     * 没发出去就退回队列（host 并发满 / 熔断打开），并回退 attempt_count。
     *
     * <p>单独一个方法而不是复用 {@link #settleFailure}：语义完全不同 ——
     * 这里没有"失败"，只有"这次没轮到"。混用会让 attempt 计数虚高。</p>
     */
    public void requeueWithoutAttempt(NotificationRow row, String reason) {
        int updated = repository.requeueAndRefund(row.id(), row.leaseOwner(),
                properties.dispatch().requeueDelayMs());
        if (updated > 0) {
            counter("SKIPPED").increment();
            log.debug("跳过投递并退回队列 no={} reason={}", row.notificationNo(), reason);
        } else {
            zombie(row, "SKIPPED");
        }
    }

    private void finish(NotificationRow row, String status, int updated) {
        if (updated > 0) {
            counter(status).increment();
            log.info("投递落定 no={} status={}", row.notificationNo(), status);
        } else {
            zombie(row, status);
        }
    }

    /**
     * 影响 0 行 = 我的租约已经不是我的了。
     *
     * <p>这不是错误，而是设计内的正常竞争：另一个实例在我卡住时接管了这条任务，
     * 我的结果已经过期。丢弃它、记一笔指标，让运维能看到"僵尸投递率"——
     * 这个指标持续大于 0，说明租约时长与真实耗时不再匹配（业务变慢或 GC 变重）。
     * 刻意不重试写入：重试等于让过期结果覆盖新结果，把一次良性竞争变成数据错误。</p>
     */
    private void zombie(NotificationRow row, String attemptedStatus) {
        meterRegistry.counter("notification.delivery.zombie", "status", attemptedStatus).increment();
        log.warn("租约已失效，丢弃本次结果 no={} wanted={} owner={}",
                row.notificationNo(), attemptedStatus, row.leaseOwner());
    }

    private Counter counter(String status) {
        return meterRegistry.counter("notification.delivery.settled", "status", status);
    }
}
