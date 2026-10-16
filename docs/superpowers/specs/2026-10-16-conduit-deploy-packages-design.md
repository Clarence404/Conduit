# Conduit 部署包整理设计：基础服务包与全量包

- 日期：2026-10-16
- 状态：已实现（2026-10-16），验收见第 9 节
- 范围：重组 `deploy/`，提供两个可直接在 Linux 上用 docker compose 启动的目录：基础服务包（开发用）与全量包（基础服务 + 应用）；删除不再使用的构建与同步方式。
- 前置：V0.2-R 已完成（EMQX 磁盘持久会话、transport 单活多备、两个 transport 服务）。

## 1. 背景

现有 `deploy/` 一个 compose 文件承担三种用途（基础设施、`app`、`demo` profile），镜像有两套构建方式：compose 的 `build:` 指向在镜像内联网执行 mvn / npm 的多阶段 Dockerfile（宿主机无外网，从未跑通），实际部署用 `build-local.sh` 临时生成的薄 Dockerfile。EMQX 配置因 Docker 在远程宿主机而烘焙进自定义镜像，编排再经 `sync-to-host.sh` 复制到宿主机 `/opt/conduit`。改为所有者把目录直接放到 Linux 上运行，挂载本地配置即可。

## 2. 目标

- 基础服务包：复制或 clone `deploy/infra` 到 Linux，`cp .env.example .env`、修改口令与 `CONDUIT_AUTH_URL`，`docker compose up -d` 即可；应用在 IDE 中运行。
- 全量包：基础服务 + server、两个 transport、web、模拟器，同样一条 `docker compose up -d --build`。
- 全量包的应用产物两种来源都支持：开发机打好的产物（Linux 无外网）或在 Linux 上从源码编译（能联网）。

## 3. 目录

```
deploy/
├── infra/
│   ├── docker-compose.yml       # postgres、redis、influxdb、influxdb-explorer、emqx；项目名 conduit
│   ├── .env.example
│   ├── emqx/base.hocon.tpl      # 挂载进容器，启动时渲染回调地址与内部 token
│   ├── emqx/acl.conf            # 挂载为 /opt/emqx/etc/conduit-acl.conf
│   └── README.md
├── full/
│   ├── docker-compose.yml       # extends ../infra/docker-compose.yml 的 5 个服务，再加应用服务
│   ├── .env.example             # 全量所需全部变量（单一 .env）
│   ├── docker/java.Dockerfile   # server、transport、simulator 共用
│   ├── docker/web.Dockerfile
│   ├── artifacts/               # 预编译产物（不入库，.gitignore 只保留 .gitkeep）
│   └── README.md
├── package.sh                   # 开发机：编译产物并打包 conduit-full-<短提交>.tar.gz
└── .dockerignore 规则           # 构建上下文为仓库根，见第 5 节
```

## 4. 基础服务包

- 服务与镜像版本、端口、健康检查、数据卷名、EMQX 磁盘持久会话三项环境变量（`EMQX_DURABLE_SESSIONS__ENABLE=true`、`EMQX_CLUSTER__DISCOVERY_STRATEGY=singleton`、`EMQX_NODE__NAME=emqx@127.0.0.1`）沿用 V0.2-R。
- EMQX 用官方镜像 `emqx/emqx:5.8.6`，`./emqx/base.hocon.tpl` 与 `./emqx/acl.conf` 只读挂载；启动命令照旧用 `sed` 渲染 `__CONDUIT_AUTH_URL__`、`__CONDUIT_INTERNAL_TOKEN__` 到 `/opt/emqx/etc/base.hocon`。
- 去掉 EMQX 对 conduit-server 的 `depends_on`（EMQX 对鉴权回调自行重试）。
- `CONDUIT_AUTH_URL` 在基础服务包中必填（开发机地址 `http://<dev-host>:8080`）；全量包中固定为 `http://conduit-server:8080`。
- `.env.example` 只含基础服务所需变量：口令（PostgreSQL、InfluxDB token、Explorer 会话密钥、EMQX 控制台、内部 token）、`CONDUIT_AUTH_URL`、`BIND_ADDR` 与端口。
- 项目名 `conduit`，数据卷 `conduit_pg-data` 等与现有一致，迁移后数据沿用。

## 5. 全量包

