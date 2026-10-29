package com.rightcapital.notify.api;

import com.rightcapital.notify.common.apierror.ApiException;
import com.rightcapital.notify.config.NotificationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;

/**
 * 接入侧 URL 策略（D10 第一道闸，T3.1 的跨字段校验）。
 *
 * <p>V0 在落库前做三类检查：scheme、字面量 IP 段、可选白名单。
 * 完整的 SSRF 防护（解析后校验实际 IP、DNS rebinding、禁跟随重定向）
 * 在投递层 P6 实现 —— 因为那发生在真正发请求的时刻，接入时的 DNS 解析
 * 与投递时的可能不一致（TTL），两道闸各管各的时刻。</p>
 *
 * <p>异常分两种信息量：@Valid 报「格式错」，本类报「策略拒绝」——
 * 调用方排障需要后者说清为什么。</p>
 */
@Component
public class UrlPolicy {

    private final NotificationProperties.UrlPolicy config;

    public UrlPolicy(NotificationProperties properties) {
        this.config = properties.urlPolicy();
    }

    /**
     * 校验目标 URL，违规抛 {@link ApiException.UrlPolicyViolation}。
     */
    public void check(String targetUrl) {
        URI uri;
        try {
            uri = URI.create(targetUrl);
            if (uri.getHost() == null) {
                throw new IllegalArgumentException("缺少 host");
            }
        } catch (IllegalArgumentException e) {
            throw new ApiException.UrlPolicyViolation("targetUrl 不是合法的绝对 URL");
        }

        if (config.requireHttps() && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new ApiException.UrlPolicyViolation("仅允许 HTTPS（require-https=true）");
        }

        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (!config.allowedHosts().isEmpty() && !config.allowedHosts().contains(host)) {
            throw new ApiException.UrlPolicyViolation("目标域名不在出站白名单内");
        }

        // 字面量 IP：拒绝私网/回环/链路本地 —— 云元数据 169.254.169.254 属于 link-local
        if (isLiteralIp(host) && isForbiddenIp(host)) {
            throw new ApiException.UrlPolicyViolation("目标地址位于私网/回环/链路本地网段，拒绝出站");
        }
    }

    private static boolean isLiteralIp(String host) {
        return host.matches("^\\d{1,3}(\\.\\d{1,3}){3}$") || host.contains(":");
    }

    private static boolean isForbiddenIp(String host) {
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return true; // IPv6 字面量一律交给投递层的解析后校验，接入层先拒绝
        }
        try {
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            if (a == 10 || a == 127 || a == 0) {
                return true;
            }
            if (a == 172 && b >= 16 && b <= 31) {
                return true; // 172.16/12
            }
            if (a == 192 && b == 168) {
                return true; // 192.168/16
            }
            if (a == 169 && b == 254) {
                return true; // link-local：云元数据端点
            }
            return false;
        } catch (NumberFormatException e) {
            return true;
        }
    }
}
