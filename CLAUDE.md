# Conduit 项目说明（给 Claude 与开发者）

个人研究用的 IoT 平台：设备通过 MQTT 接入，数据经消息总线进入服务端，按产品物模型校验后写入时序库，前端查看设备、最新值与历史曲线。本文件记录**整体设计思路与编码约定**。

**维护规则**：每次升级（新子项目、架构调整、重要修复）除了写 `docs/superpowers/` 下的 spec / plan 外，还必须把其中**决定性质的约定**（技术选型、模块职责、数据格式与语义、接口约定、界面与数据约定、部署方式等）同步更新到本文件对应章节，并在第 4 节「决定记录」追加一条；与本文件冲突的旧内容直接改掉，不留过期描述。

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
| `conduit-simulator` | 演示用设备模拟器：自动创建演示产品/设备、导入演示物模型、按间隔上报；演示设备附带枚举运行状态 st，首次连接 MQTT 失败每 2 秒重试、最多 15 次 |
| `conduit-ui` | 前端：Vue 3 + TypeScript + Element Plus + ECharts；统一框架（分组可折叠侧栏、面包屑顶栏、PageHeader）、首页总览、暗色模式 |
| `conduit-e2e` | 端到端测试（`-Pe2e`）：杀进程不丢数据 |
| `deploy/` | Docker Compose（项目名 `conduit`）、`build-local.sh` |

### 1.2 关键设计决定

- **消息总线**：经 `MessageBus` 接口，当前实现为 Redis Streams（消费组、`XPENDING` 认领、死信流 `conduit:stream:dead`）。将来评估 RabbitMQ（下行）、Kafka（大规模）时只换实现。
- **投递语义**：至少一次。下游写入成功后才 ack；可恢复错误（时序库不可用、数据库连接失败等，判断时沿异常 cause 链查找）原地退避重试 1s→30s，不消耗投递次数；意外错误只把该条消息进死信 `PROCESSING_ERROR`；超过 5 次投递进死信 `MAX_DELIVERY_EXCEEDED`。
- **多租户**：所有业务表带 `tenant_id`，MyBatis-Plus 租户拦截器自动过滤；跨租户查询（如按 productKey 找产品）必须显式标注并只在内部使用。
- **物模型**（V0.2-A）：产品定义属性（DOUBLE/INT/BOOL/TEXT/ENUM），上报按属性逐测点校验；不合格测点被剔除并记录 `FIELD_REJECTED`；没有物模型的产品按 V0.1 规则（数值一律浮点）。物模型缓存 60 秒，编辑后提交事务再失效。
- **解码器**：`PayloadDecoder` SPI，按产品选择（`standard-json`、`flat-json`），名称重复启动即失败。
- **时序库**：InfluxDB 3 Core 3.12.0，自写 HTTP 客户端（`/api/v3/write_lp`、`/api/v3/query_sql`），所有产品共用表 `property`，tag 为 `tenant_id`/`product_key`/`device_key`，每个测点一列，保留 30 天。列类型全表共享：同名测点在所有产品中必须是同一类型（例如模拟器只给物模型含枚举 st 的演示产品发送 st，e2e 等无物模型产品不发）。
- **历史查询**（V0.2-B）：`GET /api/v1/devices/{deviceKey}/history`，单设备 1～8 个测点，跨度 1 分钟～30 天（默认最近 1 小时）。先计数：每个测点 ≤ 1000 点时返回原始点，否则在固定档位（1s、5s、10s、30s、1m、5m、15m、30m、1h、3h、6h、12h）中取使桶数 ≤ 1000 的最小桶，用 `date_bin`（原点 1970）聚合：数值 avg/min/max，布尔/枚举取桶内最后值（`selector_last`）。SQL 参数化，时间参数用 ISO-8601 UTC 字符串；列名只用 `InfluxSchema`（60 秒缓存，未知列强制刷新一次）确认存在的标识符；InfluxDB 不可达或 5xx → 503，其他查询错误 → 500 且不暴露 SQL。前端 ECharts 6.1.0（5.6.0 存在 XSS 公告 GHSA-fgmj-fm8m-jvvx，故升级）经 `echarts/core` 按需引入（不用 vue-echarts），每测点一张联动小图，框选缩放后重新查询。
- **首页总览与计数**（界面刷新）：`GET /api/v1/overview` 按当前租户返回产品数、属性总数、设备数（启用/禁用/在线）、最后上报最近的 5 台设备、各产品设备数（在线/总数，按设备数降序）与最近 1 小时死信（`XREVRANGE` 死信流到 1 小时前、最多 1 万条，只统计 `pk` 属于本租户产品的条目，按 `reason` 分组）。`ProductView` 带 `deviceCount`/`onlineCount`，`UserView` 带 `tenantName`。设备计数统一由 `DeviceStats` 计算（PostgreSQL 分组计数 + Redis 在线状态批量读取）；Redis 不可用时在线数、最近上报与死信为 null（列表为空），其余照常，前端显示「—」。本期读出租户全部设备再统计，设备到万级时改增量计数（见 backlog）。
- **前端框架与外观**（界面刷新）：分组侧栏（概览 / 设备接入 / 系统管理，V0.2-C 的用户、租户为禁用项）可折叠、宽度 < 820px 自动折叠，侧栏有浅色/深色两种风格（默认深色）；顶栏为全局面包屑（路由 `meta.breadcrumb`，详情页用 `useBreadcrumb` 设置对象名称）、外观设置、租户名称与用户菜单；浏览器标题「<页面名或对象名称> · Conduit」；`/` 重定向到 `/home`，未知地址显示 404。暗色模式用 Element Plus 暗色变量（`<html>` 的 `dark` 类），主题 浅色/深色/跟随系统（默认跟随系统）；外观偏好（主题、侧栏风格、侧栏折叠、最新值视图）存 `localStorage` 键 `conduit.ui`，读写失败用默认值。ECharts 图表随主题销毁重建（内置 `dark` 主题，背景透明）。设备详情为信息卡 + 指标卡片：前 8 个数值测点一次历史查询画最近 1 小时迷你趋势线（60 秒刷新，数值 5 秒刷新），布尔/枚举显示文字标签；点击卡片进入 `?tab=history&props=<标识>`，历史页从 `props` 读取初始选中。
- **部署**：Docker 宿主机无外网时用 `deploy/build-local.sh`：本机从干净检出构建 jar 与前端，宿主机只打复制产物的薄镜像。

