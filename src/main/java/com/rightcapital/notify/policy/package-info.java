/**
 * 策略模块（P5）：失败分类 → 退避 → 重试预算 → 死信。
 * 职责：错误分类到重试档位的映射（4xx 不重试，D5）、
 * 指数退避计算（幂等、可单测）、RetryPolicy 档位（STANDARD/LONG_TAIL/FAST_FAIL）。
 * 本模块是纯函数层：输入（error_class, attempt_count）→ 输出（next_attempt_at / DEAD），
 * 无副作用，方便用单测穷举。
 */
package com.rightcapital.notify.policy;
