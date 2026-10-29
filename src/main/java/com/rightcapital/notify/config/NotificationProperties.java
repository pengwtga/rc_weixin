package com.rightcapital.notify.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * 关键参数集中管理（T1.3：代码内无魔法数）。
 *
 * <p>所有运行时可调参数从这里读，且这里承载一条启动期硬断言（D4.5）：
 * 租约时长必须大于 HTTP 总超时 + 余量，否则 worker 卡在慢请求上时
 * 租约先过期、另一实例重复认领 —— 双投率暴涨。宁可在启动时失败，不在运行时丢正确性。</p>
 */
@ConfigurationProperties(prefix = "notification")
public record NotificationProperties(
        Dispatch dispatch,
        Http http,
        Retry retry,
        Api api,
        Backpressure backpressure,
        UrlPolicy urlPolicy) {

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
        if (api == null) {
            api = new Api(Map.of(), new RateLimit(100, 100));
        }
        if (backpressure == null) {
            backpressure = new Backpressure(50_000);
        }
        if (urlPolicy == null) {
            urlPolicy = new UrlPolicy(true, java.util.List.of());
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

    /**
     * 接入侧参数（P3）。
     *
     * @param callers API Key → caller_id 的注册表。Key 泄漏可轮换（换 key 不改 caller_id，
     *                幂等域与历史数据不受影响）；生产环境应从配置中心/密管读取，不入 git
     * @param rateLimit 按 caller 的令牌桶
     */
    public record Api(Map<String, String> callers, RateLimit rateLimit) {
    }

    /** 令牌桶（T3.5）：capacity 为桶深，refillPerSecond 为恒定补充速率。 */
    public record RateLimit(int capacity, int refillPerSecond) {
    }

    /** 背压守卫（T3.6）：PENDING+RETRY_WAIT 超过 maxPending 时拒绝新请求（503）。 */
    public record Backpressure(int maxPending) {
    }

    /**
     * 接入侧 URL 策略（D10 的第一道闸，完整 SSRF 校验在投递层 P6 再做解析后 IP 校验）。
     *
     * @param requireHttps 是否强制 HTTPS（生产 true）
     * @param allowedHosts 出站域名白名单（按 caller 维度可演进）；空列表 = 不启用白名单
     */
    public record UrlPolicy(boolean requireHttps, java.util.List<String> allowedHosts) {
    }
}