### 1.3 版本路线

见 `docs/superpowers/specs/2026-10-14-conduit-v0.1-design.md` 第 2 节路线图（V0.1 → V0.2 A/B/C → V0.3 计算字段与告警 → V0.4 资产与数据管理 → V1.0 → … → V2.1 AI 能力）。每个子项目都有 spec（`docs/superpowers/specs/`）和实施计划（`docs/superpowers/plans/`）；延后事项在 `docs/backlog-v0.2.md`。

## 2. 编码约定

### 2.1 注释（必须）

- **每个类都要有注释**，说明它做什么：Java 的类、接口、record、枚举写类级 Javadoc；TypeScript 模块写文件头注释或导出函数注释；Vue 组件在 `<script setup>` 顶部写注释。
- **每个重点方法都要有注释**：public 方法、service/controller 方法、策略类纯函数、带分支逻辑的方法，写 Javadoc/JSDoc 说明做什么、重要参数与返回值、不明显的行为（异常、边界、副作用）。简单 getter、一行辅助方法可以不写。
- 注释用中文，说明"做什么、为什么"，不复述代码。
- 修改代码时同步更新相关注释，不留过期注释。

### 2.2 其他约定

- Java 21、Spring Boot 3.5、MyBatis-Plus、Flyway；包名 `dev.conduit.<模块>`；按业务分包（`device`、`product`、`thingmodel`、`telemetry`、`pipeline` …）。
- 测试：先写失败测试（TDD）；集成测试用 Testcontainers；前端用 Vitest + `@vue/test-utils`。
- 界面：中文文案；所有分页表格默认每页 10 条（可选 10/20/50/100），分页、请求序号与越界页码修正统一用 `useTable`；功能入口要显眼（列表有「操作」列按钮、全局面包屑、产品与设备互相可跳转）；详情页的标签页与路由 query `tab` 同步（`router.replace`）。页面顶部用 `PageHeader`，列表筛选用 `FilterBar`，在线状态用 `OnlineDot`，启用/禁用用 `el-tag`，时间用 `RelativeTime`；空状态为 `el-empty` + 引导按钮，首次加载显示骨架屏。样式只用 CSS 变量（`--el-*` 或 `src/styles/tokens.css` 的 `--cd-*`），不写死颜色，深色主题下必须可读。新增页面在 `router/meta.ts` 的 `ROUTE_META` 中登记页面名称、面包屑与菜单归属。
- 演示与测试数据：用有意义的中文名称（如「三相电表（演示）」「1号楼配电室 电表01」），测试结束清理自己创建的数据。
- 提交：Conventional Commits；只署名不带邮箱；不加任何 AI 署名行。
- 仓库公开：不要把 IP、本机路径、用户名、口令、token 写进受版本控制的文件；本地配置（`deploy/.env`、`application-local.yml`、`simulator-local.properties`）已在 `.gitignore`。

