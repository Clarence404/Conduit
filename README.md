# Conduit

个人研究性质的 IoT 平台。V0.1 跑通「设备 MQTT 直连 → 接入 → 总线 → 处理 → 时序存储 → Web 展示」主链路。

功能：产品与设备管理、MQTT 接入与鉴权、物模型逐测点校验与可插拔解码（V0.2-A）、设备历史曲线（V0.2-B：设备详情「历史」标签页，按时间范围自动聚合，多测点联动缩放）、前端体验优化（首页总览、分组侧栏与面包屑、设备指标卡片与迷你趋势线、暗色模式与侧栏风格切换）、多租户与用户管理（V0.2-C：平台管理员管理租户并可进入租户代为操作，租户管理员管理本租户的产品、设备与用户，只读用户只能查看）、可靠性加固（V0.2-R：EMQX 磁盘持久会话 + transport 单活多备，杀 server / transport、重启 EMQX 都不丢数据；模拟器按物模型模拟各演示租户的设备）。

设计文档：`docs/superpowers/specs/2026-10-14-conduit-v0.1-design.md`（版本路线与 V0.1）、`docs/superpowers/specs/2026-10-15-conduit-v0.2a-thing-model-design.md`（V0.2-A 物模型与编解码）、`docs/superpowers/specs/2026-10-15-conduit-v0.2b-history-design.md`（V0.2-B 历史查询与曲线）、`docs/superpowers/specs/2026-10-15-conduit-ui-refresh-design.md`（前端体验优化）、`docs/superpowers/specs/2026-10-16-conduit-v0.2c-tenants-users-design.md`（V0.2-C 多租户与用户管理）、`docs/superpowers/specs/2026-10-16-conduit-v0.2r-reliability-design.md`（V0.2-R 可靠性加固）

设备接入指南（流程、连接参数、上报格式、示例代码、排错）：[`docs/device-integration.md`](docs/device-integration.md)

## 架构

