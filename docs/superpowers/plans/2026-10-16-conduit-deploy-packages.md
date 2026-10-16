# Conduit Deploy Packages Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split `deploy/` into two packages. The first is an infrastructure package for development, `deploy/infra`. The second is a full package, `deploy/full`: the infrastructure plus the server, two transports, the web UI and the simulator. Each package starts on Linux with one `docker compose up -d [--build]`. A dev-machine script `deploy/package.sh` builds a tarball of prebuilt artifacts for the offline Docker host. The old build and sync paths are deleted.

**Architecture:** `deploy/infra/docker-compose.yml` holds the five infrastructure services. EMQX uses the official `emqx/emqx:5.8.6` image with its config template and ACL bind-mounted read-only. `deploy/full/docker-compose.yml` pulls those five services in with `extends` and adds the app services. Their images build from the repo root as context, through two Dockerfiles: `deploy/full/docker/java.Dockerfile` and `deploy/full/docker/web.Dockerfile`. Each Dockerfile picks its artifact stage with a global `ARG BUILD_FROM=prebuilt`. The `prebuilt` stage copies from `deploy/full/artifacts/`, and the `source` stage compiles from source. `FROM ${BUILD_FROM} AS app` then selects one, and the final stage runs `COPY --from=app`. BuildKit builds only the selected stage, so a tarball without sources still builds.

**Tech Stack:** Docker 28.0.2, Docker Compose v2.35.1 (on the Docker host), BuildKit, Bash (Git Bash on the dev machine, POSIX sh in containers), GNU tar, Maven 3.9.9 / JDK 21, Node.js 22 / npm, JUnit 5 + AssertJ (e2e config test).

**Governing spec:** `docs/superpowers/specs/2026-10-16-conduit-deploy-packages-design.md` (binding). Repo-root `CLAUDE.md` is also binding: conventions, comment rules, the maintenance rule, and no secrets in the public repo.

## Global Constraints

**Operator-provided placeholders.** The controller sets these in its shell. Never write their real values into any committed file.

| Placeholder | Meaning |
|---|---|
| `$JAVA21_HOME` | Path of a JDK 21 installation |
| `<docker-host>` | Address of the remote Docker host (Testcontainers, the dev infrastructure, the package acceptance) |
| `<dev-host>` | Address at which the Docker host reaches this development machine (`E2E_DEV_HOST`, and the host part of `CONDUIT_AUTH_URL` in dev mode) |
| `$DOCKER_CLI` | The docker CLI command, with `DOCKER_HOST` pointing at the Docker host |
| `$SCRATCH` | A temporary directory outside the repository (Task 4 only: test `.env` files and archives) |

**Shell preamble.** Run it once per shell before any Maven, npm or Docker command in this plan. All commands run from the repository root unless a step says otherwise.

```bash
export JAVA_HOME="$JAVA21_HOME" PATH="$JAVA21_HOME/bin:$PATH"
export DOCKER_HOST="tcp://<docker-host>:2375" TESTCONTAINERS_HOST_OVERRIDE="<docker-host>"
```

**Command patterns** (used verbatim by the tasks):

- e2e config unit test: `mvn -B -q -Pe2e -pl conduit-e2e -am test -Dtest=EmqxConfigTest -Dsurefire.failIfNoSpecifiedTests=false`
- Full backend build (Task 4 only): `mvn -B verify`
- e2e run (Task 4 only): `mvn -B -DskipTests install`, then `E2E_DEV_HOST=<dev-host> mvn -B verify -Pe2e -pl conduit-e2e`
- Compose validation (Task 4 only, controller): `docker compose config` through the host compose binary `/opt/conduit/bin/docker-compose`, run in a throwaway container (Task 4 Step 2 defines the `hc` helper).

**Execution model:**

- Four tasks on `main`, run in order: 1 → 2 → 3 → 4. Tasks 1–3 are implementer tasks. Task 4 is run by the controller: it needs the Docker host, and its last step needs the owner.
- Implementers use a cheaper model (Sonnet). Every file in this plan is given in full, or as an exact before/after edit. Copy it; do not redesign it. If a step's check fails for a reason the step does not explain, stop and report. Do not improvise.
- Tasks 1–3 do not touch the Docker host. Compose files are validated on the host in Task 4.
- The working tree has an unrelated local modification: `.run/Conduit (local all).run.xml`. Never stage it. Always `git add` the exact paths listed in the step, never `git add -A` or `git add .`.

**Spec values (copied verbatim):**

- Images (unchanged from V0.2-R): `postgres:16.8`, `redis:7.4.2`, `influxdb:3.12.0-core`, `influxdata/influxdb3-ui:1.11.0`, `emqx/emqx:5.8.6`. App runtime images: `eclipse-temurin:21.0.6_7-jre` (Java) and `nginx:1.27.4-alpine` (web). Source-build images: `maven:3.9.9-eclipse-temurin-21` and `node:22.14.0-alpine`. The `prebuilt` stages are `FROM scratch` and pull nothing.
- EMQX durable-session env, unchanged: `EMQX_DURABLE_SESSIONS__ENABLE=true`, `EMQX_CLUSTER__DISCOVERY_STRATEGY=singleton`, `EMQX_NODE__NAME=emqx@127.0.0.1`. These go in compose env, not in `base.hocon`.
- EMQX mounts: `./emqx/base.hocon.tpl` → `/conduit/base.hocon.tpl:ro`, and `./emqx/acl.conf` → `/opt/emqx/etc/conduit-acl.conf:ro`. The start command renders `__CONDUIT_AUTH_URL__` and `__CONDUIT_INTERNAL_TOKEN__` with `sed` into `/opt/emqx/etc/base.hocon`. EMQX has no `depends_on` on conduit-server.
- Project name `conduit` in both packages. Volumes keep their names: `pg-data`, `redis-data`, `influx3-data`, `influx-explorer-data`, `emqx-data` (infra and full), and `conduit-logs` (full only). On the host they appear as `conduit_pg-data` and so on.
- `CONDUIT_AUTH_URL`: required in infra (`${CONDUIT_AUTH_URL:?CONDUIT_AUTH_URL is required}`). Full fixes it at `http://conduit-server:8080`. Probe fact: the infra file is interpolated with the full project's `.env` and its `:?` checks are enforced there. So `full/.env.example` sets `CONDUIT_AUTH_URL=http://conduit-server:8080`, and the full EMQX service also overrides it with that literal.
- Env names carried over from the current compose (no renames): `POSTGRES_PASSWORD`, `INFLUX_TOKEN`, `INFLUX_EXPLORER_SESSION_KEY`, `EMQX_DASHBOARD_PASSWORD`, `CONDUIT_INTERNAL_TOKEN`, `CONDUIT_AUTH_URL`, `BIND_ADDR`, `POSTGRES_PORT`, `REDIS_PORT`, `INFLUX_PORT`, `INFLUX_EXPLORER_PORT`, `MQTT_PORT`, `EMQX_DASHBOARD_PORT`, `WEB_PORT`, `API_PORT`, `CONDUIT_ADMIN_USERNAME`, `CONDUIT_ADMIN_PASSWORD`, `CONDUIT_PLATFORM_ADMIN_USERNAME`, `CONDUIT_PLATFORM_ADMIN_PASSWORD`, `CONDUIT_JWT_SECRET`, `TRANSPORT_MQTT_USERNAME`, `TRANSPORT_MQTT_PASSWORD`, `PIPELINE_CLAIM_MIN_IDLE`, `SIM_DEVICE_COUNT`, `SIM_INTERVAL_MS`, `SIM_PAYLOAD_FORMAT`, `SIM_PLATFORM_USERNAME`, `SIM_PLATFORM_PASSWORD`, `SIM_TENANTS`, `SIM_MAX_DEVICES_PER_PRODUCT`. New: `BUILD_FROM` (`prebuilt` | `source`, default `prebuilt`). Container-side names stay as they are: `POSTGRES_HOST`, `REDIS_HOST`, `INFLUX_URL`, `EMQX_HOST`, `MQTT_HOST`, `CONDUIT_API_URL`, `CONDUIT_LOG_DIR`, `TZ`.
- Jar names: `conduit-server/target/conduit-server.jar`, `conduit-transport/target/conduit-transport.jar`, `conduit-simulator/target/conduit-simulator-exec.jar`. Artifacts dir layout: `deploy/full/artifacts/conduit-server.jar`, `conduit-transport.jar`, `conduit-simulator-exec.jar`, and `ui/` (the contents of `conduit-ui/dist`).
- Tarball: `target/conduit-full-<short sha>.tar.gz`. It contains `deploy/` (with artifacts, without any `.env`), the root `.dockerignore`, and `conduit-ui/nginx.conf`.
- Simulator: starts by default in the full package (no profile). If `SIM_PLATFORM_PASSWORD` is empty, it simulates only the demo product.
- Transports: `conduit-transport` and `conduit-transport-2` share a YAML anchor, and both have `stop_grace_period: 20s`. The second writes its logs to `CONDUIT_LOG_DIR=/var/log/conduit/transport-2`.

**Project rules:**

- **Comments:** every shell script, compose file and Dockerfile gets a Chinese header comment that says what it is for and how to use it. Non-obvious lines get a Chinese comment, matching the existing `deploy/` style. Comments say "what and why" and never restate the code. Java edits keep the Chinese Javadoc accurate (CLAUDE.md §2.1).
- **Git:** Conventional Commits. This repo commits with the author name only and an empty email; `.git/config` already holds them. Never run `git config user.email` and never pass an email. No AI attribution lines: no `Co-Authored-By`, no "Generated with".
- **No secrets in tracked files:** no IP addresses, host names, local Windows paths, real user names, real passwords or tokens. Docs use `<docker-host>` / `<dev-host>`, and `.env.example` files use `change-me-*`. Test `.env` files for the acceptance are written only to `$SCRATCH` and to the temporary host directory.
- **CLAUDE.md** is edited only in Task 3, as its maintenance rule requires.
- **Docker host safety (Task 4):** use project `conduit-pkgtest`, host directory `/tmp/conduit-pkgtest`, and the host ports listed in Task 4. Never run anything against project `conduit`. Never read or write `/opt/conduit/docker-compose.yml` or `/opt/conduit/.env`; only `/opt/conduit/bin` is mounted, read-only. Remove the temporary project, its volumes, its images and the directory at the end.

---

## File Structure

| Path | Action | Responsibility |
|---|---|---|
| `deploy/infra/docker-compose.yml` | Create (Task 1) | Infrastructure package: postgres, redis, influxdb, influxdb-explorer, emqx (official image + mounted config); project `conduit` |
| `deploy/infra/.env.example` | Create (Task 1) | Only the infrastructure variables |
| `deploy/infra/emqx/base.hocon.tpl` | Move from `deploy/emqx/` (Task 1) | EMQX auth callback / ACL / `$SYS` event template, unchanged |
| `deploy/infra/emqx/acl.conf` | Move from `deploy/emqx/` (Task 1) | Device ACL, unchanged |
| `deploy/infra/README.md` | Create (Task 1) | How to run the infrastructure package |
| `deploy/emqx/Dockerfile` | Delete (Task 1) | The config is mounted now, not baked into an image |
| `conduit-e2e/.../EmqxConfigTest.java`, `EmqxConfig.java`, `E2eEnvironment.java` | Modify (Task 1) | Template and ACL paths → `deploy/infra/emqx/` |
| `conduit-transport/src/test/java/dev/conduit/transport/support/TestBroker.java` | Modify (Task 1) | Comment path only |
| `deploy/full/docker-compose.yml` | Create (Task 2) | Full package: `extends` the 5 infra services, plus server, 2 transports, web, simulator |
| `deploy/full/.env.example` | Create (Task 2) | Every variable of the full package (one `.env`) |
| `deploy/full/docker/java.Dockerfile` | Create (Task 2) | Java app image (server, transport, simulator): `prebuilt` / `source` stages |
| `deploy/full/docker/web.Dockerfile` | Create (Task 2) | Web image (nginx): `prebuilt` / `source` stages |
| `deploy/full/artifacts/.gitkeep` | Create (Task 2) | Keeps the git-ignored artifacts dir in the repo |
| `deploy/full/README.md` | Create (Task 2) | How to run the full package (prebuilt or source) |
| `deploy/package.sh` | Create (Task 2) | Dev machine: clean checkout → build → artifacts → tarball |
| `.dockerignore` | Rewrite (Task 2) | Repo-root build context: small, no secrets |
| `.gitignore` | Modify (Task 2) | `deploy/infra/.env`, `deploy/full/.env`, `deploy/full/artifacts/*` |
| `Dockerfile`, `conduit-ui/Dockerfile`, `conduit-ui/.dockerignore`, `deploy/docker-compose.yml`, `deploy/.env.example`, `deploy/build-local.sh`, `deploy/sync-to-host.sh` | Delete (Task 2) | Replaced by the packages |
| `README.md`, `CLAUDE.md`, `docs/backlog-v0.2.md`, `docs/device-integration.md`, spec status line | Modify (Task 3) | Docs sync |
| spec `## 9. 验收记录` | Append (Task 4) | Acceptance record |

