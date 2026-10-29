package com.rightcapital.notify.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rightcapital.notify.config.NotificationProperties;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4.5 DoD：请求组装的三条不变量 —— 平台头注入、受限头过滤、不因垃圾头失败。
 */
class HttpRequestBuilderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static HttpRequestBuilder builder() {
        NotificationProperties properties = new NotificationProperties(
                new NotificationProperties.Dispatch(500, 100, 60),
                new NotificationProperties.Http(3_000, 15_000),
                new NotificationProperties.Retry(10),
                null, null, null, null);
        return new HttpRequestBuilder(MAPPER, properties);
    }

    private static DeliveryRequest request(String headersJson) {
        return new DeliveryRequest("01ABC", "https://vendor.example.com/hook", "POST",
                headersJson == null ? null : headersJson.getBytes(StandardCharsets.UTF_8),
                "{\"status\":\"paid\"}".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void 注入通知单号头() {
        HttpRequest req = builder().build(request("{}"), 1);
        assertThat(req.headers().firstValue("X-Notification-Id")).contains("01ABC");
    }

    @Test
    void 调用方伪造的单号头被覆盖() {
        // 这个头是下游去重的唯一依据，不能由调用方决定
        HttpRequest req = builder().build(request("{\"X-Notification-Id\":\"hacked\"}"), 1);
        assertThat(req.headers().allValues("X-Notification-Id")).containsExactly("01ABC");
    }

    @Test
    void 注入尝试次数头() {
        HttpRequest req = builder().build(request("{}"), 3);
        assertThat(req.headers().firstValue("X-Notification-Attempt")).contains("3");
    }

    @Test
    void 过滤JDK受限头_不让整条通知失败() {
        // Host/Content-Length 由 HttpClient 自己管理，调用方传了会抛 IllegalArgumentException
        HttpRequest req = builder().build(
                request("{\"Host\":\"evil.example.com\",\"Content-Length\":\"999\",\"X-Custom\":\"ok\"}"), 1);
        assertThat(req.headers().firstValue("X-Custom")).contains("ok");
        // 没有抛异常，且请求仍然可发送 —— 这本身就是 DoD
        assertThat(req.uri().getHost()).isEqualTo("vendor.example.com");
    }

    @Test
    void 无ContentType时默认json() {
        HttpRequest req = builder().build(request("{}"), 1);
        assertThat(req.headers().firstValue("Content-Type")).contains("application/json");
    }

    @Test
    void 调用方指定ContentType时尊重() {
        HttpRequest req = builder().build(request("{\"Content-Type\":\"application/xml\"}"), 1);
        assertThat(req.headers().firstValue("Content-Type")).contains("application/xml");
    }

    @Test
    void 数字型头值转字符串() {
        HttpRequest req = builder().build(request("{\"X-Retry\":3}"), 1);
        assertThat(req.headers().firstValue("X-Retry")).contains("3");
    }

    @Test
    void 非法头值被跳过_其余头仍然发出() {
        // 头值含换行是典型的 header injection 尝试，JDK 会拒绝设置
        HttpRequest req = builder().build(request("{\"X-Bad\":\"a\\r\\nb\",\"X-Good\":\"yes\"}"), 1);
        assertThat(req.headers().firstValue("X-Good")).contains("yes");
        assertThat(req.headers().firstValue("X-Bad")).isEmpty();
    }

    @Test
    void headers损坏时按无头继续发送() {
        HttpRequest req = builder().build(request("not-json"), 1);
        assertThat(req.headers().firstValue("X-Notification-Id")).contains("01ABC");
    }

    @Test
    void 空body用noBody但仍带方法() {
        DeliveryRequest empty = new DeliveryRequest("01ABC", "https://vendor.example.com/hook", "DELETE", null, null);
        HttpRequest req = builder().build(empty, 1);
        assertThat(req.method()).isEqualTo("DELETE");
        assertThat(req.bodyPublisher()).isPresent();
    }
}
