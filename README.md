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

## 技术栈

Java 17 · Spring Boot 3.3 · MySQL 8（DB 即队列：SKIP LOCKED + 租约） · Flyway · JdbcTemplate
