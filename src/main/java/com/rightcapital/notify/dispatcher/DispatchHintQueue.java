package com.rightcapital.notify.dispatcher;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * 提交后唤醒队列（T4.12 insert-then-hint）。
 *
 * <p>解决的问题：轮询间隔 500ms，意味着一条刚受理的通知平均要等 250ms 才被认领。
 * 对"用户付款后立刻通知 CRM"这类场景，这 250ms 是白送的。做法是：落库成功后
 * 把单号塞进这个内存队列，调度器每轮先看队列，有就直接认领，延迟降到毫秒级。</p>
 *
 * <p><b>它是提示，不是队列</b>——这个区分决定了整个设计能不能成立：</p>
 * <ul>
 *   <li>队列满 / 进程重启 / 多实例部署（hint 只在本 JVM）→ 提示丢失，
 *       但通知仍在 MySQL 里，500ms 轮询会兜底。**语义不变，只是慢一点**。</li>
 *   <li>因此这里可以放心用有界队列 + 丢弃策略。若把它当"可靠队列"设计，
 *       就得为它做持久化、做重投 —— 那就等于在 MySQL 之外又养一个队列，
 *       违背"数据库即队列"的初衷。</li>
 * </ul>
 */
@Component
public class DispatchHintQueue {

    /**
     * 容量取 1024：远大于单次突发受理量（150 QPS × 0.5s ≈ 75），
     * 但又不至于在调度停摆时吃满内存。满了就丢，丢的是"加速提示"而不是任务。
     */
    private static final int CAPACITY = 1024;

    private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(CAPACITY);
    private final MeterRegistry meterRegistry;

    public DispatchHintQueue(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * 投递一个提示。永不阻塞、永不抛异常 —— 受理路径不能因为"加速通道"满而失败。
     *
     * @return true 表示入队成功；false 表示队列已满（此时靠轮询兜底）
     */
    public boolean offer(String notificationNo) {
        boolean accepted = queue.offer(notificationNo);
        if (!accepted) {
            meterRegistry.counter("notification.dispatch.hint_dropped").increment();
        }
        return accepted;
    }

    /** 取出至多 {@code max} 个单号（不等待）。 */
    public List<String> drain(int max) {
        if (max <= 0) {
            return List.of();
        }
        List<String> drained = new ArrayList<>(Math.min(max, 64));
        queue.drainTo(drained, max);
        return drained;
    }

    public int size() {
        return queue.size();
    }
}
