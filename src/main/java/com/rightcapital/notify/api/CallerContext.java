package com.rightcapital.notify.api;

/**
 * caller 请求上下文（T3.2）：由 ApiKeyAuthFilter 解析 API Key 后注入，
 * 同一次请求内（限流、幂等域、落库、埋点）都从这里取调用方身份。
 *
 * <p>用 ThreadLocal 而非方法参数层层传递，是为了让 Service/Controller
 * 签名保持业务语义；过滤器必须保证 finally 清理，防止线程池串号。</p>
 */
public final class CallerContext {

    private static final ThreadLocal<String> CALLER = new ThreadLocal<>();

    private CallerContext() {
    }

    static void set(String callerId) {
        CALLER.set(callerId);
    }

    public static String current() {
        String callerId = CALLER.get();
        if (callerId == null) {
            // 走到这里说明过滤器没生效（如 actuator 端点被误调业务逻辑），快速失败优于拿到 null 继续跑
            throw new IllegalStateException("caller 上下文缺失：请求必须经 ApiKeyAuthFilter 进入");
        }
        return callerId;
    }

    static void clear() {
        CALLER.remove();
    }
}