---

## Task 1: Infrastructure package and e2e path updates

Creates `deploy/infra`, moves the EMQX template and ACL into it, drops the custom EMQX image, and points the e2e at the new paths. The old `deploy/docker-compose.yml` stops working after this task, because its `emqx` build context is gone. Nobody uses it, and Task 2 deletes it.

**Files:**
- Create: `deploy/infra/docker-compose.yml`, `deploy/infra/.env.example`, `deploy/infra/README.md`
- Move: `deploy/emqx/base.hocon.tpl` → `deploy/infra/emqx/base.hocon.tpl`, `deploy/emqx/acl.conf` → `deploy/infra/emqx/acl.conf`
- Delete: `deploy/emqx/Dockerfile`
- Modify: `conduit-e2e/src/test/java/dev/conduit/e2e/EmqxConfigTest.java`, `conduit-e2e/src/test/java/dev/conduit/e2e/EmqxConfig.java`, `conduit-e2e/src/test/java/dev/conduit/e2e/E2eEnvironment.java`, `conduit-transport/src/test/java/dev/conduit/transport/support/TestBroker.java`
- Test: `conduit-e2e/src/test/java/dev/conduit/e2e/EmqxConfigTest.java`

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces, for Task 2 (`extends`) and Task 4 (validation):
  - `deploy/infra/docker-compose.yml` with services `postgres`, `redis`, `influxdb`, `influxdb-explorer`, `emqx`, and top-level volumes `pg-data`, `redis-data`, `influx3-data`, `influx-explorer-data`, `emqx-data`.
  - EMQX reads env `CONDUIT_AUTH_URL` (required) and `CONDUIT_INTERNAL_TOKEN`.
  - The template path `deploy/infra/emqx/base.hocon.tpl` and the ACL path `deploy/infra/emqx/acl.conf`.

- [ ] **Step 1: Point the e2e config test at the new template path (failing test)**

In `conduit-e2e/src/test/java/dev/conduit/e2e/EmqxConfigTest.java`, replace

```java
/** EmqxConfig 单元测试：用部署用的 base.hocon.tpl 渲染出鉴权回调地址与内部 token，缺占位符时拒绝。 */
```

with

```java
/** EmqxConfig 单元测试：用基础服务包的 deploy/infra/emqx/base.hocon.tpl 渲染出鉴权回调地址与内部 token，缺占位符时拒绝。 */
```

and replace

```java
        String template = Files.readString(E2eEnvironment.root().resolve("deploy/emqx/base.hocon.tpl"));
```

with

```java
        String template = Files.readString(E2eEnvironment.root().resolve("deploy/infra/emqx/base.hocon.tpl"));
```

- [ ] **Step 2: Run the test and see it fail**

Run: `mvn -B -q -Pe2e -pl conduit-e2e -am test -Dtest=EmqxConfigTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL. `rendersTheDeployTemplate` throws `java.nio.file.NoSuchFileException` naming `deploy/infra/emqx/base.hocon.tpl`.

- [ ] **Step 3: Move the EMQX config and delete the custom image**

```bash
mkdir -p deploy/infra/emqx
git mv deploy/emqx/base.hocon.tpl deploy/infra/emqx/base.hocon.tpl
git mv deploy/emqx/acl.conf deploy/infra/emqx/acl.conf
git rm -q deploy/emqx/Dockerfile
ls deploy/emqx 2>/dev/null || echo "deploy/emqx removed"
```

Expected: `deploy/emqx removed`. The two files' contents do not change.

- [ ] **Step 4: Update the e2e environment, `EmqxConfig` Javadoc and `TestBroker` comment**

In `conduit-e2e/src/test/java/dev/conduit/e2e/E2eEnvironment.java`, replace

```java
        String hocon = EmqxConfig.render(Files.readString(root.resolve("deploy/emqx/base.hocon.tpl")),
                "http://" + devHost + ":" + serverPort, internalToken);
        byte[] acl = Files.readAllBytes(root.resolve("deploy/emqx/acl.conf"));
```

with

```java
        String hocon = EmqxConfig.render(Files.readString(root.resolve("deploy/infra/emqx/base.hocon.tpl")),
                "http://" + devHost + ":" + serverPort, internalToken);
        byte[] acl = Files.readAllBytes(root.resolve("deploy/infra/emqx/acl.conf"));
```

In `conduit-e2e/src/test/java/dev/conduit/e2e/EmqxConfig.java`, replace

```java
/** 渲染 deploy/emqx/base.hocon.tpl（与 compose 中 EMQX 容器启动时的 sed 替换相同的两个占位符）。 */
```

with

```java
/** 渲染 deploy/infra/emqx/base.hocon.tpl（与基础服务包 compose 中 EMQX 容器启动时的 sed 替换相同的两个占位符）。 */
```

In `conduit-transport/src/test/java/dev/conduit/transport/support/TestBroker.java`, replace

```java
    /** EMQX 容器（首次访问时启动），配置与 deploy/docker-compose.yml 的持久会话设置一致。 */
```

with

```java
    /** EMQX 容器（首次访问时启动），配置与 deploy/infra/docker-compose.yml 的持久会话设置一致。 */
```

- [ ] **Step 5: Run the test and see it pass**

Run: `mvn -B -q -Pe2e -pl conduit-e2e -am test -Dtest=EmqxConfigTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (2 tests). Then confirm that no Java source still uses the old path:

```bash
grep -rn "deploy/emqx\|deploy/docker-compose" --include=*.java . || echo "no old paths"
```

Expected: `no old paths`.

- [ ] **Step 6: Create `deploy/infra/docker-compose.yml`**

```yaml
# 基础服务包（开发用）：PostgreSQL、Redis、InfluxDB 3、InfluxDB 3 Explorer、EMQX。
# 应用（server、transport、模拟器、前端）在开发机的 IDE 中运行，EMQX 的鉴权回调打到开发机上的 server。
# 用法：把本目录放到 Linux 宿主机上，cp .env.example .env 并修改口令与 CONDUIT_AUTH_URL，然后 docker compose up -d。
# 全量包 deploy/full 用 extends 引用这里的 5 个服务：修改本文件会同时影响全量包，两者项目名与数据卷相同。
name: conduit

services:
  postgres:
    image: postgres:16.8
    environment:
      POSTGRES_DB: conduit
      POSTGRES_USER: conduit
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?POSTGRES_PASSWORD is required}
    volumes:
      - pg-data:/var/lib/postgresql/data
    ports:
      - "${BIND_ADDR:-127.0.0.1}:${POSTGRES_PORT:-15432}:5432"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U conduit -d conduit"]
      interval: 5s
      retries: 20
    restart: unless-stopped

  redis:
    image: redis:7.4.2
    command: ["redis-server", "--appendonly", "yes"]
    volumes:
      - redis-data:/data
    ports:
      - "${BIND_ADDR:-127.0.0.1}:${REDIS_PORT:-16379}:6379"
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      retries: 20
    restart: unless-stopped

  influxdb:
    image: influxdb:3.12.0-core
    # 启动时由 INFLUX_TOKEN 生成离线管理员 token 文件（只有库里还没有管理员 token 时才生效），再启动服务
    entrypoint: ["/bin/sh", "-c"]
    command:
      - >-
        case "$${INFLUX_TOKEN}" in apiv3_*) ;; *) echo "INFLUX_TOKEN must start with apiv3_" >&2; exit 1;; esac;
        umask 077
        && printf '{"token":"%s","name":"_admin"}' "$${INFLUX_TOKEN}" > /tmp/admin-token.json
        && exec influxdb3 serve --node-id node0 --object-store file --data-dir /var/lib/influxdb3/data
        --admin-token-file /tmp/admin-token.json --disable-authz health,ping
    environment:
      INFLUX_TOKEN: ${INFLUX_TOKEN:?INFLUX_TOKEN is required}
    ports:
      - "${BIND_ADDR:-127.0.0.1}:${INFLUX_PORT:-8096}:8181"
    volumes:
      - influx3-data:/var/lib/influxdb3
    healthcheck:
      test: ["CMD", "curl", "-fsS", "http://localhost:8181/health"]
      interval: 5s
      retries: 30
    restart: unless-stopped

  influxdb-explorer:
    # InfluxDB 3 官方图形界面（InfluxDB 3 Explorer），预置连接到本编排的 InfluxDB 与 telemetry 库。
    # admin 模式可管理库与 token；它本身没有登录，只应在可信网络中暴露（端口按 BIND_ADDR 绑定）。
    image: influxdata/influxdb3-ui:1.11.0
    environment:
      DEFAULT_SERVER_NAME: conduit
      DEFAULT_INFLUX_SERVER: http://influxdb:8181
      DEFAULT_API_TOKEN: ${INFLUX_TOKEN:?INFLUX_TOKEN is required}
      DEFAULT_INFLUX_DATABASE: telemetry
      UI_MODE: admin
      # 会话签名密钥；不设置时每次重启都会生成新密钥，已打开的页面需要重新进入
      SESSION_SECRET_KEY: ${INFLUX_EXPLORER_SESSION_KEY:?INFLUX_EXPLORER_SESSION_KEY is required}
    ports:
      - "${BIND_ADDR:-127.0.0.1}:${INFLUX_EXPLORER_PORT:-8888}:8080"
    volumes:
      - influx-explorer-data:/db
    depends_on:
      influxdb:
        condition: service_healthy
    restart: unless-stopped

  emqx:
    # 官方镜像；Conduit 的配置模板与 ACL 从本目录 emqx/ 只读挂载（不再烘焙进自定义镜像）。
    # 启动时把模板中的鉴权回调地址与内部 token 渲染成 /opt/emqx/etc/base.hocon，再以前台方式启动 EMQX
    image: emqx/emqx:5.8.6
    entrypoint: ["/bin/sh", "-c"]
    command:
      - >-
        esc() { printf '%s' "$$1" | sed 's/[&|\\]/\\&/g'; };
        sed -e "s|__CONDUIT_INTERNAL_TOKEN__|$$(esc "$${CONDUIT_INTERNAL_TOKEN}")|"
        -e "s|__CONDUIT_AUTH_URL__|$$(esc "$${CONDUIT_AUTH_URL}")|"
        /conduit/base.hocon.tpl > /opt/emqx/etc/base.hocon
        && exec /usr/bin/docker-entrypoint.sh /opt/emqx/bin/emqx foreground
    environment:
      CONDUIT_INTERNAL_TOKEN: ${CONDUIT_INTERNAL_TOKEN:?CONDUIT_INTERNAL_TOKEN is required}
      # 鉴权回调地址（必填）：开发时为开发机上 server 的地址 http://<dev-host>:8080；全量包固定为 http://conduit-server:8080
      CONDUIT_AUTH_URL: ${CONDUIT_AUTH_URL:?CONDUIT_AUTH_URL is required}
      EMQX_DASHBOARD__DEFAULT_PASSWORD: ${EMQX_DASHBOARD_PASSWORD:?EMQX_DASHBOARD_PASSWORD is required}
      # 磁盘持久会话（V0.2-R）：transport 离线期间的消息写入磁盘，保留 1 天（durable_sessions.message_retention_period 默认值）；
      # 5.8.6 要求单节点发现策略 singleton，否则启动失败。固定节点名：默认按容器 IP 命名，IP 变化会换数据目录、丢失持久会话
      EMQX_DURABLE_SESSIONS__ENABLE: "true"
      EMQX_CLUSTER__DISCOVERY_STRATEGY: singleton
      EMQX_NODE__NAME: emqx@127.0.0.1
    volumes:
      - emqx-data:/opt/emqx/data
      # 相对路径按本文件所在目录解析（全量包 extends 时同样指向这里）；文件需对容器内 emqx 用户（uid 1000）可读
      - ./emqx/base.hocon.tpl:/conduit/base.hocon.tpl:ro
      - ./emqx/acl.conf:/opt/emqx/etc/conduit-acl.conf:ro
    ports:
      - "${BIND_ADDR:-127.0.0.1}:${MQTT_PORT:-1883}:1883"
      - "${BIND_ADDR:-127.0.0.1}:${EMQX_DASHBOARD_PORT:-18083}:18083"
    # 不依赖 conduit-server：server 未运行或未就绪时，EMQX 对鉴权回调自行重试
    healthcheck:
      test: ["CMD", "/opt/emqx/bin/emqx", "ctl", "status"]
      interval: 5s
      retries: 30
    restart: unless-stopped

volumes:
  pg-data:
  redis-data:
  influx3-data:
  influx-explorer-data:
  emqx-data:
```

- [ ] **Step 7: Create `deploy/infra/.env.example`**

