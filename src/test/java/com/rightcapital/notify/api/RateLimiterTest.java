package com.rightcapital.notify.api;

import com.rightcapital.notify.config.NotificationProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    private RateLimiter limiter(int capacity, int refillPerSecond) {
        NotificationProperties properties = new NotificationProperties(
                null, null, null,
                new NotificationProperties.Api(java.util.Map.of(),
                        new NotificationProperties.RateLimit(capacity, refillPerSecond)),
                null, null);
        return new RateLimiter(properties, new SimpleMeterRegistry());
    }

    @Test
    void 桶内令牌用尽后拒绝() {
        RateLimiter limiter = limiter(3, 0);
        assertThat(limiter.tryAcquire("sys_ads")).isTrue();
        assertThat(limiter.tryAcquire("sys_ads")).isTrue();
        assertThat(limiter.tryAcquire("sys_ads")).isTrue();
        assertThat(limiter.tryAcquire("sys_ads")).isFalse();
    }

    @Test
    void 不同caller互不影响() {
        RateLimiter limiter = limiter(1, 0);
        assertThat(limiter.tryAcquire("sys_ads")).isTrue();
        assertThat(limiter.tryAcquire("sys_ads")).isFalse();
        assertThat(limiter.tryAcquire("sys_crm")).isTrue(); // 独立桶
    }

    @Test
    void 随时间补充令牌() throws Exception {
        RateLimiter limiter = limiter(1, 50); // 50/s = 20ms 补 1 个
        assertThat(limiter.tryAcquire("sys_ads")).isTrue();
        assertThat(limiter.tryAcquire("sys_ads")).isFalse();
        Thread.sleep(40); // 足够补充 1 个令牌
        assertThat(limiter.tryAcquire("sys_ads")).isTrue();
    }

    @Test
    void retryAfter不小于1秒() {
        assertThat(limiter(1, 0).retryAfterSeconds()).isGreaterThanOrEqualTo(1);
    }
}
