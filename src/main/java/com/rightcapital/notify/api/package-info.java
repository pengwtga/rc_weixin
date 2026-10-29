/**
 * 接入模块（P3）：同步受理 + 异步投递的「202 Accepted」入口。
 * 职责：API Key 鉴权（T3.2）、幂等键校验与冲突查回（T3.3）、
 * SSRF 校验（T3.4）、按 caller 限流（T3.5）。
 * 本模块只做「校验 + 落库」，不做任何投递动作（D1）。
 */
package com.rightcapital.notify.api;
