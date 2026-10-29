/**
 * HTTP 出站模块（P4 后半）：真实请求执行与结果观测。
 * 职责：按预算发请求（connect 3s / total 15s，D2）、结果分类器（T4.6）、
 * 逐次投递登记（T4.7）、不跟随重定向（D10）。
 * 本模块不做重试决策 —— 决策在 policy，执行在 dispatcher。
 */
package com.rightcapital.notify.delivery;
