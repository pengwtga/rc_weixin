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
            dispatch = new Dispatch(500, 100, 60, 32);
        }
        if (http == null) {
            http = new Http(3_000, 15_000);
        }
        if (retry == null) {
            retry = new Retry(10, 30);
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

    /**
     * 投递引擎参数。
     *
     * <p>为什么 {@code workerPoolSize} 与 {@code batchSize} 是两个参数而不是一个：
     * 前者是「同时能发多少个 HTTP」的物理上限，后者是「一次认领多少条」的批量口径。
     * 认领数超过空闲 worker 数，多出来的行会在租约里空转，租约到期又被别人认领 ——
     * 自己制造重复投递（D4.5）。调度器每次只认领 {@code min(batchSize, 空闲槽位)}。</p>
     *
     * @param pollIntervalMs      基础轮询间隔；空轮询时退避到 {@code idleBackoffMaxMs}
     * @param batchSize           单轮认领上限，必须 > 0
     * @param leaseDurationSeconds 租约时长，启动断言要求 > HTTP 总超时 + 5s
     * @param workerPoolSize      投递线程数 = 同时在途的 HTTP 上限
     * @param idleBackoffMaxMs    队列持续为空时的最大轮询间隔（T4.10）；@Scheduled 仍按基础间隔醒来，
     *                            但只是做一次时间比较、不碰 DB，因此 DB QPS 实际下降
     * @param hostConcurrency     单个下游 host 的并发上限（T4.9）：一家供应商变慢不该拖垮其他家
     * @param requeueDelayMs      抢不到 host 许可时的重新排队延迟 —— 必须回退 attempt_count（T5.7 的前身）
     * @param shutdownAwaitSeconds 停机时等待在途投递完成的上限；超时后交给租约自然回收
     */
    public record Dispatch(
            int pollIntervalMs,
            int batchSize,
            int leaseDurationSeconds,
            int workerPoolSize,
            int idleBackoffMaxMs,
            int hostConcurrency,
            int requeueDelayMs,
            int shutdownAwaitSeconds) {

        /** 兼容构造：其余参数取默认值（单测构造配置时用，避免每个用例都写 8 个参数）。 */
        public Dispatch(int pollIntervalMs, int batchSize, int leaseDurationSeconds) {
            this(pollIntervalMs, batchSize, leaseDurationSeconds, 32);
        }

        /** 兼容构造：其余参数取默认值（单测构造配置时用，避免每个用例都写 8 个参数）。 */
        public Dispatch(int pollIntervalMs, int batchSize, int leaseDurationSeconds, int workerPoolSize) {
            this(pollIntervalMs, batchSize, leaseDurationSeconds, workerPoolSize, 2_000, 8, 200, 20);
        }

        public Dispatch {
            if (pollIntervalMs <= 0) {
                throw new IllegalStateException("配置错误：notification.dispatch.poll-interval-ms 必须 > 0");
            }
            if (batchSize <= 0) {
                throw new IllegalStateException("配置错误：notification.dispatch.batch-size 必须 > 0");
            }
            if (workerPoolSize <= 0) {
                throw new IllegalStateException("配置错误：notification.dispatch.worker-pool-size 必须 > 0");
            }
            if (hostConcurrency <= 0) {
                throw new IllegalStateException("配置错误：notification.dispatch.host-concurrency 必须 > 0");
            }
            if (idleBackoffMaxMs < pollIntervalMs) {
                // 退避上限比基础间隔还小，等于"退避"变"加速" —— 配置意图被反过来了
                throw new IllegalStateException(
                        "配置错误：idle-backoff-max-ms(%d) 不得小于 poll-interval-ms(%d)"
                                .formatted(idleBackoffMaxMs, pollIntervalMs));
            }
        }
    }

    /**
     * 出站 HTTP 预算（D2）。
     *
     * <p><b>只有两段，不是三段</b>：JDK {@code HttpClient} 提供 connectTimeout 与「整个请求」的
     * timeout，没有独立的 socket read timeout。T4.4 原本写的「connect 3s / read 10s / total 15s」
     * 是照 Apache HttpClient 的接口设计的。为了三段超时引入第二个 HTTP 客户端，
     * 换来的是一个已被总超时覆盖的能力 —— 不划算，因此把 read 超时并入 total，
     * 并把这条偏差记在设计文档 §9.9（"实现与任务清单不一致时，改清单而不是硬凑"）。</p>
     *
     * @param connectTimeoutMs TCP 建连上限；DNS 解析也受它约束
     * @param totalTimeoutMs   整个请求（建连 + 发送 + 等待响应）的上限
     */
    public record Http(int connectTimeoutMs, int totalTimeoutMs) {

        public Http {
            if (connectTimeoutMs <= 0 || totalTimeoutMs <= 0) {
                throw new IllegalStateException("配置错误：HTTP 超时必须 > 0");
            }
            if (connectTimeoutMs > totalTimeoutMs) {
                // 建连预算比总预算还大，等于总超时永远先触发，connect 配置形同虚设
                throw new IllegalStateException(
                        "配置错误：connect-timeout-ms(%d) 不得大于 total-timeout-ms(%d)"
                                .formatted(connectTimeoutMs, totalTimeoutMs));
            }
        }
    }

    /**
     * 重试预算与节奏。
     *
     * @param defaultMaxAttempts 默认档位（STANDARD）的最大尝试次数
     * @param fixedDelaySeconds  <b>P4 阶段的占位退避</b>：固定延迟，只保证状态机闭环能跑通。
     *                           P5 会换成「指数 + 抖动 + 上限」的 BackoffCalculator，
     *                           届时本字段退役 —— 之所以先放配置而不写死常量，是为了让它可观察、
     *                           也让 P5 的替换只动一个实现类
     */
    public record Retry(int defaultMaxAttempts, int fixedDelaySeconds) {

        /** 兼容构造：固定延迟取 30s（P4 占位值）。 */
        public Retry(int defaultMaxAttempts) {
            this(defaultMaxAttempts, 30);
        }

        public Retry {
            if (defaultMaxAttempts <= 0) {
                throw new IllegalStateException("配置错误：retry.default-max-attempts 必须 > 0");
            }
            if (fixedDelaySeconds <= 0) {
                throw new IllegalStateException("配置错误：retry.fixed-delay-seconds 必须 > 0");
            }
        }
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
