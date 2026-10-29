-- V4：载荷保护落地（D10）
--
-- 1) body_sha256 → body_fingerprint：语义从"裸摘要"改为"密钥化指纹（HMAC-SHA256）"。
--    字段名必须跟语义一起改 —— 名字叫 sha256 会持续诱导后来人以为它可被离线穷举（§3.5 的教训）。
-- 2) headers_enc：TEXT → MEDIUMBLOB，因为现在存的是 AES-256-GCM 密封后的二进制
--    （原 TEXT 只能存 Base64，多 33% 体积且多一层编解码）。
-- 3) body_enc 的注释同步纠正为实际的加密格式。
--
-- 不改已有迁移（V2 已在本机/评审环境执行过）—— 迁移文件一旦提交即不可变，演进只能追加。

ALTER TABLE notification
  CHANGE COLUMN body_sha256 body_fingerprint CHAR(64) NOT NULL
    COMMENT '【密钥化指纹】body 明文的 HMAC-SHA256（十六进制，64 字符）。用途：归档清除 body 后仍可回答"当时发出的内容是否与某份候选一致"；相同明文必须得到相同指纹，因此不可加随机盐。裸 SHA-256 对低熵 body（如 {"status":"paid"}）可被离线比对反推，故改为带密钥的 HMAC（见 D10.3）';

ALTER TABLE notification
  MODIFY COLUMN headers_enc MEDIUMBLOB NULL
    COMMENT '请求头（JSON 序列化的 UTF-8 字节）经 AES-256-GCM 密封：[1B版本][12B随机IV][密文+16B tag]，AAD=单号+字段名。含供应商凭证，查询接口脱敏，日志不打印';

ALTER TABLE notification
  MODIFY COLUMN body_enc MEDIUMBLOB NULL
    COMMENT '请求体原始字节经 AES-256-GCM 密封：[1B版本][12B随机IV][密文+16B tag]，AAD=单号+字段名。投递时解密还原，保留期由归档任务控制（成功 24h / 失败 7d 后置 NULL）';