## 3. 本地开发

见 README「开发」与「在 IDE 中运行」：IDEA 中运行 server 与 UI，PostgreSQL / Redis / InfluxDB / EMQX 使用演示环境；本地 transport 必须 `session-expiry-seconds: 0`。

## 4. 决定记录

按时间追加，每条一行：日期、版本、决定、对应 spec / 提交。

- 2026-10-14 V0.1：Spring Boot 3 + EMQX 5 + Redis Streams 总线（经 `MessageBus` 接口）+ PostgreSQL 16 + Redis 7；前端 Vue 3 + Element Plus；transport / server 两进程；EMQX HTTP 鉴权回调；多租户从 V0.1 起带 `tenant_id`（`2026-10-14-conduit-v0.1-design.md`）。
- 2026-10-15 V0.2：拆为 A 物模型与编解码 → B 历史查询与曲线 → C 多租户与用户管理，依次实施。
- 2026-10-15 V0.2-A：时序库改为 InfluxDB 3 Core 3.12.0（自写 v3 HTTP 客户端）；物模型只做属性（8 个字段）；逐测点校验与 `FIELD_REJECTED`；解码器 SPI（`standard-json`、`flat-json`）（`2026-10-15-conduit-v0.2a-thing-model-design.md`）。
- 2026-10-15：流水线可恢复错误沿异常 cause 链判断（MyBatis 包装的数据库断连也原地重试）；死信统一"先写记录再 ack"。
- 2026-10-15：`conduit-connector` 改名 `conduit-transport`（避免与 EMQX 数据集成 Connector 混淆），env `TRANSPORT_MQTT_*`，属性前缀 `conduit.transport.mqtt`。
- 2026-10-15：Docker 宿主机无外网，部署用 `deploy/build-local.sh`（本机干净检出构建 + 薄镜像）。
- 2026-10-15：界面约定——分页每页 10 条、入口显眼（操作列 + 面包屑）、演示数据用有意义中文名、e2e 自清理。
- 2026-10-15 V0.2-B：历史曲线在设备详情「历史」标签页；服务端自动聚合（≤1000 点/曲线，固定桶档位，数值 avg/min/max、状态取桶内最后值）；ECharts 6.1.0 按需引入（原定 5.6.0，因 XSS 公告 GHSA-fgmj-fm8m-jvvx 升级）；SQL 参数化（`2026-10-15-conduit-v0.2b-history-design.md`）。
- 2026-10-15 路线图：V2.1 AI 能力（自然语言查数、异常检测、物模型草稿生成、告警摘要）。
- 2026-10-15：所有类与重点方法必须写中文注释（见 2.1）；升级时同步更新本文件。
- 2026-10-15 路线图调整：V0.3 改为「计算字段 → 告警」（计算字段先行，告警规则可引用）；新增 V0.4 资产与数据管理（资产层级/分组、历史导出 CSV、设备批量导入）；V1.0 加审计日志；V1.1 加网关与子设备；规则引擎留在 V2.0，由 V0.3 固定结构告警规则演进；可配置看板暂不纳入。
- 2026-10-15 界面刷新：统一框架（分组可折叠侧栏 + 面包屑顶栏 + PageHeader + 404）、暗色模式与侧栏风格（偏好存 `localStorage` 的 `conduit.ui`）、列表套件（`useTable`/`FilterBar`/`OnlineDot`/`RelativeTime`）、设备信息卡与指标卡片迷你趋势线、首页总览 `GET /api/v1/overview`、`ProductView` 设备计数、`UserView` 租户名称（`2026-10-15-conduit-ui-refresh-design.md`）。
- 2026-10-15 前端：品牌标志为「字母 C 信号」（`ConduitLogo`，`tone` auto/dark-bg，深色侧栏与登录品牌区用 dark-bg；favicon 为 `public/favicon.svg`，线宽加粗）；应用外壳恰好一屏高（100vh/100dvh），侧栏与顶栏固定，只有 el-main 滚动；表格行高约 44px、主内容内边距 16px，使 10 行列表页在 1080p 屏无需滚动。
