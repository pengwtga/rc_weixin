package com.rightcapital.notify.delivery;

/**
 * 一次投递的结果（T4.6）。这是投递层唯一对外的事实描述。
 *
 * <p>{@link Outcome} 决定"下一步"，{@link DeliveryErrorClass} 决定"怎么描述"。
 * 二者分开是因为它们服务于不同的人：outcome 给状态机看，errorClass 给排查的人看。
 * 早期版本把两者合成一个枚举，结果是「429 算不算失败」这类问题在状态机里被反复讨论 ——
 * 语义混在一起，代码就会替你做决定。</p>
 *
 * @param outcome      处置结论：成功 / 可重试 / 永久失败
 * @param statusCode   HTTP 状态码；网络层失败为 null（与"收到 0"区分）
 * @param errorClass   失败分类；成功为 null
 * @param durationMs   本次耗时（含 DNS/TLS/建连/传输）
 * @param responseSnippet 响应体片段（已截断，不含我方请求中的凭证）
 * @param retryAfterMs 下游通过 {@code Retry-After} 给出的建议等待；无建议为 null
 */
public record DeliveryResult(
        Outcome outcome,
        Integer statusCode,
        DeliveryErrorClass errorClass,
        long durationMs,
        String responseSnippet,
        Long retryAfterMs) {

    /** 处置结论 —— 只回答"这条任务接下来该怎样"，不掺业务语义。 */
    public enum Outcome {
        /** 2xx：供应商已接收。 */
        SUCCESS,
        /** 值得再试：5xx、429、超时、连接类错误。 */
        RETRYABLE,
        /** 再试也没用：4xx（除 429/408）、3xx、载荷损坏。 */
        PERMANENT
    }

    public boolean succeeded() {
        return outcome == Outcome.SUCCESS;
    }

    public boolean retryable() {
        return outcome == Outcome.RETRYABLE;
    }
}
