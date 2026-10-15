# V0.2 待办（V0.1 评审遗留的次要事项）

## 后端：模拟器

- 模拟器重启后 EPimp 重置；provisioning 会重置其他运行中模拟器的密钥
- `sim-%03d` 设备名可能冲突
- `loginWithRetry` 对 401 也重试
- `ConduitApiClient.resolve()` 丢失 context path；响应缺字段时 NPE
- 配置未校验；无 shutdown hook
- slf4j-simple 为 compile scope（e2e 需排除，避免与 logback 冲突）
- `DemoProvisionerTest` 忽略 query string
- 四参构造的 10 分钟 completionTimeout 对长时间有限运行偏短；连接失败的线程断言形同虚设
- 退出处理里 catch Throwable 过宽

## 后端：部署与镜像

- 后端 Dockerfile 无依赖缓存层，且以 root 运行
- entrypoint 中 sed 注入内部 token、HOCON 引号处理较脆弱
- EMQX 控制台密码仅首次启动生效（卷非空时忽略）
- 重启后的启动顺序
- API 端口同时暴露 `/internal/*` 与 actuator
- EMQX 健康检查较浅

## 后端：接入与处理（P1/P2）

- `TopicParser` 对 `$SYS` 节点为 null/空的处理
- `RawMessage` 的 `byte[]` 未做拷贝
- `claimStale` 时序测试余量偏小
- MQTT 适配器重新订阅路径缺少测试
- Spring 装配缺少冒烟测试
- 引导（bootstrap）并发启动存在竞态
- 身份缓存对字面量 `null` 的处理
- LIKE 通配符未转义
- 单批写入超过 InfluxDB 请求体上限（约 10 MiB）时返回 413，当前被当作"暂时不可用"原地无限重试，会卡住整条流水线（逐点回退的 O(N) 问题已随 V0.2-A 的部分写入解决）
- 多实例下的 claim 限制
- 禁用设备不会被 EMQX 踢下线
- 缺少 MAX-delivery 边界测试
- 每次重启会泄漏 consumer

## 后端：认证与安全

- 登录存在时序差异，可枚举用户名
- access token 在 15 分钟内不会被重新校验
- 无暴力破解防护
- refresh token 吊销（V1.0）
- Cookie 的 Secure 默认值（V1.0，随 TLS 一起）

## 前端

- client、router、store 之间存在循环 import（目前为懒加载，安全）
- `/auth/` 前缀判断脆弱
- 路由守卫的 `restore()` 绕过 single-flight
- Element Plus 全量引入，主 chunk 约 987kB
- 缺少 store / 路由守卫 / 组件测试
- `safeRedirect` 未拒绝控制字符与编码斜杠
- `load()` 无请求序号，存在竞态；翻页可能重复加载
- `formatDateTime` 对数字字符串的处理
- 对话框关闭时应清除密钥（`@closed` + `destroy-on-close`）
- `DeviceDetailView` 在 `deviceKey` 参数变化时应重新加载
- 轮询遇到 404 时静默
- 创建对话框在创建中可被 Esc / 关闭按钮打断
- `usePolling` 重叠测试偏弱
- 忙碌中的按钮无 loading 状态

## 测试

- e2e 未覆盖 60s claim 路径（由 PipelineIT 覆盖）
- e2e 的 DEPLOY 路径依赖当前工作目录
- Influx 同 ts 覆盖，重复写入无法检出

## V0.2-A 遗留（评审后延后）

- 无物模型时，设备上报名为 `time` / `tenant_id` / `product_key` / `device_key` 的测点会以误导性的 `FIELD_TYPE_CONFLICT` 进死信（应在解码时直接拒绝该名称）
- 测点名含制表符时整行被 InfluxDB 拒绝，死信原因不够明确
- 解码错误信息会带出报文中的个别 token / 测点名（仅 debug 日志）；可改为固定文案加行列号
- `DecoderRegistry` 遇到空名称时抛 NPE，启动失败信息不清晰
- INT 范围校验经 double 比较，超过 2^53 时有精度损失；TEXT 长度按 UTF-16 单元计算
- 并发删除属性时修改同一属性返回 500 而非 404
- `decimals` 传 `1.5` 被 Jackson 截断为 1 而非报错
- 导入 `mode` 非法时先于租户校验返回 400
- 物模型拒绝后又被 InfluxDB 拒绝（`FIELD_TYPE_CONFLICT`）时，死信不带 `rejected` 明细
- `MessageBus.deadLetter` 已不再被流水线使用，注释需更新
- 演示物模型导入失败会让模拟器退出（依赖 compose 重启）
- 产品详情：增删改属性后会重新加载产品，覆盖「基本信息」页未保存的编辑；`productKey` 变化时不重新加载；导出下载同步 `revokeObjectURL`
- `Dockerfile` / `.dockerignore` 头部注释未提及放行的 `docs/examples` 文件
- 测试缺口：200 属性上限的并发测试、枚举标签的负例、null 值、缓存容量上限、模型标识与模拟器测点的一致性

