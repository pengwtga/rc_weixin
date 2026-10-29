package com.rightcapital.notify.common.apierror;

/**
 * 接入侧的三种「可预期拒绝」：URL 策略、限流、背压。
 * 单独成类而非复用 Exception：Handler 需要携带 Retry-After / 具体语义。
 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;
    /** Retry-After 响应头（仅 429/503 有意义）。 */
    private final int retryAfterSeconds;

    protected ApiException(ErrorCode errorCode, String message, int retryAfterSeconds) {
        super(message);
        this.errorCode = errorCode;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }

    /** 目标 URL 被出站策略拒绝（D10 / T3.1）。 */
    public static final class UrlPolicyViolation extends ApiException {
        public UrlPolicyViolation(String message) {
            super(ErrorCode.TARGET_URL_FORBIDDEN, message, 0);
        }
    }

    /** 超过 caller 限流配额（T3.5）。 */
    public static final class RateLimited extends ApiException {
        public RateLimited(String callerId, int retryAfterSeconds) {
            super(ErrorCode.RATE_LIMITED, "调用方 %s 超出限流配额，请在 %d 秒后重试"
                    .formatted(callerId, retryAfterSeconds), retryAfterSeconds);
        }
    }

    /** 队列积压超过背压阈值（T3.6）。 */
    public static final class Backpressured extends ApiException {
        public Backpressured(int retryAfterSeconds) {
            super(ErrorCode.BACKPRESSURE, "队列积压超过安全水位，已暂停接单，请在 %d 秒后重试"
                    .formatted(retryAfterSeconds), retryAfterSeconds);
        }
    }
}