```
# 基础服务包配置：复制为 .env 后修改。以下均为占位值，所有 change-me-* 都必须替换（例如 openssl rand -hex 32）。
POSTGRES_PASSWORD=change-me-postgres
# InfluxDB 3 管理员 token，必须以 apiv3_ 开头，其后只能含字母/数字/_/-（不要引号、反斜杠、$）；首次启动时写入库中，之后修改需删除 conduit_influx3-data 卷（见 README）
INFLUX_TOKEN=apiv3_change-me-influx-token
# InfluxDB 3 Explorer 会话签名密钥
INFLUX_EXPLORER_SESSION_KEY=change-me-explorer-session-key
# EMQX 控制台 admin 密码：仅在 emqx-data 卷为空（首次启动）时生效
EMQX_DASHBOARD_PASSWORD=change-me-emqx
# EMQX 调用鉴权回调时携带的内部 token，必须与开发机上 server 的 conduit.internal.token 一致
CONDUIT_INTERNAL_TOKEN=change-me-internal-token
# 必填：EMQX 鉴权回调（开发机上 IDE 中运行的 conduit-server）的地址，<dev-host> 为宿主机访问开发机的地址；修改后需 docker compose up -d --force-recreate emqx
CONDUIT_AUTH_URL=http://<dev-host>:8080

# 端口绑定地址：应用在开发机上运行、要从其他机器连接这些端口，所以默认 0.0.0.0；只应在可信网络中使用，只供本机访问时改为 127.0.0.1
BIND_ADDR=0.0.0.0

# 宿主机端口
INFLUX_PORT=8096
# InfluxDB 3 Explorer（图形界面）
INFLUX_EXPLORER_PORT=8888
# PostgreSQL / Redis 对宿主机开放的端口，供 IDE 中运行的服务直连
POSTGRES_PORT=15432
REDIS_PORT=16379
MQTT_PORT=1883
EMQX_DASHBOARD_PORT=18083
```

- [ ] **Step 8: Create `deploy/infra/README.md`**

````markdown
# Conduit 基础服务包（开发用）

PostgreSQL 16.8、Redis 7.4.2、InfluxDB 3.12.0-core、InfluxDB 3 Explorer 1.11.0、EMQX 5.8.6（磁盘持久会话）。应用在开发机的 IDE 中运行（见仓库 README「开发」）。

## 启动

把本目录复制或 clone 到 Linux 宿主机（需 Docker 与 Docker Compose v2），在本目录中：

```bash
cp .env.example .env    # 替换全部 change-me-* 口令，CONDUIT_AUTH_URL 改为 http://<dev-host>:8080
docker compose up -d
docker compose ps       # 5 个服务均为 running，其中 4 个 healthy（Explorer 无健康检查）
```

- 项目名 `conduit`，数据卷 `conduit_pg-data`、`conduit_redis-data`、`conduit_influx3-data`、`conduit_influx-explorer-data`、`conduit_emqx-data`。与全量包 `deploy/full` 的项目名和数据卷相同，同一台宿主机上二选一。
- `CONDUIT_AUTH_URL` 必填：EMQX 把设备与 transport 的连接鉴权回调到这个地址上的 conduit-server。修改后执行 `docker compose up -d --force-recreate emqx`。
- `CONDUIT_INTERNAL_TOKEN` 必须与开发机上 server 的 `conduit.internal.token` 一致。
- `emqx/base.hocon.tpl` 与 `emqx/acl.conf` 以只读方式挂载进 EMQX 容器，需对容器内的 emqx 用户（uid 1000）可读（普通 644 权限即可）。

## 从旧的 deploy/docker-compose.yml 迁移

项目名与数据卷不变，数据沿用：

1. 在旧编排目录执行 `docker compose down`（不要加 `-v`）。
2. 把旧 `.env` 复制为本目录的 `.env`（多余的变量会被忽略），确认 `CONDUIT_AUTH_URL` 指向开发机。
3. 在本目录执行 `docker compose up -d`。
4. 旧的自定义 EMQX 镜像 `conduit-emqx` 不再使用，可以删除。
````

- [ ] **Step 9: Static checks**

```bash
grep -c "extends\|build:" deploy/infra/docker-compose.yml          # expect 0
grep -n "depends_on" deploy/infra/docker-compose.yml               # expect only the influxdb-explorer one
grep -nE "([0-9]{1,3}\.){3}[0-9]{1,3}" deploy/infra/.env.example deploy/infra/README.md   # expect only 0.0.0.0 / 127.0.0.1
git status --short deploy conduit-e2e conduit-transport
```

Expected: the counts and matches given in the comments. The status lists only the files in this task's **Files** block; `.run/` is not among them. The host validates the compose file in Task 4.

- [ ] **Step 10: Commit**

```bash
git add deploy/infra conduit-e2e/src/test/java/dev/conduit/e2e/EmqxConfigTest.java \
  conduit-e2e/src/test/java/dev/conduit/e2e/EmqxConfig.java conduit-e2e/src/test/java/dev/conduit/e2e/E2eEnvironment.java \
  conduit-transport/src/test/java/dev/conduit/transport/support/TestBroker.java
git commit -m "feat(deploy): add the infrastructure package with mounted EMQX config"
```

---

## Task 2: Full package, Dockerfiles, `.dockerignore`, `package.sh`, old files removed

**Files:**
- Create: `deploy/full/docker-compose.yml`, `deploy/full/.env.example`, `deploy/full/docker/java.Dockerfile`, `deploy/full/docker/web.Dockerfile`, `deploy/full/artifacts/.gitkeep`, `deploy/full/README.md`, `deploy/package.sh`
- Rewrite: `.dockerignore`
- Modify: `.gitignore`
- Delete: `Dockerfile`, `conduit-ui/Dockerfile`, `conduit-ui/.dockerignore`, `deploy/docker-compose.yml`, `deploy/.env.example`, `deploy/build-local.sh`, `deploy/sync-to-host.sh`

**Interfaces:**
- Consumes: `deploy/infra/docker-compose.yml` from Task 1 (service names `postgres`, `redis`, `influxdb`, `influxdb-explorer`, `emqx`; volume names as in Global Constraints).
- Produces, for Task 3 (docs) and Task 4 (acceptance):
  - `deploy/package.sh` (no arguments; env `VITE_MQTT_PORT`, default `1883`). It writes `target/conduit-full-<short sha>.tar.gz`.
  - The full compose services: `postgres`, `redis`, `influxdb`, `influxdb-explorer`, `emqx`, `conduit-server`, `conduit-transport`, `conduit-transport-2`, `conduit-web`, `simulator`. With project `P`, the containers are named `P-<service>-1` and the built images `P-<service>`.
  - Build args: `BUILD_FROM`, `MODULE`, `JAR_NAME` (Java); `BUILD_FROM`, `VITE_MQTT_PORT` (web).

- [ ] **Step 1: Create `deploy/full/docker/java.Dockerfile`**

```dockerfile
# Java 应用镜像（conduit-server、conduit-transport、conduit-simulator 共用），构建上下文为仓库根目录。
# 构建参数：
#   BUILD_FROM  产物来源：prebuilt（默认，复制 deploy/full/artifacts/ 中打好的 jar）或 source（在镜像内用 Maven 从源码编译，需联网）
#   MODULE      Maven 模块目录，例如 conduit-server（source 用）
#   JAR_NAME    jar 文件名（不含 .jar），例如 conduit-simulator-exec
# FROM ${BUILD_FROM} AS app 选中其中一个阶段，BuildKit 只构建被最终阶段用到的阶段：
# prebuilt 时不需要源码与 Maven 镜像，source 时不需要 artifacts/。
ARG BUILD_FROM=prebuilt

# 预编译产物：由开发机上的 deploy/package.sh 放入 deploy/full/artifacts/
FROM scratch AS prebuilt
ARG JAR_NAME
COPY deploy/full/artifacts/${JAR_NAME}.jar /out/app.jar

# 从源码编译：只复制 Maven 需要的父 pom、各 Java 模块与模拟器打包的演示物模型
FROM maven:3.9.9-eclipse-temurin-21 AS source
ARG MODULE
ARG JAR_NAME
WORKDIR /src
COPY pom.xml ./
COPY conduit-common conduit-common
COPY conduit-transport conduit-transport
COPY conduit-server conduit-server
COPY conduit-simulator conduit-simulator
COPY docs/examples docs/examples
# 只构建所需模块及其依赖；不激活 e2e 等 profile，测试由开发机负责
RUN mvn -B -q -DskipTests package -pl ${MODULE} -am \
    && mkdir -p /out && cp ${MODULE}/target/${JAR_NAME}.jar /out/app.jar

FROM ${BUILD_FROM} AS app

FROM eclipse-temurin:21.0.6_7-jre
WORKDIR /app
COPY --from=app /out/app.jar app.jar
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
```

- [ ] **Step 2: Create `deploy/full/docker/web.Dockerfile`**

```dockerfile
# 前端镜像（nginx 托管构建好的 Vue 应用，并把 /api/ 反向代理到 conduit-server），构建上下文为仓库根目录。
# 构建参数：
#   BUILD_FROM      产物来源：prebuilt（默认，复制 deploy/full/artifacts/ui/）或 source（在镜像内 npm ci + npm run build，需联网）
#   VITE_MQTT_PORT  「连接信息」中显示的 MQTT 端口（source 用；prebuilt 的端口在打包时已固定）
ARG BUILD_FROM=prebuilt

# 预编译产物：deploy/package.sh 把 conduit-ui/dist 的内容放到 deploy/full/artifacts/ui/
FROM scratch AS prebuilt
COPY deploy/full/artifacts/ui /out/html

# 从源码编译：先装依赖再复制源码，源码变化时复用依赖层
FROM node:22.14.0-alpine AS source
ARG VITE_MQTT_PORT=1883
WORKDIR /src
COPY conduit-ui/package.json conduit-ui/package-lock.json ./
RUN npm ci --no-audit --no-fund --loglevel=error
COPY conduit-ui/ ./
ENV VITE_MQTT_PORT=${VITE_MQTT_PORT}
RUN npm run build && mkdir -p /out && cp -r dist /out/html

FROM ${BUILD_FROM} AS app

FROM nginx:1.27.4-alpine
COPY conduit-ui/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=app /out/html /usr/share/nginx/html
```

- [ ] **Step 3: Create `deploy/full/docker-compose.yml`**

