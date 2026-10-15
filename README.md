# Conduit

个人研究性质的 IoT 平台。V0.1 跑通「设备 MQTT 直连 → 接入 → 总线 → 处理 → 时序存储 → Web 展示」主链路。

功能：产品与设备管理、MQTT 接入与鉴权、物模型逐测点校验与可插拔解码（V0.2-A）、设备历史曲线（V0.2-B：设备详情「历史」标签页，按时间范围自动聚合，多测点联动缩放）、前端体验优化（首页总览、分组侧栏与面包屑、设备指标卡片与迷你趋势线、暗色模式与侧栏风格切换）。

设计文档：`docs/superpowers/specs/2026-10-14-conduit-v0.1-design.md`（版本路线与 V0.1）、`docs/superpowers/specs/2026-10-15-conduit-v0.2a-thing-model-design.md`（V0.2-A 物模型与编解码）、`docs/superpowers/specs/2026-10-15-conduit-v0.2b-history-design.md`（V0.2-B 历史查询与曲线）、`docs/superpowers/specs/2026-10-15-conduit-ui-refresh-design.md`（前端体验优化）

设备接入指南（流程、连接参数、上报格式、示例代码、排错）：[`docs/device-integration.md`](docs/device-integration.md)

## 架构

```mermaid
flowchart TB
    D[设备] -->|MQTT| E[EMQX]
    E -->|"① 连接时同步鉴权"| A
    E -->|共享订阅| C[conduit-transport]
    C -->|写入| R[(Redis Streams)]
    R -->|"② 消费组拉取"| P
    subgraph S["conduit-server（同一个进程）"]
        A["鉴权回调<br/>/internal/mqtt/auth"]
        P[数据流水线]
        API[REST 接口]
    end
    A --> PG[(PostgreSQL)]
    P --> PG
    P --> IF[(InfluxDB)]
    P --> RD[(Redis 最新值 / 在线状态)]
    API --> PG
    API --> RD
    W["Vue 前端（nginx）"] -->|/api| API
```

`conduit-server` 只有一个进程，但有两个入口：

- **① 鉴权回调（控制面）**：设备建立 MQTT 连接时，EMQX 同步调用它，拿到 allow / deny 才放行。频率低，每次连接一次。server 不可用时新设备连不上，已连接的设备不受影响。
- **② 数据流水线（数据面）**：设备每条上报都先经 transport 写入 Redis Streams，server 以消费组身份异步拉取、处理、入库后再确认。server 重启期间消息在 Redis 中排队，恢复后继续处理，不丢数据。

设备数据从不直连 server，所以 server 重启或变慢不会堵住 EMQX；新增协议也只需在 transport 侧加适配器。完整接入流程见[设备接入指南](docs/device-integration.md)。

## 代码结构

仓库根目录即 Maven 父工程，Java 模块与前端 `conduit-ui` 平铺在顶层。Java 模块之间的依赖关系：

```mermaid
flowchart LR
    common[conduit-common<br/>消息模型 + 总线]
    transport[conduit-transport<br/>协议接入] --> common
    server[conduit-server<br/>平台服务] --> common
    simulator[conduit-simulator<br/>设备模拟器]
    e2e[conduit-e2e<br/>端到端测试] --> simulator
```

`transport` 和 `server` 之间没有代码依赖，只通过 `common` 里定义的消息格式和 Redis Streams 通信。

