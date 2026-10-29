# rc_weixin — 可靠出站 HTTP 通知服务

企业内部业务系统在关键事件（注册归因、订阅付款、库存变更）发生时，调用外部供应商 HTTP(S) API 的**可靠投递**服务。

核心定位：**可靠出站 HTTP 投递**，不是消息总线。三件事：请求先落盘（不丢）、投递有租约（不重复卡死）、失败有分级（不白重试）。

## 文档

| 文档 | 内容 |
|---|---|
| [docs/API通知系统_需求理解与设计要点.md](docs/API通知系统_需求理解与设计要点.md) | 需求重述、系统边界、核心设计决策（D1–D12）、拒绝的方案清单、容量估算与演进路径 |
| [docs/API通知系统_开发任务拆解与执行清单.md](docs/API通知系统_开发任务拆解与执行清单.md) | 9 个阶段 P0–P9、每项任务的可验证 DoD、裁剪方案 |

## 快速开始

```bash
docker compose up -d mysql          # 本地 MySQL（宿主机端口 13307）
mvn spring-boot:run                 # 应用启动，Flyway 自动执行迁移
curl http://localhost:8080/actuator/health
```

## 当前进度

| 阶段 | 状态 | 已交付 |
|---|---|---|
| P1 工程骨架 | ✅ | 6 模块边界、参数外置（含「租约 > HTTP 总超时」启动断言）、Flyway |
| P2 数据模型 | ✅ | `notification` / `delivery_attempt`；SKIP LOCKED 认领 + 租约回收 |
| P3 接入 API | ✅ | API Key 鉴权、两段式幂等键、令牌桶限流、背压守卫、AES-256-GCM 密封 + HMAC 指纹 |
| P4 投递引擎 | ✅ | 认领→解密→发送→分类→登记→落定；per-host 并发隔离、hint 唤醒、优雅停机 |
| P5 失败策略 | 待做 | 指数退避 + 抖动、per-host 熔断、死信清单与人工重放 |

测试口径：`mvn test` 跑 78 个单元测试；`mvn verify` 追加 28 个集成测试（Testcontainers 真实 MySQL 8 + WireMock 真实 HTTP），共 106 个。

P4 的实现与任务清单有 3 处不一致（JDK HttpClient 只有两段超时等），原因记在设计文档 §11，不藏在提交信息里。

## 技术栈

Java 17 · Spring Boot 3.3 · MySQL 8（DB 即队列：SKIP LOCKED + 租约） · Flyway · JdbcTemplate
