package com.rightcapital.notify.common.statemachine;

/** 非法状态迁移（T2.3）：如 SUCCEEDED → PROCESSING。映射为 409，提示调用方状态已终态。 */
public class IllegalStateTransitionException extends RuntimeException {

    private final Enum<?> from;
    private final Enum<?> to;

    public IllegalStateTransitionException(Enum<?> from, Enum<?> to) {
        super("非法状态迁移: %s -> %s（合法迁移见 NotificationStatus.ALLOWED）".formatted(from, to));
        this.from = from;
        this.to = to;
    }

    public Enum<?> getFrom() {
        return from;
    }

    public Enum<?> getTo() {
        return to;
    }
}
