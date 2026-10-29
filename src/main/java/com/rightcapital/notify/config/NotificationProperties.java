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
        UrlPolicy urlPolicy,
        Crypto crypto) {

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
        if (crypto == null) {
            crypto = new Crypto(null, null, false);
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

    /**
     * 载荷保护参数（D10）：请求体/请求头密封 + 请求体指纹。
     *
     * <p>两把密钥互相独立 —— 密封密钥泄漏不应连带削弱指纹的不可伪造性（密钥分离原则）。
     * 生产环境由环境变量注入（{@code NOTIFY_BODY_KEY} / {@code NOTIFY_FP_KEY}），
     * 最终应接到 KMS/密管；仓库里只放开发用的兜底值。</p>
     *
     * @param bodyKey        AES-256-GCM 密钥，Base64 编码的 32 字节
     * @param fingerprintKey HMAC-SHA256 密钥，Base64 编码的 32 字节
     * @param rejectDevKeys  为 true 时拒绝内置开发密钥 —— 生产 profile 必须开启，
     *                       否则"加密"只是把明文换了个名字（密钥和代码一起躺在仓库里）
     */
    public record Crypto(String bodyKey, String fingerprintKey, boolean rejectDevKeys) {

        /** 开发兜底密钥（32 字节）。生产必须通过环境变量覆盖。 */
        public static final String DEV_BODY_KEY = "ZGV2LW9ubHktYm9keS1rZXktMzItYnl0ZXMtMDAwMDE=";
        public static final String DEV_FINGERPRINT_KEY = "ZGV2LW9ubHktZnAta2V5LTMyLWJ5dGVzLTAwMDAwMDE=";

        public Crypto {
            bodyKey = bodyKey == null || bodyKey.isBlank() ? DEV_BODY_KEY : bodyKey;
            fingerprintKey = fingerprintKey == null || fingerprintKey.isBlank() ? DEV_FINGERPRINT_KEY : fingerprintKey;

            byte[] decodedBody = decodeKey(bodyKey, "notification.crypto.body-key");
            byte[] decodedFp = decodeKey(fingerprintKey, "notification.crypto.fingerprint-key");

            if (rejectDevKeys) {
                if (DEV_BODY_KEY.equals(bodyKey) || DEV_FINGERPRINT_KEY.equals(fingerprintKey)) {
                    throw new IllegalStateException(
                            "配置错误（D10）：notification.crypto.reject-dev-keys=true 时禁止使用内置开发密钥，"
                                    + "必须通过环境变量 NOTIFY_BODY_KEY / NOTIFY_FP_KEY 注入真实密钥");
                }
            }
            if (java.util.Arrays.equals(decodedBody, decodedFp)) {
                // 同一把密钥兼作两种用途，会削弱密钥分离 —— 拒绝而不是警告
                throw new IllegalStateException(
                        "配置错误（D10）：body-key 与 fingerprint-key 不得相同（密钥分离）");
            }
        }

        private static byte[] decodeKey(String base64, String propertyName) {
            try {
                byte[] bytes = java.util.Base64.getDecoder().decode(base64);
                if (bytes.length != 32) {
                    throw new IllegalStateException("配置错误（D10）：%s 必须是 Base64 编码的 32 字节，实际 %d 字节"
                            .formatted(propertyName, bytes.length));
                }
                return bytes;
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("配置错误（D10）：%s 不是合法的 Base64".formatted(propertyName), e);
            }
        }

        /** 解码后的 AES 密钥字节。 */
        public byte[] bodyKeyBytes() {
            return java.util.Base64.getDecoder().decode(bodyKey);
        }

        /** 解码后的 HMAC 密钥字节。 */
        public byte[] fingerprintKeyBytes() {
            return java.util.Base64.getDecoder().decode(fingerprintKey);
        }
    }
}
