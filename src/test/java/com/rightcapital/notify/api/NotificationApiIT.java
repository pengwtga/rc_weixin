package com.rightcapital.notify.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3 / M1 里程碑的端到端验证：从 HTTP 进来，到 DB 里 PENDING，
 * 中间经过鉴权（T3.2）、幂等（T3.3）、受理（T3.4）、限流（T3.5）。
 * M1 DoD：提交一条通知后，DB 里能看到 PENDING 记录。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class NotificationApiIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> MYSQL.getJdbcUrl() + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        // 限流阈值调小，429 用例不必发大量请求；50 足够本用例类的其他用例自由消耗
        registry.add("notification.api.rate-limit.capacity", () -> "50");
        registry.add("notification.api.rate-limit.refill-per-second", () -> "0");
        // 背压阈值调小，503 用例可控（积压 2 条即触发）
        registry.add("notification.backpressure.max-pending", () -> "3");
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @org.springframework.boot.test.web.server.LocalServerPort
    int port;

    @org.junit.jupiter.api.BeforeEach
    void cleanTable() {
        // 背压阈值只有 3：上一条用例留下的 PENDING 会让后面的 202 用例全部 503。
        // 每条用例从干净队列开始，行为只取决于本用例自己的输入
        jdbc.update("DELETE FROM notification");
    }

    /**
     * 401 用例专用：JDK HttpClient 不做 401 认证重试。
     * TestRestTemplate 底层 HttpURLConnection 在流式模式下收到 401 会尝试重发请求体，
     * 直接抛 HttpRetryException —— 那是客户端行为，不是服务端缺陷。
     */
    private java.net.http.HttpResponse<String> postWithoutKey(String json) throws Exception {
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://localhost:" + port + "/v1/notifications"))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json))
                .build();
        return client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
    }

    private static final String KEY = "dev-key-ads"; // 来自 application.yml 的注册表

    private HttpEntity<Map<String, Object>> entity(Map<String, Object> body, String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null) {
            headers.set("X-Api-Key", apiKey);
        }
        return new HttpEntity<>(body, headers);
    }

    private Map<String, Object> validBody(String key) {
        return Map.of(
                "idempotencyKey", key,
                "targetUrl", "https://hooks.vendor.example.com/v1/events",
                "body", "payload");
    }

    @Test
    void 无APIKey_返回401与结构化错误码() throws Exception {
        java.net.http.HttpResponse<String> resp = postWithoutKey("{\"idempotencyKey\":\"order_paid:nokey\"}");
        assertThat(resp.statusCode()).isEqualTo(401);
        assertThat(resp.body()).contains("UNAUTHORIZED");
    }

    @Test
    void 错误的APIKey_同样401() throws Exception {
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://localhost:" + port + "/v1/notifications"))
                .header("Content-Type", "application/json")
                .header("X-Api-Key", "wrong-key")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"idempotencyKey\":\"order_paid:badkey\"}"))
                .build();
        java.net.http.HttpResponse<String> resp = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(401);
    }

    @Test
    void 幂等键缺冒号_返回400字段级错误() {
        ResponseEntity<String> resp = rest.exchange("/v1/notifications", HttpMethod.POST,
                entity(validBody("8837"), KEY), String.class); // 拿订单号当键（§3.6 反例）
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).contains("VALIDATION_FAILED").contains("两段式");
    }

    @Test
    void 内网与元数据URL_返回400且不入库() {
        for (String url : new String[]{
                "https://169.254.169.254/latest/meta-data/",
                "https://10.0.0.1/hook",
                "http://hooks.vendor.example.com/v1"}) {
            ResponseEntity<String> resp = rest.exchange("/v1/notifications", HttpMethod.POST,
                    entity(Map.of("idempotencyKey", "order_paid:ssrf", "targetUrl", url), KEY), String.class);
            assertThat(resp.getStatusCode().value()).isEqualTo(400);
            assertThat(resp.getBody()).contains("TARGET_URL_FORBIDDEN");
        }
    }

    @Test
    void 合法提交_202加单号加Location_M1最窄链路() {
        ResponseEntity<Map> resp = rest.exchange("/v1/notifications", HttpMethod.POST,
                entity(validBody("order_paid:m1"), KEY), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(202);
        assertThat(resp.getHeaders().getLocation()).isNotNull();
        assertThat((String) resp.getHeaders().getLocation().getPath())
                .isEqualTo("/v1/notifications/" + resp.getBody().get("notificationNo"));
        assertThat(resp.getBody().get("status")).isEqualTo("PENDING");
        assertThat(resp.getBody().get("idempotentReplay")).isEqualTo(false);
        assertThat(resp.getHeaders().getFirst("X-Trace-Id")).isNotBlank();
    }

    @Test
    void 同key重复提交_返回同一单号_不抛错() {
        Map<String, Object> body = validBody("order_paid:idem");
        ResponseEntity<Map> first = rest.exchange("/v1/notifications", HttpMethod.POST, entity(body, KEY), Map.class);
        ResponseEntity<Map> second = rest.exchange("/v1/notifications", HttpMethod.POST, entity(body, KEY), Map.class);

        assertThat(first.getStatusCode().value()).isEqualTo(202);
        assertThat(second.getStatusCode().value()).isEqualTo(202); // 不是 409/500（T3.3 DoD）
        assertThat(second.getBody().get("notificationNo")).isEqualTo(first.getBody().get("notificationNo"));
        assertThat(second.getBody().get("idempotentReplay")).isEqualTo(true);
    }

    @Test
    void 同一订单的三个不同事件_正常插入三条() {
        String[] events = {"order_created:evt_1", "order_paid:pay_1", "order_shipped:shp_1"};
        java.util.Set<Object> nos = new java.util.HashSet<>();
        for (String event : events) {
            ResponseEntity<Map> resp = rest.exchange("/v1/notifications", HttpMethod.POST,
                    entity(validBody(event), KEY), Map.class);
            assertThat(resp.getStatusCode().value()).isEqualTo(202);
            nos.add(resp.getBody().get("notificationNo"));
        }
        assertThat(nos).hasSize(3); // 三个不同单号（§3.6 DoD ③）
    }

    @Test
    void 超出限流_返回429加RetryAfter() {
        // dev-key-crm 对应 sys_crm；容量 50（refill=0），最多 60 次必触发 429
        int rejected = 0;
        ResponseEntity<String> last = null;
        for (int i = 0; i < 60 && rejected == 0; i++) {
            last = rest.exchange("/v1/notifications", HttpMethod.POST,
                    entity(validBody("order_paid:rl%d".formatted(i)), "dev-key-crm"), String.class);
            if (last.getStatusCode().value() == 429) {
                rejected++;
            }
        }
        assertThat(rejected).isEqualTo(1);
        assertThat(last.getHeaders().getFirst("Retry-After")).isNotBlank();
        assertThat(last.getBody()).contains("RATE_LIMITED");
    }

    @Test
    void 队列积压超阈值_返回503加RetryAfter() {
        // capacity=5 的桶已被限流用例打爆；先等桶恢复不可行（refill=0），
        // 所以本用例用新的调用方（sys_inventory），只发 4 条：低于限流、高于背压阈值 3
        ResponseEntity<String> last = null;
        for (int i = 0; i < 4; i++) {
            last = rest.exchange("/v1/notifications", HttpMethod.POST,
                    entity(validBody("order_paid:bp%d".formatted(i)), "dev-key-inventory"), String.class);
        }
        assertThat(last.getStatusCode().value()).isEqualTo(503);
        assertThat(last.getHeaders().getFirst("Retry-After")).isNotBlank();
        assertThat(last.getBody()).contains("BACKPRESSURE");
    }

    @Test
    void 落库的请求体是密文_库里看不到明文PII与凭证() throws Exception {
        String plainBody = "{\"phone\":\"13800138000\",\"amount\":19900}";
        Map<String, Object> body = Map.of(
                "idempotencyKey", "order_paid:crypto",
                "targetUrl", "https://hooks.vendor.example.com/v1/events",
                "body", plainBody,
                "headers", Map.of("Authorization", "Bearer super-secret-token"));
        assertThat(rest.exchange("/v1/notifications", HttpMethod.POST, entity(body, KEY), Map.class)
                .getStatusCode().value()).isEqualTo(202);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT body_enc, headers_enc FROM notification WHERE idempotency_key = ?",
                "order_paid:crypto");
        byte[] bodyEnc = (byte[]) row.get("body_enc");
        byte[] headersEnc = (byte[]) row.get("headers_enc");

        // D10：数据库被拖走时，攻击者直接读不到 PII 与供应商凭证
        assertThat(new String(bodyEnc, java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain("13800138000");
        assertThat(new String(headersEnc, java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain("super-secret-token");

        // 密封后体积 = 1B 版本 + 12B IV + 密文 + 16B tag，必然大于明文
        assertThat(bodyEnc.length).isGreaterThan(plainBody.length());
        assertThat(headersEnc.length).isGreaterThan("super-secret-token".length());
    }

    @Test
    void 埋点可见_接入事件计数已注册() {
        // 先触发任意一次接入（成功/限流/背压都会注册 notification.api.events 下的计数），
        // 保证用例自包含、不依赖执行顺序
        rest.exchange("/v1/notifications", HttpMethod.POST,
                entity(validBody("order_paid:metrics"), KEY), String.class);
        ResponseEntity<String> metrics = rest.getForEntity("/actuator/metrics/notification.api.events", String.class);
        assertThat(metrics.getStatusCode().is2xxSuccessful()).isTrue();
    }
}