```yaml
# 全量包：基础服务（extends ../infra/docker-compose.yml）+ conduit-server、两个 transport、conduit-web 与演示模拟器。
# 用法：cp .env.example .env 并修改口令，然后 docker compose up -d --build。
# 应用镜像的产物来源由 .env 的 BUILD_FROM 决定：prebuilt（默认，用 artifacts/ 中由 deploy/package.sh 打好的产物）
# 或 source（在宿主机上从源码编译，需联网）。构建上下文为仓库根目录（../..），见根目录 .dockerignore。
# 基础服务的定义只在 ../infra/docker-compose.yml：其中的相对路径（EMQX 配置挂载）按 infra 文件所在目录解析，
# 变量插值用本目录的 .env，所以本目录 .env 要包含 infra 必填的全部变量。项目名与数据卷和基础服务包相同，同一宿主机上二选一。
name: conduit

services:
  postgres:
    extends:
      file: ../infra/docker-compose.yml
      service: postgres

  redis:
    extends:
      file: ../infra/docker-compose.yml
      service: redis

  influxdb:
    extends:
      file: ../infra/docker-compose.yml
      service: influxdb

  influxdb-explorer:
    extends:
      file: ../infra/docker-compose.yml
      service: influxdb-explorer
    # 与 infra 中的依赖相同，在此显式声明，不依赖 extends 是否继承 depends_on
    depends_on:
      influxdb:
        condition: service_healthy

  emqx:
    extends:
      file: ../infra/docker-compose.yml
      service: emqx
    environment:
      # 全量包中鉴权回调固定指向容器内的 conduit-server（.env 中的同名变量只用于满足 infra 文件的必填检查）
      CONDUIT_AUTH_URL: http://conduit-server:8080

  conduit-server:
    build:
      context: ../..
      dockerfile: deploy/full/docker/java.Dockerfile
      args:
        BUILD_FROM: ${BUILD_FROM:-prebuilt}
        MODULE: conduit-server
        JAR_NAME: conduit-server
    environment:
      POSTGRES_HOST: postgres
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
      REDIS_HOST: redis
      INFLUX_URL: http://influxdb:8181
      INFLUX_TOKEN: ${INFLUX_TOKEN}
      CONDUIT_ADMIN_USERNAME: ${CONDUIT_ADMIN_USERNAME:-admin}
      CONDUIT_ADMIN_PASSWORD: ${CONDUIT_ADMIN_PASSWORD:?CONDUIT_ADMIN_PASSWORD is required}
      CONDUIT_PLATFORM_ADMIN_USERNAME: ${CONDUIT_PLATFORM_ADMIN_USERNAME:-sysadmin}
      CONDUIT_PLATFORM_ADMIN_PASSWORD: ${CONDUIT_PLATFORM_ADMIN_PASSWORD:-}
      CONDUIT_JWT_SECRET: ${CONDUIT_JWT_SECRET:?CONDUIT_JWT_SECRET is required}
      CONDUIT_INTERNAL_TOKEN: ${CONDUIT_INTERNAL_TOKEN}
      TRANSPORT_MQTT_USERNAME: ${TRANSPORT_MQTT_USERNAME:-conduit-transport}
      TRANSPORT_MQTT_PASSWORD: ${TRANSPORT_MQTT_PASSWORD:?TRANSPORT_MQTT_PASSWORD is required}
      PIPELINE_CLAIM_MIN_IDLE: ${PIPELINE_CLAIM_MIN_IDLE:-60s}
      CONDUIT_LOG_DIR: /var/log/conduit
    # Java 服务共用命名卷 conduit-logs 存放日志（JRE 镜像默认以 root 运行，可直接写入）
    volumes:
      - conduit-logs:/var/log/conduit
    ports:
      - "${BIND_ADDR:-127.0.0.1}:${API_PORT:-8091}:8080"
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy
      influxdb:
        condition: service_healthy
    healthcheck:
      # JRE 镜像无 curl/wget：用 bash /dev/tcp 请求 readiness 探针，
      # 只有 ApplicationRunner（含 AdminBootstrap）执行完毕后才返回 UP
      test:
        - CMD
        - bash
        - -c
        - >-
          exec 3<>/dev/tcp/127.0.0.1/8080
          && printf 'GET /actuator/health/readiness HTTP/1.0\r\nHost: localhost\r\n\r\n' >&3
          && grep -q '"UP"' <&3
      interval: 5s
      timeout: 3s
      start_period: 30s
      retries: 30
    restart: unless-stopped

  # transport 单活多备（V0.2-R）：两个实例经 Redis 锁 conduit:transport:leader 选主，只有主实例连接 EMQX；
  # 两者共用 clientId conduit-transport（默认值），主实例故障后备实例最长 15 秒后接管同一个磁盘持久会话
  conduit-transport: &transport
    build:
      context: ../..
      dockerfile: deploy/full/docker/java.Dockerfile
      args:
        BUILD_FROM: ${BUILD_FROM:-prebuilt}
        MODULE: conduit-transport
        JAR_NAME: conduit-transport
    # 给主实例留时间断开 MQTT 并释放选主锁（Compose 默认 10 秒），备实例才能立即接管
    stop_grace_period: 20s
    environment: &transport-env
      REDIS_HOST: redis
      EMQX_HOST: emqx
      TRANSPORT_MQTT_USERNAME: ${TRANSPORT_MQTT_USERNAME:-conduit-transport}
      TRANSPORT_MQTT_PASSWORD: ${TRANSPORT_MQTT_PASSWORD}
      CONDUIT_LOG_DIR: /var/log/conduit
    volumes:
      - conduit-logs:/var/log/conduit
    depends_on:
      redis:
        condition: service_healthy
      emqx:
        condition: service_healthy
      conduit-server:
        condition: service_healthy
    restart: unless-stopped

  # 第二个 transport（与上面相同的构建与配置，镜像各自构建一次，第二次命中 BuildKit 缓存）：
  # 日志写到共享卷的子目录，避免两个进程滚动同一个日志文件
  conduit-transport-2:
    <<: *transport
    environment:
      <<: *transport-env
      CONDUIT_LOG_DIR: /var/log/conduit/transport-2

  conduit-web:
    build:
      context: ../..
      dockerfile: deploy/full/docker/web.Dockerfile
      args:
        BUILD_FROM: ${BUILD_FROM:-prebuilt}
        VITE_MQTT_PORT: ${MQTT_PORT:-1883}
    ports:
      - "${BIND_ADDR:-127.0.0.1}:${WEB_PORT:-8090}:80"
    depends_on:
      - conduit-server
    restart: unless-stopped

  # 演示模拟器（默认启动）：自动创建「三相电表（演示）」产品与设备并按间隔上报；
  # SIM_PLATFORM_PASSWORD 非空时再模拟 SIM_TENANTS 中各租户有物模型的产品（会重置这些设备的密钥）
  simulator:
    build:
      context: ../..
      dockerfile: deploy/full/docker/java.Dockerfile
      args:
        BUILD_FROM: ${BUILD_FROM:-prebuilt}
        MODULE: conduit-simulator
        JAR_NAME: conduit-simulator-exec
    environment:
      CONDUIT_API_URL: http://conduit-server:8080
      CONDUIT_ADMIN_USERNAME: ${CONDUIT_ADMIN_USERNAME:-admin}
      CONDUIT_ADMIN_PASSWORD: ${CONDUIT_ADMIN_PASSWORD}
      MQTT_HOST: emqx
      SIM_DEVICE_COUNT: ${SIM_DEVICE_COUNT:-100}
      SIM_INTERVAL_MS: ${SIM_INTERVAL_MS:-10000}
      SIM_PAYLOAD_FORMAT: ${SIM_PAYLOAD_FORMAT:-standard}
      # 多租户模拟（V0.2-R）：平台管理员密码为空时只模拟演示产品；非空时模拟 SIM_TENANTS 中各租户有物模型的产品
      SIM_PLATFORM_USERNAME: ${SIM_PLATFORM_USERNAME:-sysadmin}
      SIM_PLATFORM_PASSWORD: ${SIM_PLATFORM_PASSWORD:-}
      SIM_TENANTS: ${SIM_TENANTS:-*}
      SIM_MAX_DEVICES_PER_PRODUCT: ${SIM_MAX_DEVICES_PER_PRODUCT:-100}
      # 光伏、用电、用水的日曲线按本地时间计算
      TZ: Asia/Shanghai
      CONDUIT_LOG_DIR: /var/log/conduit
    volumes:
      - conduit-logs:/var/log/conduit
    depends_on:
      emqx:
        condition: service_healthy
      conduit-server:
        condition: service_healthy
      conduit-transport:
        condition: service_started
    restart: on-failure

# extends 不带入顶层数据卷，这里按同名重新声明（项目名 conduit 下即 conduit_pg-data 等，与基础服务包共用）
volumes:
  pg-data:
  redis-data:
  influx3-data:
  influx-explorer-data:
  emqx-data:
  conduit-logs:
```

- [ ] **Step 4: Create `deploy/full/.env.example`**

```
# 全量包配置（单一 .env，含基础服务与应用的全部变量）：复制为 .env 后修改。
# 以下均为占位值，所有 change-me-* 都必须在宿主机可被他人访问之前替换（例如 openssl rand -hex 32）。

# 应用镜像的产物来源：prebuilt（默认，用 artifacts/ 中由 deploy/package.sh 打好的产物，宿主机无需外网）
# 或 source（在宿主机上从源码编译，需能访问 Maven 中央仓库与 npm 源）
BUILD_FROM=prebuilt

POSTGRES_PASSWORD=change-me-postgres
# InfluxDB 3 管理员 token，必须以 apiv3_ 开头，其后只能含字母/数字/_/-（不要引号、反斜杠、$）；首次启动时写入库中，之后修改需删除 conduit_influx3-data 卷（见 README）
INFLUX_TOKEN=apiv3_change-me-influx-token
INFLUX_EXPLORER_SESSION_KEY=change-me-explorer-session-key
# EMQX 控制台 admin 密码：仅在 emqx-data 卷为空（首次启动）时生效
EMQX_DASHBOARD_PASSWORD=change-me-emqx
CONDUIT_INTERNAL_TOKEN=change-me-internal-token
# 全量包中固定为容器内的 conduit-server，不要修改（compose 中 EMQX 也以此值覆盖；这一行用于满足基础服务定义的必填检查）
CONDUIT_AUTH_URL=http://conduit-server:8080

# 默认租户的租户管理员
CONDUIT_ADMIN_USERNAME=admin
CONDUIT_ADMIN_PASSWORD=change-me-admin
# 平台管理员（不属于任何租户，管理租户）：库中没有平台管理员时按此创建；密码留空则不创建（server 只打一条 WARN）
CONDUIT_PLATFORM_ADMIN_USERNAME=sysadmin
CONDUIT_PLATFORM_ADMIN_PASSWORD=change-me-platform-admin
CONDUIT_JWT_SECRET=change-me-to-a-random-string-of-at-least-32-bytes
TRANSPORT_MQTT_USERNAME=conduit-transport
TRANSPORT_MQTT_PASSWORD=change-me-transport
PIPELINE_CLAIM_MIN_IDLE=60s

# 端口绑定地址：默认仅本机可访问；需被其他机器访问时改为 0.0.0.0，并确保网络可信
BIND_ADDR=127.0.0.1

# 宿主机端口
WEB_PORT=8090
API_PORT=8091
INFLUX_PORT=8096
# InfluxDB 3 Explorer（图形界面）
INFLUX_EXPLORER_PORT=8888
POSTGRES_PORT=15432
REDIS_PORT=16379
# MQTT 端口；BUILD_FROM=source 时也作为前端「连接信息」显示的端口（prebuilt 的前端在打包时已固定）
MQTT_PORT=1883
EMQX_DASHBOARD_PORT=18083

# 演示模拟器（默认启动）
SIM_DEVICE_COUNT=100
SIM_INTERVAL_MS=10000
# 模拟器上报格式：standard（标准 JSON）或 flat（扁平 JSON）；模拟器新建产品时按此选用解码器，已有产品不会被修改，需在产品详情中手动切换
SIM_PAYLOAD_FORMAT=standard
# 多租户模拟（V0.2-R）：填写平台管理员密码后，模拟器在启动时发现 SIM_TENANTS 中各租户有物模型的产品与启用设备并模拟上报；
# 它会重置这些设备的密钥（真实设备随之连不上），只应对演示租户使用。留空则只模拟演示产品
SIM_PLATFORM_USERNAME=sysadmin
SIM_PLATFORM_PASSWORD=
# 逗号分隔的租户编码，* 表示全部启用中的租户
SIM_TENANTS=*
# 每个产品最多模拟的设备数
SIM_MAX_DEVICES_PER_PRODUCT=100
```

- [ ] **Step 5: Create `deploy/full/README.md` and the artifacts placeholder**

Create the empty file `deploy/full/artifacts/.gitkeep`:

```bash
mkdir -p deploy/full/artifacts && : > deploy/full/artifacts/.gitkeep
```

Create `deploy/full/README.md`:

````markdown
# Conduit 全量包

基础服务（PostgreSQL、Redis、InfluxDB 3、InfluxDB 3 Explorer、EMQX，定义在 `../infra/docker-compose.yml`，以 `extends` 引用）+ conduit-server、两个 transport（一主一备）、conduit-web 与演示模拟器。需要 Docker 与 Docker Compose v2。

## 方式一：预编译产物（宿主机无外网）

在开发机上（需 git、JDK 21 上的 Maven、Node.js 22）：

```bash
deploy/package.sh       # 生成 target/conduit-full-<短提交>.tar.gz
```

把包复制到 Linux 宿主机后：

```bash
tar -xzf conduit-full-<短提交>.tar.gz
cd deploy/full
cp .env.example .env    # 替换全部 change-me-* 口令；BUILD_FROM 保持 prebuilt
docker compose up -d --build
```

宿主机上需已有镜像 `eclipse-temurin:21.0.6_7-jre`、`nginx:1.27.4-alpine` 以及基础服务的镜像（`postgres:16.8`、`redis:7.4.2`、`influxdb:3.12.0-core`、`influxdata/influxdb3-ui:1.11.0`、`emqx/emqx:5.8.6`）。包里只有部署文件与产物，没有源码；`.env` 不在包内。

## 方式二：从源码构建（宿主机能联网）

```bash
git clone <仓库地址> conduit && cd conduit/deploy/full
cp .env.example .env    # 替换口令，并设置 BUILD_FROM=source
docker compose up -d --build
```

镜像内用 `maven:3.9.9-eclipse-temurin-21` 与 `node:22.14.0-alpine` 编译，需要能下载 Maven 与 npm 依赖。

## 说明

- 项目名 `conduit`，数据卷与基础服务包 `deploy/infra` 相同（`conduit_pg-data` 等），同一台宿主机上二选一；从基础服务包切换过来时数据沿用。
- 构建上下文是包（或仓库）的根目录，`deploy/infra` 与 `deploy/full` 的相对位置不能改变。
- EMQX 的鉴权回调固定为 `http://conduit-server:8080`。
- 浏览器打开 `http://<docker-host>:8090`（`WEB_PORT`），用 `CONDUIT_ADMIN_USERNAME` / `CONDUIT_ADMIN_PASSWORD` 登录；平台管理员用 `CONDUIT_PLATFORM_ADMIN_USERNAME` / `CONDUIT_PLATFORM_ADMIN_PASSWORD`。
- 日志在命名卷 `conduit_conduit-logs`（第二个 transport 在其 `transport-2` 子目录）。
````

