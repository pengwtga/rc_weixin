package com.rightcapital.notify.common.apierror;

import org.slf4j.MDC;

/**
 * traceId 来源：暂取 MDC（P6 接入过滤器后由其注入，与日志同源）。
 * 目的只有一个：错误响应里的 traceId 能直接 grep 到对应日志行。
 */
final class TraceIds {

    private TraceIds() {
    }

    static String current() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "unset" : traceId;
    }
}
