package com.rightcapital.notify.dispatcher;

import com.rightcapital.notify.config.NotificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 投递引擎的轮询入口（T4.1 骨架）。
 *
 * <p>每轮做的事（P4 逐项实现）：
 * ① claimDue —— SKIP LOCKED 批量认领到期任务 + 租约回收（T2.5 / T4.2）；
 * ② 认领条数不超过空闲 worker 数（D4.5：否则自己制造重复认领）；
 * ③ 空 results 时走空轮询退避（T4.10，DB QPS 可测下降）。</p>
 *
 * <p>轮询间隔是配置项（application.yml notification.dispatch.poll-interval-ms）。
 * 强时效接入的降级路径见设计文档 §9.8：调间隔 → insert-then-hint（T4.12）→ 跨实例信号。</p>
 */
@Component
public class DispatcherPoller {

    private static final Logger log = LoggerFactory.getLogger(DispatcherPoller.class);

    private final NotificationProperties properties;

    public DispatcherPoller(NotificationProperties properties) {
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${notification.dispatch.poll-interval-ms:500}")
    public void poll() {
        // TODO(T4.2): claimDue —— 认领 SQL 见设计文档 D4.4
        // TODO(T4.3): 断言 batchSize <= 空闲 worker 数
        log.debug("poll tick, batchSize={}, lease={}s",
                properties.dispatch().batchSize(), properties.dispatch().leaseDurationSeconds());
    }
}
