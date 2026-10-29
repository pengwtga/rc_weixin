package com.rightcapital.notify.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 关键参数集中管理（T1.3：代码内无魔法数）。
 *
 * <p>所有运行时可调参数从这里读，且这里承载一条启动期硬断言（D4.5）：
 * 租约时长必须大于 HTTP 总超时 + 余量，否则 worker 卡在慢请求上时
 * 租约先过期、另一实例重复认领 —— 双投率暴涨。宁可在启动时失败，不在运行时丢正确性。</p>
 */
@ConfigurationProperties(prefix = "notification")
public record NotificationProperties(Dispatch dispatch, Http http, Retry retry) {

    /** 租约必须超出 HTTP 总超时的最小余量（秒）——覆盖 GC 停顿、结果落库耗时。 */
    private static final int LEASE_MARGIN_SECONDS = 5;

    public NotificationProperties {
        if (dispatch == null) {
            dispatch = new Dispatch(500, 100, 60);
        }
        if (http == null) {
            http = new Http(3_000, 15_000);
        }
        if (retry == null) {
            retry = new Retry(10);
        }
        assertLeaseLongerThanHttpTimeout(dispatch, http);
    }

    private static void assertLeaseLongerThanHttpTimeout(Dispatch dispatch, Http http) {
        long requiredLeaseSeconds = http.totalTimeoutMs() / 1000 + LEASE_MARGIN_SECONDS;
        if (dispatch.leaseDurationSeconds() <= requiredLeaseSeconds) {
            throw new IllegalStateException(
                    "配置错误（D4.5）：lease-duration-seconds(%ds) 必须 > http.total-timeout-ms(%dms)/1000 + %ds 余量，"
                            + "否则慢请求期间租约先过期，会被其他实例重复认领"
                                    .formatted(dispatch.leaseDurationSeconds(),
                                            http.totalTimeoutMs(), LEASE_MARGIN_SECONDS));
        }
    }

    /** 投递引擎参数。 */
    public record Dispatch(
            int pollIntervalMs,
            int batchSize,
            int leaseDurationSeconds) {
    }

    /** 出站 HTTP 预算（D2）。 */
    public record Http(
            int connectTimeoutMs,
            int totalTimeoutMs) {
    }

    /** 重试预算默认值（按 RetryPolicy 档位可覆盖，P5 实现）。 */
    public record Retry(
            int defaultMaxAttempts) {
    }
}
