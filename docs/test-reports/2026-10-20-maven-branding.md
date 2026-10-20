# Conduit IoT Maven 命名验证报告

- 日期：2026-10-20
- 被测状态：基于 `0b0a525` 的工作区命名变更，覆盖根 POM 和全部五个子模块。
- 范围：根 artifactId 改为 `conduit-iot-parent`，同步父引用；补齐产品展示名、简介、仓库 URL 与 SCM 信息。
- 环境：Windows、PowerShell、Maven 3.9.9；实际 Maven 运行时为 Eclipse Adoptium Java 17.0.9，项目编译要求仍为 Java 21。

## 实际命令与结果

```powershell
mvn -B -o -Pe2e validate -l target/pom-branding-validate.log
mvn -version
java -version
git diff --check
```

- Maven validate：通过。根父工程与 common、transport、server、simulator、e2e 六个 reactor 项目均为 SUCCESS，日志为 `target/pom-branding-validate.log`。Maven 总耗时 0.547 秒，命令调用墙钟耗时约 2.9 秒。
- POM 静态校验：通过，6/6。使用 PowerShell `[xml]` 解析六个 POM，检查根 `artifactId`、五个子模块 `parent.artifactId` 和各工程展示名均符合约定；未新增测试代码。
- 差异空白检查：通过，`git diff --check` 无输出。
- 实际执行测试用例数：0；validate 验证工程模型与父子引用，不运行测试。

静态检查的等价复现命令：

```powershell
$pomFiles = @('pom.xml','conduit-common/pom.xml','conduit-transport/pom.xml','conduit-server/pom.xml','conduit-simulator/pom.xml','conduit-e2e/pom.xml')
foreach ($pomFile in $pomFiles) {
    $pom = [xml](Get-Content -Raw -Encoding UTF8 -LiteralPath $pomFile)
    $project = $pom.project
    if ($pomFile -eq 'pom.xml') {
        if ($project.artifactId -ne 'conduit-iot-parent') { throw 'Invalid root artifactId' }
    } elseif ($project.parent.artifactId -ne 'conduit-iot-parent') {
        throw "Invalid parent: $pomFile"
    }
    if ($project.name -notlike 'Conduit IoT*') { throw "Invalid display name: $pomFile" }
    "PASS $pomFile"
}
```

## 警告、未执行与边界

- Maven 启动时控制台出现 `Access is denied.`，但命令退出码为 0，实际生成的本次日志明确包含六个 SUCCESS 和 BUILD SUCCESS；`mvn -version` 同样先输出该警告，再正常显示版本。警告来源未定位，不能将控制台单行作为构建结果。
- 未执行编译、打包、单元/集成/端到端测试。本次只改 Maven 坐标与展示元数据；Java 17 下 validate 成功不代表项目可使用 Java 17 编译，编译仍须使用 Java 21。
- 无失败或阻塞的已执行校验。历史 spec / plan 中保留旧父工程命名用于历史记录；当前 POM 为构建依据。
- 父工程坐标变化后，依赖旧 `dev.conduit:conduit-parent` 的外部工程须同步迁移。本仓库五个子模块已同步，子模块 artifactId、Java 包名、版本号和模块依赖关系均不变，因此架构 SVG 无需修改。
- 工作区已有未跟踪的遥测重放实施计划与本次无关，未修改或纳入本次提交。
