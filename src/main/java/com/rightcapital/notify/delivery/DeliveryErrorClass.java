package com.rightcapital.notify.delivery;

/**
 * 投递失败的归一化分类（T4.6）。
 *
 * <p>为什么要归一化，而不是直接存异常类名/原始 message：下游看到
 * {@code java.net.SocketTimeoutException: Connect timed out} 无法决策，
 * 看到 {@code TIMEOUT} 才能决定「等 30 秒再试」还是「叫人来修 DNS」。
 * 分类同时是三条下游逻辑的输入：重试策略（P5）、失败路由（T5.9c）、运维告警。</p>
 *
 * <p>枚举名直接落库（{@code notification.last_error_class} / {@code delivery_attempt.error_class}），
 * 名字即契约 —— 改名等于改数据语义，必须配迁移。</p>
 */
public enum DeliveryErrorClass {

    /** 建连/等待响应超出预算。可重试，但连续超时通常说明对方已经挂了。 */
    TIMEOUT,
    /** 域名解析失败。多半是配置写错或对方 DNS 故障 —— 可重试，但价值低。 */
    DNS,
    /** TLS 握手/证书失败。证书过期是永久失败，握手超时是临时故障，本类不细分。 */
    TLS,
    /** 连接被拒绝（端口没开）。 */
    CONN_REFUSED,
    /** 其他 I/O 失败（连接被重置、响应体读到一半断开）。 */
    CONNECT_FAILED,
    /** 对方返回 3xx。本服务禁止跟随重定向，3xx 一律视为配置错误。 */
    REDIRECT_REFUSED,
    /** 4xx：请求本身有问题（鉴权/参数/路径），重试只会重复被拒。 */
    HTTP_4XX,
    /** 5xx：对方自己出错，可重试。 */
    HTTP_5XX,
    /**
     * 载荷无法解密：密文被篡改、AAD 不匹配（跨行搬运）、或密钥已轮换。
     *
     * <p>这是 P4 引入的新失败面 —— 加密之后，"发不出去"多了一种与网络无关的原因。
     * 归到网络错误里会让人一直查对方，所以单列一类，直接进死信。</p>
     */
    PAYLOAD_CORRUPT
}
