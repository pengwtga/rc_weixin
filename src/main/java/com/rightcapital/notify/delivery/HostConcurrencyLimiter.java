package com.rightcapital.notify.delivery;

import java.net.URI;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * per-host 并发隔离（T4.9）。
 *
 * <p>没有这一层会发生什么：某家供应商的接口从 200ms 变成 30s，本服务的 worker 线程
 * 会一条条堵在它身上。线程池是全局的，堵满之后**其他所有供应商的通知一起停摆** ——
 * 一个下游的故障被放大成整个平台不可用。这是"共享线程池 + 慢下游"的经典事故形态，
 * 不是理论风险。</p>
 *
 * <p>语义是「不等待」：拿不到许可就立刻返回 false，由调度层把任务退回队列（并回退
 * attempt_count），而不是让 worker 线程原地排队。排队会让线程数失去意义 ——
 * 32 个线程可能全在等同一家供应商。</p>
 */
public class HostConcurrencyLimiter {

    private final int permitsPerHost;
    /** 每个 host 一个信号量。供应商数量有限（数十家），不需要淘汰。 */
    private final ConcurrentHashMap<String, Semaphore> permits = new ConcurrentHashMap<>();

    public HostConcurrencyLimiter(int permitsPerHost) {
        if (permitsPerHost <= 0) {
            throw new IllegalArgumentException("per-host 并发上限必须 > 0");
        }
        this.permitsPerHost = permitsPerHost;
    }

    /** 尝试获取一个该 host 的并发许可；不阻塞。 */
    public boolean tryAcquire(String url) {
        return permits.computeIfAbsent(hostOf(url), h -> new Semaphore(permitsPerHost))
                .tryAcquire();
    }

    /** 归还许可（必须在 finally 里调用，否则该 host 的容量会永久泄漏）。 */
    public void release(String url) {
        Semaphore semaphore = permits.get(hostOf(url));
        if (semaphore != null) {
            semaphore.release();
        }
    }

    /** 当前某个 host 已占用的许可数（仅用于指标与测试断言）。 */
    int inUse(String url) {
        Semaphore semaphore = permits.get(hostOf(url));
        return semaphore == null ? 0 : permitsPerHost - semaphore.availablePermits();
    }

    /**
     * 提取 host 作为隔离维度。
     *
     * <p>按 host 而不是按完整 URL：同一家供应商的不同路径共享同一个下游进程，
     * 按路径隔离等于没隔离（对方仍然被我们打满）。</p>
     */
    static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "unknown" : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return "unknown";
        }
    }
}