```
conduit/
├── pom.xml                          Maven 父工程（JDK 21，Spring Boot 3.5），聚合下列 conduit-* Java 模块
├── Dockerfile                       构建 Java 应用镜像（构建参数 MODULE 指定模块）
├── conduit-common/                  dev.conduit.common
│   ├── message/                     RawMessage：协议无关的统一消息，及其与 Stream 字段的编解码
│   └── bus/                         MessageBus 接口和 Redis Streams 实现（投递、消费组、认领、死信）
├── conduit-transport/               dev.conduit.transport（端口 8081）
│   ├── adapter/                     ProtocolAdapter 接口；新增协议就是新增一个实现
│   └── mqtt/                        MQTT 适配器：共享订阅、写入总线成功后才确认、topic 解析
├── conduit-server/                  dev.conduit.server（端口 8080），按业务领域分包
│   ├── auth/                        登录、JWT、Spring Security 配置
│   ├── tenant/                      租户上下文与租户隔离
│   ├── product/  device/            产品、设备的增删改查和接口
│   ├── mqttauth/                    EMQX 鉴权回调
│   ├── pipeline/                    数据流水线：消费总线、按物模型逐测点校验、重试、死信
│   │   └── codec/                   可插拔解码器（标准 JSON、扁平 JSON）
│   ├── thingmodel/                  物模型：属性增删改查、导入导出、按产品缓存
│   ├── telemetry/                   InfluxDB 写入与历史查询（原始 / 自动聚合）、最新值存储与查询
│   ├── presence/                    设备上下线状态
│   ├── overview/                    首页总览接口：按租户统计设备、在线与最近 1 小时死信
│   ├── security/  bootstrap/        密钥生成与哈希；启动时创建默认租户和管理员
│   └── config/  web/                框架配置；统一错误响应、分页
├── conduit-simulator/               dev.conduit.simulator：通过 REST 开通设备，再按周期上报
├── conduit-e2e/                     端到端测试（-Pe2e 时才参与构建）
├── conduit-ui/                      Vue 3 + TypeScript + Element Plus（独立 npm 工程，自带 Dockerfile）
│   └── src/  api/  stores/  router/  layouts/  views/  components/  composables/  utils/  styles/
├── deploy/                          docker-compose.yml、.env.example、EMQX 鉴权与 ACL 配置
├── docs/                            设备接入指南、待办清单、设计文档与实现计划
└── .run/                            IntelliJ IDEA 共享运行配置
```

## 部署：基础设施与完整演示

Docker Compose 默认只启动基础设施（PostgreSQL、Redis、InfluxDB、EMQX）；conduit-server、conduit-transport、conduit-web 属于 `app` profile，演示模拟器属于 `demo` profile，需要时才整套部署。日常开发时应用在 IDE 中运行，见下方「开发」。

需要 Docker（本机或远程均可；远程时先设置 `DOCKER_HOST=tcp://<docker-host>:2375`；该端口是无认证的 Docker API，仅限可信局域网使用）。

> **安全提示**
>
> - `.env` 中所有 `change-me-*` 值必须在宿主机可被他人访问之前替换掉，例如用 `openssl rand -hex 32` 生成。
> - 端口默认只绑定 `127.0.0.1`。Docker 宿主机是远程机器、或需被其他机器访问时，在 `.env` 中设置 `BIND_ADDR=0.0.0.0`，并确保网络可信。
> - API 端口同时暴露 `/internal/mqtt/auth`（EMQX 鉴权回调，受内部 token 保护）和健康检查端点。
> - `EMQX_DASHBOARD_PASSWORD` 仅在首次启动（`emqx-data` 卷为空）时生效；之后修改请在控制台中操作，或 `down -v` 清卷重来。

```bash
cp deploy/.env.example deploy/.env   # 按需修改口令与端口
# 只启动基础设施（日常开发用）
docker compose -f deploy/docker-compose.yml up -d
# 完整演示：基础设施 + server / transport / web + 模拟器（demo 依赖 app，两个 profile 要同时给）
docker compose -f deploy/docker-compose.yml --profile app --profile demo up -d --build
```

EMQX 的鉴权回调地址由 `deploy/.env` 中的 `CONDUIT_AUTH_URL` 决定：完整 Docker 部署保持默认 `http://conduit-server:8080`；server 在 IDE 中运行时改为 `http://<dev-host>:8080`（`<dev-host>` 为开发机地址），修改后需重建 emqx 容器（`up -d --force-recreate emqx`）。

Docker 宿主机无法访问外网时（镜像内的 `mvn` / `npm ci` 下载不了依赖），改用本机构建：

```bash
deploy/build-local.sh
```

这是按需的完整部署入口，会带上 `--profile app --profile demo`。脚本从当前提交的干净检出构建 jar 和前端（未提交的修改与 `application-local.yml` 等本地配置不会进入镜像），在宿主机上只打复制产物的薄镜像，再执行 `up -d --no-build`。需要本机有 JDK 21 上的 Maven 和 Node.js 22，宿主机上已有 compose 用到的基础镜像（`eclipse-temurin:21.0.6_7-jre`、`nginx:1.27.4-alpine` 以及 postgres、redis、influxdb、emqx）。`--no-up` 只构建不启动；docker / compose 命令可用环境变量 `DOCKER`、`COMPOSE` 覆盖。

