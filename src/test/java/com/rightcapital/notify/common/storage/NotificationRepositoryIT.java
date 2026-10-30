package com.rightcapital.notify.common.storage;

import com.rightcapital.notify.common.storage.NotificationRepository.NotificationRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T2.4/T2.5 DoD：Repository 的行为必须用真实 MySQL 验证 —— 认领 SQL 的
 * SKIP LOCKED 语义、租约回收、唯一键仲裁，H2 上根本测不出来。
 */
@SpringBootTest
@Testcontainers
class NotificationRepositoryIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        // 与生产保持同一纪律：DB 时间一律 UTC（D4.3）。容器默认会话时区跟随 JVM（GMT+8），
        // 会让 next_attempt_at 偏移 8 小时 —— 认领 SQL 的 UTC_TIMESTAMP(3) 比较全部落空
        registry.add("spring.datasource.url",
                () -> MYSQL.getJdbcUrl() + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    NotificationRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAllForTest();
    }

    private static NotificationRow row(String no, String caller, String key) {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        return new NotificationRow(0, no, caller, key,
                "https://hooks.vendor.example.com/v1", "POST",
                null, new byte[]{1},
                "PENDING", 0, 10, 0, now, null, null, null, null, now, now);
    }

    @Test
    void 插入后可按幂等键与单号查回() {
        NotificationRow r = row("01TESTNO0000000000000000A", "sys_ads", "order_paid:p1");
        repository.insertPending(r);

        assertThat(repository.findByIdempotencyKey("sys_ads", "order_paid:p1")).isPresent();
        assertThat(repository.findByNo("01TESTNO0000000000000000A")).isPresent();
        assertThat(repository.findByIdempotencyKey("sys_crm", "order_paid:p1")).isEmpty();
    }

    @Test
    void 同caller同幂等键_唯一索引拒绝() {
        repository.insertPending(row("01TESTNO0000000000000000B", "sys_ads", "order_paid:p2"));
        assertThatThrownBy(() -> repository.insertPending(row("01TESTNO0000000000000000C", "sys_ads", "order_paid:p2")))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void 同幂等键不同caller_互不冲突() {
        repository.insertPending(row("01TESTNO0000000000000000D", "sys_ads", "order_paid:p3"));
        org.assertj.core.api.Assertions.assertThatCode(
                        () -> repository.insertPending(row("01TESTNO0000000000000000E", "sys_crm", "order_paid:p3")))
                .doesNotThrowAnyException();
    }

    @Test
    void 认领_到期任务被认领_租约字段与计数正确() {
        repository.insertPending(row("01TESTNO0000000000000000F", "sys_ads", "order_paid:p4"));

        List<NotificationRow> claimed = repository.claimDue("worker-1", 60, 100);
        assertThat(claimed).hasSize(1);
        NotificationRow r = claimed.get(0);
        assertThat(r.status()).isEqualTo("PROCESSING");
        assertThat(r.leaseOwner()).startsWith("worker-1"); // lease_owner = workerId#批次token
        assertThat(r.attemptCount()).isEqualTo(1); // 认领即消耗预算（D4.2）
        assertThat(r.leaseUntil()).isNotNull();
    }

    @Test
    void 认领_未到期任务不可认领() {
        NotificationRow r = row("01TESTNO0000000000000000G", "sys_ads", "order_paid:p5");
        // 把 next_attempt_at 推到未来：模拟退避中
        NotificationRow future = new NotificationRow(0, r.notificationNo(), r.callerId(), r.idempotencyKey(),
                r.targetUrl(), r.httpMethod(), r.headersEnc(), r.bodyEnc(),
                "PENDING", 0, 10, 0,
                new Timestamp(System.currentTimeMillis() + 60_000),
                null, null, null, null, r.createdAt(), r.updatedAt());
        repository.insertPending(future);

        assertThat(repository.claimDue("worker-1", 60, 100)).isEmpty();
    }

    @Test
    void 认领_已认领的任务不会被第二个实例重复认领() {
        repository.insertPending(row("01TESTNO0000000000000000H", "sys_ads", "order_paid:p6"));

        List<NotificationRow> first = repository.claimDue("worker-1", 60, 100);
        List<NotificationRow> second = repository.claimDue("worker-2", 60, 100);
        // 已被 worker-1 认领且租约未过期 → worker-2 拿不到（T4.2 DoD ① 的单实例等价验证）
        assertThat(first).hasSize(1);
        assertThat(second).isEmpty();
    }

    @Test
    void 租约回收_过期的PROCESSING被重新认领() {
        repository.insertPending(row("01TESTNO0000000000000000I", "sys_ads", "order_paid:p7"));
        assertThat(repository.claimDue("worker-1", 60, 100)).hasSize(1);

        // 模拟 worker-1 崩溃：租约拨到过去（T4.2 DoD ②）
        repository.expireLeaseForTest("01TESTNO0000000000000000I");

        List<NotificationRow> reclaimed = repository.claimDue("worker-2", 60, 100);
        assertThat(reclaimed).hasSize(1);
        assertThat(reclaimed.get(0).leaseOwner()).startsWith("worker-2");
        assertThat(reclaimed.get(0).attemptCount()).isEqualTo(2); // 两次认领各 +1
    }

    @Test
    void 并发认领_无一条被重复认领() throws Exception {
        // T4.2 DoD ①：多实例并发执行 claimDue，行集必须互不相交
        int total = 200;
        for (int i = 0; i < total; i++) {
            repository.insertPending(row("01TESTNO%017dX".formatted(i), "sys_ads", "k:%04d".formatted(i)));
        }
        int workers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        Set<String> claimedNos = ConcurrentHashMap.newKeySet();
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger totalClaimed = new AtomicInteger();

        for (int w = 0; w < workers; w++) {
            final String workerId = "worker-" + w;
            pool.submit(() -> {
                start.await();
                for (int round = 0; round < 20; round++) {
                    List<NotificationRow> batch = repository.claimDue(workerId, 60, 100);
                    totalClaimed.addAndGet(batch.size());
                    batch.forEach(r -> assertThat(claimedNos.add(r.notificationNo()))
                            .as("任务 %s 被重复认领", r.notificationNo()).isTrue());
                    if (claimedNos.size() >= total) {
                        break;
                    }
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        assertThat(claimedNos).hasSize(total); // 每条恰好被认领一次
        assertThat(totalClaimed.get()).isEqualTo(total);
    }

    @Test
    void 条件状态更新_状态不匹配时影响零行() {
        NotificationRow r = row("01TESTNO0000000000000000J", "sys_ads", "order_paid:p8");
        repository.insertPending(r);
        long id = repository.findByIdempotencyKey("sys_ads", "order_paid:p8").orElseThrow().id();

        assertThat(repository.updateStatusGuarded(id, "RETRY_WAIT", "DEAD")).isZero(); // 状态守卫生效
        assertThat(repository.updateStatusGuarded(id, "PENDING", "PROCESSING")).isEqualTo(1);
        assertThat(repository.updateStatusGuarded(id, "PENDING", "PROCESSING")).isZero(); // 已不是 PENDING
    }

    @Test
    void 背压计数_PENDING与RETRY_WAIT均计入() {
        repository.insertPending(row("01TESTNO0000000000000000K", "sys_ads", "order_paid:p9"));
        long before = repository.countPending();
        assertThat(repository.claimDue("worker-1", 60, 100)).hasSize(1);
        assertThat(repository.countPending()).isEqualTo(before - 1); // PROCESSING 不计入积压
    }
}
