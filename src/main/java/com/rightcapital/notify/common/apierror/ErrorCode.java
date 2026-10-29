package com.rightcapital.notify.common.apierror;

/**
 * 结构化错误码（T1.5）：所有 4xx/5xx 都返回该结构，调用方按 code 判断、不解析 message。
 * code 命名与任务清单的错误模型对齐，新增错误先在这里登记。
 */
public enum ErrorCode {

    /** 请求参数/格式不合法（如幂等键缺冒号分隔，§3.6 校验）。 */
    VALIDATION_FAILED("VALIDATION_FAILED", 400),
    /** 幂等冲突：同 caller + 同幂等键已存在（应查回原单号，见 T3.3）。 */
    IDEMPOTENT_CONFLICT("IDEMPOTENT_CONFLICT", 409),
    /** 状态机拒绝：目标状态不允许从当前状态迁移（T2.3）。 */
    STATE_TRANSITION_ILLEGAL("STATE_TRANSITION_ILLEGAL", 409),
    /** 未预期的内部错误：对外只给 traceId，细节只进日志。 */
    INTERNAL_ERROR("INTERNAL_ERROR", 500);

    private final String code;
    private final int httpStatus;

    ErrorCode(String code, int httpStatus) {
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
