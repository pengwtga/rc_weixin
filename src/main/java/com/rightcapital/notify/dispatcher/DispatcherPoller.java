package com.rightcapital.notify.dispatcher;

import com.rightcapital.notify.common.storage.NotificationRepository;
import com.rightcapital.notify.common.storage.NotificationRepository.NotificationRow;
import com.rightcapital.notify.config.NotificationProperties;
import com.rightcapital.notify.delivery.DeliveryWorker;
import com.rightcapital.notify.delivery.NotificationStateWriter;
import com.rightcapital.notify.delivery.WorkerPool;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 调度器（T4.1 / T4.2 / T4.10 / T4.12）：决定"这一轮认领谁、认领几条"，然后把活交给线程池。
 *
 * <p>它自己<b>不投递</b>。这个边界很重要：调度是单线程的、必须快（几十毫秒），
 * 投递是阻塞的、可能耗 15 秒。混在一起，一次慢投递就会让整轮调度停摆。</p>
 *
 * <p>每轮三步：</p>
 * <ol>
 *   <li><b>先看 hint</b>（T4.12）：受理时塞进来的单号，直接认领，省掉一个轮询间隔；</li>
 *   <li><b>再扫描到期任务</b>：认领条数 = {@code min(batchSize, 空闲槽位)}（D4.5）；</li>
 *   <li><b>空轮询退避</b>（T4.10）：连续空转就把间隔翻倍到上限。注意 {@code @Scheduled}
 *       仍按基础间隔醒来，但只做一次时间比较、不碰数据库 —— 空转时 DB QPS 下降是真实可测的。</li>
 * </ol>
 */
@Component
public class DispatcherPoller {

    private static final Logger log = LoggerFactory.getLogger(DispatcherPoller.class);

    private final NotificationRepository repository;
    private final DeliveryWorker deliveryWorker;
    private final WorkerPool workerPool;
    private final DispatchHintQueue hintQueue;
    private final NotificationStateWriter stateWriter;
    private final NotificationProperties properties;
    private final WorkerIdentity identity;
    private final MeterRegistry meterRegistry;

    /**
     * 是否自动起跑。
     *
     * <p>生产恒为 true。存在的理由是集成测试：一个 {@code @SpringBootTest} 会把整个
     * 应用（包括这个调度器）拉起来，后台线程会去认领测试刚插入的行、真发 HTTP、
     * 把断言用的数据改成 DEAD —— 测试就会以一种极难排查的方式失败
     * （P4 开发时确实踩到了：并发认领用例里 25 条记录莫名变成 DEAD，
     * 因为它们真的被投递了 10 次）。</p>
     *
     * <p>不加进 {@code NotificationProperties}：它不影响任何投递语义，
     * 只决定"这个进程要不要自己起跑"，属于测试/运维开关，不是业务参数。</p>
     */
    private final boolean autoStart;

    /** 停机标志：一旦置位，不再认领新任务（在途的照常完成）。 */
    private volatile boolean shuttingDown;

    /** 下一次真正查库的时刻。@Scheduled 醒来先比这个时间，避免空转打库。 */
    private volatile long nextPollAtMillis;

    /** 当前退避间隔；拿到任务后立刻回落到基础间隔。 */
    private volatile long currentBackoffMs;

    public DispatcherPoller(NotificationRepository repository,
                            DeliveryWorker deliveryWorker,
                            WorkerPool workerPool,
                            DispatchHintQueue hintQueue,
                            NotificationStateWriter stateWriter,
                            NotificationProperties properties,
                            WorkerIdentity identity,
                            MeterRegistry meterRegistry,
                            @org.springframework.beans.factory.annotation.Value(
                                    "${notification.dispatch.auto-start:true}") boolean autoStart) {
        this.repository = repository;
        this.deliveryWorker = deliveryWorker;
        this.workerPool = workerPool;
        this.hintQueue = hintQueue;
        this.stateWriter = stateWriter;
        this.properties = properties;
        this.identity = identity;
        this.meterRegistry = meterRegistry;
        this.autoStart = autoStart;
        this.currentBackoffMs = properties.dispatch().pollIntervalMs();
    }

    /**
     * 定时入口。fixedDelay 用配置值，保证"配置改了间隔真的会变"；
     * 退避逻辑在方法内部，所以这里不需要动态调度器。
     */
    @Scheduled(fixedDelayString = "${notification.dispatch.poll-interval-ms:500}")
    public void pollTick() {
        if (!autoStart) {
            return;
        }
        if (System.currentTimeMillis() < nextPollAtMillis) {
            return;
        }
        pollOnce();
    }

