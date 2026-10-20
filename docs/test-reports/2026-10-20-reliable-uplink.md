# V0.2-R2 可靠上报测试报告

- 测试时间：2026-10-20 11:51～12:01（Asia/Shanghai）
- 测试结论：**通过**
- 被测分支：`main`
- 基线提交：`057b219`
- 被测代码状态：基线提交之上的未提交 V0.2-R2 工作区改动
- 测试范围：可靠上报核心逻辑、全量后端单元与集成测试、五个可靠性故障场景、前端单元测试、类型检查及生产构建

## 1. 测试环境

| 项目 | 版本/说明 |
|---|---|
| 开发机 | Windows 11 amd64 |
| Java | OpenJDK 21.0.12.1 |
| Maven | 3.9.9 |
| Node.js / npm | 20.19.2 / 10.8.2 |
| Docker 宿主机 | 私有测试 VM，Ubuntu 24.04.2 LTS，Docker 28.0.2，约 7.9 GiB 内存 |
| Testcontainers | 1.21.3；测试临时容器由 Ryuk 清理 |
| 关键镜像 | PostgreSQL 16.8、Redis 7.4.2、InfluxDB 3.12.0-core、EMQX 5.10.5 |

VM 地址与凭证按仓库安全约定不写入版本控制。

## 2. 执行结果

### 2.1 可靠上报核心测试

执行命令：

```powershell
mvn -B -pl conduit-transport,conduit-simulator -am test `
  '-Dtest=ReliableUplinkTest,ApplicationAckTest,PendingReportQueueTest,MqttProtocolAdapterTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false'
```

结果：`BUILD SUCCESS`，耗时 5.947 秒。

| 模块 | 用例数 | 失败 | 错误 | 跳过 |
|---|---:|---:|---:|---:|
| conduit-transport | 21 | 0 | 0 | 0 |
| conduit-simulator | 4 | 0 | 0 | 0 |
| 合计 | 25 | 0 | 0 | 0 |

覆盖了可靠消息元数据解析、平台业务回执发送、回执失败降级、待确认队列、确认出队和容量边界。

### 2.2 全量后端单元与集成测试

执行命令：

```powershell
$env:DOCKER_HOST = 'tcp://<docker-host>:2375'
$env:TESTCONTAINERS_HOST_OVERRIDE = '<docker-host>'
mvn -B verify
```

结果：`BUILD SUCCESS`，耗时 2 分 53 秒。

| 模块 | 单元测试 | 集成测试 | 合计 | 失败/错误/跳过 |
|---|---:|---:|---:|---:|
| conduit-common | 9 | 11 | 20 | 0 / 0 / 0 |
| conduit-transport | 48 | 16 | 64 | 0 / 0 / 0 |
| conduit-server | 209 | 228 | 437 | 0 / 0 / 0 |
| conduit-simulator | 82 | 4 | 86 | 0 / 0 / 0 |
| 合计 | 348 | 259 | 607 | 0 / 0 / 0 |

### 2.3 可靠性端到端测试

准备和执行命令：

```powershell
mvn -B -DskipTests install
$env:DOCKER_HOST = 'tcp://<docker-host>:2375'
$env:TESTCONTAINERS_HOST_OVERRIDE = '<docker-host>'
$env:E2E_DEV_HOST = '<dev-host>'
mvn -B verify -Pe2e -pl conduit-e2e
```

准备构建 `BUILD SUCCESS`，耗时 3.517 秒。e2e `BUILD SUCCESS`，耗时 3 分 25 秒；`EmqxConfigTest` 2 个单元用例及 `ReliabilityE2EIT` 5 个场景全部通过。

| 场景 | 故障注入 | 结果 |
|---|---|---|
| 1 | 第 100 条确认后强杀 server，3 秒后重启 | 500/500，恰好入库，无死信、无待确认 |
| 2 | 第 100 条确认后强杀主 transport | 备用实例接管，500/500，恰好入库 |
| 3 | 第 100 条确认后同时强杀两个 transport，约 20 秒后重启 | 500/500，恰好入库 |
| 4 | 第 100 条确认后重启 EMQX 容器 | 500/500，恰好入库 |
| 5 | 第 100 条平台业务回执后以 SIGKILL 强杀 EMQX，再原地启动 | 可靠设备重发未确认消息，最终 500/500，恰好入库 |

本次 e2e 运行日志：`conduit-e2e/target/e2e-logs/20261020-115804/`。该目录是本机构建产物，不纳入 Git。

### 2.4 前端测试与构建

执行命令：

```powershell
cd conduit-ui
npm test
npm run build
```

结果：

- Vitest：63 个测试文件、406 个用例全部通过，耗时 32.25 秒。
- `vue-tsc --noEmit`：通过。
- Vite 生产构建：通过，转换 2234 个模块，耗时 9.61 秒。

## 3. 汇总结论

- 后端全量：607 个用例通过。
- 独立 e2e：7 个用例通过，其中 5 个故障场景均满足精确计数和队列清空断言。
- 前端：406 个用例通过，类型检查与生产构建通过。
- 去除核心测试的重复计数后，本轮完整测试共验证 1020 个用例，0 失败、0 错误、0 跳过。
- V0.2-R2 验收标准中“强杀 EMQX 后可靠上报不丢数据”已在真实远程 Docker 环境通过。

## 4. 无效尝试与非阻塞警告

1. 第一次核心测试命令中一个 Maven `-D` 参数未加引号，被 PowerShell 解析为生命周期阶段；构建在测试开始前失败。修正参数后重新运行并通过。
2. 受限进程中 Mockito/Byte Buddy 无法连接 fork 出的测试 JVM，Windows pipe 返回 `Access is denied`，导致测试挂起；终止该无效运行后在允许 JVM attach 的测试进程中重跑并通过。
3. Mockito 提示未来 JDK 将默认禁止动态加载 agent；当前不影响结果，后续可按 Mockito 文档显式配置 Java agent。
4. `HistoryPanel.spec.ts` 的测试 mock 出现 3 条 Vue `change` 事件未声明警告；用例仍全部通过，属于测试桩质量问题。
5. Vite 提示部分生产 chunk 超过 500 kB；不影响构建，但后续可评估动态导入或 `manualChunks`。

## 5. 未执行项

无。与本次 V0.2-R2 交付相关的后端、e2e、前端测试及构建均已执行。