- [ ] **Step 6: Create `deploy/package.sh`**

```bash
#!/usr/bin/env bash
# 打包全量包（开发机执行，Git Bash 或 Linux）：从当前提交（HEAD）的干净检出编译后端 jar 与前端，
# 放进检出中的 deploy/full/artifacts/，再把 deploy/（含产物，不含 .env）、根 .dockerignore 与 conduit-ui/nginx.conf
# 打成 target/conduit-full-<短提交>.tar.gz。包复制到 Linux 上解压后，按 deploy/full/README.md 以 BUILD_FROM=prebuilt 启动，
# 宿主机不需要外网。
#
# 未提交的修改与 .gitignore 中的本地配置（.env、application-local.yml、simulator-local.properties 等）不会进入包。
#
# 用法（仓库内任意目录）：deploy/package.sh
# 环境变量：VITE_MQTT_PORT  前端「连接信息」中显示的 MQTT 端口（默认 1883，打包时固定进前端）
# 需要：git、tar、JDK 21 上的 mvn、Node.js 22 / npm。
set -euo pipefail

ROOT=$(git rev-parse --show-toplevel)
SHA=$(git -C "$ROOT" rev-parse --short HEAD)
OUT_DIR="$ROOT/target"
OUT="$OUT_DIR/conduit-full-$SHA.tar.gz"
MQTT_PORT=${VITE_MQTT_PORT:-1883}

if ! mvn -v 2>/dev/null | grep -q "Java version: 21"; then
  echo "mvn 需要运行在 JDK 21 上（设置 JAVA_HOME 指向 JDK 21）" >&2
  exit 1
fi
if [[ -n "$(git -C "$ROOT" status --porcelain)" ]]; then
  echo "注意：工作区有未提交的修改，它们不会进入包（只打包提交 $SHA）" >&2
fi

WORK=$(mktemp -d)
SRC="$WORK/src"
# 结束时（含失败）移除临时检出
cleanup() {
  git -C "$ROOT" worktree remove --force "$SRC" >/dev/null 2>&1 || rm -rf "$SRC"
  git -C "$ROOT" worktree prune
  rm -rf "$WORK"
}
trap cleanup EXIT

echo "==> 干净检出 $SHA"
git -C "$ROOT" worktree add --detach --quiet "$SRC" HEAD

echo "==> 构建后端 jar"
(cd "$SRC" && mvn -B -q -DskipTests package)

echo "==> 构建前端（VITE_MQTT_PORT=$MQTT_PORT）"
(cd "$SRC/conduit-ui" && npm ci --no-audit --no-fund --loglevel=error && VITE_MQTT_PORT=$MQTT_PORT npm run build)

echo "==> 复制产物到 deploy/full/artifacts/"
ART="$SRC/deploy/full/artifacts"
cp "$SRC/conduit-server/target/conduit-server.jar" "$ART/"
cp "$SRC/conduit-transport/target/conduit-transport.jar" "$ART/"
cp "$SRC/conduit-simulator/target/conduit-simulator-exec.jar" "$ART/"
mkdir -p "$ART/ui"
cp -r "$SRC/conduit-ui/dist/." "$ART/ui/"

mkdir -p "$OUT_DIR"
# 经标准输出写包：Git Bash 的 GNU tar 会把 -f C:/... 中的盘符当成远程主机名
(cd "$SRC" && tar -czf - deploy .dockerignore conduit-ui/nginx.conf) > "$OUT"
echo "==> 已生成 $OUT"
```

Then make it executable in git:

```bash
git add deploy/package.sh && git update-index --chmod=+x deploy/package.sh
```

- [ ] **Step 7: Rewrite the root `.dockerignore`**

Replace the whole file with:

```
# 构建上下文为仓库根目录（deploy/full/docker/ 下的 Dockerfile 使用）：
# 排除依赖、构建产物、版本库、日志与本地配置，使上下文小且不带主机地址与口令。
# 预编译产物在 deploy/full/artifacts/（由 deploy/package.sh 生成），最后一行保证它们始终进入上下文。
.git
**/node_modules
**/target
conduit-ui/dist
logs
.claude
.superpowers
.idea
**/.idea
**/*.iml
**/.env
**/.env.*
**/application-local.yml
**/simulator-local.properties
!deploy/full/artifacts/**
```

- [ ] **Step 8: Update `.gitignore`**

Replace

```
# Secrets
.env
deploy/.env
```

with

```
# Secrets
.env
deploy/infra/.env
deploy/full/.env

# 全量包的预编译产物（deploy/package.sh 在临时检出中生成），仓库里只保留占位文件
deploy/full/artifacts/*
!deploy/full/artifacts/.gitkeep
```

- [ ] **Step 9: Delete the old build and sync files**

```bash
git rm -q Dockerfile conduit-ui/Dockerfile conduit-ui/.dockerignore \
  deploy/docker-compose.yml deploy/.env.example deploy/build-local.sh deploy/sync-to-host.sh
ls deploy
```

Expected: `full  infra  package.sh`. An untracked, git-ignored `.env` from earlier work may also be listed; leave it alone.

- [ ] **Step 10: Static checks**

```bash
bash -n deploy/package.sh && echo "syntax ok"
git check-ignore -q deploy/full/.env && git check-ignore -q deploy/infra/.env && git check-ignore -q deploy/full/artifacts/x.jar && echo "ignored ok"
git check-ignore -q deploy/full/artifacts/.gitkeep || echo "gitkeep tracked ok"
grep -c "extends:" deploy/full/docker-compose.yml          # expect 5
grep -n "profiles" deploy/full/docker-compose.yml || echo "no profiles"
grep -rn "build-local\|sync-to-host" deploy .dockerignore .gitignore || echo "no old references"
```

Expected: `syntax ok`, `ignored ok`, `gitkeep tracked ok`, `5`, `no profiles`, `no old references`.

- [ ] **Step 11: Commit**

```bash
git add deploy/full deploy/package.sh .dockerignore .gitignore
git status --short
git commit -m "feat(deploy): add the full package with prebuilt/source Dockerfiles and package.sh"
```

The moves and deletions of Task 1 Step 3 and Task 2 Step 9 were already staged by `git mv` / `git rm`. Expected before the commit: `git status --short` shows only this task's files, staged. `.run/Conduit (local all).run.xml` stays unstaged (` M`).

- [ ] **Step 12: Build the tarball and check its contents**

`package.sh` packs `HEAD`, so it runs after the commit. It takes a few minutes: a Maven package of every default module plus `npm ci` and the UI build.

```bash
deploy/package.sh
SHA=$(git rev-parse --short HEAD)
TGZ=target/conduit-full-$SHA.tar.gz
tar -tzf "$TGZ" | grep -vE "^deploy/full/artifacts/ui/." | sort
tar -tzf "$TGZ" | grep -c "^deploy/full/artifacts/ui/index.html$"
tar -tzf "$TGZ" | grep -E "(^|/)\.env$" || echo "no .env in package"
git worktree list
```

Expected:
- The script ends with `==> 已生成 …/target/conduit-full-<sha>.tar.gz`.
- The sorted listing contains at least these entries (directories may appear with a trailing `/`): `.dockerignore`, `conduit-ui/nginx.conf`, `deploy/package.sh`, `deploy/infra/docker-compose.yml`, `deploy/infra/.env.example`, `deploy/infra/README.md`, `deploy/infra/emqx/base.hocon.tpl`, `deploy/infra/emqx/acl.conf`, `deploy/full/docker-compose.yml`, `deploy/full/.env.example`, `deploy/full/README.md`, `deploy/full/docker/java.Dockerfile`, `deploy/full/docker/web.Dockerfile`, `deploy/full/artifacts/.gitkeep`, `deploy/full/artifacts/conduit-server.jar`, `deploy/full/artifacts/conduit-transport.jar`, `deploy/full/artifacts/conduit-simulator-exec.jar`, `deploy/full/artifacts/ui/`.
- `1` (the UI `index.html` is present).
- `no .env in package`.
- `git worktree list` shows only the main checkout (plus any pre-existing `.claude/worktrees/*`), so the temporary worktree was removed.

The tarball sits in the git-ignored `target/` and is not committed. If a check fails, fix the cause in a follow-up commit (`fix(deploy): …`) and rerun this step.

---

## Task 3: Docs sync (README, CLAUDE.md, backlog, device guide, spec status)

**Files:**
- Modify: `README.md`, `CLAUDE.md`, `docs/backlog-v0.2.md`, `docs/device-integration.md`, `docs/superpowers/specs/2026-10-16-conduit-deploy-packages-design.md`

**Interfaces:**
- Consumes: the paths, names and commands from Tasks 1–2 (`deploy/infra`, `deploy/full`, `deploy/package.sh`, `BUILD_FROM`, `target/conduit-full-<短提交>.tar.gz`).
- Produces: docs with no reference to `build-local.sh`, `sync-to-host.sh`, `deploy/docker-compose.yml`, `deploy/.env`, profiles `app` / `demo`, or the root / UI Dockerfiles. The exception is the dated history lines in CLAUDE.md §4 and in older specs and plans, which stay as written.

Every edit below is an exact replacement: find the old text and replace it with the new text.

- [ ] **Step 1: README — code structure tree**

Delete this line:

```
├── Dockerfile                       构建 Java 应用镜像（构建参数 MODULE 指定模块）
```

Replace

```
├── conduit-ui/                      Vue 3 + TypeScript + Element Plus（独立 npm 工程，自带 Dockerfile）
```

with

```
├── conduit-ui/                      Vue 3 + TypeScript + Element Plus（独立 npm 工程）
```

Replace

```
├── deploy/                          docker-compose.yml、.env.example、EMQX 鉴权与 ACL 配置
```

with

```
├── deploy/
│   ├── infra/                       基础服务包（开发用）：compose、.env.example、EMQX 鉴权与 ACL 配置
│   ├── full/                        全量包：extends 基础服务 + 应用服务，Dockerfile（预编译产物 / 源码两种来源）
│   └── package.sh                   开发机打全量包 target/conduit-full-<短提交>.tar.gz
├── .dockerignore                    全量包镜像的构建上下文（仓库根目录）排除规则
```

- [ ] **Step 2: README — rewrite the deployment section**

Replace everything from the heading `## 部署：基础设施与完整演示` down to and including the paragraph that begins `这是按需的完整部署入口`, which ends with `覆盖。`, with the block below. The paragraphs that follow it stay; Step 3 edits their first words.

````markdown
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
````

- [ ] **Step 3: README — the paragraphs after the section, the port table, the dev section, the panel section, logs**

Replace

```
完整部署起来后，浏览器打开 `http://<docker-host>:8090`
```

with

```
全量包启动后，浏览器打开 `http://<docker-host>:8090`
```

Replace

```
宿主机端口可在 `deploy/.env` 中配置，括号内为默认值：
```

with

```
宿主机端口在 `.env` 中配置（基础服务包 `deploy/infra/.env`，全量包 `deploy/full/.env`），括号内为默认值：
```

Replace

```
| `WEB_PORT`（8090） | Web（app profile） |
| `API_PORT`（8091） | REST API（app profile） |
```

with

```
| `WEB_PORT`（8090） | Web（全量包） |
| `API_PORT`（8091） | REST API（全量包） |
```

Replace the four numbered items of 「日常开发：基础设施在 Docker 宿主机，应用在 IDE」, from `1. 在 Docker 宿主机（或用 \`DOCKER_HOST\` 指向它）启动基础设施：` through `4. 完整的 Docker 演示部署只在需要时进行，用 \`deploy/build-local.sh\`（见上文「部署」）。`, with:

````markdown
1. 在 Docker 宿主机上启动基础服务包（见上文「基础服务包」）：把 `deploy/infra` 放到宿主机，在该目录中 `cp .env.example .env` 并修改，然后

   ```bash
   docker compose up -d
   ```

2. `deploy/infra/.env` 中的 `CONDUIT_AUTH_URL=http://<dev-host>:8080` 让 EMQX 的鉴权回调打到开发机上本地运行的 server；修改后执行 `docker compose up -d --force-recreate emqx`。同时确认 `BIND_ADDR`（默认 `0.0.0.0`）、`POSTGRES_PORT`（默认 15432）、`REDIS_PORT`（默认 16379）使这些端口对开发机开放。
3. 在 IDEA 中运行 `Conduit (local all)`（一次启动 Server、Transport、Simulator、UI）。首次需要先完成下面的 IDE 配置。
4. 完整的 Docker 演示部署只在需要时进行，用全量包（见上文「全量包」）。
````

Replace

```
如果同时还部署了完整的 Docker 应用栈（`app` profile），
```

with

```
如果同时还部署了全量包，
```

Replace the whole section from `## 在宿主机面板（1Panel 等）中管理编排` up to, but not including, `## 日志` with:

