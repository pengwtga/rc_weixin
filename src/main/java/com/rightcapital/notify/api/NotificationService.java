package com.rightcapital.notify.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rightcapital.notify.api.dto.CreateNotificationRequest;
import com.rightcapital.notify.api.dto.CreateNotificationResponse;
import com.rightcapital.notify.common.apierror.ApiException;
import com.rightcapital.notify.common.idgen.Ulid;
import com.rightcapital.notify.common.storage.NotificationRepository;
import com.rightcapital.notify.common.storage.NotificationRepository.NotificationRow;
import com.rightcapital.notify.config.NotificationProperties;
import io.micrometer.core.instrument.Counter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.HexFormat;

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

    public NotificationService(NotificationRepository repository,
                               UrlPolicy urlPolicy,
                               RateLimiter rateLimiter,
                               BackpressureGuard backpressureGuard,
                               NotificationProperties properties,
                               ObjectMapper objectMapper,
                               io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.repository = repository;
        this.urlPolicy = urlPolicy;
        this.rateLimiter = rateLimiter;
        this.backpressureGuard = backpressureGuard;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
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
        byte[] bodyBytes = request.body() == null ? null : request.body().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        NotificationRow row = new NotificationRow(
                0,
                Ulid.next(),
                callerId,
                request.idempotencyKey(),
                request.targetUrl(),
                request.httpMethod(),
                toJson(request.headers()),
                bodyBytes,
                sha256Hex(bodyBytes),
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

    private String toJson(Object headers) {
        if (headers == null || (headers instanceof java.util.Map<?, ?> m && m.isEmpty())) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(headers);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("headers 无法序列化", e);
        }
    }

    private static String sha256Hex(byte[] body) {
        if (body == null || body.length == 0) {
            // 空体也要有指纹：保持「body_sha256 非空」的 DDL 约束，避免两种语义
            body = new byte[0];
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256", e);
        }
    }

    private io.micrometer.core.instrument.Counter counter(String callerId, String event) {
        return io.micrometer.core.instrument.Counter.builder("notification.api.events")
                .tag("caller", callerId)
                .tag("event", event)
                .register(meterRegistry);
    }
}
