package com.rightcapital.notify.common.statemachine;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 通知状态机（设计文档 §3.2）。
 *
 * <p>合法迁移在此集中定义并由 {@link #assertTransitionTo} 强制校验，
 * 不允许散落在各处的裸 UPDATE。`SUCCEEDED` / `CANCELED` 是终态；
 * 仅 `DEAD` 可通过显式 replay 回到 `PENDING`（D8，同时 replay_epoch + 1）。</p>
 */
public enum NotificationStatus {

    /** 已落库，等待首次投递。 */
    PENDING,
    /** 本轮失败，退避等待下次重试。 */
    RETRY_WAIT,
    /** 已被某实例认领（租约期内）。 */
    PROCESSING,
    /** 终态：供应商返回 2xx。 */
    SUCCEEDED,
    /** 终态：重试预算耗尽或永久性失败（4xx）；显式 replay 可回 PENDING。 */
    DEAD,
    /** 终态：调用方主动取消。 */
    CANCELED;

    private static final Map<NotificationStatus, Set<NotificationStatus>> ALLOWED = Map.of(
            PENDING, EnumSet.of(PROCESSING, CANCELED),
            RETRY_WAIT, EnumSet.of(PROCESSING, CANCELED),
            PROCESSING, EnumSet.of(SUCCEEDED, RETRY_WAIT, DEAD),
            DEAD, EnumSet.of(PENDING),
            SUCCEEDED, EnumSet.noneOf(NotificationStatus.class),
            CANCELED, EnumSet.noneOf(NotificationStatus.class));

    public boolean canTransitionTo(NotificationStatus target) {
        return ALLOWED.get(this).contains(target);
    }

    /**
     * 校验迁移合法性，非法则抛 {@link IllegalStateTransitionException}。
     *
     * <p>调用点约定：所有状态 UPDATE 必须带「WHERE status = 期望的当前状态」守卫，
     * 本方法用于代码路径的显式校验；并发下真正的仲裁靠 DB 行的条件更新。</p>
     */
    public void assertTransitionTo(NotificationStatus target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateTransitionException(this, target);
        }
    }
}
