# Conduit 项目说明（给 Claude 与开发者）

个人研究用的 IoT 平台：设备通过 MQTT 接入，数据经消息总线进入服务端，按产品物模型校验后写入时序库，前端查看设备、最新值与历史曲线。本文件记录**整体设计思路与编码约定**。

**维护规则**：每次升级（新子项目、架构调整、重要修复）除了写 `docs/superpowers/` 下的 spec / plan 外，还必须把其中**决定性质的约定**（技术选型、模块职责、数据格式与语义、接口约定、界面与数据约定、部署方式等）同步更新到本文件对应章节，并在第 4 节「决定记录」追加一条；与本文件冲突的旧内容直接改掉，不留过期描述。讨论中确定的后续工作与方向性判断（即使暂不实施）也要当时写进路线图（`docs/superpowers/specs/2026-10-14-conduit-v0.1-design.md` 第 2 节）或对应 spec，不能只记在对话或个人记忆里。

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
| `conduit-server` | 业务核心：EMQX 鉴权回调、总线消费流水线（解码 → 物模型校验 → 写时序库/最新值）、REST API、多租户与用户（平台管理员 / 租户管理员 / 只读用户、进入租户） |
| `conduit-simulator` | 演示用设备模拟器：自动创建演示产品/设备、导入演示物模型、按间隔上报；演示设备附带枚举运行状态 st，首次连接 MQTT 失败每 2 秒重试、最多 15 次 |
| `conduit-ui` | 前端：Vue 3 + TypeScript + Element Plus + ECharts；统一框架（分组可折叠侧栏、面包屑顶栏、PageHeader）、首页总览、暗色模式、按角色的菜单与写操作、平台首页与租户/用户管理 |
| `conduit-e2e` | 端到端测试（`-Pe2e`）：杀进程不丢数据 |
| `deploy/` | Docker Compose（项目名 `conduit`）、`build-local.sh` |

### 1.2 关键设计决定

