package com.rightcapital.notify.delivery;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * 结果分类器（T4.6）：把「一次 HTTP 交互的原始结局」翻译成「可被状态机消费的结论」。
 *
 * <p>这是整套系统里最容易被写随意的一处。随便写就是
 * {@code if (status >= 200 && status < 300) success else retry 3 times} ——
 * 然后把一次 401（凭证过期）重试 10 次，10 分钟后进死信，中间白烧 10 次配额，
 * 而真正该做的是立刻告警让人换凭证。分类的价值就在这里：**把"失败"拆成"值得再试"和"别再试了"**。</p>
 *
 * <p>无状态、纯函数、不碰 IO —— 因此可以穷举测试全部分支（ResultClassifierTest）。</p>
 */
public final class ResultClassifier {

    /** 响应片段上限。DB 列是 VARCHAR(2048)，按字符截断与列宽一致。 */
    public static final int RESPONSE_SNIPPET_MAX_CHARS = 2048;

    /**
     * 分类一次「拿到了 HTTP 响应」的投递。
     *
     * @param statusCode       响应码
     * @param body             响应体（可为 null）
     * @param durationMs       耗时
     * @param retryAfterHeader {@code Retry-After} 原值（可为 null）
     */
    public DeliveryResult classifyHttp(int statusCode, String body, long durationMs, String retryAfterHeader) {
        String snippet = snippet(body);

        if (statusCode >= 200 && statusCode < 300) {
            return new DeliveryResult(DeliveryResult.Outcome.SUCCESS, statusCode, null,
                    durationMs, snippet, null);
        }

        // 禁止跟随重定向（D10）：3xx 不是"再走一步"，而是目标地址配置错了。
        // 跟随重定向等于允许对方把我们引到任意地址 —— SSRF 校验在第一跳就失效了。
        if (statusCode >= 300 && statusCode < 400) {
            return new DeliveryResult(DeliveryResult.Outcome.PERMANENT, statusCode,
                    DeliveryErrorClass.REDIRECT_REFUSED, durationMs, snippet, null);
        }

        if (statusCode >= 400 && statusCode < 500) {
            // 429 是"你太快了"而不是"你错了"；408 是"我没等到你的完整请求"，
            // 两者都属于临时状态，与 401/403/404 的性质不同 —— 这是 4xx 里唯二可重试的。
            boolean retryable = statusCode == 429 || statusCode == 408;
            Long retryAfter = parseRetryAfterMs(retryAfterHeader);
            return new DeliveryResult(
                    retryable ? DeliveryResult.Outcome.RETRYABLE : DeliveryResult.Outcome.PERMANENT,
                    statusCode, DeliveryErrorClass.HTTP_4XX, durationMs, snippet, retryAfter);
        }

        if (statusCode >= 500 && statusCode < 600) {
            return new DeliveryResult(DeliveryResult.Outcome.RETRYABLE, statusCode,
                    DeliveryErrorClass.HTTP_5XX, durationMs, snippet, parseRetryAfterMs(retryAfterHeader));
        }

        // 1xx 不可能作为最终响应出现。真出现了说明链路有问题，按永久失败处理，
        // 避免一条诡异响应把重试预算烧光。
        return new DeliveryResult(DeliveryResult.Outcome.PERMANENT, statusCode,
                DeliveryErrorClass.HTTP_4XX, durationMs, snippet, null);
    }

    /**
     * 分类一次「没拿到响应」的投递。
     *
     * <p>按 cause 链判断而不是只看最外层类型：JDK HttpClient 会把
     * {@link UnknownHostException} 包在 {@link ConnectException} 里，
     * 只看外层会把"域名写错了"记成"端口没开"，运维照着 CONN_REFUSED 去查对方端口，
     * 查半天发现是自己配错了域名。</p>
     */
    public DeliveryResult classifyThrowable(Throwable error, long durationMs) {
        DeliveryErrorClass errorClass = resolve(error);
        return new DeliveryResult(DeliveryResult.Outcome.RETRYABLE, null, errorClass, durationMs, null, null);
    }

    private static DeliveryErrorClass resolve(Throwable error) {
        // 先把整条 cause 链摊平成集合，再按"最具体优先"判断。
        //
        // 为什么不能边遍历边返回：JDK HttpClient 的典型异常是
        // ConnectException("Connection refused") 包着 UnknownHostException ——
        // 外层先命中就会把"域名写错了"记成"端口没开"，运维照着 CONN_REFUSED
        // 去查对方端口，查半天发现是自己配错了域名。最内层的因才是真因。
        java.util.List<Throwable> chain = new java.util.ArrayList<>(4);
        Throwable t = error;
        for (int depth = 0; t != null && depth < 10; depth++) {
            chain.add(t);
            Throwable cause = t.getCause();
            if (cause == t) { // 自引用（测试里用匿名类模拟过）
                break;
            }
            t = cause;
        }

        if (chain.stream().anyMatch(UnknownHostException.class::isInstance)) {
            return DeliveryErrorClass.DNS;
        }
        if (chain.stream().anyMatch(HttpConnectTimeoutException.class::isInstance)
                || chain.stream().anyMatch(HttpTimeoutException.class::isInstance)) {
            return DeliveryErrorClass.TIMEOUT;
        }
        if (chain.stream().anyMatch(SSLException.class::isInstance)) {
            return DeliveryErrorClass.TLS;
        }
        if (chain.stream().anyMatch(ConnectException.class::isInstance)) {
            return DeliveryErrorClass.CONN_REFUSED;
        }
        if (chain.stream().anyMatch(IOException.class::isInstance)) {
            return DeliveryErrorClass.CONNECT_FAILED;
        }
        // 非 IO 异常（请求头非法被本地拒绝、URL 拼错等）：这是实现/配置缺陷，
        // 但仍然归入可重试 —— 宁可多试几次让人从告警里发现，也不要静默丢弃一条业务通知。
        // 它们会带着完整栈进日志，与真正的网络故障区分得开。
        return DeliveryErrorClass.CONNECT_FAILED;
    }

    /**
     * 解析 {@code Retry-After}：支持「秒数」与「HTTP-date」两种合法形式。
     *
     * <p>只作为 P5 退避计算的**下界参考**，不作为唯一依据：下游说 3600 秒，
     * 我们不真的等一小时（那会让任务在队列里躺到用户来投诉），
     * 而是取 {@code max(自己的退避, 下游建议)} 并封顶 —— 决策在 P5。</p>
     *
     * @return 毫秒数；缺失或格式非法返回 null（"没说"和"说 0 秒"是两回事）
     */
    public static Long parseRetryAfterMs(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String value = header.trim();
        try {
            long seconds = Long.parseLong(value);
            return seconds < 0 ? null : Duration.ofSeconds(seconds).toMillis();
        } catch (NumberFormatException ignored) {
            // 不是秒数，按 HTTP-date 再试一次
        }
        try {
            ZonedDateTime at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.US));
            long ms = Duration.between(ZonedDateTime.now(at.getZone()), at).toMillis();
            return Math.max(ms, 0);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 截断响应片段；非法字节已在读取时替换为 U+FFFD，这里不会抛。 */
    public static String snippet(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        return body.length() <= RESPONSE_SNIPPET_MAX_CHARS
                ? body
                : body.substring(0, RESPONSE_SNIPPET_MAX_CHARS);
    }
}
