package com.rightcapital.notify.common.apierror;

import java.time.Instant;

/**
 * 统一错误响应体（T1.5）。
 *
 * @param code      机器可判定的错误码，取值见 {@link ErrorCode}
 * @param message   面向人的说明；不包含内部细节（异常栈、SQL 等）
 * @param traceId   日志关联 ID，便于从响应直接定位到日志
 * @param timestamp 服务端时间（UTC）
 */
public record ApiError(String code, String message, String traceId, Instant timestamp) {

    public static ApiError of(ErrorCode errorCode, String message, String traceId) {
        return new ApiError(errorCode.code(), message, traceId, Instant.now());
    }
}
