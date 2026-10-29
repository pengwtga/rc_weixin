package com.rightcapital.notify.delivery;

import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import javax.net.ssl.SSLHandshakeException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4.6 DoD：分类器必须覆盖全部分支。这类"纯映射"代码最容易被认为不需要测试，
 * 但它决定了每一条失败通知是"再试"还是"叫人"，错一个分支就是一条业务通知的生死。
 */
class ResultClassifierTest {

    private final ResultClassifier classifier = new ResultClassifier();

    @Test
    void 成功_2xx() {
        DeliveryResult r = classifier.classifyHttp(200, "{\"ok\":true}", 12, null);
        assertThat(r.outcome()).isEqualTo(DeliveryResult.Outcome.SUCCESS);
        assertThat(r.errorClass()).isNull();
        assertThat(r.statusCode()).isEqualTo(200);
    }

    @Test
    void 无响应体的204也是成功() {
        DeliveryResult r = classifier.classifyHttp(204, null, 3, null);
        assertThat(r.outcome()).isEqualTo(DeliveryResult.Outcome.SUCCESS);
        assertThat(r.responseSnippet()).isNull();
    }

    @Test
    void 客户端错误400_永久失败_不重试() {
        // 参数错误重试 10 次只会换来 10 次 400，白烧预算
        DeliveryResult r = classifier.classifyHttp(400, "bad request", 5, null);
        assertThat(r.outcome()).isEqualTo(DeliveryResult.Outcome.PERMANENT);
        assertThat(r.errorClass()).isEqualTo(DeliveryErrorClass.HTTP_4XX);
    }

    @Test
    void 未授权与禁止_永久失败() {
        assertThat(classifier.classifyHttp(401, null, 5, null).outcome())
                .isEqualTo(DeliveryResult.Outcome.PERMANENT);
        assertThat(classifier.classifyHttp(403, null, 5, null).outcome())
                .isEqualTo(DeliveryResult.Outcome.PERMANENT);
    }

    @Test
    void 限流429_可重试_并解析RetryAfter秒数() {
        DeliveryResult r = classifier.classifyHttp(429, "slow down", 5, "120");
        assertThat(r.outcome()).isEqualTo(DeliveryResult.Outcome.RETRYABLE);
        assertThat(r.retryAfterMs()).isEqualTo(120_000L);
    }

    @Test
    void 请求超时408_可重试() {
        assertThat(classifier.classifyHttp(408, null, 5, null).outcome())
                .isEqualTo(DeliveryResult.Outcome.RETRYABLE);
    }

    @Test
    void 服务端错误与不可用_可重试() {
        assertThat(classifier.classifyHttp(500, "boom", 5, null).outcome())
                .isEqualTo(DeliveryResult.Outcome.RETRYABLE);
        assertThat(classifier.classifyHttp(503, null, 5, null).errorClass())
                .isEqualTo(DeliveryErrorClass.HTTP_5XX);
    }

    @Test
    void 重定向3xx_禁止跟随_判永久失败() {
        // 跟随重定向会让第一跳的 SSRF 校验失效（D10）
        DeliveryResult r = classifier.classifyHttp(302, null, 5, null);
        assertThat(r.outcome()).isEqualTo(DeliveryResult.Outcome.PERMANENT);
        assertThat(r.errorClass()).isEqualTo(DeliveryErrorClass.REDIRECT_REFUSED);
    }

    @Test
    void 超时_归TIMEOUT() {
        DeliveryResult r = classifier.classifyThrowable(new HttpTimeoutException("timeout"), 15_000);
        assertThat(r.outcome()).isEqualTo(DeliveryResult.Outcome.RETRYABLE);
        assertThat(r.errorClass()).isEqualTo(DeliveryErrorClass.TIMEOUT);
        assertThat(r.statusCode()).isNull();
    }

    @Test
    void 建连超时_归TIMEOUT而不是CONN_REFUSED() {
        DeliveryResult r = classifier.classifyThrowable(new HttpConnectTimeoutException("connect timeout"), 3_000);
        assertThat(r.errorClass()).isEqualTo(DeliveryErrorClass.TIMEOUT);
    }

    @Test
    void 域名解析失败_归DNS() {
        ConnectException wrapped = new ConnectException("fail");
        wrapped.initCause(new UnknownHostException("nope"));
        DeliveryResult r = classifier.classifyThrowable(wrapped, 8);
        assertThat(r.errorClass()).isEqualTo(DeliveryErrorClass.DNS);
    }

    @Test
    void 连接被拒_归CONN_REFUSED() {
        DeliveryResult r = classifier.classifyThrowable(new ConnectException("Connection refused"), 4);
        assertThat(r.errorClass()).isEqualTo(DeliveryErrorClass.CONN_REFUSED);
    }

    @Test
    void TLS握手失败_归TLS() {
        DeliveryResult r = classifier.classifyThrowable(new SSLHandshakeException("cert expired"), 30);
        assertThat(r.errorClass()).isEqualTo(DeliveryErrorClass.TLS);
    }

    @Test
    void 其他IO异常_归CONNECT_FAILED() {
        DeliveryResult r = classifier.classifyThrowable(new java.io.IOException("reset"), 20);
        assertThat(r.errorClass()).isEqualTo(DeliveryErrorClass.CONNECT_FAILED);
        assertThat(r.outcome()).isEqualTo(DeliveryResult.Outcome.RETRYABLE);
    }

    @Test
    void 异常cause链自引用_不会死循环() {
        // 真实世界里出现过（某些代理/反射包装重写 getCause 返回自身）。
        // 用匿名类模拟，避免用反射改 Throwable.cause —— JDK 17 的模块系统不允许
        RuntimeException loop = new RuntimeException("loop") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertThat(classifier.classifyThrowable(loop, 5).errorClass())
                .isEqualTo(DeliveryErrorClass.CONNECT_FAILED);
    }

    @Test
    void 响应片段超长被截断到2048字符() {
        String longBody = "x".repeat(5000);
        DeliveryResult r = classifier.classifyHttp(500, longBody, 5, null);
        assertThat(r.responseSnippet()).hasSize(ResultClassifier.RESPONSE_SNIPPET_MAX_CHARS);
    }

    @Test
    void RetryAfter支持HTTP日期格式() {
        String future = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                .format(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(60));
        Long ms = ResultClassifier.parseRetryAfterMs(future);
        assertThat(ms).isNotNull().isBetween(50_000L, 61_000L);
    }

    @Test
    void RetryAfter非法值返回null() {
        assertThat(ResultClassifier.parseRetryAfterMs("soon")).isNull();
        assertThat(ResultClassifier.parseRetryAfterMs(null)).isNull();
        assertThat(ResultClassifier.parseRetryAfterMs("-5")).isNull();
    }
}
