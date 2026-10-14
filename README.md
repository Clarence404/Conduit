# Conduit

个人研究性质的 IoT 平台。V0.1 跑通「设备 MQTT 直连 → 接入 → 总线 → 处理 → 时序存储 → Web 展示」主链路。

设计文档：`docs/superpowers/specs/2026-10-14-conduit-v0.1-design.md`

## 架构

```
设备 ──MQTT──▶ EMQX ──HTTP 鉴权──▶ conduit-server
                │
                ▼
        conduit-connector ──▶ Redis Streams ──▶ conduit-server ──▶ InfluxDB / Redis / PostgreSQL
                                                     │
                                                     ▼
                                               Vue 前端（nginx）
```

| 模块 | 说明 |
|---|---|
| `backend/conduit-common` | 统一消息模型、`MessageBus`（Redis Streams 实现） |
| `backend/conduit-connector` | 协议适配层，V0.1 只有 MQTT |
| `backend/conduit-server` | REST 接口、EMQX 鉴权回调、数据流水线 |
| `backend/conduit-simulator` | 演示设备模拟器 |
| `backend/conduit-e2e` | 端到端测试（`-Pe2e` 时才参与构建） |
| `frontend` | Vue 3 + Element Plus 管理界面 |
| `deploy` | Docker Compose 一键环境 |

## 一键演示

需要 Docker（本机或远程均可；远程时先设置 `DOCKER_HOST=tcp://<docker-host>:2375`）。

```bash
cp deploy/.env.example deploy/.env   # 按需修改口令与端口
docker compose -f deploy/docker-compose.yml --profile demo up -d --build
```

浏览器打开 `http://<docker-host>:8090`（Docker 在本机时为 `localhost`），用 `.env` 中的 `CONDUIT_ADMIN_USERNAME` / `CONDUIT_ADMIN_PASSWORD` 登录。模拟器会自动创建「Demo Meter」产品和设备（默认 100 台，每 10 秒上报一次，见 `SIM_DEVICE_COUNT` / `SIM_INTERVAL_MS`）。

宿主机端口可在 `deploy/.env` 中配置，括号内为默认值：

| 变量 | 服务 |
|---|---|
| `WEB_PORT`（8090） | Web |
| `API_PORT`（8091） | REST API |
| `MQTT_PORT`（1883） | MQTT |
| `EMQX_DASHBOARD_PORT`（18083） | EMQX 控制台 |
| `INFLUX_PORT`（8096） | InfluxDB |

PostgreSQL 与 Redis 不对外暴露。

## 设备接入

- 连接：`clientId = username = deviceKey`，`password = deviceSecret`（创建或重置设备时显示一次）
- 上报：QoS1 发布到 `conduit/{productKey}/{deviceKey}/up/property`

```json
{ "id": "可选", "ts": 1760400000000, "params": { "Ua": 220.1, "Ia": 5.2 } }
```

## 开发

```bash
mvn -f backend/pom.xml verify
```

```bash
cd frontend && npm install && npm test && npm run dev
```

## 端到端测试：杀进程不丢数据

10 台设备各发 50 条消息，发送中途 kill 并重启 `conduit-server`，最终 InfluxDB 中应恰好 500 条。需要 Compose 环境已启动（不带 demo profile 亦可）。

```bash
mvn -f backend/pom.xml install -DskipTests
mvn -f backend/pom.xml verify -Pe2e -pl conduit-e2e
```

测试读取 `deploy/.env` 中的凭证与端口，并支持环境变量：

| 变量 | 默认 | 说明 |
|---|---|---|
| `E2E_HOST` | `localhost` | Docker 主机地址（远程 Docker 时填其地址） |
| `E2E_COMPOSE` | `docker compose` | compose 命令，例如独立二进制的完整路径；子进程继承 `DOCKER_HOST` |

## 已知限制（V0.1）

- 未启用 TLS；凭证均为演示用途。
- Redis 长时间不可用时，EMQX 会话队列（`max_mqueue_len`）溢出会丢消息，V1.0 压测时量化。
- 死信只能通过 `redis-cli XRANGE conduit:stream:dead - +` 查看。