## V0.2-B 遗留（评审后延后）

- 历史原始模式的查询不过滤"所选测点全为空"的行：很少上报的测点在 30 天范围内可能读出大量空行（应加 `"col" IS NOT NULL OR …` 条件）
- `date_bin` 按 1970 原点对齐，按跨度选桶时首尾可能多出一个桶（最多 1001 点）
- InfluxDB 的查询/规划错误同样返回 5xx，目前被映射为 503「时序库暂不可用」，而不是 500
- 物模型类型与列实际类型不一致时（如旧浮点列被定义为布尔）的展示与聚合
- 历史接口中 `IllegalStateException` / `IllegalArgumentException` 走 Spring 默认错误页而非 ProblemDetail
- 参数错误信息直接回显原始测点名（未截断）；标识符正则与保留名在 `telemetry` 与 `thingmodel` 各有一份
- 演示物模型 merge 导入会重新补回用户主动删除的演示属性
- 多实例部署时，新导入的物模型最多 60 秒后在其他实例生效，期间新测点会被 `FIELD_REJECTED`（本地 IDEA 与演示环境同时消费时可见）
- 图表：枚举值不连续或只有一个枚举项时纵轴刻度不理想；30 秒刷新会打断正在进行的框选
- 测试缺口：全新库上的 `InfluxSchema`、面板级页面隐藏/恢复、404 断言仅校验状态码

## 前端体验优化遗留（2026-10-15）

- 首页总览与产品列表的在线数、最近上报按"读出租户全部设备 + Redis 批量读取"统计；设备到 1 万台规模时改为增量计数（例如按产品维护在线计数或在线集合，在上下线事件中更新）。
- 最近 1 小时死信按死信流倒序扫描、最多 1 万条（全平台共用一条死信流），超过时计数封顶；需要精确统计时改为按租户/产品分流或写入计数器。
- 死信的 `pk` 取自设备主题，设备可以伪造其他租户的 productKey 来抬高对方的「最近 1 小时死信」；应由服务端按设备真实所属租户归属死信。
- 应用时钟与 Redis 流时间（死信流 ID）可能存在偏差，影响「最近 1 小时」的截止点。
- 迷你趋势线与历史图表只在窗口 resize 时重绘；容器尺寸变化（侧栏折叠等）应改用 ResizeObserver。
- 最新值 5 秒轮询在页面隐藏时不暂停。
- `?props` 在选择变化后不再同步，会变旧。
- 首页列表行是 role=button 而非真正的链接；首次加载失败时骨架屏不会消失。
- 产品列表没有关键字筛选（`GET /api/v1/products` 无 keyword 参数），产品变多时再加。
- 缺少产品计数（deviceCount/onlineCount）的跨租户测试。
- `index.html` 内联的主题预渲染脚本：以后加严格 CSP（`script-src`）时需要给它配 nonce 或 hash。
- `LatestValueStore` 解析最后上报时间用的 `Long.parseLong` 未做容错，某台设备的值损坏会让 `/overview` 返回 500。
- 缺少测试：首页轮询在页面隐藏时暂停、`/` 重定向到 `/home`；设备列表"保留已应用筛选"的翻页测试偏弱（没有断言请求了第 2 页）。
- `DeviceStats.Snapshot.onlineKeys` 是可变集合，应改为不可变。
- 产品详情页在路由参数 `productKey` 变化时不重新加载（同组件复用时显示旧产品）。

## 设备筛选与在线状态遗留（2026-10-16）

- 在线状态 TTL 兜底：EMQX 断连事件丢失时设备会一直显示在线；应加 lastSeen 超过 N 分钟则视为离线的兜底（lastSeen 已由数据路径写入，事件路径不写）。
- 设备列表 `online` / `reported` 筛选目前读出全部匹配设备的 Redis 状态再内存分页（O(设备数)）；设备到 1 万台规模时改为维护在线集合（Redis Set / ZSet）后直接分页，与首页统计的增量计数一并做。
- 语义差异：`reported` 依据设备时间戳（最新值 `__last`），`online`（数据驱动部分）依据 transport 的 receivedAt；设备时钟偏差大时两者可能不一致，界面上需留意。
- 数据驱动在线只在设备有上报时生效；仍在连接但长期不上报的设备在漏掉连接事件时依旧显示离线。
