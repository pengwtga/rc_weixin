package com.rightcapital.notify.delivery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 真实发一次 HTTP（T4.4），并把结局交给 {@link ResultClassifier}。
 *
 * <p><b>为什么用 JDK HttpClient 而不是 RestTemplate/WebClient</b>：投递是阻塞式短任务，
 * 跑在自己的线程池里，不需要响应式栈；JDK 客户端零额外依赖，且
 * {@code followRedirects(NEVER)} 是显式的一等配置 —— 重定向这件事必须由我们决定，
 * 不能交给框架默认值（RestTemplate 默认跟随 SimpleClientHttpRequestFactory 的行为）。</p>
 *
 * <p><b>响应体读取的取舍</b>：用 {@code ofByteArray} 而不是 {@code ofInputStream}。
 * 前者受 request timeout 保护（整个响应读完才算完成），后者在响应头到达时 future 就完成，
 * 后续读流不再受 timeout 约束 —— 一个只发头不发体的下游能把 worker 线程永久挂住。
 * 代价是响应体会完整读进内存；由于读取受 15s 总预算约束，且下游是已知供应商，
 * 这个代价可接受。真正的"响应体上限"属于 P6 出站安全，已在文档记为已知局限。</p>
 */
public class HttpDeliveryClient {

    private static final Logger log = LoggerFactory.getLogger(HttpDeliveryClient.class);

    private final HttpClient client;
    private final HttpRequestBuilder requestBuilder;
    private final ResultClassifier classifier;

    public HttpDeliveryClient(HttpClient client, HttpRequestBuilder requestBuilder, ResultClassifier classifier) {
        this.client = client;
        this.requestBuilder = requestBuilder;
        this.classifier = classifier;
    }

    /**
     * 发送一次。本方法不抛业务异常 —— 所有结局都表达为 {@link DeliveryResult}，
     * 因为"失败"在这里是正常返回值，不是意外（每 100 条就有几条会失败）。
     */
    public DeliveryResult send(DeliveryRequest request, int attempt) {
        HttpRequest httpRequest;
        try {
            httpRequest = requestBuilder.build(request, attempt);
        } catch (IllegalArgumentException e) {
            // URL 非法（受理时校验过，但可能是历史数据或手工改库）：
            // 属于永久失败，不要让它在重试队列里转 10 次
            log.warn("请求组装失败 no={} reason={}", request.notificationNo(), e.getMessage());
            return new DeliveryResult(DeliveryResult.Outcome.PERMANENT, null,
                    DeliveryErrorClass.CONNECT_FAILED, 0, null, null);
        }

        long startNanos = System.nanoTime();
        try {
            HttpResponse<byte[]> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            long durationMs = elapsedMs(startNanos);
            String body = response.body() == null ? null : new String(response.body(), StandardCharsets.UTF_8);
            String retryAfter = response.headers().firstValue("Retry-After").orElse(null);
            DeliveryResult result = classifier.classifyHttp(response.statusCode(), body, durationMs, retryAfter);
            log.debug("投递完成 no={} status={} outcome={} duration={}ms",
                    request.notificationNo(), result.statusCode(), result.outcome(), durationMs);
            return result;
        } catch (IOException e) {
            long durationMs = elapsedMs(startNanos);
            DeliveryResult result = classifier.classifyThrowable(e, durationMs);
            log.debug("投递异常 no={} class={} duration={}ms reason={}",
                    request.notificationNo(), result.errorClass(), durationMs, e.toString());
            return result;
        } catch (InterruptedException e) {
            // 停机时会走到这里：恢复中断标记，让上层（线程池）知道该收尾了
            Thread.currentThread().interrupt();
            return classifier.classifyThrowable(e, elapsedMs(startNanos));
        }
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