```mermaid
flowchart TB
    D[设备] -->|MQTT| E[EMQX]
    E -->|"① 连接时同步鉴权"| A
    E -->|"持久会话订阅"| C["conduit-transport ×2<br/>（Redis 选主，单活多备）"]
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

### transport 单活多备与磁盘持久会话

- EMQX 开启磁盘持久会话（`durable_sessions`，单节点 `singleton`）。transport 以固定 clientId `conduit-transport`、`cleanStart=false`、会话保留 86400 秒普通订阅 `conduit/+/+/up/#`（订阅选项 `retainHandling=DO_NOT_SEND`，重连时不重放保留消息）；transport 离线期间的消息写入 EMQX 磁盘，保留 1 天，重连后补发，不受内存队列 1000 条的上限。EMQX 5.8.6 的磁盘持久会话不支持共享订阅，所以多个 transport 不再分摊消息，而是单活多备。
- 选主：Redis 键 `conduit:transport:leader`（值为实例 id），用一段 Lua 脚本抢锁：键不存在则 `SET PX` 写入；键里已是本实例 id 则刷新过期时间并视为抢到（所以 Redis 短暂抖动后原主实例立即拿回锁）；其他情况拒绝。主实例每 5 秒续期，锁 15 秒过期，备实例每 2 秒尝试抢锁，只有主实例连接 EMQX。续期失败（Redis 不可用、锁已被他人持有、超时）立即断开 MQTT 转为备用；正常停止时释放锁，备实例立即接管；主实例崩溃时备实例最长 15 秒后接管同一个会话。配置项 `conduit.transport.leader.ttl` / `renew-interval` / `acquire-interval`。抢锁或适配器启动首次失败记 WARN，之后的重试记 DEBUG，恢复与每次角色变化记 INFO。
- 健康检查：transport（端口 8081）的 `GET /actuator/health` 在 `components.leader.details` 中显示 `role`（`LEADER` / `STANDBY`）、`instanceId` 与 `connected`；`connected` 仅在已连接且订阅已被 EMQX 批准后才为 true。
- 全量包起两个 transport（一主一备），都配置 `stop_grace_period: 20s` 以便正常停止时释放锁；开发时 IDE 中一个 transport，直接成为主实例。

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
├── conduit-common/                  dev.conduit.common
│   ├── message/                     RawMessage：协议无关的统一消息，及其与 Stream 字段的编解码
│   └── bus/                         MessageBus 接口和 Redis Streams 实现（投递、消费组、认领、死信）
├── conduit-transport/               dev.conduit.transport（端口 8081）
│   ├── adapter/                     ProtocolAdapter 接口；新增协议就是新增一个实现
│   ├── leader/                      选主：Redis 锁、只有主实例连接 EMQX、健康检查显示 role
│   └── mqtt/                        MQTT 适配器：固定 clientId 的持久会话 + 普通订阅、写入总线成功后才确认、topic 解析
├── conduit-server/                  dev.conduit.server（端口 8080），按业务领域分包
│   ├── auth/                        登录、JWT、权限矩阵、强制改密码、个人接口、代入留痕
│   ├── tenant/                      租户上下文与租户隔离、租户编码规则
│   ├── user/                        租户用户管理（/api/v1/users）
│   ├── platform/                    平台管理员接口：平台概况、租户管理、进入租户
│   ├── product/  device/            产品、设备的增删改查和接口
│   ├── mqttauth/                    EMQX 鉴权回调
│   ├── pipeline/                    数据流水线：消费总线、按物模型逐测点校验、重试、死信
│   │   └── codec/                   可插拔解码器（标准 JSON、扁平 JSON）
│   ├── thingmodel/                  物模型：属性增删改查、导入导出、按产品缓存
│   ├── telemetry/                   InfluxDB 写入与历史查询（原始 / 自动聚合）、最新值存储与查询
│   ├── presence/                    设备上下线状态
│   ├── overview/                    首页总览接口：按租户统计设备、在线与最近 1 小时死信
│   ├── security/  bootstrap/        密钥生成与哈希；启动时创建默认租户、租户管理员与平台管理员
│   └── config/  web/                框架配置；统一错误响应、分页
├── conduit-simulator/               dev.conduit.simulator：演示产品 + 各租户的物模型产品，按周期上报
├── conduit-e2e/                     端到端测试（-Pe2e 时才参与构建）：临时环境中的四个故障场景
├── conduit-ui/                      Vue 3 + TypeScript + Element Plus（独立 npm 工程）
│   └── src/  api/  stores/  router/  layouts/  views/  components/  composables/  utils/  styles/
├── deploy/
│   ├── infra/                       基础服务包（开发用）：compose、.env.example、EMQX 鉴权与 ACL 配置
│   ├── full/                        全量包：extends 基础服务 + 应用服务，Dockerfile（预编译产物 / 源码两种来源）
│   └── package.sh                   开发机打全量包 target/conduit-full-<短提交>.tar.gz
├── .dockerignore                    全量包镜像的构建上下文（仓库根目录）排除规则
├── docs/                            设备接入指南、待办清单、设计文档与实现计划
└── .run/                            IntelliJ IDEA 共享运行配置
```

## 部署：基础服务包与全量包

`deploy/` 下有两个可以直接放到 Linux 上用 Docker Compose（v2）启动的目录。两者项目名都是 `conduit`，数据卷相同（`conduit_pg-data` 等），同一台宿主机上二选一，切换时数据沿用：

| 目录 | 内容 | 用途 |
|---|---|---|
| `deploy/infra` | PostgreSQL、Redis、InfluxDB 3、InfluxDB 3 Explorer、EMQX | 日常开发：应用在 IDE 中运行，见下方「开发」 |
| `deploy/full` | 基础服务（`extends` 引用 `deploy/infra`）+ conduit-server、两个 transport（`conduit-transport`、`conduit-transport-2`，一主一备）、conduit-web、演示模拟器 | 完整演示部署 |

> **安全提示**
>
> - `.env` 中所有 `change-me-*` 值必须在宿主机可被他人访问之前替换掉，例如用 `openssl rand -hex 32` 生成。
> - 端口按 `.env` 的 `BIND_ADDR` 绑定：基础服务包默认 `0.0.0.0`（开发机要连接这些端口），全量包默认 `127.0.0.1`。对外开放时确保网络可信。
> - API 端口同时暴露 `/internal/mqtt/auth`（EMQX 鉴权回调，受内部 token 保护）和健康检查端点。
> - `EMQX_DASHBOARD_PASSWORD` 仅在首次启动（`emqx-data` 卷为空）时生效；之后修改请在控制台中操作，或 `down -v` 清卷重来。

### 基础服务包

把 `deploy/infra` 复制或 clone 到 Linux 宿主机，在该目录中：

```bash
cp .env.example .env    # 替换口令，CONDUIT_AUTH_URL 改为 http://<dev-host>:8080
docker compose up -d
```

`CONDUIT_AUTH_URL` 必填，是 EMQX 鉴权回调的地址，即开发机上 IDE 中运行的 conduit-server（`<dev-host>` 为宿主机访问开发机的地址）；修改后执行 `docker compose up -d --force-recreate emqx`。EMQX 使用官方镜像 `emqx/emqx:5.8.6`，`emqx/base.hocon.tpl` 与 `emqx/acl.conf` 以只读方式挂载，启动时渲染回调地址与内部 token；EMQX 不依赖 conduit-server，server 未就绪时自行重试。详见 `deploy/infra/README.md`。

从旧的 `deploy/docker-compose.yml` 迁移：在旧编排目录 `docker compose down`（不加 `-v`），把旧 `.env` 复制为 `deploy/infra/.env`，在新目录 `docker compose up -d`；项目名与数据卷不变，数据沿用。旧的自定义镜像 `conduit-emqx` 不再使用。

EMQX 开启了磁盘持久会话：compose 环境变量 `EMQX_DURABLE_SESSIONS__ENABLE=true`、`EMQX_CLUSTER__DISCOVERY_STRATEGY=singleton`，并固定节点名 `EMQX_NODE__NAME=emqx@127.0.0.1`（见「transport 单活多备与磁盘持久会话」）。已有部署升级注意：EMQX 以前按容器 IP 命名节点，改为固定节点名后，旧节点名下的运行时数据不再使用，例如在控制台里改过的密码会恢复为 `.env` 中的 `EMQX_DASHBOARD_PASSWORD`，首次重建后 EMQX 也以全新的数据目录启动。

> **EMQX 配置是 bind mount**：`emqx/base.hocon.tpl` 与 `emqx/acl.conf` 以挂载方式读取，所以 `deploy/infra`（或 `deploy/full`）目录必须放在运行 Docker 的那台 Linux 机器上。不再支持在另一台机器上通过远程 `DOCKER_HOST` 执行 compose。

**修改 InfluxDB token**：`INFLUX_TOKEN` 在 InfluxDB 首次启动时写入库中，之后改 `.env` 不生效。要更换时先停止整套服务，执行 `docker volume rm conduit_influx3-data`（会丢失全部时序数据），再重新 `docker compose up -d`。

### 全量包

应用镜像的产物有两种来源，由 `deploy/full/.env` 的 `BUILD_FROM` 选择：

- `prebuilt`（默认，宿主机无外网时用）：在开发机上执行 `deploy/package.sh`（需 JDK 21 上的 Maven 与 Node.js 22）。脚本从当前提交的干净检出编译 jar 与前端（未提交的修改与 `application-local.yml` 等本地配置不会进入包），生成 `target/conduit-full-<短提交>.tar.gz`，内含 `deploy/`（含产物、不含 `.env`）、根 `.dockerignore` 与 `conduit-ui/nginx.conf`。复制到宿主机后：

  ```bash
  tar -xzf conduit-full-<短提交>.tar.gz
  cd deploy/full
  cp .env.example .env    # 替换口令
  docker compose up -d --build
  ```

  宿主机上需已有 `eclipse-temurin:21.0.6_7-jre`、`nginx:1.27.4-alpine` 与基础服务的镜像。前端「连接信息」显示的 MQTT 端口在打包时固定（`VITE_MQTT_PORT`，默认 1883）。
- `source`（宿主机能联网时用）：clone 仓库，`cd deploy/full`，`cp .env.example .env` 并设 `BUILD_FROM=source`，再 `docker compose up -d --build`；镜像内用 `maven:3.9.9-eclipse-temurin-21` 与 `node:22.14.0-alpine` 编译。

两个 Dockerfile（`deploy/full/docker/java.Dockerfile`、`web.Dockerfile`）的构建上下文都是仓库（或包）根目录，用全局 `ARG BUILD_FROM` 与 `FROM ${BUILD_FROM} AS app` 选择 `prebuilt` 或 `source` 阶段，BuildKit 只构建选中的阶段。EMQX 的鉴权回调在全量包中固定为 `http://conduit-server:8080`。模拟器默认启动。详见 `deploy/full/README.md`。

