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
        assertThatCode(() -> new NotificationProperties(DISPATCH, http, RETRY, null, null, null, null))
                .doesNotThrowAnyException();
    }

    @Test
    void 非法配置_租约短于总超时_启动失败() {
        // 租约 15s = 总超时 15s，无余量：慢请求期间租约先过期 → 双投
        NotificationProperties.Http http = new NotificationProperties.Http(3_000, 15_000);
        NotificationProperties.Dispatch badDispatch = new NotificationProperties.Dispatch(500, 100, 15);
        assertThatThrownBy(() -> new NotificationProperties(badDispatch, http, RETRY, null, null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lease-duration-seconds");
    }

    @Test
    void 非法配置_租约等于总超时加4s余量_仍失败() {
        // 15s + 5s 余量 = 20s；租约恰好 20s 不满足「>」，同样拒绝
        NotificationProperties.Http http = new NotificationProperties.Http(3_000, 15_000);
        NotificationProperties.Dispatch badDispatch = new NotificationProperties.Dispatch(500, 100, 20);
        assertThatThrownBy(() -> new NotificationProperties(badDispatch, http, RETRY, null, null, null, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 生产profile_禁止使用内置开发密钥() {
        // 密钥与代码一起躺在仓库里，"加密"就只是把明文换了个名字（D10）
        // 校验在 Crypto 的紧凑构造器里 —— 所以构造必须写在 lambda 内，异常才会被 assertThatThrownBy 捕获
        NotificationProperties.Http http = new NotificationProperties.Http(3_000, 15_000);

        assertThatThrownBy(() -> new NotificationProperties(DISPATCH, http, RETRY, null, null, null,
                new NotificationProperties.Crypto(null, null, true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reject-dev-keys");
    }

    @Test
    void 加密与指纹不得共用同一把密钥() {
        NotificationProperties.Http http = new NotificationProperties.Http(3_000, 15_000);

        assertThatThrownBy(() -> new NotificationProperties(DISPATCH, http, RETRY, null, null, null,
                new NotificationProperties.Crypto(
                        NotificationProperties.Crypto.DEV_BODY_KEY,
                        NotificationProperties.Crypto.DEV_BODY_KEY,
                        false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("密钥分离");
    }

    @Test
    void 密钥不是32字节_启动失败() {
        NotificationProperties.Http http = new NotificationProperties.Http(3_000, 15_000);
        // 16 字节（AES-128）长度不对 —— 统一要求 256 位，避免"看起来配了但强度不足"
        String shortKey = java.util.Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new NotificationProperties(DISPATCH, http, RETRY, null, null, null,
                new NotificationProperties.Crypto(shortKey, NotificationProperties.Crypto.DEV_FINGERPRINT_KEY, false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 字节");
    }
}
