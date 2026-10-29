package com.rightcapital.notify.delivery;

/**
 * 一次待执行的投递（已解密、可直接发送）。
 *
 * <p>刻意不传整个 {@code NotificationRow}：投递器只该看到"要发什么"，
 * 不该看到 attempt_count、status、lease 这些调度状态。字段少一个，
 * 就少一处"顺手改一下状态"的机会（状态只能由 {@link NotificationStateWriter} 改）。</p>
 */
public record DeliveryRequest(
        String notificationNo,
        String url,
        String method,
        byte[] headersJson,
        byte[] body) {
}
