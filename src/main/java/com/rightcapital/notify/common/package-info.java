/**
 * 跨模块共享层：状态机、统一错误模型、ID 生成、数据访问。
 *
 * <p>摆放约定：只有「api / dispatcher / delivery / policy / admin 至少两个模块都要用」的东西
 * 才允许进这里 —— 比如 NotificationRepository 同时服务接入落库（api）与认领投递（dispatcher）。
 * 单一模块私有的逻辑禁止上移，防止 common 退化成杂物间。</p>
 */
package com.rightcapital.notify.common;
