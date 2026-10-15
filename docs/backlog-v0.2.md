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
- e2e 测试数据未清理
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
