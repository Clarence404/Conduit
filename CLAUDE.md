# Conduit 项目说明（给 Claude 与开发者）

个人研究用的 IoT 平台：设备通过 MQTT 接入，数据经消息总线进入服务端，按产品物模型校验后写入时序库，前端查看设备、最新值与历史曲线。本文件记录**整体设计思路与编码约定**，设计有变化时同步更新这里（以及对应 spec）。

## 1. 整体设计

### 1.1 组件与数据流

```
设备 ──MQTT──> EMQX ──共享订阅──> conduit-transport ──XADD──> Redis Streams(conduit:stream:up)
                 │                                                  │
                 └─鉴权 HTTP 回调─> conduit-server <──消费组读取──────┘
                                       │
                                       ├─> InfluxDB 3（表 property，时序数据）
                                       ├─> Redis（最新值、在线状态、设备身份缓存）
                                       └─> PostgreSQL（租户、产品、物模型、设备、用户）
conduit-ui（Vue）──REST──> conduit-server
```

| 模块 | 职责 |
|---|---|
| `conduit-common` | 跨进程共享：`MessageBus` 接口与 Redis Streams 实现、协议无关的 `RawMessage` |
| `conduit-transport` | 协议接入层（对应 ThingsBoard 的 transport）：订阅 EMQX 共享主题，把设备报文原样组装成 `RawMessage` 发布到总线；不做业务解析 |
| `conduit-server` | 业务核心：EMQX 鉴权回调、总线消费流水线（解码 → 物模型校验 → 写时序库/最新值）、REST API、多租户 |
| `conduit-simulator` | 演示用设备模拟器：自动创建演示产品/设备、导入演示物模型、按间隔上报 |
| `conduit-ui` | 前端：Vue 3 + TypeScript + Element Plus + ECharts |
| `conduit-e2e` | 端到端测试（`-Pe2e`）：杀进程不丢数据 |
| `deploy/` | Docker Compose（项目名 `conduit`）、`build-local.sh` |

### 1.2 关键设计决定

- **消息总线**：经 `MessageBus` 接口，当前实现为 Redis Streams（消费组、`XPENDING` 认领、死信流 `conduit:stream:dead`）。将来评估 RabbitMQ（下行）、Kafka（大规模）时只换实现。
- **投递语义**：至少一次。下游写入成功后才 ack；可恢复错误（时序库不可用、数据库连接失败等，判断时沿异常 cause 链查找）原地退避重试 1s→30s，不消耗投递次数；意外错误只把该条消息进死信 `PROCESSING_ERROR`；超过 5 次投递进死信 `MAX_DELIVERY_EXCEEDED`。
- **多租户**：所有业务表带 `tenant_id`，MyBatis-Plus 租户拦截器自动过滤；跨租户查询（如按 productKey 找产品）必须显式标注并只在内部使用。
- **物模型**（V0.2-A）：产品定义属性（DOUBLE/INT/BOOL/TEXT/ENUM），上报按属性逐测点校验；不合格测点被剔除并记录 `FIELD_REJECTED`；没有物模型的产品按 V0.1 规则（数值一律浮点）。物模型缓存 60 秒，编辑后提交事务再失效。
- **解码器**：`PayloadDecoder` SPI，按产品选择（`standard-json`、`flat-json`），名称重复启动即失败。
- **时序库**：InfluxDB 3 Core 3.12.0，自写 HTTP 客户端（`/api/v3/write_lp`、`/api/v3/query_sql`），所有产品共用表 `property`，tag 为 `tenant_id`/`product_key`/`device_key`，每个测点一列，保留 30 天。
- **历史查询**（V0.2-B）：单设备多测点，服务端自动选择原始或按时间桶聚合（每条曲线 ≤ 1000 点），SQL 参数化。
- **部署**：Docker 宿主机无外网时用 `deploy/build-local.sh`：本机从干净检出构建 jar 与前端，宿主机只打复制产物的薄镜像。

### 1.3 版本路线

见 `docs/superpowers/specs/2026-10-14-conduit-v0.1-design.md` 第 2 节路线图（V0.1 → V0.2 A/B/C → V0.3 告警 → V1.0 → … → V2.1 AI 能力）。每个子项目都有 spec（`docs/superpowers/specs/`）和实施计划（`docs/superpowers/plans/`）；延后事项在 `docs/backlog-v0.2.md`。

## 2. 编码约定

### 2.1 注释（必须）

- **每个类都要有注释**，说明它做什么：Java 的类、接口、record、枚举写类级 Javadoc；TypeScript 模块写文件头注释或导出函数注释；Vue 组件在 `<script setup>` 顶部写注释。
- **每个重点方法都要有注释**：public 方法、service/controller 方法、策略类纯函数、带分支逻辑的方法，写 Javadoc/JSDoc 说明做什么、重要参数与返回值、不明显的行为（异常、边界、副作用）。简单 getter、一行辅助方法可以不写。
- 注释用中文，说明"做什么、为什么"，不复述代码。
- 修改代码时同步更新相关注释，不留过期注释。

### 2.2 其他约定

- Java 21、Spring Boot 3.5、MyBatis-Plus、Flyway；包名 `dev.conduit.<模块>`；按业务分包（`device`、`product`、`thingmodel`、`telemetry`、`pipeline` …）。
- 测试：先写失败测试（TDD）；集成测试用 Testcontainers；前端用 Vitest + `@vue/test-utils`。
- 界面：中文文案；所有分页表格默认每页 10 条（可选 10/20/50/100）；功能入口要显眼（列表有「操作」列按钮、详情页有面包屑、产品与设备互相可跳转）。
- 演示与测试数据：用有意义的中文名称（如「三相电表（演示）」「1号楼配电室 电表01」），测试结束清理自己创建的数据。
- 提交：Conventional Commits；只署名不带邮箱；不加任何 AI 署名行。
- 仓库公开：不要把 IP、本机路径、用户名、口令、token 写进受版本控制的文件；本地配置（`deploy/.env`、`application-local.yml`、`simulator-local.properties`）已在 `.gitignore`。

## 3. 本地开发

见 README「开发」与「在 IDE 中运行」：IDEA 中运行 server 与 UI，PostgreSQL / Redis / InfluxDB / EMQX 使用演示环境；本地 transport 必须 `session-expiry-seconds: 0`。
