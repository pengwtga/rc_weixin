package com.rightcapital.notify.api;

import com.rightcapital.notify.common.apierror.ApiException;
import com.rightcapital.notify.config.NotificationProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T3.1 / D10 第一道闸：接入层 URL 策略。
 * 重点用例来自 T8.7（SSRF）：云元数据地址与私网地址必须被拒。
 */
class UrlPolicyTest {

    private UrlPolicy policy(boolean requireHttps, List<String> allowedHosts) {
        NotificationProperties properties = new NotificationProperties(
                null, null, null, null, null,
                new NotificationProperties.UrlPolicy(requireHttps, allowedHosts));
        return new UrlPolicy(properties);
    }

    @Test
    void 合法的公网HTTPS地址_通过() {
        policy(true, List.of()).check("https://hooks.vendor.example.com/v1/events");
    }

    @Test
    void 非HTTPS_拒绝() {
        assertThatThrownBy(() -> policy(true, List.of()).check("http://hooks.vendor.example.com/v1"))
                .isInstanceOf(ApiException.UrlPolicyViolation.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    void 云元数据地址_拒绝() {
        // 169.254.169.254 是云上 SSRF 的头号目标（获取实例凭证）
        assertThatThrownBy(() -> policy(true, List.of()).check("https://169.254.169.254/latest/meta-data/"))
                .isInstanceOf(ApiException.UrlPolicyViolation.class)
                .hasMessageContaining("私网");
    }

    @Test
    void 私网与回环地址_拒绝() {
        UrlPolicy p = policy(true, List.of());
        assertThatThrownBy(() -> p.check("https://10.0.1.5/hook")).isInstanceOf(ApiException.UrlPolicyViolation.class);
        assertThatThrownBy(() -> p.check("https://192.168.1.1/hook")).isInstanceOf(ApiException.UrlPolicyViolation.class);
        assertThatThrownBy(() -> p.check("https://172.16.0.9/hook")).isInstanceOf(ApiException.UrlPolicyViolation.class);
        assertThatThrownBy(() -> p.check("https://127.0.0.1/hook")).isInstanceOf(ApiException.UrlPolicyViolation.class);
    }

    @Test
    void 白名单启用后_非白名单域名_拒绝() {
        UrlPolicy p = policy(true, List.of("hooks.vendor.example.com"));
        assertThatCode(() -> p.check("https://hooks.vendor.example.com/v1")).doesNotThrowAnyException();
        assertThatThrownBy(() -> p.check("https://other.example.com/v1"))
                .isInstanceOf(ApiException.UrlPolicyViolation.class)
                .hasMessageContaining("白名单");
    }

    @Test
    void 非法URL_拒绝() {
        assertThatThrownBy(() -> policy(true, List.of()).check("not a url"))
                .isInstanceOf(ApiException.UrlPolicyViolation.class);
    }

    @Test
    void 域名形态的私网别名_接入层放行_留给投递层解析后校验() {
        // rebind.attacker.example.com 解析后可能指向内网 —— 接入层不解析 DNS，
        // 这条防线在投递层 P6（解析后校验实际 IP）。接入层不越权假装能防。
        assertThatCode(() -> policy(true, List.of()).check("https://rebind.attacker.example.com/hook"))
                .doesNotThrowAnyException();
    }
}
