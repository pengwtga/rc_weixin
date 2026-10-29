package com.rightcapital.notify.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rightcapital.notify.common.crypto.PayloadCipher;
import com.rightcapital.notify.common.storage.DeliveryAttemptRepository;
import com.rightcapital.notify.common.storage.NotificationRepository;
import com.rightcapital.notify.delivery.AttemptRecorder;
import com.rightcapital.notify.delivery.DeliveryWorker;
import com.rightcapital.notify.delivery.HostConcurrencyLimiter;
import com.rightcapital.notify.delivery.HttpDeliveryClient;
import com.rightcapital.notify.delivery.HttpRequestBuilder;
import com.rightcapital.notify.delivery.NotificationStateWriter;
import com.rightcapital.notify.delivery.ResultClassifier;
import com.rightcapital.notify.delivery.WorkerPool;
import com.rightcapital.notify.dispatcher.WorkerIdentity;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 投递引擎的装配（P4）。
 *
 * <p>这里的每个类都是可 new 的普通对象，不是 {@code @Component} —— 故意如此：
 * 投递引擎的单元测试需要在不启动 Spring 的情况下拼装组件（比如只测分类器、
 * 只测 host 限流），构造依赖显式写出来，测试才不用为了一个 bean 起整个上下文。</p>
 *
 * <p>依赖方向保持单向：dispatcher → delivery → common。delivery 不认识 dispatcher，
 * 所以"投递"可以被单独复用（比如将来做同步补发接口），也不会因为调度策略变化而改动。</p>
 */
@Configuration
public class DeliveryConfig {

    /**
     * 出站 HTTP 客户端。
     *
     * <p>{@code followRedirects(NEVER)} 是硬要求，不是默认值偏好：一旦跟随重定向，
     * 受理时的 SSRF 校验只覆盖了第一跳，对方（或一个被劫持的 DNS）就能把我们
     * 引到 {@code http://169.254.169.254/} 去读云元数据。3xx 一律判永久失败（见 ResultClassifier）。</p>
     *
     * <p>显式指定 HTTP/1.1：不少企业网关对 h2c/HTTP2 支持不完整，升级协商失败会表现成
     * "偶发连接重置"，排查成本极高。这里的通信是低频、短请求，HTTP/2 的多路复用没有收益。</p>
     */
    @Bean
    public HttpClient deliveryHttpClient(NotificationProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.http().connectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Bean
    public ResultClassifier resultClassifier() {
        return new ResultClassifier();
    }

    @Bean
    public HttpRequestBuilder httpRequestBuilder(ObjectMapper objectMapper, NotificationProperties properties) {
        return new HttpRequestBuilder(objectMapper, properties);
    }

    @Bean
    public HttpDeliveryClient httpDeliveryClient(HttpClient deliveryHttpClient,
                                                 HttpRequestBuilder httpRequestBuilder,
                                                 ResultClassifier resultClassifier) {
        return new HttpDeliveryClient(deliveryHttpClient, httpRequestBuilder, resultClassifier);
    }

    @Bean
    public HostConcurrencyLimiter hostConcurrencyLimiter(NotificationProperties properties) {
        return new HostConcurrencyLimiter(properties.dispatch().hostConcurrency());
    }

    @Bean
    public AttemptRecorder attemptRecorder(DeliveryAttemptRepository attemptRepository) {
        return new AttemptRecorder(attemptRepository);
    }

    @Bean
    public NotificationStateWriter notificationStateWriter(NotificationRepository repository,
                                                           NotificationProperties properties,
                                                           MeterRegistry meterRegistry) {
        return new NotificationStateWriter(repository, properties, meterRegistry);
    }

    @Bean
    public DeliveryWorker deliveryWorker(PayloadCipher payloadCipher,
                                         HttpDeliveryClient httpDeliveryClient,
                                         HostConcurrencyLimiter hostConcurrencyLimiter,
                                         AttemptRecorder attemptRecorder,
                                         NotificationStateWriter notificationStateWriter,
                                         MeterRegistry meterRegistry) {
        return new DeliveryWorker(payloadCipher, httpDeliveryClient, hostConcurrencyLimiter,
                attemptRecorder, notificationStateWriter, meterRegistry);
    }

    @Bean
    public WorkerPool workerPool(NotificationProperties properties) {
        return new WorkerPool(properties.dispatch().workerPoolSize(),
                properties.dispatch().shutdownAwaitSeconds());
    }

    /**
     * 本实例的调度身份。
     *
     * <p>主机名可能含中文或长域名，先规整再截断：写进 {@code lease_owner} 的字符串
     * 要能被人一眼读懂，也要满足 30 字符上限（见 {@link WorkerIdentity}）。</p>
     */
    @Bean
    public WorkerIdentity workerIdentity() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "unknown-host";
        }
        String normalized = host.replaceAll("[^A-Za-z0-9.-]", "_");
        String shortHost = normalized.length() > 12 ? normalized.substring(0, 12) : normalized;
        return new WorkerIdentity(shortHost + "-" + ProcessHandle.current().pid());
    }
}