- **消息总线**：经 `MessageBus` 接口，当前实现为 Redis Streams（消费组、`XPENDING` 认领、死信流 `conduit:stream:dead`）。将来评估 RabbitMQ（下行）、Kafka（大规模）时只换实现。
- **投递语义**：至少一次。下游写入成功后才 ack；可恢复错误（时序库不可用、数据库连接失败等，判断时沿异常 cause 链查找）原地退避重试 1s→30s，不消耗投递次数；意外错误只把该条消息进死信 `PROCESSING_ERROR`；超过 5 次投递进死信 `MAX_DELIVERY_EXCEEDED`。
- **多租户与用户**（V0.2-C）：所有业务表带 `tenant_id`，MyBatis-Plus 租户拦截器按令牌中的租户自动过滤；`tenant`、`app_user` 不过滤，按租户查用户必须显式带 `tenant_id`；跨租户查询（按 productKey 找产品、设备身份、平台统计 `PlatformStatsMapper`）必须显式标注 `@InterceptorIgnore` 并只在内部或平台接口使用，按租户检查数据用 `TenantContext.callAs`。角色 `PLATFORM_ADMIN`（不属于任何租户，`tenant_id` 为空，数据库约束保证）/ `TENANT_ADMIN` / `TENANT_VIEWER`；用户名 3～32 位字母开头（字母、数字、`_-.`）全平台唯一，密码 8～64 位含字母和数字（BCrypt），租户编码 `^[a-z][a-z0-9-]{1,31}$` 不可改；用户与租户状态 `ACTIVE`/`DISABLED`。启动时没有平台管理员则按 `CONDUIT_PLATFORM_ADMIN_USERNAME`（默认 `sysadmin`）/ `CONDUIT_PLATFORM_ADMIN_PASSWORD` 创建，密码为空只 WARN。默认租户 `default` 不能停用或删除；删除租户需无产品与设备，用户随之删除。租户用户保护：不能删除、禁用自己或改自己的角色，本租户最后一个启用的 `TENANT_ADMIN` 不能被降级、禁用或删除（租户行锁内检查）。停用租户时清除该租户设备的身份缓存 `conduit:auth:*`；设备身份带租户状态，MQTT 鉴权拒绝停用租户的设备，已连接设备的上报进死信 `TENANT_DISABLED`。
- **权限与会话**（V0.2-C）：JWT（access 15 分钟、refresh 7 天 HttpOnly Cookie）声明 `sub` 用户 id、`tid`（平台管理员没有）、`role`（有效角色）、`typ`，代入时加 `imp`（平台管理员 id），需改密码时加 `mcp`。登录拒绝禁用用户与停用租户；续期重读数据库（禁用、停用、角色变化最长 15 分钟生效），代入令牌续期时目标租户停用或删除则退回平台管理员自己的令牌。`SecurityConfig` 按路径与方法统一判断：`/platform/**` 仅平台管理员（代入令牌的有效角色是 `TENANT_ADMIN`，被拒），`/users/**` 租户管理员，其余 `/api/v1/**` GET 为租户管理员与只读用户、写为租户管理员，`/auth/**`、`/me/**` 所有登录用户，`/actuator/**` 除 health 外仅平台管理员；前端只隐藏入口。需改密码时（新用户、首个管理员、被重置密码）除 `GET /auth/me`、`PUT /me/password`、登录/刷新/登出外一律 403 `code=PASSWORD_CHANGE_REQUIRED`，`PUT /me/password` 返回新令牌。进入租户 `POST /platform/tenants/{code}/enter`（停用租户 409）与退出 `POST /auth/exit-tenant` 各记 INFO，代入期间每个写请求由 `ImpersonationLogFilter` 记一行 INFO（不记请求体），代入期间不能改密码。
- **物模型**（V0.2-A）：产品定义属性（DOUBLE/INT/BOOL/TEXT/ENUM），上报按属性逐测点校验；不合格测点被剔除并记录 `FIELD_REJECTED`；没有物模型的产品按 V0.1 规则（数值一律浮点）。物模型缓存 60 秒，编辑后提交事务再失效。
- **解码器**：`PayloadDecoder` SPI，按产品选择（`standard-json`、`flat-json`），名称重复启动即失败。
- **时序库**：InfluxDB 3 Core 3.12.0，自写 HTTP 客户端（`/api/v3/write_lp`、`/api/v3/query_sql`），所有产品共用表 `property`，tag 为 `tenant_id`/`product_key`/`device_key`，每个测点一列，保留 30 天。列类型全表共享：同名测点在所有产品中必须是同一类型（例如模拟器只给物模型含枚举 st 的演示产品发送 st，e2e 等无物模型产品不发）。
- **历史查询**（V0.2-B）：`GET /api/v1/devices/{deviceKey}/history`，单设备 1～8 个测点，跨度 1 分钟～30 天（默认最近 1 小时）。先计数：每个测点 ≤ 1000 点时返回原始点，否则在固定档位（1s、5s、10s、30s、1m、5m、15m、30m、1h、3h、6h、12h）中取使桶数 ≤ 1000 的最小桶，用 `date_bin`（原点 1970）聚合：数值 avg/min/max，布尔/枚举取桶内最后值（`selector_last`）。SQL 参数化，时间参数用 ISO-8601 UTC 字符串；列名只用 `InfluxSchema`（60 秒缓存，未知列强制刷新一次）确认存在的标识符；InfluxDB 不可达或 5xx → 503，其他查询错误 → 500 且不暴露 SQL。前端 ECharts 6.1.0（5.6.0 存在 XSS 公告 GHSA-fgmj-fm8m-jvvx，故升级）经 `echarts/core` 按需引入（不用 vue-echarts），每测点一张联动小图，框选缩放后重新查询。
- **首页总览与计数**（界面刷新）：`GET /api/v1/overview` 按当前租户返回产品数、属性总数、设备数（启用/禁用/在线）、最后上报最近的 5 台设备、各产品设备数（在线/总数，按设备数降序）与最近 1 小时死信（`XREVRANGE` 死信流到 1 小时前、最多 1 万条，只统计 `pk` 属于本租户产品的条目，按 `reason` 分组）。`ProductView` 带 `deviceCount`/`onlineCount`，`UserView` 带显示名称、租户编码与名称、是否需改密码与代入者（`impersonatedBy`）。设备计数统一由 `DeviceStats` 计算（PostgreSQL 分组计数 + Redis 在线状态批量读取）；Redis 不可用时在线数、最近上报与死信为 null（列表为空），其余照常，前端显示「—」。本期读出租户全部设备再统计，设备到万级时改增量计数（见 backlog）。
- **前端框架与外观**（界面刷新）：分组侧栏按身份给出（平台管理员：概览 / 系统管理→租户；租户管理员与代入者：概览 / 设备接入 / 系统管理→用户；只读用户：概览 / 设备接入）、可折叠、宽度 < 820px 自动折叠，侧栏有浅色/深色两种风格（默认深色）；顶栏为全局面包屑（路由 `meta.breadcrumb`，详情页用 `useBreadcrumb` 设置对象名称）、外观设置、租户名称与用户菜单（显示名称、角色、个人设置、退出登录），代入时顶栏下方固定显示代入提示条与「退出」，需改密码时弹出不可关闭的改密码对话框；路由 `meta.scope`（tenant / platform）与 `meta.requires`（manageUsers）由守卫与 AppLayout 统一处理，身份不匹配时改去租户管理或首页；`/home` 对未代入的平台管理员显示平台首页；浏览器标题「<页面名或对象名称> · Conduit」；`/` 重定向到 `/home`，未知地址显示 404。暗色模式用 Element Plus 暗色变量（`<html>` 的 `dark` 类），主题 浅色/深色/跟随系统（默认跟随系统）；外观偏好（主题、侧栏风格、侧栏折叠、最新值视图）存 `localStorage` 键 `conduit.ui`，读写失败用默认值。ECharts 图表随主题销毁重建（内置 `dark` 主题，背景透明）。设备详情为信息卡 + 指标卡片：前 8 个数值测点一次历史查询画最近 1 小时迷你趋势线（60 秒刷新，数值 5 秒刷新），布尔/枚举显示文字标签；点击卡片进入 `?tab=history&props=<标识>`，历史页从 `props` 读取初始选中。
- **在线状态**：Redis hash `conduit:presence:{deviceKey}`（online / since / clientIp / source / lastSeen），由 Lua 脚本 `presence-apply.lua` 原子写入。两个来源：①EMQX 上下线事件（权威，时间为 EMQX 时钟，只接受 `eventTime >= since` 的事件，`since` 是唯一的乱序保护）；②设备上报数据（补偿设备在 transport 订阅之前就已连接、没有连接事件），在数据落库并 ACK 后由流水线每批按设备 pipeline 一次写入（尽力而为，失败只记 WARN）。数据时间是 transport 的接收时间 `receivedAt`，与 EMQX 时钟不同源，所以**数据永远不推进 `since`**：无记录 → 置在线；已在线 → 不改（只刷新 lastSeen）；已离线 → 仅当 `ts > since + 10 秒宽限期`（`PresenceStore.DATA_ONLINE_GRACE_MILLIS`）才复活。记录由数据产生（source=data）时 `since` 不可信，EMQX 事件总是生效。上线事件无 IP 时保留原 IP。
- **设备列表筛选**：`GET /api/v1/devices` 在 productKey / keyword 之外支持 `online`（true|false）、`status`（ENABLED|DISABLED，不区分大小写）、`reported`（RECENT=最近 1 小时内有上报 | STALE=有上报但更久 | NEVER=从未上报）、`createdFrom`（毫秒，含）、`createdTo`（毫秒，不含），范围 0～253402300799999，非法值 400。status / 创建时间 / 产品 / 关键字在 SQL 过滤；`online` 与 `reported` 依赖 Redis，走「SQL 取全部匹配设备 → 批量读 Redis → 内存过滤 → 内存分页」，`total` 为过滤后数量，Redis 不可用返回 503「在线状态暂不可用」，开销 O(设备数)（见 backlog）。`reported` 依据设备时间戳（最新值 `__last`），`online` 依据 receivedAt / 事件时间，两者可能不一致。
- **日志**：所有 Java 服务写文件日志，目录由环境变量 `CONDUIT_LOG_DIR` 指定（默认 `logs`，相对工作目录），每服务一个文件 `<服务名>.log`；按天 + 按大小滚动（单文件 50MB、保留 14 天、总量 1GB，归档 `<名>.%d{yyyy-MM-dd}.%i.log.gz`），控制台输出不变。server / transport 通过 `application.yml` 的 `logging.file.name` 与 `logging.logback.rollingpolicy.*` 配置；simulator 用 logback-classic + 自带 `logback.xml`（不再用 slf4j-simple）；测试 profile 写 `target/test-logs`，e2e 用 `logback-test.xml` 只输出控制台。Docker 中三个服务共用命名卷 `conduit-logs`（`/var/log/conduit`）。
- **部署**：Compose 默认只起基础设施（PostgreSQL、Redis、InfluxDB、EMQX）；conduit-server / transport / web 属于 `app` profile，模拟器属于 `demo` profile，完整部署需 `--profile app --profile demo`，只通过 `deploy/build-local.sh` 按需进行。Docker 宿主机无外网，该脚本在本机从干净检出构建 jar 与前端，宿主机只打复制产物的薄镜像。EMQX 鉴权回调地址由 `deploy/.env` 的 `CONDUIT_AUTH_URL` 配置（默认容器内 `http://conduit-server:8080`，server 在 IDE 中运行时改为 `http://<dev-host>:8080`）。

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
- 界面：中文文案；所有分页表格默认每页 10 条（可选 10/20/50/100），分页、请求序号与越界页码修正统一用 `useTable`；功能入口要显眼（列表有「操作」列按钮、全局面包屑、产品与设备互相可跳转）；详情页的标签页与路由 query `tab` 同步（`router.replace`）。页面顶部用 `PageHeader`，列表筛选用 `FilterBar`，在线状态用 `OnlineDot`，启用/禁用用 `el-tag`，时间用 `RelativeTime`；空状态为 `el-empty` + 引导按钮，首次加载显示骨架屏。样式只用 CSS 变量（`--el-*` 或 `src/styles/tokens.css` 的 `--cd-*`），不写死颜色，深色主题下必须可读。新增页面在 `router/meta.ts` 的 `ROUTE_META` 中登记页面名称、面包屑与菜单归属。写操作按钮（新建、编辑、删除、导入、启用/禁用、重置密钥/密码）按 `usePermission().canWrite` 显示，只读身份隐藏而非禁用；新页面在 `ROUTE_META` 标注 `scope` 与 `requires`。
- 演示与测试数据：用有意义的中文名称（如「三相电表（演示）」「1号楼配电室 电表01」），测试结束清理自己创建的数据。
- 日志：SLF4J 参数化；消息链路上 DEBUG 每条消息/每批一行（transport 收发、流水线解码/校验/写库/ACK、鉴权放行、在线状态、历史与总览、REST 访问行），逐字段细节放 TRACE；构造字符串或集合的日志用 `isDebugEnabled()`/`isTraceEnabled()` 保护；绝不记录设备密钥、口令、JWT、内部 token、Authorization；payload 值只在 TRACE 且截断 256 字符。；管理操作（租户与用户的增删改、重置密码、进入/退出租户、代入期间的写请求）各记一行 INFO，不记请求体与口令。
- 提交：Conventional Commits；只署名不带邮箱；不加任何 AI 署名行。
- 仓库公开：不要把 IP、本机路径、用户名、口令、token 写进受版本控制的文件；本地配置（`deploy/.env`、`application-local.yml`、`simulator-local.properties`）已在 `.gitignore`。

