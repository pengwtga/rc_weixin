package com.rightcapital.notify.delivery;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.rightcapital.notify.common.crypto.ContentFingerprint;
import com.rightcapital.notify.common.crypto.PayloadCipher;
import com.rightcapital.notify.common.storage.DeliveryAttemptRepository;
import com.rightcapital.notify.common.storage.NotificationRepository;
import com.rightcapital.notify.common.storage.NotificationRepository.NotificationRow;
import com.rightcapital.notify.config.NotificationProperties;
import com.rightcapital.notify.dispatcher.DispatcherPoller;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P4 端到端验收（M2）：成功链路、可重试失败链路、永久失败链路、超时链路、host 隔离链路。
 *
 * <p>为什么必须用真实 HTTP 服务而不是 mock 掉 HttpClient：
 * 这一层要验证的恰恰是"请求真的发出去了、头真的带上了、对方返回 500 时我们真的没重试"。
 * Mock 掉客户端之后，这些断言全部退化成"我调用了自己写的方法"。</p>
 *
 * <p>调度器自动轮询在测试里被关掉（间隔设为 1 小时），改为手动 {@code pollOnce()} ——
 * 否则断言会和后台线程抢时序，测试变成薛定谔的。</p>
 */
@SpringBootTest(properties = {
        "notification.dispatch.auto-start=false",
        "notification.dispatch.poll-interval-ms=3600000",
        "notification.dispatch.idle-backoff-max-ms=3600000",
        "notification.dispatch.lease-duration-seconds=30",
        "notification.http.total-timeout-ms=2000",
        "notification.http.connect-timeout-ms=1000"
})
@Testcontainers
class DeliveryEngineIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    /** 静态初始化：@DynamicPropertySource 需要在 context 建起来之前拿到端口。 */
    static final WireMockServer WIRE_MOCK = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        WIRE_MOCK.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> MYSQL.getJdbcUrl() + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("test.wiremock.port", WIRE_MOCK::port);
    }

    @AfterAll
    static void stopWireMock() {
        WIRE_MOCK.stop();
    }

    @Autowired
    NotificationRepository repository;
    @Autowired
    DeliveryAttemptRepository attemptRepository;
    @Autowired
    DispatcherPoller dispatcher;
    @Autowired
    PayloadCipher cipher;
    @Autowired
    ContentFingerprint fingerprint;
    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired
    HostConcurrencyLimiter hostLimiter;
    @Autowired
    NotificationProperties properties;
    @Autowired
    com.rightcapital.notify.dispatcher.DispatchHintQueue hintQueue;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM delivery_attempt");
        jdbc.update("DELETE FROM notification");
        WIRE_MOCK.resetAll();
        hintQueue.drain(Integer.MAX_VALUE);
    }

    // ───────────────────────── 用例 ─────────────────────────

    @Test
    void 成功链路_2xx_落SUCCEEDED并留下投递明细() {
        WIRE_MOCK.stubFor(post(urlEqualTo("/ok")).willReturn(aResponse().withStatus(200).withBody("{\"accepted\":true}")));
        String no = "01ITSUCCESS000000000000001";
        insertPending(no, url("/ok"));

        assertThat(dispatcher.pollOnce()).isEqualTo(1);

        NotificationRow row = awaitStatus(no, "SUCCEEDED");
        assertThat(row.attemptCount()).isEqualTo(1);
        assertThat(row.leaseOwner()).isNull();  // 落定必须清租约，否则要等 60s 才回收
        assertThat(row.leaseUntil()).isNull();
        assertThat(row.lastStatusCode()).isEqualTo(200);
        assertThat(attemptRepository.countByNotificationId(row.id())).isEqualTo(1);

        // 下游去重契约：没有这个头，at-least-once 就是"重复执行业务动作"
        assertThat(WIRE_MOCK.findAll(postRequestedFor(urlEqualTo("/ok"))))
                .singleElement()
                .satisfies(req -> assertThat(req.header("X-Notification-Id").firstValue()).isEqualTo(no));
    }

    @Test
    void 服务端错误_可重试_落RETRY_WAIT且记录错误分类() {
        WIRE_MOCK.stubFor(post(urlEqualTo("/boom")).willReturn(aResponse().withStatus(500).withBody("internal error")));
        String no = "01ITRETRY00000000000000001";
        insertPending(no, url("/boom"));

        dispatcher.pollOnce();

        NotificationRow row = awaitStatus(no, "RETRY_WAIT");
        assertThat(row.attemptCount()).isEqualTo(1);
        assertThat(row.lastErrorClass()).isEqualTo("HTTP_5XX");
        assertThat(row.lastStatusCode()).isEqualTo(500);
        assertThat(row.nextAttemptAt()).isAfter(new Timestamp(System.currentTimeMillis() - 1000));
        assertThat(attemptRepository.countByNotificationId(row.id())).isEqualTo(1);
        // 只发了一次 —— 重试由 next_attempt_at 驱动，不在本次调用里循环
        assertThat(WIRE_MOCK.findAll(postRequestedFor(urlEqualTo("/boom")))).hasSize(1);
    }

    @Test
    void 客户端错误400_一次就进死信() {
        WIRE_MOCK.stubFor(post(urlEqualTo("/bad")).willReturn(aResponse().withStatus(400).withBody("invalid payload")));
        String no = "01ITDEAD000000000000000001";
        insertPending(no, url("/bad"));

        dispatcher.pollOnce();

        NotificationRow row = awaitStatus(no, "DEAD");
        assertThat(row.lastErrorClass()).isEqualTo("HTTP_4XX");
        assertThat(row.attemptCount()).isEqualTo(1);
        // 4xx 重试没有任何意义：多试 9 次只是把一次配置错误放大成 9 次告警
        assertThat(WIRE_MOCK.findAll(postRequestedFor(urlEqualTo("/bad")))).hasSize(1);
    }

    @Test
    void 限流429_可重试_并保留状态码() {
        WIRE_MOCK.stubFor(post(urlEqualTo("/limited"))
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "30")));
        String no = "01ITLIMIT00000000000000001";
        insertPending(no, url("/limited"));

        dispatcher.pollOnce();

        NotificationRow row = awaitStatus(no, "RETRY_WAIT");
        assertThat(row.lastStatusCode()).isEqualTo(429);
        assertThat(row.lastErrorClass()).isEqualTo("HTTP_4XX");
    }

    @Test
    void 超时_归TIMEOUT而不是连接失败() {
        // 延迟 3s > total timeout 2s：模拟下游"接了连接但不响应"
        WIRE_MOCK.stubFor(post(urlEqualTo("/slow")).willReturn(aResponse().withStatus(200).withFixedDelay(3000)));
        String no = "01ITTIMEOUT000000000000001";
        insertPending(no, url("/slow"));

        dispatcher.pollOnce();

        NotificationRow row = awaitStatus(no, "RETRY_WAIT");
        assertThat(row.lastErrorClass()).isEqualTo("TIMEOUT");
        assertThat(row.lastStatusCode()).isNull(); // 网络层失败没有状态码，与"收到 0"区分
    }

    @Test
    void host并发已满_退回队列且回退尝试次数() {
        String no = "01ITHOSTFULL00000000000001";
        String target = url("/busy");
        // 占满该 host 的全部许可（模拟"这家供应商正在被 8 个 worker 打着"）
        int permits = properties.dispatch().hostConcurrency();
        for (int i = 0; i < permits; i++) {
            assertThat(hostLimiter.tryAcquire(target)).isTrue();
        }
        try {
            insertPending(no, target);
            dispatcher.pollOnce();

            NotificationRow row = awaitStatus(no, "RETRY_WAIT");
            // 核心断言：没发出去就不算一次尝试。否则对方挂 30 分钟，
            // 健康任务会被自己的调度逻辑耗成死信（T5.7 的同款问题）
            assertThat(row.attemptCount()).isZero();
        } finally {
            for (int i = 0; i < permits; i++) {
                hostLimiter.release(target);
            }
        }
        assertThat(WIRE_MOCK.findAll(postRequestedFor(urlEqualTo("/busy")))).isEmpty();
    }

    @Test
    void hint被消费_且同一条不会因两条认领路径重复投递() {
        WIRE_MOCK.stubFor(post(urlEqualTo("/hint")).willReturn(aResponse().withStatus(200)));
        String no = "01ITHINT000000000000000001";
        insertPending(no, url("/hint"));
        // 模拟受理侧 insert-then-hint：落库成功后把单号塞进交接队列
        assertThat(hintQueue.offer(no)).isTrue();

        long start = System.nanoTime();
        dispatcher.pollOnce();
        NotificationRow row = awaitStatus(no, "SUCCEEDED");
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(row.status()).isEqualTo("SUCCEEDED");
        assertThat(hintQueue.size()).isZero(); // 提示已被消费
        // 关键：hint 路径与轮询路径不能各投一次。同一条任务只能被认领一次，
        // 认领的互斥由「UPDATE ... WHERE status IN ('PENDING','RETRY_WAIT')」保证
        assertThat(WIRE_MOCK.findAll(postRequestedFor(urlEqualTo("/hint")))).hasSize(1);
        assertThat(elapsedMs).isLessThan(1_500);
    }

    // ───────────────────────── 辅助 ─────────────────────────

    private String url(String path) {
        return "http://127.0.0.1:" + WIRE_MOCK.port() + path;
    }

    private void insertPending(String no, String targetUrl) {
        byte[] body = "{\"status\":\"paid\",\"phone\":\"13800138000\"}".getBytes(StandardCharsets.UTF_8);
        byte[] headers = "{\"X-Vendor-Token\":\"super-secret\"}".getBytes(StandardCharsets.UTF_8);
        Timestamp now = new Timestamp(System.currentTimeMillis());
        repository.insertPending(new NotificationRow(
                0, no, "sys_ads", "order_paid:" + no, targetUrl, "POST",
                cipher.seal(headers, PayloadCipher.aadFor(no, "headers")),
                cipher.seal(body, PayloadCipher.aadFor(no, "body")),
                fingerprint.of(body),
                "PENDING", 0, 10, 0, now, null, null, null, null, now, now));
    }

    /** 等待状态落定；投递是异步的，断言必须等而不是 sleep 一个固定值。 */
    private NotificationRow awaitStatus(String no, String expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        NotificationRow last = null;
        while (System.nanoTime() < deadline) {
            last = repository.findByNo(no).orElseThrow();
            if (expected.equals(last.status())) {
                return last;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("等待状态 %s 超时，当前=%s（no=%s）"
                .formatted(expected, last == null ? "null" : last.status(), no));
    }
}
