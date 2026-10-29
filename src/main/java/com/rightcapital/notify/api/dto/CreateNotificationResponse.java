package com.rightcapital.notify.api.dto;

/**
 * 受理响应（T3.4）：202 + notification_no。
 *
 * @param notificationNo  对外唯一单号（ULID），后续查询/对账都以它为准
 * @param status          固定 PENDING（新受理）或当前状态（幂等重放命中时）
 * @param idempotentReplay true = 本次命中幂等，返回的是既有通知而非新建
 */
public record CreateNotificationResponse(String notificationNo, String status, boolean idempotentReplay) {

    public static CreateNotificationResponse created(String notificationNo) {
        return new CreateNotificationResponse(notificationNo, "PENDING", false);
    }

    public static CreateNotificationResponse replayed(String notificationNo, String status) {
        return new CreateNotificationResponse(notificationNo, status, true);
    }
}
