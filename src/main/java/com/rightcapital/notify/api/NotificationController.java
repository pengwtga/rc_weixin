package com.rightcapital.notify.api;

import com.rightcapital.notify.api.dto.CreateNotificationRequest;
import com.rightcapital.notify.api.dto.CreateNotificationResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * 接入端点（T3.4）：POST /v1/notifications → 202 Accepted。
 *
 * <p>为什么是 202 不是 200/201：受理 ≠ 完成，语义必须诚实（D1）。
 * 调用方拿到的是「已受理的凭据」，不是投递结果。</p>
 */
@RestController
@RequestMapping("/v1/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<CreateNotificationResponse> submit(@Valid @RequestBody CreateNotificationRequest request) {
        CreateNotificationResponse response = service.submit(request);
        return ResponseEntity
                .accepted()
                .location(URI.create("/v1/notifications/" + response.notificationNo()))
                .body(response);
    }
}
