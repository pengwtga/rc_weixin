-- V3：逐次投递明细 —— 只追加不更新，用于追责、排查与 SLA 统计（设计文档 §3.3）
CREATE TABLE delivery_attempt (
  id               BIGINT UNSIGNED   NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  notification_id  BIGINT UNSIGNED   NOT NULL                COMMENT '所属通知，关联 notification.id（用整数关联而非 notification_no，避免字符串索引与关联代价）',
  replay_epoch     SMALLINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '所属重放轮次，与 notification.replay_epoch 对应；同通知不同轮次的 attempt_no 允许重复',
  attempt_no       SMALLINT UNSIGNED NOT NULL                COMMENT '本轮内第几次尝试，从 1 开始；跨轮次由 replay_epoch 区分（累计次数见 notification.attempt_count）',
  started_at       DATETIME(3)       NOT NULL                COMMENT '本次尝试开始时刻（UTC）',
  duration_ms      INT               NULL                    COMMENT '本次尝试耗时（毫秒），含 DNS/TLS/连接/传输；超时场景记为超时上限',
  status_code      SMALLINT          NULL                    COMMENT 'HTTP 响应状态码；网络层失败（超时/DNS/TLS/连接拒绝）时为 NULL',
  error_class      VARCHAR(32)       NULL                    COMMENT '失败归一化分类，成功为 NULL：TIMEOUT/DNS/TLS/CONN_REFUSED/REDIRECT_REFUSED/HTTP_4XX/HTTP_5XX',
  response_snippet VARCHAR(2048)     NULL                    COMMENT '响应体前 2KB（截断存储），用于查看供应商真实返回；不含我方请求中的凭证',
  PRIMARY KEY (id),
  UNIQUE KEY uk_n_epoch_attempt (notification_id, replay_epoch, attempt_no) COMMENT '同通知同轮同序号只允许一条，防止重复登记',
  KEY idx_n (notification_id) COMMENT '按通知查全部投递记录（状态详情接口一次查全）'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='逐次投递明细 —— 只追加不更新，用于追责、排查与 SLA 统计';
