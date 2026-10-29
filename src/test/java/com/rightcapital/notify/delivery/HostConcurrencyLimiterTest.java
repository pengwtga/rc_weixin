package com.rightcapital.notify.delivery;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4.9 DoD：单 host 达上限时其他 host 不受影响。
 * 这条"证伪测试"是这一层存在的唯一理由 —— 如果隔离不生效，它就只是白加了信号量。
 */
class HostConcurrencyLimiterTest {

    @Test
    void 同一host达到上限后拒绝_其他host不受影响() {
        HostConcurrencyLimiter limiter = new HostConcurrencyLimiter(2);
        String slow = "https://slow.vendor.example.com/hook";
        String other = "https://other.vendor.example.com/hook";

        assertThat(limiter.tryAcquire(slow)).isTrue();
        assertThat(limiter.tryAcquire(slow)).isTrue();
        assertThat(limiter.tryAcquire(slow)).isFalse(); // 慢的这家满了

        // 关键断言：另一家完全不受影响（否则一个慢下游会拖垮全平台）
        assertThat(limiter.tryAcquire(other)).isTrue();
        assertThat(limiter.inUse(slow)).isEqualTo(2);
        assertThat(limiter.inUse(other)).isEqualTo(1);
    }

    @Test
    void 归还有许可后可再次获取() {
        HostConcurrencyLimiter limiter = new HostConcurrencyLimiter(1);
        String url = "https://vendor.example.com/a";
        assertThat(limiter.tryAcquire(url)).isTrue();
        assertThat(limiter.tryAcquire(url)).isFalse();
        limiter.release(url);
        assertThat(limiter.tryAcquire(url)).isTrue();
    }

    @Test
    void 同一host不同路径共享配额() {
        // 按 host 隔离而不是按 URL：同一家供应商的不同路径打的是同一个下游进程
        HostConcurrencyLimiter limiter = new HostConcurrencyLimiter(1);
        assertThat(limiter.tryAcquire("https://vendor.example.com/a")).isTrue();
        assertThat(limiter.tryAcquire("https://vendor.example.com/b")).isFalse();
    }

    @Test
    void host大小写与端口不影响隔离维度() {
        assertThat(HostConcurrencyLimiter.hostOf("https://VENDOR.example.com:8443/a"))
                .isEqualTo("vendor.example.com");
    }

    @Test
    void 非法URL归入unknown桶而不是抛异常() {
        assertThat(HostConcurrencyLimiter.hostOf("not a url")).isEqualTo("unknown");
    }
}