全量包启动后，浏览器打开 `http://<docker-host>:8090`（Docker 在本机时为 `localhost`），用 `.env` 中的 `CONDUIT_ADMIN_USERNAME` / `CONDUIT_ADMIN_PASSWORD` 登录（默认租户的租户管理员）；管理租户用 `CONDUIT_PLATFORM_ADMIN_USERNAME` / `CONDUIT_PLATFORM_ADMIN_PASSWORD` 登录（平台管理员，见「账号与角色」）。模拟器会自动创建「三相电表（演示）」产品和设备（设备名形如「1号楼配电室 电表01」，每栋楼 10 台；已有设备的旧名称会在启动时改成这种格式，Key 不变），并在产品缺少演示物模型（docs/examples/demo-meter.thing-model.json）中的任一属性时以合并方式导入（已有部署因此会自动补上 V0.2-B 新增的枚举属性「运行状态」st：每台设备每个整点可能切换一次，多数时间为「运行」）（默认 100 台，每 10 秒上报一次，见 `SIM_DEVICE_COUNT` / `SIM_INTERVAL_MS`；上报格式由 `SIM_PAYLOAD_FORMAT`（`standard` / `flat`）指定，新建产品时按此选用解码器，已有产品的解码器不会被修改）。模拟器首次连接 MQTT 失败时每 2 秒重试，最多 15 次。

