package com.rightcapital.notify.api;

import com.rightcapital.notify.common.storage.NotificationRepository;
import com.rightcapital.notify.config.NotificationProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import org.springframework.stereotype.Component;

/**
 * 队列健康度守卫（T3.6）：PENDING+RETRY_WAIT 积压超过阈值时对新请求返回 503。
 *
 * <p>为什么不放行：「可靠」的前提是队列本身健康。积压超阈值意味着投递速度
 * 跟不上，此时继续接单只会让所有调用方的延迟一起恶化 —— 提前拒绝
 * 让调用方走自己的降级路径（重试/暂存），比无限排队诚实。
 * 阈值取值依据 §8：worker 池 200 × 保守周转 250 秒 ≈ 5 万条安全水位。</p>
 */
@Component
public class BackpressureGuard {

    private final NotificationRepository repository;
    private final int maxPending;
    private final MeterRegistry meterRegistry;

    public BackpressureGuard(NotificationRepository repository,
                             NotificationProperties properties,
                             MeterRegistry meterRegistry) {
        this.repository = repository;
        this.maxPending = properties.backpressure().maxPending();
        this.meterRegistry = meterRegistry;
    }

    /** @return true = 队列健康，可接单；false = 背压生效，应返回 503。 */
    public boolean accept() {
        long pending = repository.countPending();
        if (pending >= maxPending) {
            counter("backpressure_rejected").increment();
            return false;
        }
        return true;
    }

    /** 建议的 Retry-After 秒数：按当前积压量线性估计排空时间。 */
    public int retryAfterSeconds() {
        long pending = repository.countPending();
        long drainPerSecond = 50; // 与 worker 池/单请求耗时的保守匹配（§8），V0 取常数
        return (int) Math.min(300, Math.max(5, pending / drainPerSecond));
    }

    private Counter counter(String event) {
        return Counter.builder("notification.api.events")
                .tag("caller", "system")
                .tag("event", event)
                .register(meterRegistry);
    }
}
