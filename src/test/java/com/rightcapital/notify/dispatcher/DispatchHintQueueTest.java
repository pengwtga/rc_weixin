package com.rightcapital.notify.dispatcher;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4.12 DoD ②：hint 丢失（队列满）时语义不变 —— 任务仍会被 500ms 轮询捡起来。
 * 这里能验证的是"不抛、不阻塞、丢弃有计数"；"仍被投递"由 DeliveryEngineIT 端到端验证。
 */
class DispatchHintQueueTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    void 入队出队保持顺序() {
        DispatchHintQueue queue = new DispatchHintQueue(meterRegistry);
        queue.offer("A");
        queue.offer("B");
        assertThat(queue.drain(10)).containsExactly("A", "B");
        assertThat(queue.size()).isZero();
    }

    @Test
    void drain受上限约束() {
        DispatchHintQueue queue = new DispatchHintQueue(meterRegistry);
        for (int i = 0; i < 5; i++) {
            queue.offer("N" + i);
        }
        assertThat(queue.drain(2)).hasSize(2);
        assertThat(queue.size()).isEqualTo(3);
    }

    @Test
    void 队列满时静默丢弃并计数() {
        // 受理路径不能因为"加速通道"满而失败：offer 必须返回 false 而不是抛
        DispatchHintQueue queue = new DispatchHintQueue(meterRegistry);
        int accepted = 0;
        for (int i = 0; i < 2000; i++) {
            if (queue.offer("N" + i)) {
                accepted++;
            }
        }
        assertThat(accepted).isEqualTo(1024);
        assertThat(meterRegistry.counter("notification.dispatch.hint_dropped").count())
                .isEqualTo(2000 - 1024);
    }

    @Test
    void drain零或负数返回空() {
        DispatchHintQueue queue = new DispatchHintQueue(meterRegistry);
        queue.offer("A");
        assertThat(queue.drain(0)).isEqualTo(List.of());
        assertThat(queue.drain(-3)).isEqualTo(List.of());
        assertThat(queue.size()).isEqualTo(1);
    }
}
