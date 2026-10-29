/**
 * 管理模块（P5 后半）：状态查询、死信清单、人工重放。
 * 职责：GET 状态详情（含每轮 attempt，T5.5）、失败清单游标 API（T5.8）、
 * 单条/批量重放（T5.9/T5.9b：状态守卫 + replay_epoch + 摊平）、失败通知路由（T5.9c）。
 * 边界（D8）：本模块负责「让人知道失败」，业务方负责「决定业务上怎么办」。
 */
package com.rightcapital.notify.admin;
