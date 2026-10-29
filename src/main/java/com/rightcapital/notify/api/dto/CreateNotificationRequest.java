package com.rightcapital.notify.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 接入请求模型（T3.1）。
 *
 * <p>字段级校验在这里声明；跨字段的语义校验（URL 策略）在 UrlPolicy，服务层调用。
 * 分两层的原因：@Valid 报「字段长什么样错了」，UrlPolicy 报「值为什么被拒绝」——
 * 调用方排障时需要的是后者。</p>
 *
 * @param idempotencyKey 【事件级】幂等键，强制两段式 {@code <事件类型>:<事件唯一ID>}（§3.6）。
 *                       这条校验挡住「拿订单号当键」——那会让同一订单的后续通知被误判
 *                       重复而静默丢失（接口还返回 202），是最难排查的一类故障
 * @param targetUrl     供应商 API 完整地址，落库前经 UrlPolicy 校验
 * @param httpMethod    HTTP 方法白名单（DDL 注释：POST/PUT/PATCH/DELETE/GET）
 * @param headers       请求头（可含供应商凭证；存储层加密，响应永不回显）
 * @param body          请求体（原样字节；到期扫描只关心 next_attempt_at，body 只属于投递）
 */
public record CreateNotificationRequest(
        @NotBlank
        @Size(max = 128, message = "长度不能超过 128")
        @Pattern(regexp = "^[A-Za-z0-9_-]+:[A-Za-z0-9._:-]+$",
                message = "幂等键必须为两段式 <事件类型>:<事件唯一ID>，如 order_paid:pay_20261028_001（§3.6）")
        String idempotencyKey,

        @NotBlank
        @Size(max = 2048, message = "长度不能超过 2048")
        String targetUrl,

        @Pattern(regexp = "^(POST|PUT|PATCH|DELETE|GET)$",
                message = "仅允许 POST/PUT/PATCH/DELETE/GET")
        String httpMethod,

        @Size(max = 32, message = "headers 数量不能超过 32")
        Map<String, String> headers,

        /**
         * 请求体（UTF-8 文本）。用 String 而非 byte[]：调用方发的是业务 JSON/表单文本，
         * 让他们为传输做 base64 编码是反人类；真有二进制需求时再按 Content-Type 分流。
         */
        String body) {

    public CreateNotificationRequest {
        if (httpMethod == null || httpMethod.isBlank()) {
            httpMethod = "POST";
        }
        if (headers == null) {
            headers = Map.of();
        }
    }
}