已有部署升级注意：演示产品的默认名称已从「Demo Meter」改为「三相电表（演示）」，升级前请先在界面上把旧产品改名为「三相电表（演示）」，否则模拟器会新建一个产品、旧产品及其设备会遗留下来；改名后模拟器会复用该产品，并把其下设备原地改成新名称。

### 模拟器：演示产品与各租户的产品

模拟器始终维护默认租户的演示产品（见上）。配置平台管理员密码后，它在启动时用平台管理员登录，列出启用中的租户（按 `SIM_TENANTS` 过滤），逐个进入租户，取物模型非空的产品及其启用设备（每产品最多 `SIM_MAX_DEVICES_PER_PRODUCT` 台），重置这些设备的密钥后连接，并按产品解码器（`standard-json` / `flat-json`）上报；任一租户或产品失败只记 WARN，不影响其他。退出租户失败时模拟器用平台管理员重新登录，重新登录也失败则发现到此为止，保留已发现的部分；某台设备重置密钥失败只跳过该设备。

| 变量 | 默认 | 说明 |
|---|---|---|
| `SIM_PLATFORM_USERNAME` | `sysadmin` | 平台管理员账号 |
| `SIM_PLATFORM_PASSWORD` | 空 | 平台管理员密码；为空时只模拟演示产品 |
| `SIM_TENANTS` | `*` | 逗号分隔的租户编码，`*` 表示全部启用中的租户 |
| `SIM_MAX_DEVICES_PER_PRODUCT` | `100` | 每个产品最多模拟的设备数 |