```markdown
## 在宿主机面板（1Panel 等）中管理编排

两个包都直接放在 Linux 宿主机的目录中运行，面板以该目录下的 `docker-compose.yml` 创建或导入编排即可（项目名 `conduit`）。全量包引用 `../infra/docker-compose.yml`，导入时保持 `deploy/infra` 与 `deploy/full` 的相对位置。

```

Replace

```
- Docker：三个服务设置 `CONDUIT_LOG_DIR=/var/log/conduit` 并共用命名卷 `conduit-logs`，
```

with

```
- Docker（全量包）：server、两个 transport 与模拟器设置 `CONDUIT_LOG_DIR=/var/log/conduit`（第二个 transport 为其 `transport-2` 子目录）并共用命名卷 `conduit-logs`，
```

Check:

```bash
grep -n "build-local\|sync-to-host\|deploy/docker-compose\|deploy/\.env\|profile app\|--profile\|app profile\|自带 Dockerfile" README.md || echo "README clean"
```

Expected: `README clean`. The IDEA lines about Spring `local` profiles do not match these patterns.

- [ ] **Step 4: Device guide**

In `docs/device-integration.md`, replace

```
docker compose -f deploy/docker-compose.yml exec redis \
  redis-cli XREVRANGE conduit:stream:dead + - COUNT 5
```

with

```
# 在宿主机的 deploy/infra（或 deploy/full）目录中执行
docker compose exec redis \
  redis-cli XREVRANGE conduit:stream:dead + - COUNT 5
```

- [ ] **Step 5: CLAUDE.md §1.1, §1.2, §2.2, §3**

§1.1, replace

```
| `deploy/` | Docker Compose（项目名 `conduit`）、`build-local.sh` |
```

with

```
| `deploy/` | 基础服务包 `infra/`（开发用）、全量包 `full/`（extends 基础服务 + 应用，预编译产物或源码构建）、`package.sh`（打全量包）；项目名均为 `conduit` |
```

§1.2 日志 bullet, replace

```
Docker 中 server、simulator 与两个 transport 容器共用命名卷 `conduit-logs`（`/var/log/conduit`），第二个 transport 写入其 `transport-2` 子目录。
```

with

```
全量包中 server、simulator 与两个 transport 容器共用命名卷 `conduit-logs`（`/var/log/conduit`），第二个 transport 写入其 `transport-2` 子目录。
```

§1.2, replace the whole bullet that begins `- **部署**：Compose 默认只起基础设施` (one line, ending `首次重建后 EMQX 数据目录为全新。`) with:

```markdown
- **部署**（部署包整理）：
  - 两个包都可直接放到 Linux 上 `docker compose up -d`，项目名均为 `conduit`、数据卷同名（`conduit_pg-data` 等），同一宿主机二选一，切换时数据沿用。
  - 基础服务包 `deploy/infra`：PostgreSQL、Redis、InfluxDB、InfluxDB Explorer、EMQX。EMQX 用官方镜像 `emqx/emqx:5.8.6`，`emqx/base.hocon.tpl` 与 `emqx/acl.conf` 只读挂载（需对 uid 1000 可读），启动命令用 `sed` 渲染回调地址与内部 token；EMQX 不依赖 conduit-server（自行重试鉴权回调）。`.env` 只含基础服务变量，`CONDUIT_AUTH_URL` 必填（开发机 `http://<dev-host>:8080`），`BIND_ADDR` 默认 `0.0.0.0`。
  - 全量包 `deploy/full`：以 `extends` 引用 infra 的 5 个服务（相对路径按 infra 文件解析；插值用全量包的 `.env` 并执行 infra 的必填检查，所以全量 `.env` 含 infra 的全部必填变量，`CONDUIT_AUTH_URL=http://conduit-server:8080`，EMQX 也以该值覆盖），数据卷在全量文件中同名重新声明。应用服务：conduit-server、两个 transport（`conduit-transport`、`conduit-transport-2`，YAML 锚点复用配置，`stop_grace_period: 20s`，后者日志在 `conduit-logs` 卷的 `transport-2` 子目录；不用 `deploy.replicas`，避免两个进程滚动同一个日志文件）、conduit-web、模拟器（默认启动，不用 profile）。
  - 镜像：构建上下文为仓库根目录，`deploy/full/docker/java.Dockerfile`（参数 `MODULE`、`JAR_NAME`，运行镜像 `eclipse-temurin:21.0.6_7-jre`）与 `web.Dockerfile`（`nginx:1.27.4-alpine`，`conduit-ui/nginx.conf`）。全局 `ARG BUILD_FROM=prebuilt`：阶段 `prebuilt`（`FROM scratch`）从 `deploy/full/artifacts/` 复制产物，阶段 `source` 用 `maven:3.9.9-eclipse-temurin-21` / `node:22.14.0-alpine` 从源码编译（需联网），`FROM ${BUILD_FROM} AS app` 选择，BuildKit 只构建选中的阶段；`.env` 中 `BUILD_FROM=prebuilt|source`。根 `.dockerignore` 排除 `.git`、`node_modules`、`target`、日志、IDE 与本地配置。
  - Docker 宿主机无外网：开发机执行 `deploy/package.sh`，从当前提交的干净检出（git worktree）编译，产物放入 `deploy/full/artifacts/`（jar 三个 + `ui/`），把 `deploy/`（不含 `.env`）、根 `.dockerignore` 与 `conduit-ui/nginx.conf` 打成 `target/conduit-full-<短提交>.tar.gz`，复制到宿主机解压后以 prebuilt 启动。
  - EMQX 磁盘持久会话写在 compose 环境变量中（`EMQX_DURABLE_SESSIONS__ENABLE=true`、`EMQX_CLUSTER__DISCOVERY_STRATEGY=singleton`、`EMQX_NODE__NAME=emqx@127.0.0.1`），不在 base.hocon；固定节点名的副作用：控制台里改过的密码会恢复为 `.env` 值，首次重建后 EMQX 数据目录为全新。
```

§2.2, replace

```
本地配置（`deploy/.env`、`application-local.yml`、`simulator-local.properties`）已在 `.gitignore`。
```

with

```
本地配置（`deploy/infra/.env`、`deploy/full/.env`、`application-local.yml`、`simulator-local.properties`）与全量包产物 `deploy/full/artifacts/` 已在 `.gitignore`。
```

§3, replace

```
开发模式是 Docker 宿主机只跑基础设施（`docker compose -f deploy/docker-compose.yml up -d`），server、transport、simulator、UI 在 IDEA 中运行（运行配置 `Conduit (local all)`），`deploy/.env` 的 `CONDUIT_AUTH_URL` 指向开发机。
```

with

```
开发模式是 Docker 宿主机只跑基础服务包（宿主机上 `deploy/infra` 目录中 `docker compose up -d`），server、transport、simulator、UI 在 IDEA 中运行（运行配置 `Conduit (local all)`），`deploy/infra/.env` 的 `CONDUIT_AUTH_URL` 指向开发机。
```

and in the same paragraph replace

```
只有所有者要求时才用 `deploy/build-local.sh` 部署完整应用栈。
```

with

```
只有所有者要求时才部署全量包（`deploy/package.sh` 打包后在宿主机启动）。
```

- [ ] **Step 6: CLAUDE.md §4 decision record**

Append this line at the end of §4, after the last `- 2026-10-16 V0.2-R：…` line:

```markdown
- 2026-10-16 部署包整理：`deploy/` 拆为基础服务包 `infra/`（EMQX 官方镜像 + 只读挂载配置，`CONDUIT_AUTH_URL` 必填，不依赖 server）与全量包 `full/`（extends infra，应用服务与模拟器默认启动，单一 `.env`）；项目名均为 `conduit`、数据卷不变；应用镜像构建上下文为仓库根，`ARG BUILD_FROM=prebuilt|source` + `FROM ${BUILD_FROM} AS app` 选择预编译产物或源码构建；`deploy/package.sh` 从干净检出打 `target/conduit-full-<短提交>.tar.gz`；删除根 `Dockerfile`、`conduit-ui/Dockerfile`、自定义 EMQX 镜像、`build-local.sh` 与 `sync-to-host.sh`（`2026-10-16-conduit-deploy-packages-design.md`）。
```

Check:

```bash
grep -n "build-local\|sync-to-host\|deploy/docker-compose\|deploy/\.env\|profile" CLAUDE.md
```

Expected: matches only in the dated §4 history lines (`2026-10-15 …`, the new `2026-10-16 部署包整理` line), in `e2e` / test-profile wording (`-Pe2e`, `测试 profile`), and in §3's `local` profile wording, if any. §1 and §3 have no `build-local`, `sync-to-host`, `deploy/docker-compose` or `deploy/.env`.

- [ ] **Step 7: Backlog**

In `docs/backlog-v0.2.md`, section 「后端：部署与镜像」, replace

```
- 后端 Dockerfile 无依赖缓存层，且以 root 运行
```

with

```
- 全量包 Java 镜像以 root 运行；`source` 阶段无 Maven 依赖缓存层（每次构建全量下载依赖）
- `BUILD_FROM=source` 需要宿主机联网（Maven 中央仓库、npm 源）；无外网时只能用 `prebuilt`
- 预编译前端的 MQTT 端口（`VITE_MQTT_PORT`）在打包时固定，宿主机改 `MQTT_PORT` 后「连接信息」显示的端口不变，需重新打包
- 基础服务包的 EMQX 配置为只读挂载，宿主机上的 `emqx/` 文件需对 uid 1000 可读；权限不对时 EMQX 启动失败
```

In section 「V0.2-A 遗留（评审后延后）」, delete the line (the files it refers to are gone):

```
- `Dockerfile` / `.dockerignore` 头部注释未提及放行的 `docs/examples` 文件
```

In section 「V0.2-R 遗留（2026-10-16）」, replace

```
- Compose 的 `conduit-transport-2` 从 YAML 锚点继承了 `build:`，`docker compose up --build` 会把同一镜像构建两次；`build-local.sh` 用 `--no-build`，不受影响。确认宿主机 compose 版本后用 `build: !reset` 修正。
```

with

```
- 全量包的 `conduit-transport-2` 从 YAML 锚点继承了 `build:`，`docker compose up --build` 为两个 transport 各构建一个内容相同的镜像（第二次命中 BuildKit 缓存，耗时很短）。可改为后者 `build: !reset null` 并共用前者的固定镜像名，需先确认 `up` 时不会在构建前去拉取该镜像。
```

- [ ] **Step 8: Spec status line**

In `docs/superpowers/specs/2026-10-16-conduit-deploy-packages-design.md`, replace

```
- 状态：已评审，待实现
```

with

```
- 状态：已实现（2026-10-16），验收见第 9 节
```

- [ ] **Step 9: Commit**

```bash
git add README.md CLAUDE.md docs/backlog-v0.2.md docs/device-integration.md docs/superpowers/specs/2026-10-16-conduit-deploy-packages-design.md
git commit -m "docs: describe the infra and full deploy packages"
```

---

## Task 4: Verification and acceptance on the Docker host (controller)

The controller runs this task on `main` after Tasks 1–3. It runs the full build and the e2e, then validates and starts both packages on the Docker host under a temporary project. Last, it hands the owner a short checklist for spec §8 item 5.

**Files:**
- Modify: `docs/superpowers/specs/2026-10-16-conduit-deploy-packages-design.md` (append `## 9. 验收记录（2026-10-16）`)

**Interfaces:**
- Consumes: everything from Tasks 1–3. Host facts: the image list in Global Constraints, the compose binary `/opt/conduit/bin/docker-compose`, and access only through the remote Docker API.
- Produces: green `mvn -B verify` and e2e runs, the host acceptance results for spec §8 items 1–4, the owner checklist for item 5, and the acceptance record.

**Temporary host resources** (all removed in Step 9):

| Item | Value |
|---|---|
| Project | `conduit-pkgtest` (the `-p` flag overrides `name: conduit`) |
| Host directory | `/tmp/conduit-pkgtest` (`pkg/` = extracted tarball, `src/` = source archive) |
| Copy container | `conduit-pkgtest-copy` |
| Host ports (`BIND_ADDR=127.0.0.1`) | `POSTGRES_PORT=25432`, `REDIS_PORT=26379`, `INFLUX_PORT=28096`, `INFLUX_EXPLORER_PORT=28888`, `MQTT_PORT=21883`, `EMQX_DASHBOARD_PORT=28083`, `WEB_PORT=28090`, `API_PORT=28091` |

- [ ] **Step 1: Full build and e2e**

```bash
mvn -B verify
mvn -B -DskipTests install
E2E_DEV_HOST=<dev-host> mvn -B verify -Pe2e -pl conduit-e2e
```

Expected: `BUILD SUCCESS` for both `verify` runs. The e2e reports `ReliabilityE2EIT` with 4 tests, 0 failures. This proves the e2e reads the template and ACL from `deploy/infra/emqx/`. If the e2e fails, look in `conduit-e2e/target/e2e-logs/<时间>/` first.

