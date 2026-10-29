package com.rightcapital.notify.delivery;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4.1 DoD 的一半：槽位必须精确 —— 调度器靠它决定认领几条，多算一条就是一次重复投递。
 */
class WorkerPoolTest {

    @Test
    void 槽位随任务提交与完成增减() throws Exception {
        WorkerPool pool = new WorkerPool(2, 5);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        assertThat(pool.freeSlots()).isEqualTo(2);
        assertThat(pool.tryAcquireSlot()).isTrue();
        assertThat(pool.freeSlots()).isEqualTo(1);

        pool.submit(() -> {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        pool.tryAcquireSlot();
        assertThat(pool.tryAcquireSlot()).isFalse(); // 两个槽位都占了

        release.countDown();
        // 任务结束会归还槽位 —— 等到 2 个可用
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (pool.freeSlots() < 1 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(pool.freeSlots()).isEqualTo(1); // 第二个仍然被本测试占着
        pool.releaseSlot();
        assertThat(pool.freeSlots()).isEqualTo(2);
        pool.shutdownAndAwait();
    }

    @Test
    void 停机后不再接受新槽位() {
        WorkerPool pool = new WorkerPool(2, 1);
        pool.shutdownAndAwait();
        assertThat(pool.isShuttingDown()).isTrue();
        assertThat(pool.tryAcquireSlot()).isFalse();
    }

    @Test
    void 停机时会等待在途任务完成() throws Exception {
        WorkerPool pool = new WorkerPool(2, 5);
        CountDownLatch done = new CountDownLatch(1);
        pool.tryAcquireSlot();
        pool.submit(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });

        pool.shutdownAndAwait();
        // 优雅停机的语义：shutdownAndAwait 返回时，在途任务已经结束
        assertThat(done.getCount()).isZero();
        assertThat(pool.freeSlots()).isEqualTo(2);
    }
}