完整部署起来后，浏览器打开 `http://<docker-host>:8090`（Docker 在本机时为 `localhost`），用 `.env` 中的 `CONDUIT_ADMIN_USERNAME` / `CONDUIT_ADMIN_PASSWORD` 登录。模拟器会自动创建「三相电表（演示）」产品和设备（设备名形如「1号楼配电室 电表01」，每栋楼 10 台；已有设备的旧名称会在启动时改成这种格式，Key 不变），并在产品缺少演示物模型（docs/examples/demo-meter.thing-model.json）中的任一属性时以合并方式导入（已有部署因此会自动补上 V0.2-B 新增的枚举属性「运行状态」st：每台设备每个整点可能切换一次，多数时间为「运行」）（默认 100 台，每 10 秒上报一次，见 `SIM_DEVICE_COUNT` / `SIM_INTERVAL_MS`；上报格式由 `SIM_PAYLOAD_FORMAT`（`standard` / `flat`）指定，新建产品时按此选用解码器，已有产品的解码器不会被修改）。模拟器首次连接 MQTT 失败时每 2 秒重试，最多 15 次。

已有部署升级注意：演示产品的默认名称已从「Demo Meter」改为「三相电表（演示）」，升级前请先在界面上把旧产品改名为「三相电表（演示）」，否则模拟器会新建一个产品、旧产品及其设备会遗留下来；改名后模拟器会复用该产品，并把其下设备原地改成新名称。

宿主机端口可在 `deploy/.env` 中配置，括号内为默认值：

| 变量 | 服务 |
|---|---|
| `WEB_PORT`（8090） | Web（app profile） |
| `API_PORT`（8091） | REST API（app profile） |
| `MQTT_PORT`（1883） | MQTT |
| `EMQX_DASHBOARD_PORT`（18083） | EMQX 控制台 |
| `INFLUX_EXPLORER_PORT`（8888） | InfluxDB 3 Explorer（时序数据图形界面，已预置连接与 `telemetry` 库；admin 模式、无登录，仅在可信网络暴露） |
| `INFLUX_PORT`（8096） | InfluxDB 3 Core HTTP API（容器内 8181，需 token） |
| `POSTGRES_PORT`（15432） | PostgreSQL（供 IDE 中运行的服务直连） |
| `REDIS_PORT`（16379） | Redis（同上） |

### InfluxDB 3 Core

- 镜像 `influxdb:3.12.0-core`，数据库 `telemetry`，保留 30 天（conduit-server 启动时建库）。
- `INFLUX_TOKEN` 必须以 `apiv3_` 开头，容器启动时据此生成离线管理员 token。首次启动时写入库中，之后修改 INFLUX_TOKEN 不会生效：需停止服务、删除 `conduit_influx3-data` 卷（时序数据随之清空）后重新启动。
- 从 V0.1（InfluxDB 2.7）升级：新版本使用新卷 `conduit_influx3-data`，旧卷 `conduit_influx-data` 中的历史数据**不迁移**；确认不需要后可执行 `docker volume rm conduit_influx-data`。
- 查询示例：

  ```bash
  curl -s -H "Authorization: Bearer <INFLUX_TOKEN>" --get "http://<docker-host>:8096/api/v3/query_sql" \
    --data-urlencode "db=telemetry" --data-urlencode "format=json" \
    --data-urlencode "q=SELECT time, device_key, \"Ua\" FROM property ORDER BY time DESC LIMIT 5"
  ```

## 设备接入

- 连接：`clientId = username = deviceKey`，`password = deviceSecret`（创建或重置设备时显示一次）
- 上报：QoS1 发布到 `conduit/{productKey}/{deviceKey}/up/property`

```json
{ "id": "可选", "ts": 1760400000000, "params": { "Ua": 220.1, "Ia": 5.2 } }
```

- 产品可选解码器「标准 JSON」（上面的格式）或「扁平 JSON」（`{"ts":…,"Ua":220.1}`），并可定义物模型逐测点校验，详见 [设备接入指南](docs/device-integration.md)。

## 开发

```bash
mvn verify
```

```bash
cd conduit-ui && npm install && npm test && npm run dev
```

### 日常开发：基础设施在 Docker 宿主机，应用在 IDE

默认工作流：Docker 宿主机只跑 PostgreSQL、Redis、InfluxDB、EMQX；server、transport、simulator 和前端都在 IntelliJ 里本地运行。

1. 在 Docker 宿主机（或用 `DOCKER_HOST` 指向它）启动基础设施：

   ```bash
   docker compose -f deploy/docker-compose.yml up -d
   ```

