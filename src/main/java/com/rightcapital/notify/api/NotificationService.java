package com.rightcapital.notify.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rightcapital.notify.api.dto.CreateNotificationRequest;
import com.rightcapital.notify.api.dto.CreateNotificationResponse;
import com.rightcapital.notify.common.apierror.ApiException;
import com.rightcapital.notify.common.crypto.ContentFingerprint;
import com.rightcapital.notify.common.crypto.PayloadCipher;
import com.rightcapital.notify.common.idgen.Ulid;
import com.rightcapital.notify.common.storage.NotificationRepository;
import com.rightcapital.notify.common.storage.NotificationRepository.NotificationRow;
import com.rightcapital.notify.config.NotificationProperties;
import io.micrometer.core.instrument.Counter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;

/**
 * 受理服务（T3.3/T3.4）：校验 → 落库 → 202。
 *
 * <p>本类只有一个职责：把「一次合法提交」变成一行 PENDING。
 * 不做任何投递动作（D1：受理与投递解耦）。</p>
 *
 * <p>幂等冲突的处理路径值得注意：不先查后插（有竞态），
 * 而是直接 INSERT，靠 uk_idem 兜底，撞唯一键后查回原单号。
 * DB 的唯一索引才是并发下唯一可信的仲裁者。</p>
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository repository;
    private final UrlPolicy urlPolicy;
    private final RateLimiter rateLimiter;
    private final BackpressureGuard backpressureGuard;
    private final NotificationProperties properties;
    private final ObjectMapper objectMapper;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;
    private final PayloadCipher payloadCipher;
    private final ContentFingerprint fingerprint;

    public NotificationService(NotificationRepository repository,
                               UrlPolicy urlPolicy,
                               RateLimiter rateLimiter,
                               BackpressureGuard backpressureGuard,
                               NotificationProperties properties,
                               ObjectMapper objectMapper,
                               io.micrometer.core.instrument.MeterRegistry meterRegistry,
                               PayloadCipher payloadCipher,
                               ContentFingerprint fingerprint) {
        this.repository = repository;
        this.urlPolicy = urlPolicy;
        this.rateLimiter = rateLimiter;
        this.backpressureGuard = backpressureGuard;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.payloadCipher = payloadCipher;
        this.fingerprint = fingerprint;
    }

    /**
     * 受理一条通知。
     *
     * <p>校验顺序即失败优先级：限流（保护自己）→ 背压（保护队列）→
     * URL 策略（保护出站安全）→ 幂等落库。便宜的检查在前，贵的在后。</p>
     */
    public CreateNotificationResponse submit(CreateNotificationRequest request) {
        String callerId = CallerContext.current();

        if (!rateLimiter.tryAcquire(callerId)) {
            throw new ApiException.RateLimited(callerId, rateLimiter.retryAfterSeconds());
        }
        if (!backpressureGuard.accept()) {
            throw new ApiException.Backpressured(backpressureGuard.retryAfterSeconds());
        }
        urlPolicy.check(request.targetUrl());

        Timestamp now = new Timestamp(System.currentTimeMillis());
        String notificationNo = Ulid.next();
        byte[] bodyBytes = request.body() == null ? null : request.body().getBytes(StandardCharsets.UTF_8);

        // 密封发生在落库之前：明文 body（含手机号/邮箱/金额）与 headers（含供应商凭证）
        // 不经过任何持久化路径。指纹取的是明文，用于归档清空 body 后的"内容是否一致"比对。
        byte[] sealedBody = payloadCipher.seal(bodyBytes, PayloadCipher.aadFor(notificationNo, "body"));
        byte[] sealedHeaders = payloadCipher.seal(toJsonBytes(request.headers()),
                PayloadCipher.aadFor(notificationNo, "headers"));

        NotificationRow row = new NotificationRow(
                0,
                notificationNo,
                callerId,
                request.idempotencyKey(),
                request.targetUrl(),
                request.httpMethod(),
                sealedHeaders,
                sealedBody,
                fingerprint.of(bodyBytes),
                "PENDING",
                0,
                properties.retry().defaultMaxAttempts(),
                0,
                now,
                null,
                null,
                null,
                null,
                now,
                now);

        try {
            repository.insertPending(row);
            counter(callerId, "accepted").increment();
            log.info("通知已受理 no={} caller={} url={}", row.notificationNo(), callerId, request.targetUrl());
            return CreateNotificationResponse.created(row.notificationNo());
        } catch (DuplicateKeyException e) {
            // 幂等命中：查回原单号，返回 202 + idempotentReplay=true（T3.3，不抛 409/500）
            NotificationRow existing = repository
                    .findByIdempotencyKey(callerId, request.idempotencyKey())
                    .orElseThrow(() -> new IllegalStateException("幂等冲突但查不到原记录，可能被并发清理", e));
            counter(callerId, "idempotent_hit").increment();
            log.info("幂等命中 no={} caller={} key={}",
                    existing.notificationNo(), callerId, request.idempotencyKey());
            return CreateNotificationResponse.replayed(existing.notificationNo(), existing.status());
        }
    }

    /** headers 序列化为 UTF-8 字节；空/nil 返回 null（与"没有 headers"同义，密封后落 NULL）。 */
    private byte[] toJsonBytes(Object headers) {
        if (headers == null || (headers instanceof java.util.Map<?, ?> m && m.isEmpty())) {
            return null;
        }
        try {
            return objectMapper.writeValueAsBytes(headers);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("headers 无法序列化", e);
        }
    }

    private io.micrometer.core.instrument.Counter counter(String callerId, String event) {
        return io.micrometer.core.instrument.Counter.builder("notification.api.events")
                .tag("caller", callerId)
                .tag("event", event)
                .register(meterRegistry);
    }
}
