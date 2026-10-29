package com.rightcapital.notify.common.statemachine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 状态机单测（T2.3 DoD：非法迁移抛异常）。
 * 重点覆盖两条最容易写错的路径：认领（PENDING→PROCESSING）与重放（DEAD→PENDING）。
 */
class NotificationStatusTest {

    @Test
    void 认领路径_合法() {
        NotificationStatus.PENDING.assertTransitionTo(NotificationStatus.PROCESSING);
        NotificationStatus.RETRY_WAIT.assertTransitionTo(NotificationStatus.PROCESSING);
    }

    @Test
    void 结果落定路径_合法() {
        NotificationStatus.PROCESSING.assertTransitionTo(NotificationStatus.SUCCEEDED);
        NotificationStatus.PROCESSING.assertTransitionTo(NotificationStatus.RETRY_WAIT);
        NotificationStatus.PROCESSING.assertTransitionTo(NotificationStatus.DEAD);
    }

    @Test
    void 重放路径_仅DEAD可回PENDING() {
        NotificationStatus.DEAD.assertTransitionTo(NotificationStatus.PENDING);
        // SUCCEEDED 是终态：被供应商成功接收过的通知不允许重放（重放 = 主动制造重复投递）
        assertThat(NotificationStatus.SUCCEEDED.canTransitionTo(NotificationStatus.PENDING)).isFalse();
        assertThatThrownBy(() -> NotificationStatus.SUCCEEDED.assertTransitionTo(NotificationStatus.PROCESSING))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void 非法迁移_抛异常() {
        // 常见错误：跳过认领直接把 PENDING 改成 SUCCEEDED（绕过投递引擎）
        assertThatThrownBy(() -> NotificationStatus.PENDING.assertTransitionTo(NotificationStatus.SUCCEEDED))
                .isInstanceOf(IllegalStateTransitionException.class);
        // PROCESSING 不允许直接回 PENDING：崩溃恢复走「租约过期重新认领」，不是回退状态
        assertThatThrownBy(() -> NotificationStatus.PROCESSING.assertTransitionTo(NotificationStatus.PENDING))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void 终态_除DEAD重放外不可迁出() {
        assertThat(NotificationStatus.CANCELED.canTransitionTo(NotificationStatus.PENDING)).isFalse();
        assertThat(NotificationStatus.SUCCEEDED.canTransitionTo(NotificationStatus.RETRY_WAIT)).isFalse();
    }
}
