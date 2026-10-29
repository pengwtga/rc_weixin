package com.rightcapital.notify.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D4.5 启动断言：租约必须 > HTTP 总超时 + 余量。
 * 这是「错误配置宁可在启动时失败，不在运行时丢正确性」的体现。
 */
class NotificationPropertiesTest {

    private static final NotificationProperties.Dispatch DISPATCH = new NotificationProperties.Dispatch(500, 100, 60);
    private static final NotificationProperties.Retry RETRY = new NotificationProperties.Retry(10);

    @Test
    void 合法配置_租约60s_总超时15s_通过() {
        NotificationProperties.Http http = new NotificationProperties.Http(3_000, 15_000);
        // 接入侧参数传 null 走默认值；本测试只关心 D4.5 断言
        assertThatCode(() -> new NotificationProperties(DISPATCH, http, RETRY, null, null, null))
                .doesNotThrowAnyException();
    }

    @Test
    void 非法配置_租约短于总超时_启动失败() {
        // 租约 15s = 总超时 15s，无余量：慢请求期间租约先过期 → 双投
        NotificationProperties.Http http = new NotificationProperties.Http(3_000, 15_000);
        NotificationProperties.Dispatch badDispatch = new NotificationProperties.Dispatch(500, 100, 15);
        assertThatThrownBy(() -> new NotificationProperties(badDispatch, http, RETRY, null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lease-duration-seconds");
    }

    @Test
    void 非法配置_租约等于总超时加4s余量_仍失败() {
        // 15s + 5s 余量 = 20s；租约恰好 20s 不满足「>」，同样拒绝
        NotificationProperties.Http http = new NotificationProperties.Http(3_000, 15_000);
        NotificationProperties.Dispatch badDispatch = new NotificationProperties.Dispatch(500, 100, 20);
        assertThatThrownBy(() -> new NotificationProperties(badDispatch, http, RETRY, null, null, null))
                .isInstanceOf(IllegalStateException.class);
    }
}