    /**
     * 执行一轮调度。
     *
     * <p>异常绝不能逃出这个方法：{@code @Scheduled} 遇到未捕获异常会记一条日志然后
     * <b>继续下一次</b>，看似无害，但如果异常发生在提交任务之后、退避之前，
     * 这一轮的状态就丢了。自己兜住并计数，运维才能从指标上看到"调度在报错"。</p>
     *
     * @return 本轮成功提交的投递数
     */
    public int pollOnce() {
        if (shuttingDown) {
            return 0;
        }
        int dispatched = 0;
        try {
            dispatched += dispatchHints();
            dispatched += dispatchDue();
        } catch (RuntimeException e) {
            meterRegistry.counter("notification.dispatch.error").increment();
            log.error("调度轮次失败，本轮已跳过（下一轮继续）", e);
        }

        if (dispatched == 0) {
            backoff();
        } else {
            resetBackoff();
        }
        return dispatched;
    }

    /**
     * 处理 hint（T4.12）：按单号精确认领。
     *
     * <p>认领失败是常态而不是异常 —— 这条可能已被别的实例抢走，或者还在退避期
     * （比如重试任务的 hint 早已过期）。直接跳过，把槽位还回去。</p>
     */
    private int dispatchHints() {
        int free = workerPool.freeSlots();
        if (free <= 0) {
            return 0;
        }
        List<String> candidates = hintQueue.drain(Math.min(free, properties.dispatch().batchSize()));
        int dispatched = 0;
        for (String notificationNo : candidates) {
            if (!workerPool.tryAcquireSlot()) {
                break;
            }
            Optional<NotificationRow> claimed = repository.claimByNo(
                    notificationNo, identity.value(), properties.dispatch().leaseDurationSeconds());
            if (claimed.isEmpty()) {
                workerPool.releaseSlot();
                continue;
            }
            if (submit(claimed.get())) {
                dispatched++;
            } else {
                workerPool.releaseSlot();
            }
        }
        return dispatched;
    }

    /**
     * 批量认领到期任务。
     *
     * <p>{@code batch = min(batchSize, 空闲槽位)} 是 D4.5 的落地点：
     * 多认领一条，就多一条在租约里排队、到期被重复投递的任务。</p>
     */
    private int dispatchDue() {
        int free = workerPool.freeSlots();
        int batch = Math.min(properties.dispatch().batchSize(), free);
        if (batch <= 0) {
            return 0;
        }
        List<NotificationRow> rows = repository.claimDue(
                identity.value(), properties.dispatch().leaseDurationSeconds(), batch);
        int dispatched = 0;
        for (NotificationRow row : rows) {
            if (!workerPool.tryAcquireSlot()) {
                // 理论上不会发生（batch ≤ free 且调度单线程），但并发下"理论"不值钱：
                // 拿不到槽位就把这条退回队列，别让它占着租约空转 60 秒
                stateWriter.requeueWithoutAttempt(row, "无空闲槽位");
                continue;
            }
            if (submit(row)) {
                dispatched++;
            } else {
                workerPool.releaseSlot();
            }
        }
        if (!rows.isEmpty()) {
            meterRegistry.counter("notification.dispatch.claimed").increment(rows.size());
        }
        return dispatched;
    }

    private boolean submit(NotificationRow row) {
        return workerPool.submit(() -> deliveryWorker.deliver(row));
    }

    /** 空轮询：间隔翻倍，封顶在 idleBackoffMaxMs。 */
    private void backoff() {
        currentBackoffMs = Math.min(currentBackoffMs * 2, properties.dispatch().idleBackoffMaxMs());
        nextPollAtMillis = System.currentTimeMillis() + currentBackoffMs;
        meterRegistry.counter("notification.dispatch.idle_backoff").increment();
        log.debug("队列为空，轮询退避 {}ms", currentBackoffMs);
    }

    private void resetBackoff() {
        currentBackoffMs = properties.dispatch().pollIntervalMs();
        nextPollAtMillis = 0;
    }

    /**
     * 停机（T4.1）：先关闸，再等在途投递结束。
     *
     * <p>顺序不能反：先让线程池停，调度器还可能在这一瞬间认领新任务，
     * 然后发现池已关闭 —— 那些任务会带着 attempt_count+1 退回队列。
     * 虽然最终结果正确，但会凭空消耗一次重试预算。</p>
     */
    @PreDestroy
    public void shutdown() {
        shuttingDown = true;
        int inFlight = workerPool.poolSize() - workerPool.freeSlots();
        log.info("调度器停机中：workerId={} 在途={} 条 hint={} 条",
                identity.value(), inFlight, hintQueue.size());
        workerPool.shutdownAndAwait();
    }

    /** 供测试与运维端点读取当前退避间隔。 */
    long currentBackoffMs() {
        return currentBackoffMs;
    }
}