取值按物模型逐测点生成，满足上下限与小数位：
- 电压在 220 V 附近，三相略有差别；电流与用电功率按日用电曲线。
- 产品含 eday / etotal / 发电 / 光伏 / pv / solar 测点或辐照度测点时视为光伏模型：其中单位为 kW / W 的功率测点（如「交流功率」）按日照曲线，夜间为 0，并累加成日发电量；用电类模型的功率按用电曲线。同一产品有多个功率测点时取最大的一个，不求和。
- 光伏功率与辐照度在 6～18 点按正弦日照，叠加云层扰动，夜间为 0。
- 电能、用水等累计量单调递增，增量与功率或流量相符；日发电量每天零点归零。
- 环境温度昼夜变化，绕组等设备温度随负载升高；流量有早晚用水高峰。
- 水压、pH、浊度、余氯、湿度、PM2.5、CO₂、风速在合理范围内缓慢漂移。
- 枚举多数时间取正常值（标签含 运行/发电/开启/正常，否则第一个值），同一小时内不变；布尔多数为否。
- 其他数值在上下限内随机游走；文本跳过。

日曲线按模拟器所在时区计算（Docker 中 `TZ=Asia/Shanghai`）。

> **注意**：模拟器会重置所模拟设备的密钥，真实设备随之连不上，只应对演示租户使用；用 `SIM_TENANTS` 限定范围。「华东电力运维」「华南水务」「西北新能源」是本地手工通过平台管理界面或 API 创建的演示租户，并非代码创建；`SIM_TENANTS` 填的是租户编码，不是名称。

宿主机端口在 `.env` 中配置（基础服务包 `deploy/infra/.env`，全量包 `deploy/full/.env`），括号内为默认值：

| 变量 | 服务 |
|---|---|
| `WEB_PORT`（8090） | Web（全量包） |
| `API_PORT`（8091） | REST API（全量包） |
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

## 账号与角色

| 角色 | 所属 | 能力 |
|---|---|---|
| 平台管理员 `PLATFORM_ADMIN` | 不属于任何租户 | 平台首页、租户管理（新建含首个管理员、改名、停用/启用、删除）、查看租户用户、重置租户管理员密码、进入租户 |
| 租户管理员 `TENANT_ADMIN` | 一个租户 | 本租户产品、物模型、设备的全部读写，用户管理 |
| 只读用户 `TENANT_VIEWER` | 一个租户 | 本租户只读 |

- **初始账号**：启动时若没有默认租户 `default` 与 `CONDUIT_ADMIN_USERNAME`（默认 `admin`）就创建它们（`admin` 是默认租户的租户管理员）；若库中没有任何平台管理员，用 `CONDUIT_PLATFORM_ADMIN_USERNAME`（默认 `sysadmin`）与 `CONDUIT_PLATFORM_ADMIN_PASSWORD` 创建一个，密码为空则不创建（server 打一条 WARN，照常启动）。已有账号不会被改动。
- **规则**：用户名 3～32 位，字母开头，只能含字母、数字、`_`、`-`、`.`，全平台唯一（登录不选租户）；密码 8～64 位，同时含字母和数字；租户编码 `^[a-z][a-z0-9-]{1,31}$`，创建后不可改。
- **首次登录改密码**：新建的用户、新租户的首个管理员、被重置密码的用户登录后必须先修改密码，界面弹出不可关闭的对话框；此前其余接口返回 403 `PASSWORD_CHANGE_REQUIRED`。
- **进入租户**：平台管理员在「租户」页点「进入」，确认后以租户管理员身份操作该租户，顶栏下方显示「正在以平台管理员身份操作租户：XX」与「退出」；代入期间每个写请求在 server 日志记一行 INFO（`Impersonated write by platform admin …`）。代入期间不能修改密码。
- **停用租户**：其用户登录与续期被拒，其设备 MQTT 连接被拒，已连接设备的上报进入死信 `TENANT_DISABLED`；默认租户不能停用或删除；删除租户前须先删掉其产品与设备，用户随之删除。
- **生效时间**：禁用用户、停用租户、修改角色在对方下次续期时生效，最长 15 分钟（access token 有效期）。

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