- `full/docker-compose.yml` 项目名同为 `conduit`；基础服务用 `extends: { file: ../infra/docker-compose.yml, service: <名> }` 引用（相对路径按 infra 文件解析，挂载的 EMQX 配置不复制），仅 EMQX 覆盖 `CONDUIT_AUTH_URL`；数据卷在本文件重新声明（同名）。
- 应用服务：conduit-server、conduit-transport 与 conduit-transport-2（YAML 锚点复用，`stop_grace_period: 20s`，后者日志在 `transport-2` 子目录）、conduit-web、simulator，环境变量与 V0.2-R 相同；模拟器默认启动（不再用 profile），`SIM_PLATFORM_PASSWORD` 留空时只模拟演示产品。
- 构建上下文为仓库根目录（`../..`）。Dockerfile 两阶段选择：
  - 全局 `ARG BUILD_FROM=prebuilt`；阶段 `prebuilt` 从 `deploy/full/artifacts/` 复制产物，阶段 `source` 用 `maven:3.9.9-eclipse-temurin-21`（web 用 `node:22.14.0-alpine`）从源码编译；`FROM ${BUILD_FROM} AS app` 后最终阶段 `COPY --from=app`。BuildKit 只构建选中的阶段，所以 tar 包不含源码也能构建。
  - Java 镜像 `eclipse-temurin:21.0.6_7-jre`，参数 `MODULE`、`JAR_NAME`；web 镜像 `nginx:1.27.4-alpine`，`nginx.conf` 取自 `conduit-ui/nginx.conf`。
  - `.env` 中 `BUILD_FROM=prebuilt|source`（默认 prebuilt）。
- 仓库根 `.dockerignore`：排除 `**/node_modules`、`**/target`（但放行 `deploy/full/artifacts/**`）、`.git`、`logs`、`.claude`、`.superpowers`、`.idea` 与本地配置文件，保证上下文小且不带密钥。
- `package.sh`（开发机，Git Bash / Linux）：
  1. 从当前提交干净检出（git worktree，结束清理）；
  2. `mvn -B -q -DskipTests package`，`npm ci` + `npm run build`；
  3. 复制 `conduit-server.jar`、`conduit-transport.jar`、`conduit-simulator-exec.jar` 与 `conduit-ui/dist` 到 `deploy/full/artifacts/`；
  4. 打包 `deploy/`（含 artifacts，不含 `.env`）与根 `.dockerignore`、`conduit-ui/nginx.conf` 为 `target/conduit-full-<短提交>.tar.gz`。
- 运行：解压 → `cd deploy/full` → `cp .env.example .env` 并修改 → `docker compose up -d --build`。从源码：clone → 同上，`.env` 设 `BUILD_FROM=source`。

## 6. 删除

根 `Dockerfile`、`conduit-ui/Dockerfile`、`conduit-ui/.dockerignore`、`deploy/emqx/`（Dockerfile 与配置移入 `deploy/infra/emqx/`）、`deploy/docker-compose.yml`、`deploy/.env.example`、`deploy/build-local.sh`、`deploy/sync-to-host.sh`。根 `.dockerignore` 按第 5 节重写。

## 7. 其他改动

- e2e：`EmqxConfig` 读取的模板路径改为 `deploy/infra/emqx/base.hocon.tpl`，ACL 路径同步。
- 本地忽略文件：`deploy/infra/.env`、`deploy/full/.env`、`deploy/full/artifacts/*` 加入 `.gitignore`。
- 文档：README（开发、部署两节重写）、CLAUDE.md（1.1 deploy 行、1.2 部署与日志、3 本地开发、4 决定记录）、backlog 中关于 `build-local.sh` / 同步 / transport-2 重复构建的条目更新。

## 8. 验收

1. 在 Docker 宿主机上用临时项目名与临时目录从 tar 包启动全量包（`BUILD_FROM=prebuilt`），全部服务健康，模拟器数据入库，然后删除该临时项目与数据卷，不影响开发环境。
2. 同样方式以 `BUILD_FROM=source` 构建一次镜像成功（宿主机已有 maven 与 node 镜像；若依赖下载需外网而失败，记录为已知限制）。
3. 基础服务包用临时项目名在宿主机启动，5 个服务健康、EMQX 持久会话配置生效，然后删除。
4. `mvn -B verify` 与 e2e 四个场景通过。
5. 所有者把 `deploy/infra` 放到宿主机并以项目名 `conduit` 重新启动开发基础设施，数据保留，本地 `Conduit (local all)` 正常。
