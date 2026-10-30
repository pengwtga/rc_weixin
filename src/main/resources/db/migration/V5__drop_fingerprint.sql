-- V5：MVP 裁剪 —— 删除 body_fingerprint（请求体指纹）
--
-- 裁剪理由（保留这条决策记录，它比字段本身更有价值）：
--   该字段唯一的消费方是「归档清空 body 后，仍能回答"当时发出的内容是否与某份候选一致"」。
--   这个能力需求里没有，是设计阶段自己发明的 —— 为一个没有需求方的能力
--   付一张列 + 一个组件 + 一把密钥 + 六个测试，正是 MVP 阶段该砍掉的复杂度。
--   未来若真出现归档比对的需求，加回一列的成本是 O(1)。
--
-- 同批裁剪的还有：指纹的 HMAC 密钥（fingerprint-key）与"两把密钥分离"断言。
-- 保留：headers_enc / body_enc 的 AES-256-GCM 密封（凭证加密 + PII 留存）、SSRF 校验。

ALTER TABLE notification
  DROP COLUMN body_fingerprint;
