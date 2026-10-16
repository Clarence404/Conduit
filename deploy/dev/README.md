# Conduit 开发环境包（deploy/dev）

PostgreSQL 16.8、Redis 7.4.2、InfluxDB 3.12.0-core、InfluxDB 3 Explorer 1.11.0、EMQX 5.8.6（磁盘持久会话）。应用在开发机的 IDE 中运行（见仓库 README「开发」）。

## 启动

把本目录复制或 clone 到 Linux 宿主机（需 Docker 与 Docker Compose v2），在本目录中：

```bash
cp .env.example .env    # 替换全部 change-me-* 口令，CONDUIT_AUTH_URL 改为 http://<dev-host>:8080
docker compose up -d
docker compose ps       # 5 个服务均为 running，其中 4 个 healthy（Explorer 无健康检查）
```

- 项目名 `conduit`，数据卷 `conduit_pg-data`、`conduit_redis-data`、`conduit_influx3-data`、`conduit_influx-explorer-data`、`conduit_emqx-data`。与独立部署包 `deploy/standalone` 的项目名和数据卷相同，同一台宿主机上二选一。
- `CONDUIT_AUTH_URL` 必填：EMQX 把设备与 transport 的连接鉴权回调到这个地址上的 conduit-server。修改后执行 `docker compose up -d --force-recreate emqx`。
- `CONDUIT_INTERNAL_TOKEN` 必须与开发机上 server 的 `conduit.internal.token` 一致。
- `emqx/base.hocon.tpl` 与 `emqx/acl.conf` 以只读方式挂载进 EMQX 容器，需对容器内的 emqx 用户（uid 1000）可读（普通 644 权限即可）。

## 从旧的 deploy/docker-compose.yml 迁移

项目名与数据卷不变，数据沿用：

1. 在旧编排目录执行 `docker compose down`（不要加 `-v`）。
2. 把旧 `.env` 复制为本目录的 `.env`（多余的变量会被忽略），确认 `CONDUIT_AUTH_URL` 指向开发机。
3. 在本目录执行 `docker compose up -d`。
4. 旧的自定义 EMQX 镜像 `conduit-emqx` 不再使用，可以删除。