- [ ] **Step 2: Build the tarball, write the test `.env` files, set up the helpers**

```bash
deploy/package.sh
SHA=$(git rev-parse --short HEAD)
TGZ="$PWD/target/conduit-full-$SHA.tar.gz"
git archive --format=tar.gz -o "$SCRATCH/conduit-src.tar.gz" HEAD

r() { openssl rand -hex "$1"; }
cat > "$SCRATCH/pkgtest-infra.env" <<EOF
POSTGRES_PASSWORD=$(r 16)
INFLUX_TOKEN=apiv3_$(r 24)
INFLUX_EXPLORER_SESSION_KEY=$(r 24)
EMQX_DASHBOARD_PASSWORD=$(r 12)
CONDUIT_INTERNAL_TOKEN=$(r 24)
CONDUIT_AUTH_URL=http://127.0.0.1:9
BIND_ADDR=127.0.0.1
POSTGRES_PORT=25432
REDIS_PORT=26379
INFLUX_PORT=28096
INFLUX_EXPLORER_PORT=28888
MQTT_PORT=21883
EMQX_DASHBOARD_PORT=28083
EOF
ADMIN_PW="Pkg-$(r 8)-9"
{ grep -v '^CONDUIT_AUTH_URL=' "$SCRATCH/pkgtest-infra.env"
  cat <<EOF
CONDUIT_AUTH_URL=http://conduit-server:8080
BUILD_FROM=prebuilt
CONDUIT_ADMIN_USERNAME=admin
CONDUIT_ADMIN_PASSWORD=$ADMIN_PW
CONDUIT_PLATFORM_ADMIN_USERNAME=sysadmin
CONDUIT_PLATFORM_ADMIN_PASSWORD=Pkg-$(r 8)-9
CONDUIT_JWT_SECRET=$(r 32)
TRANSPORT_MQTT_USERNAME=conduit-transport
TRANSPORT_MQTT_PASSWORD=$(r 24)
PIPELINE_CLAIM_MIN_IDLE=60s
WEB_PORT=28090
API_PORT=28091
SIM_DEVICE_COUNT=5
SIM_INTERVAL_MS=5000
SIM_PAYLOAD_FORMAT=standard
SIM_PLATFORM_USERNAME=sysadmin
SIM_PLATFORM_PASSWORD=
SIM_TENANTS=*
SIM_MAX_DEVICES_PER_PRODUCT=100
EOF
} > "$SCRATCH/pkgtest-full.env"
sed 's/^BUILD_FROM=prebuilt$/BUILD_FROM=source/' "$SCRATCH/pkgtest-full.env" > "$SCRATCH/pkgtest-src.env"

export MSYS_NO_PATHCONV=1
T=/tmp/conduit-pkgtest
H=conduit-pkgtest-copy
# 复制本地文件到宿主机目录（经长驻的复制容器；docker cp 需要 Windows 路径）
hostcp() { $DOCKER_CLI cp "$(cygpath -w "$1")" "$H:$2"; }
# 在宿主机上执行 compose：<工作目录> <compose 参数…>；只挂载 /opt/conduit/bin（只读），不碰开发编排
hc() {
  local dir=$1; shift
  $DOCKER_CLI run --rm -v /var/run/docker.sock:/var/run/docker.sock -v $T:$T -v /opt/conduit/bin:/opt/cbin:ro \
    -w "$dir" eclipse-temurin:21.0.6_7-jre /opt/cbin/docker-compose -p conduit-pkgtest "$@"
}
```

Do not print the `.env` files. Record the dev project's state so Step 9 can show it is unchanged:

```bash
$DOCKER_CLI ps -a --filter label=com.docker.compose.project=conduit --format '{{.Names}} {{.Status}}' | sort > "$SCRATCH/dev-before.txt"
cat "$SCRATCH/dev-before.txt"
```

- [ ] **Step 3: Copy the tarball and the source archive to the host and extract them**

```bash
$DOCKER_CLI run -d --name $H -v $T:/dst --entrypoint sleep eclipse-temurin:21.0.6_7-jre 3600
hostcp "$TGZ" /dst/conduit-full.tar.gz
hostcp "$SCRATCH/conduit-src.tar.gz" /dst/conduit-src.tar.gz
$DOCKER_CLI exec $H sh -c 'mkdir -p /dst/pkg /dst/src && tar -xzf /dst/conduit-full.tar.gz -C /dst/pkg && tar -xzf /dst/conduit-src.tar.gz -C /dst/src'
hostcp "$SCRATCH/pkgtest-infra.env" /dst/pkg/deploy/infra/.env
hostcp "$SCRATCH/pkgtest-full.env" /dst/pkg/deploy/full/.env
hostcp "$SCRATCH/pkgtest-src.env" /dst/src/deploy/full/.env
$DOCKER_CLI exec $H sh -c 'chmod 600 /dst/pkg/deploy/infra/.env /dst/pkg/deploy/full/.env /dst/src/deploy/full/.env; ls -l /dst/pkg/deploy/infra/emqx /dst/pkg/deploy/full/artifacts; ls /dst/src/deploy/full/artifacts'
```

Expected: `base.hocon.tpl` and `acl.conf` are readable by all users (`-rw-r--r--`). The package artifacts list the three jars and `ui`. The source tree's `artifacts` holds only `.gitkeep`.

- [ ] **Step 4: Validate both compose files**

```bash
hc $T/pkg/deploy/infra config > "$SCRATCH/infra-config.yml"
hc $T/pkg/deploy/full config > "$SCRATCH/full-config.yml"
grep -n "source: $T/pkg/deploy/infra/emqx" "$SCRATCH/infra-config.yml" "$SCRATCH/full-config.yml"
grep -n "CONDUIT_AUTH_URL" "$SCRATCH/full-config.yml"
grep -n "EMQX_DURABLE_SESSIONS__ENABLE\|EMQX_CLUSTER__DISCOVERY_STRATEGY\|EMQX_NODE__NAME" "$SCRATCH/full-config.yml"
grep -nA3 "^  influxdb-explorer:" "$SCRATCH/full-config.yml" | head -5
grep -n "name: conduit-pkgtest_" "$SCRATCH/full-config.yml"
grep -n "BUILD_FROM\|dockerfile:" "$SCRATCH/full-config.yml"
```

Expected:
- Both `config` runs exit 0.
- Each file shows two bind sources: `…/pkg/deploy/infra/emqx/base.hocon.tpl` and `…/acl.conf`. The full config resolves them against the infra directory.
- `CONDUIT_AUTH_URL: http://conduit-server:8080`.
- The three EMQX durable values (`"true"`, `singleton`, `emqx@127.0.0.1`).
- `influxdb-explorer` has a `depends_on` on `influxdb`.
- Six volumes, `conduit-pkgtest_pg-data` … `conduit-pkgtest_conduit-logs`.
- `BUILD_FROM: prebuilt` with `dockerfile: deploy/full/docker/java.Dockerfile` / `web.Dockerfile`.

The configs contain the test secrets. Delete them once checked: `rm "$SCRATCH"/infra-config.yml "$SCRATCH"/full-config.yml`.

- [ ] **Step 5: Acceptance item 3 — the infrastructure package**

```bash
hc $T/pkg/deploy/infra up -d --wait --wait-timeout 300
hc $T/pkg/deploy/infra ps --format '{{.Service}} {{.Status}}'
$DOCKER_CLI exec conduit-pkgtest-emqx-1 /opt/emqx/bin/emqx ctl conf show durable_sessions | grep -n "enable"
$DOCKER_CLI exec conduit-pkgtest-emqx-1 sh -c 'grep -c "http://127.0.0.1:9/internal/mqtt/auth" /opt/emqx/etc/base.hocon; grep -c __CONDUIT_ /opt/emqx/etc/base.hocon; head -1 /opt/emqx/etc/conduit-acl.conf'
$DOCKER_CLI exec conduit-pkgtest-emqx-1 /opt/emqx/bin/emqx ctl status
hc $T/pkg/deploy/infra down -v
```

Expected:
- `up --wait` exits 0.
- `ps` lists 5 services: postgres, redis, influxdb and emqx `(healthy)`, influxdb-explorer `Up`.
- `enable = true` under `durable_sessions`.
- `1`, then `0`, then the ACL comment line `%% 设备只能发布自己的上行 topic…`.
- `Node 'emqx@127.0.0.1' … is started`.
- `down -v` removes the 5 containers and 5 `conduit-pkgtest_*` volumes.

- [ ] **Step 6: Acceptance item 1 — the full package from the tarball (`BUILD_FROM=prebuilt`)**

```bash
hc $T/pkg/deploy/full up -d --build --wait --wait-timeout 600
hc $T/pkg/deploy/full ps --format '{{.Service}} {{.Status}}'
for c in conduit-transport conduit-transport-2; do
  $DOCKER_CLI exec conduit-pkgtest-$c-1 bash -c 'exec 3<>/dev/tcp/127.0.0.1/8081 && printf "GET /actuator/health HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3 && tail -1 <&3'; echo
done
$DOCKER_CLI exec conduit-pkgtest-influxdb-1 sh -c 'for i in $(seq 1 30); do n=$(curl -fsS -H "Authorization: Bearer $INFLUX_TOKEN" --get http://localhost:8181/api/v3/query_sql --data-urlencode db=telemetry --data-urlencode format=csv --data-urlencode "q=SELECT count(*) AS n FROM property" 2>/dev/null | tail -1); case "$n" in ""|0|n) sleep 5;; *) echo "rows=$n"; exit 0;; esac; done; echo "no data"; exit 1'
$DOCKER_CLI logs conduit-pkgtest-simulator-1 2>&1 | grep -E "Simulating .* devices every" | tail -1
$DOCKER_CLI exec conduit-pkgtest-conduit-web-1 sh -c 'wget -qO- http://127.0.0.1/ | grep -c "<div id=\"app\">"'
$DOCKER_CLI exec -e PW="$ADMIN_PW" conduit-pkgtest-conduit-web-1 sh -c 'wget -qO- --header "Content-Type: application/json" --post-data "{\"username\":\"admin\",\"password\":\"$PW\"}" http://127.0.0.1/api/v1/auth/login | grep -c accessToken'
$DOCKER_CLI exec conduit-pkgtest-conduit-server-1 sh -c 'ls /var/log/conduit /var/log/conduit/transport-2'
$DOCKER_CLI image ls --format '{{.Repository}}' | grep '^conduit-pkgtest-' | sort
```

