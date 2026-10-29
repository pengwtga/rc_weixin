package com.rightcapital.notify.delivery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 投递线程池（T4.1 的执行载体）。
 *
 * <p>关键设计：<b>槽位与线程数是一回事，并且调度器必须能问出"还有几个空位"</b>。
 * 只用 {@link ThreadPoolExecutor} 的队列长度无法回答这个问题（队列里排队的任务
 * 已经占用了未来的线程）。因此额外用一个 {@link Semaphore} 显式表达"空闲槽位"：
 * 认领 N 条之前先拿到 N 个许可，认领多少就跑多少，不多认领一条。</p>
 *
 * <p>为什么要限制认领数量：认领即写租约、即 attempt_count+1。如果一次认领 100 条
 * 但只有 32 个线程，剩下 68 条会在租约里排队；一旦排队时间超过租约时长，
 * 它们会被别的实例回收并重复投递 —— 系统自己制造了重复。这就是 D4.5 说的
 * "batchSize 必须 ≤ 空闲 worker 数"，在这里落成代码而不是口号。</p>
 */
public class WorkerPool {

    private static final Logger log = LoggerFactory.getLogger(WorkerPool.class);

    private final ThreadPoolExecutor executor;
    private final Semaphore freeSlots;
    private final int poolSize;
    private final int shutdownAwaitSeconds;
    private volatile boolean shuttingDown;

    public WorkerPool(int poolSize, int shutdownAwaitSeconds) {
        this.poolSize = poolSize;
        this.shutdownAwaitSeconds = shutdownAwaitSeconds;
        this.freeSlots = new Semaphore(poolSize);
        AtomicInteger seq = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(
                poolSize, poolSize,
                60, TimeUnit.SECONDS,
                // 容量 = 池大小：正常情况下任务数不会超过许可数，队列只吸收瞬时抖动
                new LinkedBlockingQueue<>(poolSize),
                r -> {
                    Thread t = new Thread(r, "notify-worker-" + seq.incrementAndGet());
                    t.setDaemon(false);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
        this.executor.allowCoreThreadTimeOut(true);
    }

    /** 当前空闲槽位数。调度器用它决定这一轮最多认领多少条。 */
    public int freeSlots() {
        return freeSlots.availablePermits();
    }

    /**
     * 占用一个槽位。非阻塞 —— 调度线程不能因为池满而卡住，
     * 它需要立刻返回去算"这轮不认领了，退避多久"。
     */
    public boolean tryAcquireSlot() {
        return !shuttingDown && freeSlots.tryAcquire();
    }

    /**
     * 提交一个任务。调用前必须已成功 {@link #tryAcquireSlot()}；
     * 任务结束（无论成功/异常）自动归还槽位。
     *
     * @return 是否成功提交；false 表示池已关闭，调用方应立即归还槽位
     */
    public boolean submit(Runnable task) {
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } finally {
                    freeSlots.release();
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            log.warn("投递任务被拒绝（池已关闭）");
            return false;
        }
    }

    /** 归还一个未使用的槽位（提交失败时用）。 */
    public void releaseSlot() {
        freeSlots.release();
    }

    public int poolSize() {
        return poolSize;
    }

    public boolean isShuttingDown() {
        return shuttingDown;
    }

    /**
     * 优雅停机（T4.1）：停止接收新任务，等待在途投递完成。
     *
     * <p>为什么等而不是直接中断：一次已经发出的 HTTP 请求，对方可能已经处理了。
     * 强行中断只会让我们不知道结果，然后靠租约过期重投 —— 制造一次重复。
     * 等的上限是「HTTP 总超时 + 余量」，超过这个时间还没结束的，交给租约回收
     * 反而是正确的（那条 worker 大概率已经卡死）。</p>
     *
     * <p>不主动把 lease_until 拨到过去：那会让其他实例立刻接手，
     * 而此刻本进程的请求可能已经到达下游 —— 用租约自然过期换取"最多晚 60 秒"，
     * 比"立刻双投"划算。</p>
     */
    public void shutdownAndAwait() {
        shuttingDown = true;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(shutdownAwaitSeconds, TimeUnit.SECONDS)) {
                log.warn("优雅停机超时（{}s），仍有 {} 个任务在途，交由租约回收",
                        shutdownAwaitSeconds, poolSize - freeSlots.availablePermits());
                executor.shutdownNow();
            } else {
                log.info("投递线程池已停止，在途任务全部完成");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
