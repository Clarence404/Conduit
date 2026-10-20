# 演示数据版本发布与 VM 烟测报告

- 日期：2026-10-20
- 发布提交：`3770442 feat(demo): seed portable demo inventory`
- 发布目标：私有演示 VM（地址和凭据不写入版本控制）
- 部署目录：`/opt/conduit-demo-3770442`
- 当前版本链接：`/opt/conduit-current`
- 结论：通过

## 1. 发布物

| 项目 | 结果 |
|---|---|
| GitHub `origin/main` | `7368a6f..3770442` 推送成功 |
| 独立部署包 | `conduit-standalone-3770442.tar.gz`，88,018,723 bytes |
| SHA-256 | `DF3BD7E53CDCF22CCD1AA0B71118B8334441ABFFC68DC09875BF192DD258F356` |
| 包内迁移 | Server JAR 包含 `BOOT-INF/classes/db/migration/V4__demo_seed.sql` |
| 本地配置隔离 | 包中没有 `.env`、`application-local.yml` 或 `simulator-local.properties` |

## 2. 部署检查

| 检查项 | 结果 |
|---|---|
| Compose 配置 | `docker compose config --quiet` 通过 |
| 服务状态 | PostgreSQL、Redis、InfluxDB、Explorer、EMQX、Server、双 Transport、Web、Simulator 全部运行；带健康检查的服务均为 healthy |
| 容器稳定性 | 除 Simulator 首次启动瞬态重试外，其余容器重启次数均为 0 |
| EMQX | 已从 5.8.6 升级为 5.10.5 |
| 数据保留 | 沿用原 PostgreSQL、Redis、InfluxDB、EMQX 数据卷，没有删卷 |
| Flyway | schema 版本为 4 |

## 3. 数据与功能烟测

| 验收项 | 结果 |
|---|---|
| 关系库资产 | 4 租户、8 产品、174 设备、36 物模型属性 |
| Web（VM 内） | HTTP 200 |
| API 健康检查（VM 内） | HTTP 200 |
| Web（开发机访问 VM） | HTTP 200 |
| API（开发机访问 VM） | `UP` |
| 平台管理员登录 | HTTP 200，角色 `PLATFORM_ADMIN` |
| 默认租户管理员登录 | HTTP 200，角色 `TENANT_ADMIN`，租户 `default` |
| Transport 选主 | 唯一实例成为 leader 并连接协议适配器 |
| 多租户模拟 | 发现西北 18、华南 20、华东 26、默认租户产品；全部产品按物模型启动模拟 |
| 最新值 | Redis 中 `conduit:latest:*` 共 174 个设备键 |
| 在线状态 | Redis 中 `conduit:presence:*` 共 174 个设备键 |

## 4. 部署命令

```text
docker compose config --quiet
docker compose up -d --build --remove-orphans --wait --wait-timeout 240
docker compose ps
```

验收还使用了 PostgreSQL 只读计数查询、HTTP 健康检查与登录请求、Transport/Simulator 日志筛选、Redis 键计数以及容器重启次数检查。

## 5. 观察项

首次启动时，Simulator 在 EMQX 刚完成升级后的连接阶段耗尽一次连接重试并由 `restart: on-failure` 自动重启。第二次启动后发现全部演示产品，重启次数稳定为 1，174 台设备均产生最新值和在线状态。该瞬态没有造成最终验收失败；后续可单独优化大量设备首次并发连接的启动退避策略。
