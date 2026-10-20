# README SVG 文档验证报告

- 日期：2026-10-20
- 范围：README 架构图与 Java 模块依赖图替换为 SVG；未修改应用代码。
- 代码状态：当前工作区未提交版本，包含既有 SaaS Roadmap 文档修改；仅验证本次 README 与新增 SVG。
- 环境：Windows、PowerShell、Microsoft Edge 无界面渲染，缩放系数 1。

## 结果

| 检查 | 结果 | 证据 |
|---|---|---|
| 两份 SVG 的 XML 解析 | 通过，2/2 | PowerShell `[xml]` 解析未报错 |
| 框内文字基线距底边 | 通过，2/2 | 遍历 `.box` 中的文字坐标，最小距离不低于 12px |
| 架构图亮色渲染 | 通过，1/1 | `target/architecture-light.png`，已人工查看 |
| 架构图暗色渲染 | 通过，1/1 | `target/architecture-dark.png`，已人工查看 |
| 模块图亮色渲染 | 通过，1/1 | `target/module-dependencies-light.png`，已人工查看 |
| README 引用替换 | 通过 | 两份 SVG 引用存在，无剩余 Mermaid 代码块 |
| 差异空白检查 | 通过 | `git diff --check` 无输出 |

视觉检查：已查看上述 3 张图片，未发现文字裁切、文字压线、箭头进入目标框或模块重叠；架构图暗色文字与连线可读。两份 SVG 均包含 CSS 主题变量、显式暗色选择器、系统暗色媒体查询、标题和可访问性描述。图中 server 外框包含其入口与流水线，属于有意的父子包含关系；存储连线表示进程级访问关系。

## 执行与复现

实际执行了 PowerShell XML 解析与框内文字基线坐标检查、`git diff --check`，以及以下 README 搜索：

```powershell
rg -n 'mermaid|docs/images/(architecture|module-dependencies)' README.md
```

实际通过 Edge 的 `--headless --disable-gpu --no-first-run --hide-scrollbars --force-device-scale-factor=1` 参数分别渲染 3 次；暗色额外传入 `--force-dark-mode`。实际参数与下列复现命令等价，仅将机器绝对路径改写为仓库相对路径。Edge 可执行文件需由执行环境提供：

```powershell
# 在仓库根目录执行；$edgePath 指向本机的 msedge.exe。
$repoUri = ([System.Uri]((Get-Location).Path + '/')).AbsoluteUri
& $edgePath --headless --disable-gpu --no-first-run --hide-scrollbars --force-device-scale-factor=1 --window-size=1100,740 "--user-data-dir=$PWD/target/svg-preview-profile" "--screenshot=$PWD/target/architecture-light.png" "${repoUri}docs/images/architecture.svg"
& $edgePath --headless --disable-gpu --no-first-run --hide-scrollbars --force-device-scale-factor=1 --force-dark-mode --window-size=1100,740 "--user-data-dir=$PWD/target/svg-preview-dark-profile" "--screenshot=$PWD/target/architecture-dark.png" "${repoUri}docs/images/architecture.svg"
& $edgePath --headless --disable-gpu --no-first-run --hide-scrollbars --force-device-scale-factor=1 --window-size=950,360 "--user-data-dir=$PWD/target/svg-preview-modules-profile" "--screenshot=$PWD/target/module-dependencies-light.png" "${repoUri}docs/images/module-dependencies.svg"
```

三次渲染命令均正常返回；随后检查截图文件存在并逐张查看。渲染调用总耗时约 5 秒（含启动与调度，不是应用性能指标）。XML 与差异检查约 1 秒。截图与浏览器临时配置在已忽略的 `target/` 下，未纳入版本控制。

## 未执行与限制

- 未运行后端、前端或端到端功能测试：本次仅文档与 SVG 变更。
- 未单独渲染模块图暗色版本；其使用与已验证架构图相同的主题变量与样式。
- 未验证 GitHub 或第三方 Markdown 阅读器的实际渲染；SVG 自适应主题取决于阅读器传递的配色偏好。
- 无失败、跳过或阻塞的已执行检查；以上未执行项目不得视为通过。
