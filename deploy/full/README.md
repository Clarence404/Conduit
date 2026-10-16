# Conduit 全量包

基础服务（PostgreSQL、Redis、InfluxDB 3、InfluxDB 3 Explorer、EMQX，定义在 `../infra/docker-compose.yml`，以 `extends` 引用）+ conduit-server、两个 transport（一主一备）、conduit-web 与演示模拟器。需要 Docker 与 Docker Compose v2。

## 方式一：预编译产物（宿主机无外网）

在开发机上（需 git、JDK 21 上的 Maven、Node.js 22）：

```bash
deploy/package.sh       # 生成 target/conduit-full-<短提交>.tar.gz
```

把包复制到 Linux 宿主机后：

```bash
mkdir -p conduit && tar -xzf conduit-full-<短提交>.tar.gz -C conduit
cd conduit/deploy/full
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
- 升级：先删除旧的 `deploy/full/artifacts/`，再把新包解压到同一目录（`.env` 不在包内，会保留），然后 `docker compose up -d --build`。
- 更换 InfluxDB token：token 在首次启动时写入库中，之后要改需 `docker compose down`、`docker volume rm conduit_influx3-data`（会丢失时序数据）再启动。
- 预编译前端里「连接信息」显示的 MQTT 端口在打包时确定（`VITE_MQTT_PORT`，默认 1883）；宿主机 `MQTT_PORT` 不是 1883 时，打包前设置 `VITE_MQTT_PORT`。
- 日志在命名卷 `conduit_conduit-logs`（第二个 transport 在其 `transport-2` 子目录）。