## 3. 本地开发

见 README「开发」：开发模式是 Docker 宿主机只跑基础设施（`docker compose -f deploy/docker-compose.yml up -d`），server、transport、simulator、UI 在 IDEA 中运行（运行配置 `Conduit (local all)`），`deploy/.env` 的 `CONDUIT_AUTH_URL` 指向开发机。本地 transport 必须 `session-expiry-seconds: 0`；本地模拟器默认使用「三相电表（演示）」。本地 server 需在 `application-local.yml` 配 `conduit.bootstrap.platform-admin-password`（或环境变量 `CONDUIT_PLATFORM_ADMIN_PASSWORD`）才会创建平台管理员 `sysadmin`。实施计划不再包含部署步骤，本地日志在仓库根目录 `logs/`（`CONDUIT_LOG_DIR`），排查问题先看这里。验收用测试和对本地运行的整套服务做 API 检查；只有所有者要求时才用 `deploy/build-local.sh` 部署完整应用栈。

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
- 2026-10-15 部署与开发模式：Compose 默认只起基础设施，应用栈走 `app` profile（模拟器 `demo`），EMQX 鉴权回调地址可配置（`CONDUIT_AUTH_URL`）；开发时应用在 IDE 本地运行，实施计划不含部署步骤，验收用测试与 API 检查，部署仅按所有者要求通过 `deploy/build-local.sh`。
- 2026-10-15：设备列表筛选——主行关键字/产品/在线状态/启用状态，「更多筛选」含最后上报与创建时间（本地日界、左闭右开毫秒）；已应用筛选经 router.replace 同步到 query（productKey、keyword、online、status、reported、createdFrom、createdTo），非法值忽略；首页「设备」「在线设备」卡片链接到列表。
- 2026-10-15：设备列表筛选修正——路由 query 在页面内变化时重新应用；「更多筛选」角标与强制展开只看已应用条件；useTable 加载失败清空数据（各列表显示「加载失败」而非旧数据）；非法 query 值用 replace 改写掉。
- 2026-10-15 设备列表筛选与数据驱动在线：列表新增 online / status / reported / createdFrom / createdTo 筛选（online、reported 走内存分页）；上报数据可把设备标记为在线，但数据不推进 `since`、离线后需超过 10 秒宽限期才复活，EMQX 事件仍是权威。
- 2026-10-15：Java 服务日志统一写入 `CONDUIT_LOG_DIR`（默认 `logs`）下的每服务一个滚动文件（50MB/14 天/1GB）；simulator 由 slf4j-simple 换为 logback-classic；Docker 共用卷 `conduit-logs`。
- 2026-10-15：编排可同步到 Docker 宿主机 `/opt/conduit`（`deploy/sync-to-host.sh`，借临时容器 + docker cp 写入），供宿主机面板（1Panel）管理；仓库 `deploy/` 仍是唯一来源。
- 2026-10-15：基础设施加入 InfluxDB 3 Explorer（`influxdata/influxdb3-ui:1.11.0`，端口 `INFLUX_EXPLORER_PORT` 默认 8888，admin 模式，预置连接 compose 内的 InfluxDB 与 `telemetry` 库，数据卷 `influx-explorer-data`）。
- 2026-10-15：沿消息链路补全 DEBUG/TRACE 日志与 REST 访问日志（`RequestLogFilter`），日志约定见 2.2。
- 2026-10-16：新增 V0.2-R 可靠性加固（V0.2-C 之后）：e2e 本地化；transport 多实例 + 会话不持久；EMQX 磁盘持久会话（待验证 5.8.6 支持）；e2e 增加杀 transport / 杀 EMQX 场景。讨论中确定的后续工作须当时写入路线图或 spec。
- 2026-10-16 V0.2-C：多租户与用户管理——角色 `PLATFORM_ADMIN`/`TENANT_ADMIN`/`TENANT_VIEWER`（Flyway V3：平台管理员 `tenant_id` 为空、旧 `ADMIN` 迁为 `TENANT_ADMIN`、删除租户级联删除用户）；JWT 可空 `tid`、代入 `imp`、需改密码 `mcp`；`SecurityConfig` 按路径与方法的权限矩阵；强制改密码 403 `PASSWORD_CHANGE_REQUIRED`；平台接口 `/api/v1/platform/**`（概况、租户、进入租户）与用户管理 `/api/v1/users`；代入写请求 INFO 留痕；停用租户拒绝登录、续期与 MQTT 鉴权并以 `TENANT_DISABLED` 死信；前端 `usePermission` 按角色隐藏写操作、菜单与路由按身份（`2026-10-16-conduit-v0.2c-tenants-users-design.md`）。
- 2026-10-16：V0.2-R 并入「模拟器多产品」：按各产品物模型生成数据，演示租户的设备也有数据。
- 2026-10-16 V0.2-R 方向调整：EMQX 5.8.6 磁盘持久会话与共享订阅互斥（实测），改为「磁盘持久会话 + transport 单活多备（Redis 锁选主、同 clientId 接管）」；多实例共享订阅放弃，升级 EMQX 留待 V1.0。
