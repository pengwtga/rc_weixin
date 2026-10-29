package com.rightcapital.notify.api;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import org.springframework.stereotype.Component;

import com.rightcapital.notify.config.NotificationProperties;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 caller 的令牌桶限流（T3.5）。
 *
 * <p>V0 用进程内单例桶：每 caller 独立桶（隔离，互不拖累），
 * 恒定速率补充（refillPerSecond），桶深容忍脉冲（capacity）。
 * 多实例部署时这是每实例配额 —— 总配额 = 单实例 × 实例数，
 * 够用（§8：峰值 170 QPS 全局），精确的分布式配额等真实需要再上 Redis。</p>
 */
@Component
public class RateLimiter {

    private final int capacity;
    private final double refillPerSecond;
    private final MeterRegistry meterRegistry;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(NotificationProperties properties, MeterRegistry meterRegistry) {
        this.capacity = properties.api().rateLimit().capacity();
        this.refillPerSecond = properties.api().rateLimit().refillPerSecond();
        this.meterRegistry = meterRegistry;
    }

    /**
     * 尝试取一个令牌。
     *
     * @param callerId 调用方（限流桶的键）
     * @return true = 放行；false = 超限，调用方应返回 429
     */
    public boolean tryAcquire(String callerId) {
        long now = System.nanoTime();
        Bucket bucket = buckets.computeIfAbsent(callerId, k -> new Bucket(capacity));
        synchronized (bucket) {
            refill(bucket, now);
            if (bucket.tokens >= 1) {
                bucket.tokens -= 1;
                return true;
            }
            counter(callerId, "rate_limit_rejected").increment();
            return false;
        }
    }

    /** 超限时建议调用方等待的秒数（Retry-After 响应头的值）。 */
    public int retryAfterSeconds() {
        return (int) Math.max(1, Math.ceil(1.0 / refillPerSecond));
    }

    private void refill(Bucket bucket, long nowNanos) {
        double elapsedSeconds = (nowNanos - bucket.lastRefillNanos) / 1_000_000_000.0;
        if (elapsedSeconds > 0) {
            bucket.tokens = Math.min(capacity, bucket.tokens + elapsedSeconds * refillPerSecond);
            bucket.lastRefillNanos = nowNanos;
        }
    }

    private Counter counter(String callerId, String event) {
        return Counter.builder("notification.api.events")
                .tag("caller", callerId)
                .tag("event", event)
                .register(meterRegistry);
    }

    private static final class Bucket {
        double tokens;
        long lastRefillNanos;

        Bucket(int capacity) {
            this.tokens = capacity; // 初始满桶：重启后不惩罚第一批正常请求
            this.lastRefillNanos = System.nanoTime();
        }
    }
}