1. 在 Docker 宿主机上启动基础服务包（见上文「基础服务包」）：把 `deploy/infra` 放到宿主机，在该目录中 `cp .env.example .env` 并修改，然后

   ```bash
   docker compose up -d
   ```

2. `deploy/infra/.env` 中的 `CONDUIT_AUTH_URL=http://<dev-host>:8080` 让 EMQX 的鉴权回调打到开发机上本地运行的 server；修改后执行 `docker compose up -d --force-recreate emqx`。同时确认 `BIND_ADDR`（默认 `0.0.0.0`）、`POSTGRES_PORT`（默认 15432）、`REDIS_PORT`（默认 16379）使这些端口对开发机开放。
3. 在 IDEA 中运行 `Conduit (local all)`（一次启动 Server、Transport、Simulator、UI）。首次需要先完成下面的 IDE 配置。
4. 完整的 Docker 演示部署只在需要时进行，用全量包（见上文「全量包」）。

提示：

- 开发机上首次运行 server 时，Windows 防火墙或 Java 网络提示会询问是否允许 Java 接受入站连接，需要选择允许，否则 EMQX 无法回调 `http://<dev-host>:8080` 的鉴权接口，设备和 transport 连不上。
- 本地 transport 启动后经开发环境 Redis 的选主锁直接成为主实例（`http://localhost:8081/actuator/health` 显示 `"role":"LEADER"`）。不要在 `application-local.yml` 中覆盖 `client-id` 或 `session-expiry-seconds`：固定 clientId 与 86400 秒的会话保留让 EMQX 在 transport 停止期间把消息存在磁盘上，重启后补发。transport 被强制结束时锁最长 15 秒后过期，期间新进程等待接管，数据仍在 EMQX 中。
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
       platform-admin-password: <CONDUIT_PLATFORM_ADMIN_PASSWORD>   # 不填则不创建平台管理员 sysadmin
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
   # 多租户模拟（可选）：填写平台管理员密码后，同时模拟 SIM_TENANTS 中各租户有物模型的产品（会重置这些设备的密钥）
   SIM_PLATFORM_PASSWORD=<CONDUIT_PLATFORM_ADMIN_PASSWORD>
   SIM_TENANTS=*
   ```

4. 单独调试时先启动 `Conduit Server (local)`，再按需启动 Transport、Simulator、UI。前端默认把 `/api` 代理到本机 8080。

如果同时还部署了全量包，容器里的 server 会和 IDE 中的 server 加入同一个消费组分担消息；transport 则与 IDE 中的实例竞争同一把选主锁，只有一个实例连接 EMQX。数据不会重复也不会丢，但 EMQX 的鉴权回调只指向 `CONDUIT_AUTH_URL` 一处。一般不建议两套同时运行。

## 在宿主机面板（1Panel 等）中管理编排

两个包都直接放在 Linux 宿主机的目录中运行，面板以该目录下的 `docker-compose.yml` 创建或导入编排即可（项目名 `conduit`）。全量包引用 `../infra/docker-compose.yml`，导入时保持 `deploy/infra` 与 `deploy/full` 的相对位置。

## 日志

所有 Java 服务（server、transport、simulator）除控制台外，还把日志写到文件：

- 位置：环境变量 `CONDUIT_LOG_DIR` 指定目录，默认 `logs`（相对进程工作目录）。IDEA 运行配置的工作目录是仓库根目录，所以本地日志在 `<仓库>/logs/`（已加入 `.gitignore`）；每个服务一个文件：`conduit-server.log`、`conduit-transport.log`、`conduit-simulator.log`。
- 滚动：按天并按大小（单文件 50MB）滚动，保留 14 天，总量上限 1GB；归档文件名形如 `conduit-server.2026-10-15.0.log.gz`。
- Docker（全量包）：server、两个 transport 与模拟器设置 `CONDUIT_LOG_DIR=/var/log/conduit`（第二个 transport 为其 `transport-2` 子目录）并共用命名卷 `conduit-logs`，可用 `docker run --rm -v conduit_conduit-logs:/logs alpine ls /logs` 查看，或挂载到其他容器读取。
- 不在此列：前端开发服务器（Vite）的日志只在 IDE 控制台；`conduit-web` 的 nginx 日志走 `docker logs`。
- 集成测试的日志写到 `conduit-server/target/test-logs/`，不污染工作目录。
- 日志级别：默认 INFO。本地开发时调高：
  - server / transport：在各自不入库的 `application-local.yml` 中加 `logging.level.dev.conduit: DEBUG`（server 再加 `org.springframework.web: DEBUG` 可看到请求映射；`dev.conduit` 为 DEBUG 时 MyBatis 会打印执行的 SQL 与参数）。
  - simulator：`Conduit Simulator (local)` 运行配置已带 `-DCONDUIT_LOG_LEVEL=DEBUG`；也可用同名环境变量设置。

## 端到端测试：故障场景不丢数据

`conduit-e2e`（`-Pe2e` 时才参与构建）在完全独立的临时环境中验证四种故障下数据不丢。Testcontainers 在 Docker 宿主机上起 PostgreSQL 16.8、Redis 7.4.2、InfluxDB 3.12.0-core，以及开启磁盘持久会话的 EMQX 5.8.6（端口随机，与 `conduit` 开发环境互不影响）；测试机本地以 jar 启动 1 个 server 与 2 个 transport。每个场景新建产品「e2e 场景 N 电表」与 10 台设备，每台以 QoS1 发送 50 条（未收到 PUBACK 时重连重发），发送中途注入故障：

| 场景 | 故障 | 验证的机制 |
|---|---|---|
| 1 | 强制结束 server，3 秒后重启 | 未确认消息被重新认领处理 |
| 2 | 强制结束主 transport | 备实例接管，磁盘积压补齐 |
| 3 | 两个 transport 都结束，设备继续上报约 20 秒后重启 | 磁盘持久会话兜底 |
| 4 | 重启 EMQX 容器 | 设备与 transport 重连，会话与积压不丢 |

每个场景断言 InfluxDB 中该产品恰好 500 条、死信流中没有该产品、消费组 `conduit-server` 没有待确认消息，结束时删除所建产品与设备。口令与 token 每次运行随机生成。

```bash
mvn -B -DskipTests install                                    # 先打包 server / transport jar 并安装模拟器
E2E_DEV_HOST=<dev-host> mvn -B verify -Pe2e -pl conduit-e2e
```

- `E2E_DEV_HOST`（必填）：Docker 宿主机访问测试机的地址。EMQX 的鉴权回调打到测试机上本地运行的 server，未设置时测试直接失败并说明原因；测试机防火墙需允许该 JDK 的 java 接受入站连接。
- 其余沿用 `DOCKER_HOST` / `TESTCONTAINERS_HOST_OVERRIDE`。
- 耗时约 6～10 分钟，不进默认构建。进程日志在 `conduit-e2e/target/e2e-logs/<时间>/`（`server`、`transport-a`、`transport-b` 各一个目录），失败时先看这里。

## 已知限制

- 物模型缓存为进程内缓存：多实例部署时，修改物模型最多 60 秒后在其他实例生效。
- 未启用 TLS；凭证均为演示用途。
- transport 离线或 Redis 不可用期间，消息保存在 EMQX 磁盘持久会话中，保留 1 天（`durable_sessions.message_retention_period`），超过即丢；同一时刻只有主 transport 消费（单活多备），接入吞吐受单个实例限制，升级 EMQX 后改回多实例共享订阅（见 `docs/backlog-v0.2.md`）。
- 死信只能通过 `redis-cli XRANGE conduit:stream:dead - +` 查看。