2. 在 `deploy/.env` 中设置 `CONDUIT_AUTH_URL=http://<dev-host>:8080`，让 EMQX 的鉴权回调打到开发机上本地运行的 server，然后让 emqx 容器重新读取配置（`docker compose -f deploy/docker-compose.yml up -d --force-recreate emqx`）。同时确认 `BIND_ADDR`、`POSTGRES_PORT`（默认 15432）、`REDIS_PORT`（默认 16379）已按需设置，使这些端口对开发机开放。
3. 在 IDEA 中运行 `Conduit (local all)`（一次启动 Server、Transport、Simulator、UI）。首次需要先完成下面的 IDE 配置。
4. 完整的 Docker 演示部署只在需要时进行，用 `deploy/build-local.sh`（见上文「部署」）。

提示：

- 开发机上首次运行 server 时，Windows 防火墙或 Java 网络提示会询问是否允许 Java 接受入站连接，需要选择允许，否则 EMQX 无法回调 `http://<dev-host>:8080` 的鉴权接口，设备和 transport 连不上。
- 本地 transport 的 `session-expiry-seconds` 必须为 0：持久会话会让 EMQX 在进程停止后继续把共享订阅的消息分给离线会话，这些消息要等会话过期（默认 1 小时）才被丢弃，期间相当于丢数据。
- 本地模拟器默认使用演示产品「三相电表（演示）」。开发时 Docker 宿主机上不再运行模拟器，不会有两份模拟器争用同一产品的设备密钥，原先「不要用演示产品名」的警告已作废。

#### IDE 配置

1. 用 IDEA 打开仓库根目录，把根目录的 `pom.xml` 作为 Maven 工程导入，Project SDK 选 **JDK 21**。仓库自带的运行配置（`.run/`）会自动出现：

   | 运行配置 | 作用 |
   |---|---|
   | `Conduit Server (local)` | Spring Boot 运行配置，`ServerApplication`，Active profiles 已设为 `local` |
   | `Conduit Transport (local)` | 同上，`TransportApplication`。请从这里或 `Conduit (local all)` 启动；IDEA 在 Services 面板自动生成的同名 Spring Boot 项不带 `local` profile，会去连 localhost 而卡住 |
   | `Conduit Simulator (local)` | `SimulatorApplication`，读取 `simulator-local.properties` |
   | `Conduit UI (local)` | 在 `conduit-ui/` 下执行 `npm run dev`（首次先 `npm install`） |
   | `Conduit (local all)` | 一次启动 Server、Transport、Simulator、UI |

2. 在 `conduit-server/src/main/resources/` 和 `conduit-transport/src/main/resources/` 下各新建一个 `application-local.yml`，写入 Docker 宿主机地址、端口和 `.env` 中的口令。这个文件已在 `.gitignore` 中，不会被提交。

   > 注意：本版本起模块已由 `conduit-connector` 改名为 `conduit-transport`。已有的本地 `application-local.yml` 需要移到 `conduit-transport/src/main/resources/`，并把配置前缀改为 `conduit.transport.mqtt`；server 侧的 `conduit.internal.connector-password` 也已改为 `transport-password`。

   ```yaml
   # conduit-server/application-local.yml
   spring:
     datasource:
       url: jdbc:postgresql://<docker-host>:15432/conduit
       password: <POSTGRES_PASSWORD>
     data:
       redis:
         host: <docker-host>
         port: 16379
   conduit:
     bootstrap:
       admin-password: <CONDUIT_ADMIN_PASSWORD>
     auth:
       jwt-secret: <CONDUIT_JWT_SECRET>
     internal:
       token: <CONDUIT_INTERNAL_TOKEN>
       transport-password: <TRANSPORT_MQTT_PASSWORD>
     influx:
       url: http://<docker-host>:8096
       token: <INFLUX_TOKEN>   # apiv3_ 开头
   ```

   ```yaml
   # conduit-transport/application-local.yml
   spring:
     data:
       redis:
         host: <docker-host>
         port: 16379
   conduit:
     transport:
       mqtt:
         host: <docker-host>
         port: 1883
         client-id: conduit-transport-idea   # 必须以 conduit- 开头
         session-expiry-seconds: 0           # 必须为 0，见上方提示
         username: <TRANSPORT_MQTT_USERNAME>
         password: <TRANSPORT_MQTT_PASSWORD>
   ```

3. 模拟器不是 Spring 应用，在 `conduit-simulator/src/main/resources/` 下新建 `simulator-local.properties`（同样不入库），键名与环境变量相同，环境变量优先：

   ```properties
   CONDUIT_API_URL=http://localhost:8080
   CONDUIT_ADMIN_USERNAME=<CONDUIT_ADMIN_USERNAME>
   CONDUIT_ADMIN_PASSWORD=<CONDUIT_ADMIN_PASSWORD>
   MQTT_HOST=<docker-host>
   MQTT_PORT=1883
   # SIM_PRODUCT_NAME 不设置则使用默认的「三相电表（演示）」
   SIM_DEVICE_COUNT=5
   SIM_INTERVAL_MS=5000
   ```

