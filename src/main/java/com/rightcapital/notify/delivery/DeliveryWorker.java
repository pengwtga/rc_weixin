package com.rightcapital.notify.delivery;

import com.rightcapital.notify.common.crypto.PayloadCipher;
import com.rightcapital.notify.common.storage.NotificationRepository.NotificationRow;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * 一条通知的完整投递流程（T4.4 的执行者）。
 *
 * <p>顺序是刻意设计的，每一步失败都有明确归属：</p>
 * <pre>
 *  解密 → 取 host 许可 → 发送 → 登记明细 → 落状态
 * </pre>
 *
 * <p>三个不显然的点：</p>
 * <ul>
 *   <li><b>解密放在最前面</b>：密文坏了就根本发不出去，应当在消耗 host 许可之前发现。
 *       解密失败归 {@code PAYLOAD_CORRUPT}（永久失败），不是网络错误 ——
 *       归错类会让人去查对方接口，而问题在本地数据。</li>
 *   <li><b>取不到 host 许可 = 退回队列，不是失败</b>：这条通知没被发出去过，
 *       不能算一次尝试（见 {@code requeueAndRefund}）。</li>
 *   <li><b>明细先于状态</b>：先留下证据再改状态。状态写失败（租约丢了）时，
 *       至少还能查到"这次确实发了、对方返回了什么"。</li>
 * </ul>
 *
 * <p>本类不 sleep、不重试、不调度 —— 它只负责"把这一条处理完然后返回"，
 * 什么时候再来由 notification 表的 next_attempt_at 决定。</p>
 */
public class DeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);

    private final PayloadCipher cipher;
    private final HttpDeliveryClient client;
    private final HostConcurrencyLimiter hostLimiter;
    private final AttemptRecorder recorder;
    private final NotificationStateWriter stateWriter;
    private final MeterRegistry meterRegistry;

    public DeliveryWorker(PayloadCipher cipher,
                          HttpDeliveryClient client,
                          HostConcurrencyLimiter hostLimiter,
                          AttemptRecorder recorder,
                          NotificationStateWriter stateWriter,
                          MeterRegistry meterRegistry) {
        this.cipher = cipher;
        this.client = client;
        this.hostLimiter = hostLimiter;
        this.recorder = recorder;
        this.stateWriter = stateWriter;
        this.meterRegistry = meterRegistry;
    }

    /** 处理一条已认领的通知。方法内部吞掉所有异常 —— 线程池里的任务不该把异常抛给无处可接的地方。 */
    public void deliver(NotificationRow row) {
        String url = row.targetUrl();
        if (!hostLimiter.tryAcquire(url)) {
            // 该 host 已达并发上限：退回队列，200ms 后再来（且不计入尝试次数）
            stateWriter.requeueWithoutAttempt(row, "host 并发已满");
            return;
        }
        try {
            doDeliver(row, url);
        } catch (RuntimeException e) {
            // 走到这里说明是实现缺陷（不是下游问题）。按"可重试的失败"处理：
            // 消耗一次预算，最多 max_attempts 次后进死信 —— 既不静默吞掉，也不会无限疯转。
            log.error("投递流程未预期异常 no={}", row.notificationNo(), e);
            meterRegistry.counter("notification.delivery.unexpected_error").increment();
            DeliveryResult unexpected = new DeliveryResult(DeliveryResult.Outcome.RETRYABLE, null,
                    DeliveryErrorClass.CONNECT_FAILED, 0, null, null);
            recorder.record(row.id(), Instant.now(), unexpected);
            stateWriter.settleFailure(row, unexpected, row.attemptCount());
        } finally {
            hostLimiter.release(url);
        }
    }

    private void doDeliver(NotificationRow row, String url) {
        DeliveryRequest request;
        try {
            request = decrypt(row);
        } catch (PayloadCipher.PayloadIntegrityException e) {
            // 密文被篡改 / AAD 不匹配 / 密钥轮换后未回填 —— 永久失败，重试无意义
            log.error("载荷解密失败，转死信 no={} reason={}", row.notificationNo(), e.getMessage());
            DeliveryResult corrupt = new DeliveryResult(DeliveryResult.Outcome.PERMANENT, null,
                    DeliveryErrorClass.PAYLOAD_CORRUPT, 0, null, null);
            recorder.record(row.id(), Instant.now(), corrupt);
            stateWriter.settleFailure(row, corrupt, row.attemptCount());
            return;
        }

        Instant startedAt = Instant.now();
        DeliveryResult result = client.send(request, row.attemptCount());
        recorder.record(row.id(), startedAt, result);

        if (result.succeeded()) {
            stateWriter.settleSucceeded(row, result);
        } else {
            stateWriter.settleFailure(row, result, row.attemptCount());
        }
        log.debug("投递处理完成 no={} outcome={} next=取决于落定结果",
                row.notificationNo(), result.outcome());
    }

    /**
     * 解密落库的请求。
     *
     * <p>AAD 必须与受理侧完全一致（{@link PayloadCipher#aadFor}）：单号 + 字段名。
     * 这也是为什么"顺手把 headers 的密文塞进 body 字段"这种数据库层面的乱改会被立刻发现。</p>
     */
    private DeliveryRequest decrypt(NotificationRow row) {
        byte[] headers = cipher.open(row.headersEnc(), PayloadCipher.aadFor(row.notificationNo(), "headers"));
        byte[] body = cipher.open(row.bodyEnc(), PayloadCipher.aadFor(row.notificationNo(), "body"));
        return new DeliveryRequest(row.notificationNo(), row.targetUrl(), row.httpMethod(), headers, body);
    }
}
