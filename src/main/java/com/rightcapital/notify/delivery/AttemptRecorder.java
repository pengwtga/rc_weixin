package com.rightcapital.notify.delivery;

import com.rightcapital.notify.common.storage.DeliveryAttemptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * 逐次投递登记（T4.7）。
 *
 * <p>一条通知被投了 7 次，就有 7 行明细。听起来像日志，其实不是：
 * 它承担的是**对外解释责任**——业务方问"为什么我的通知晚了 20 分钟"，
 * 需要的是时间线（每次什么时候发、多久、对方返回什么），不是一行最终状态。</p>
 *
 * <p>登记失败绝不阻断投递结论：明细是证据，不是状态。证据丢了要报警，
 * 但不能因此把一条已经成功的通知判成失败。</p>
 */
public class AttemptRecorder {

    private static final Logger log = LoggerFactory.getLogger(AttemptRecorder.class);

    private final DeliveryAttemptRepository repository;

    public AttemptRecorder(DeliveryAttemptRepository repository) {
        this.repository = repository;
    }

    /**
     * 登记一次尝试。
     *
     * @param startedAt 本次尝试的开始时刻（用开始时间而不是结束时间：耗时分析要的是"从何时开始等"）
     */
    public void record(long notificationId, Instant startedAt, DeliveryResult result) {
        try {
            repository.append(
                    notificationId,
                    Timestamp.from(startedAt),
                    result.durationMs(),
                    result.statusCode(),
                    result.errorClass() == null ? null : result.errorClass().name(),
                    bound(result.responseSnippet()));
        } catch (RuntimeException e) {
            // 明细写失败不改变投递结论，但必须让人知道证据链断了
            log.error("投递明细登记失败 notificationId={} outcome={}", notificationId, result.outcome(), e);
        }
    }

    /**
     * 兜底截断：分类器已按 2048 字符截断，这里再按列宽保护一次。
     *
     * <p>列是 {@code VARCHAR(2048)}（字符数），多字节字符不会溢出；
     * 但若将来列改成 BYTE 语义，这里就是唯一的安全网。多一次判断的成本可以忽略。</p>
     */
    private static String bound(String snippet) {
        if (snippet == null) {
            return null;
        }
        return snippet.length() <= ResultClassifier.RESPONSE_SNIPPET_MAX_CHARS
                ? snippet
                : snippet.substring(0, ResultClassifier.RESPONSE_SNIPPET_MAX_CHARS);
    }
}