4. 单独调试时先启动 `Conduit Server (local)`，再按需启动 Transport、Simulator、UI。前端默认把 `/api` 代理到本机 8080。

如果同时还部署了完整的 Docker 应用栈（`app` profile），容器里的 server / transport 会和 IDE 中的实例加入同一个消费组与共享订阅分担消息，数据不会重复也不会丢；但 EMQX 的鉴权回调只指向 `CONDUIT_AUTH_URL` 一处。一般不建议两套同时运行。

## 在宿主机面板（1Panel 等）中管理编排

在本机通过 `DOCKER_HOST` 远程执行 `docker compose` 时，容器标签记录的是本机的编排文件路径，宿主机上的面板因此找不到编排文件。需要由面板管理时：

1. 执行 `deploy/sync-to-host.sh`，把 `docker-compose.yml`、`.env`、`emqx/` 同步到宿主机的 `/opt/conduit`（可用 `CONDUIT_HOST_DIR` 修改）。
2. 在面板中以该目录的 `docker-compose.yml` 创建/导入编排，项目名为 `conduit`；面板会在宿主机上重建基础服务，此后标签指向宿主机路径。

仓库里的 `deploy/` 仍是唯一来源：修改编排后重新执行同步脚本。之后若又从本机执行 `docker compose` 或 `deploy/build-local.sh`，标签会变回本机路径，需要再在面板中重建一次。

## 日志

所有 Java 服务（server、transport、simulator）除控制台外，还把日志写到文件：

- 位置：环境变量 `CONDUIT_LOG_DIR` 指定目录，默认 `logs`（相对进程工作目录）。IDEA 运行配置的工作目录是仓库根目录，所以本地日志在 `<仓库>/logs/`（已加入 `.gitignore`）；每个服务一个文件：`conduit-server.log`、`conduit-transport.log`、`conduit-simulator.log`。
- 滚动：按天并按大小（单文件 50MB）滚动，保留 14 天，总量上限 1GB；归档文件名形如 `conduit-server.2026-10-15.0.log.gz`。
- Docker：三个服务设置 `CONDUIT_LOG_DIR=/var/log/conduit` 并共用命名卷 `conduit-logs`，可用 `docker run --rm -v conduit_conduit-logs:/logs alpine ls /logs` 查看，或挂载到其他容器读取。
- 不在此列：前端开发服务器（Vite）的日志只在 IDE 控制台；`conduit-web` 的 nginx 日志走 `docker logs`。
- 集成测试的日志写到 `conduit-server/target/test-logs/`，不污染工作目录。

## 端到端测试：杀进程不丢数据

10 台设备各发 50 条消息，发送中途 kill 并重启 `conduit-server`，最终 InfluxDB 中应恰好 500 条。需要完整应用栈已部署（`--profile app`，测试会 kill / start 容器里的 `conduit-server`，所以日常只起基础设施时无法运行；可不带 demo profile）。不要同时运行两个 e2e：它们共用同一个 conduit-server，一个 kill 它会让另一个连接失败。

```bash
mvn install -DskipTests
mvn verify -Pe2e -pl conduit-e2e
```

测试读取 `deploy/.env` 中的凭证与端口，并支持环境变量：

| 变量 | 默认 | 说明 |
|---|---|---|
| `E2E_HOST` | `localhost` | Docker 主机地址（远程 Docker 时填其地址） |
| `E2E_COMPOSE` | `docker compose` | compose 命令，例如独立二进制的完整路径；子进程继承 `DOCKER_HOST` |

失败时的诊断命令（在 Compose 所在的 Docker 上执行）：

```bash
docker compose -f deploy/docker-compose.yml exec redis redis-cli XLEN conduit:stream:dead
docker compose -f deploy/docker-compose.yml exec redis redis-cli XRANGE conduit:stream:dead - + COUNT 5
docker compose -f deploy/docker-compose.yml exec redis redis-cli XPENDING conduit:stream:up conduit-server
```

## 已知限制

- 物模型缓存为进程内缓存：多实例部署时，修改物模型最多 60 秒后在其他实例生效。
- 未启用 TLS；凭证均为演示用途。
- Redis 长时间不可用时，EMQX 会话队列（`max_mqueue_len`）溢出会丢消息，V1.0 压测时量化。
- 死信只能通过 `redis-cli XRANGE conduit:stream:dead - +` 查看。