Expected:
- `up --build --wait` exits 0, and no step of the build pulls from a registry (the `prebuilt` stage is `FROM scratch`).
- `ps` shows 10 services: postgres, redis, influxdb, emqx and conduit-server `(healthy)`; the rest `Up`.
- One transport prints JSON with `"role":"LEADER"` and `"connected":true`, the other `"role":"STANDBY"`.
- `rows=<n>` with n > 0: simulator data reached InfluxDB.
- A `Simulating 5 devices every 5000 ms` line.
- `1` (the web serves `index.html`), and `1` (login through the web's `/api/` proxy returns `accessToken`).
- The log volume holds `conduit-server.log`, `conduit-transport.log` and `conduit-simulator.log`, and the `transport-2` directory holds its own `conduit-transport.log`.
- Five images: `conduit-pkgtest-conduit-server`, `-conduit-transport`, `-conduit-transport-2`, `-conduit-web`, `-simulator`.

Then:

```bash
hc $T/pkg/deploy/full down -v --rmi local
```

- [ ] **Step 7: Acceptance item 2 — build from source (`BUILD_FROM=source`)**

```bash
hc $T/src/deploy/full build conduit-server conduit-web 2>&1 | tail -30
```

Expected: one of two outcomes; record whichever happens.
- The build succeeds: two images `conduit-pkgtest-conduit-server` and `conduit-pkgtest-conduit-web`. This proves the `source` stages and `.dockerignore` give Maven and npm everything they need.
- The build fails inside `RUN mvn …` or `RUN npm ci …` with a dependency download or network error, because the host has no internet. Record this as the known limitation that spec §8 item 2 foresees. If it fails for any other reason (missing file in the context, wrong path, a stage error), that is a plan defect: stop and report.

Then clean up:

```bash
hc $T/src/deploy/full down -v --rmi local
$DOCKER_CLI image rm conduit-pkgtest-conduit-server conduit-pkgtest-conduit-web 2>/dev/null || true
```

- [ ] **Step 8: Remove the temporary host directory and the copy container**

```bash
$DOCKER_CLI exec $H sh -c 'rm -rf /dst/* /dst/.[!.]* 2>/dev/null; ls -A /dst | wc -l'
$DOCKER_CLI rm -f $H
$DOCKER_CLI run --rm -v /tmp:/hosttmp eclipse-temurin:21.0.6_7-jre rmdir /hosttmp/conduit-pkgtest
rm -f "$SCRATCH"/pkgtest-*.env "$SCRATCH/conduit-src.tar.gz"
```

Expected: `0`, then the container removed, then `rmdir` exits 0.

- [ ] **Step 9: Prove nothing is left and the dev project is untouched**

```bash
$DOCKER_CLI ps -a --filter label=com.docker.compose.project=conduit-pkgtest --format '{{.Names}}' | wc -l
$DOCKER_CLI volume ls -q --filter name=conduit-pkgtest | wc -l
$DOCKER_CLI image ls --format '{{.Repository}}' | grep -c '^conduit-pkgtest-' || true
$DOCKER_CLI network ls --format '{{.Name}}' | grep -c '^conduit-pkgtest' || true
$DOCKER_CLI ps -a --filter label=com.docker.compose.project=conduit --format '{{.Names}} {{.Status}}' | sort > "$SCRATCH/dev-after.txt"
diff <(cut -d' ' -f1 "$SCRATCH/dev-before.txt") <(cut -d' ' -f1 "$SCRATCH/dev-after.txt") && echo "dev project unchanged"
```

Expected: `0`, `0`, `0`, `0`, `dev project unchanged`. The dev containers' uptimes keep growing and they were never restarted.

- [ ] **Step 10: Owner checklist (acceptance item 5)**

Send the owner this checklist and wait for the reply:

```
部署包已验收（临时项目已清理）。请按下面步骤把开发基础设施切换到基础服务包：
1. 在宿主机上停止旧编排：在 /opt/conduit 目录执行 docker compose down（不要加 -v），或在 1Panel 中停止该编排。
2. 把仓库的 deploy/infra 放到宿主机（目录自选，例如 /opt/conduit-infra），把旧的 /opt/conduit/.env 复制为其中的 .env（多余变量会被忽略），确认 CONDUIT_AUTH_URL 指向开发机。
3. 在该目录执行 docker compose up -d，docker compose ps 中 4 个服务 healthy、Explorer 为 Up；docker volume ls 中没有新增卷（仍是 conduit_pg-data 等）。
4. 在 1Panel 中以新目录的 docker-compose.yml 重新导入编排（项目名 conduit）；旧镜像 conduit-emqx 可删除。
5. 开发机上本地的 deploy/.env 已不再使用，可移到 deploy/infra/.env 保存（已被 git 忽略）。
6. 在 IDEA 运行 Conduit (local all)：设备在线、历史曲线保留，transport 健康检查 role=LEADER、connected=true。完成后告诉我结果。
```

- [ ] **Step 11: Record the acceptance and commit**

Append to `docs/superpowers/specs/2026-10-16-conduit-deploy-packages-design.md`:

```markdown

## 9. 验收记录（2026-10-16）

- 全量测试：`mvn -B verify` 通过；e2e 四个场景通过（EMQX 模板与 ACL 读自 `deploy/infra/emqx/`）。
- 第 8 节（Docker 宿主机，临时项目 `conduit-pkgtest`、临时目录，结束后容器、数据卷、镜像与目录均已删除，开发项目 `conduit` 未受影响）：
  1. 从 `deploy/package.sh` 生成的包以 `BUILD_FROM=prebuilt` 启动全量包：10 个服务正常（有健康检查的均 healthy），两个 transport 一主一备，模拟器数据写入 InfluxDB，经 web 代理登录成功。`docker compose config` 显示 extends 后的 EMQX 挂载按 infra 目录解析。
  2. `BUILD_FROM=source`：<构建成功 | 因宿主机无外网下载依赖失败，记为已知限制>。
  3. 基础服务包单独启动：5 个服务正常，EMQX `durable_sessions.enable = true`、节点名 `emqx@127.0.0.1`，回调地址与内部 token 已渲染。
  4. `mvn -B verify` 与 e2e 通过（见上）。
  5. 所有者把 `deploy/infra` 放到宿主机，以项目名 `conduit` 重新启动开发基础设施，数据保留，本地 `Conduit (local all)` 正常。
```

In item 2, keep the one alternative that happened and delete the other, together with the angle brackets and the `|`. If any item failed, write what was observed instead of the passing wording. Fix the cause in a follow-up commit, rerun that item, and only then write the record. Write item 5 only after the owner confirms. If the owner has not confirmed yet, write `5. 待所有者切换开发基础设施后确认。` and amend the record in a later commit once they confirm.

```bash
git add docs/superpowers/specs/2026-10-16-conduit-deploy-packages-design.md
git commit -m "docs: record the deploy packages acceptance"
```

---

## Self-Review Notes

**Spec coverage** (spec section → task):
- §1 background, §2 goals → the whole plan. Infra `cp .env.example .env` + `up -d` → Task 1 Steps 6–8. Full `up -d --build` → Task 2 Steps 3–5. Both artifact sources → Task 2 Steps 1–2 (Dockerfiles) and Task 4 Steps 6–7.
- §3 layout:
  - `infra/` (compose, `.env.example`, `emqx/`, README) → Task 1.
  - `full/` (compose, `.env.example`, `docker/java.Dockerfile`, `docker/web.Dockerfile`, `artifacts/` with `.gitkeep`, README) → Task 2 Steps 1–5.
  - `package.sh` → Task 2 Step 6. `.dockerignore` → Task 2 Step 7.
- §4 infra:
  - Images, ports, health checks, volume names, the three EMQX durable env values → Task 1 Step 6 (copied from the V0.2-R compose).
  - Official image with read-only mounts and the `sed` render → Task 1 Step 6.
  - No `depends_on` on the server → Task 1 Step 6, checked in Step 9.
  - `CONDUIT_AUTH_URL` required in infra and fixed in full → Task 1 Step 6, Task 2 Steps 3–4.
  - Infra-only `.env.example` → Task 1 Step 7.
  - Project `conduit` with unchanged volumes → Task 1 Step 6, migration in the README (Task 1 Step 8, Task 3 Step 2).
- §5 full:
  - `extends` with paths relative to infra; only EMQX overrides `CONDUIT_AUTH_URL`; volumes redeclared → Task 2 Step 3, verified in Task 4 Step 4.
  - App services, anchor, `stop_grace_period`, `transport-2` log dir, env as in V0.2-R, simulator on by default → Task 2 Step 3.
  - Repo-root context; `ARG BUILD_FROM=prebuilt`; `prebuilt` / `source` stages; `FROM ${BUILD_FROM} AS app`; final `COPY --from=app`; the Java/nginx images; `MODULE` / `JAR_NAME`; `nginx.conf` from `conduit-ui/` → Task 2 Steps 1–2.
  - `BUILD_FROM` in `.env` → Task 2 Step 4.
  - `.dockerignore` rules → Task 2 Step 7.
  - `package.sh` steps 1–4 → Task 2 Step 6, checked in Step 12.
  - Run instructions → `deploy/full/README.md` (Task 2 Step 5) and the README (Task 3 Step 2).
- §6 deletions → Task 1 Step 3 (`deploy/emqx/`), Task 2 Step 9 (the rest), Task 2 Step 7 (`.dockerignore` rewritten).
- §7 other changes:
  - e2e paths → Task 1 Steps 1–5.
  - `.gitignore` → Task 2 Step 8.
  - README, CLAUDE.md (§1.1 deploy row, §1.2 部署 and 日志, §3, §4), backlog → Task 3. The device guide and the `TestBroker` comment were also stale and are fixed in Task 3 Step 4 and Task 1 Step 4.
- §8 acceptance:
  - Item 1 → Task 4 Step 6.
  - Item 2 → Task 4 Step 7.
  - Item 3 → Task 4 Step 5.
  - Item 4 → Task 4 Step 1.
  - Item 5 → Task 4 Step 10 (owner checklist).
  - Record → Task 4 Step 11.
- CLAUDE.md rules:
  - Chinese comments → Global Constraints, and every new file has a header.
  - No secrets → the `.env.example` placeholders, `<dev-host>` / `<docker-host>`, test envs only in `$SCRATCH` and `/tmp/conduit-pkgtest`.
  - Maintenance rule → Task 3 Steps 5–6.
  - Git rules → Global Constraints.

**Placeholder scan:** no "TBD", "TODO" or "similar to Task N". Every file is given in full or as an exact replacement. The only execution-time choice is the two-way alternative in the Task 4 Step 11 record (item 2), with an explicit instruction for each outcome. Operator values appear only as the placeholders in the Global Constraints table. `<短提交>`, `<dev-host>`, `<docker-host>` and `<仓库地址>` in docs and READMEs are user-facing placeholders, not plan gaps.

**Name consistency:**
- Services: `postgres`, `redis`, `influxdb`, `influxdb-explorer`, `emqx`. They are identical in the infra file (Task 1 Step 6), in the `extends` service keys (Task 2 Step 3) and in the container names used in Task 4 (`conduit-pkgtest-emqx-1`, `conduit-pkgtest-influxdb-1`).
- App services `conduit-server`, `conduit-transport`, `conduit-transport-2`, `conduit-web`, `simulator` match the Task 4 container and image names (`conduit-pkgtest-<service>-1`, `conduit-pkgtest-<service>`).
- Build args: `BUILD_FROM`, `MODULE`, `JAR_NAME` (Java) and `BUILD_FROM`, `VITE_MQTT_PORT` (web) match between the Dockerfiles and the compose `args`.
- `JAR_NAME` values `conduit-server`, `conduit-transport`, `conduit-simulator-exec` match the files that `package.sh` copies into `artifacts/`, and the module `target/` names (`finalName ${project.artifactId}`, simulator classifier `exec`).
- The artifacts layout `deploy/full/artifacts/{conduit-server.jar, conduit-transport.jar, conduit-simulator-exec.jar, ui/}` is the same in `package.sh`, both `prebuilt` stages, `.gitignore`, the Task 2 Step 12 check and the Task 4 Step 3 check.
- Volumes `pg-data`, `redis-data`, `influx3-data`, `influx-explorer-data`, `emqx-data` (+ `conduit-logs` in full) are the same in both files and match the V0.2-R names.
- Template and ACL paths `deploy/infra/emqx/base.hocon.tpl` / `acl.conf` are the same in the compose mounts, `E2eEnvironment`, `EmqxConfigTest` and the docs.

**Resolved ambiguities:**
- **`CONDUIT_AUTH_URL` in full:** the controller probe showed that the infra file's `${CONDUIT_AUTH_URL:?}` is enforced in the full project. So `full/.env.example` sets `CONDUIT_AUTH_URL=http://conduit-server:8080`, and the full EMQX service also overrides it with the same literal. The value stays fixed even if someone edits the `.env` line.
- **`influxdb-explorer` `depends_on`** is redeclared in the full file, so the dependency holds whether or not `extends` carries `depends_on`.
- **`BIND_ADDR` defaults:** infra `.env.example` uses `0.0.0.0`, because its apps run on another machine by design and the spec's "change passwords and `CONDUIT_AUTH_URL`" should be enough. Full keeps `127.0.0.1`, as before.
- **`prebuilt` stage base** is `FROM scratch`, so it pulls nothing and needs no image on the offline host.
- **Java `source` stage** copies only `pom.xml`, the four default Java modules and `docs/examples`, then builds `-pl ${MODULE} -am`. The web `source` stage installs dependencies before copying the sources.
- **Where `package.sh` puts the artifacts:** inside the temporary clean worktree's `deploy/full/artifacts/`, which then goes into the tarball. The developer's checkout stays untouched. The tarball is written through stdout, because GNU tar in Git Bash reads `C:` in `-f C:/…` as a remote host.
- **Web artifacts dir** is named `ui/`, not `dist/`, so the global `dist/` ignore and the `conduit-ui/dist` docker-ignore never match it. The prebuilt UI's MQTT port is fixed at package time (`VITE_MQTT_PORT`, default 1883); this is in the backlog.
- **`.dockerignore`:** `deploy/full/artifacts/` is not under any `target/`, so the spec's "放行 artifacts" is a safety re-include placed last. `.env*` files are excluded, so `.env.example` is not in the context either; no stage needs it.
- **Image names:** the full compose sets no `image:`. Compose names images `<project>-<service>`, so the acceptance project's images (`conduit-pkgtest-*`) never collide with the dev ones and `down --rmi local` removes them. `conduit-transport-2` builds its own identical image from the BuildKit cache, instead of `build: !reset`. The backlog entry is updated.
- **Infra validation without the owner:** Task 4 Step 5 runs the infra package under `conduit-pkgtest` with a dummy `CONDUIT_AUTH_URL`, and checks health, durable sessions and the rendered config. The real switch of the dev infrastructure is the owner checklist (Step 10).
- **Source build acceptance** uses a `git archive HEAD` copy on the host, because the tarball has no sources. A dependency-download failure is recorded as the known limitation of spec §8 item 2; any other failure stops the task.
- **History lines** in CLAUDE.md §4 and in older specs and plans keep their references to `build-local.sh` and the profiles. They are dated records, not current descriptions.
