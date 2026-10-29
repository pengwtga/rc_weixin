package com.rightcapital.notify.delivery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rightcapital.notify.config.NotificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 请求组装（T4.5）：把「落库时密封保存的原始请求」还原成一个可发送的 {@link HttpRequest}。
 *
 * <p>三个必须守住的点：</p>
 * <ol>
 *   <li><b>注入 {@code X-Notification-Id}</b>：这是与下游唯一的去重契约。
 *       at-least-once 意味着下游一定会收到重复，没有这个头，重复就是两次真实业务动作。
 *       调用方自己传的同名头会被覆盖 —— 这个头归平台所有，不归调用方。</li>
 *   <li><b>过滤 JDK 受限头</b>：{@code Host}/{@code Content-Length}/{@code Connection} 等
 *       由 HttpClient 自己管理，调用方传了会抛 IllegalArgumentException。
 *       一条本可成功的通知不该因为一个多余的头而失败，所以是过滤而不是让它抛。</li>
 *   <li><b>不跟随重定向</b>：在客户端层设置（{@link HttpDeliveryClient}），
 *       因为一旦跟随，第一跳的 SSRF 校验就失去意义 —— 对方可以把你引到 169.254.169.254。</li>
 * </ol>
 */
public final class HttpRequestBuilder {

    private static final Logger log = LoggerFactory.getLogger(HttpRequestBuilder.class);

    /** 平台保留头：由本服务写入，调用方传入会被覆盖。 */
    public static final String NOTIFICATION_ID_HEADER = "X-Notification-Id";

    /** 允许下游排查"这是第几次尝试"的辅助头（不参与去重，仅诊断用）。 */
    public static final String ATTEMPT_HEADER = "X-Notification-Attempt";

    /**
     * JDK HttpClient 的受限头（见 {@code jdk.httpclient.allowRestrictedHeaders}）。
     * 大小写不敏感，因此判断前统一转小写。
     */
    private static final Set<String> RESTRICTED_HEADERS = Set.of(
            "connection", "content-length", "expect", "host", "upgrade");

    private final ObjectMapper objectMapper;
    private final NotificationProperties properties;

    public HttpRequestBuilder(ObjectMapper objectMapper, NotificationProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * 组装请求。
     *
     * @param request 已解密的投递内容
     * @param attempt 本轮第几次尝试（仅用于诊断头）
     */
    public HttpRequest build(DeliveryRequest request, int attempt) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(request.url()))
                .timeout(Duration.ofMillis(properties.http().totalTimeoutMs()));

        Map<String, String> headers = parseHeaders(request.headersJson());
        headers.forEach((name, value) -> addSafely(builder, name, value));

        // 放在调用方 header 之后：同名时后者覆盖前者，保证平台头不可被伪造
        builder.setHeader(NOTIFICATION_ID_HEADER, request.notificationNo());
        builder.setHeader(ATTEMPT_HEADER, String.valueOf(attempt));

        if (request.body() != null && request.body().length > 0) {
            if (headers.keySet().stream().noneMatch("content-type"::equalsIgnoreCase)) {
                builder.setHeader("Content-Type", "application/json");
            }
            builder.method(request.method(), HttpRequest.BodyPublishers.ofByteArray(request.body()));
        } else {
            builder.method(request.method(), HttpRequest.BodyPublishers.noBody());
        }
        return builder.build();
    }

    private void addSafely(HttpRequest.Builder builder, String name, String value) {
        if (name == null || name.isBlank()) {
            return;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (RESTRICTED_HEADERS.contains(lower)) {
            log.debug("忽略受限请求头 name={}", name);
            return;
        }
        if (NOTIFICATION_ID_HEADER.equalsIgnoreCase(name) || ATTEMPT_HEADER.equalsIgnoreCase(name)) {
            log.debug("忽略调用方伪造的平台保留头 name={}", name);
            return;
        }
        try {
            builder.header(name, value);
        } catch (IllegalArgumentException e) {
            // 非法头名/头值（含换行、冒号）：跳过这一个头，不让整条通知失败。
            // 这条通知仍然会带着剩下的头发出去 —— 下游可能因为缺这个头而拒绝，
            // 那时错误会以 4xx 的形式回到死信，比"本地直接抛异常"更容易追。
            log.warn("跳过非法请求头 name={} reason={}", name, e.getMessage());
        }
    }

    /**
     * 解析落库的 headers JSON。
     *
     * <p>非字符串值（数字/布尔）统一转字符串：HTTP 头本质是文本，供应商传
     * {@code {"X-Retry": 3}} 是很常见的写法，不该因此失败。</p>
     */
    Map<String, String> parseHeaders(byte[] headersJson) {
        if (headersJson == null || headersJson.length == 0) {
            return Map.of();
        }
        try {
            Map<String, Object> raw = objectMapper.readValue(headersJson, new TypeReference<>() {
            });
            Map<String, String> result = new LinkedHashMap<>();
            raw.forEach((k, v) -> {
                if (k != null && v != null) {
                    result.put(k, String.valueOf(v));
                }
            });
            return result;
        } catch (IOException e) {
            // headers 是受理时由我方序列化的，读不出来说明数据损坏。
            // 用空头继续发送（至少 body 还能到），把"缺头导致的 4xx"留给下游判定。
            log.warn("headers 反序列化失败，按无头发送: {}", e.getMessage());
            return Map.of();
        }
    }
}
