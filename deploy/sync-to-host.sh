#!/usr/bin/env bash
# 把编排目录（docker-compose.yml、.env、emqx/）同步到 Docker 宿主机上的固定目录，
# 让宿主机上的面板（如 1Panel）能找到并管理 conduit 编排。
#
# 原理：Docker 宿主机只开放了远程 API，没有 SSH，所以借一个临时容器挂载宿主机目录，再用 docker cp 写入。
# 宿主机上需要已有基础镜像 eclipse-temurin:21.0.6_7-jre（不需要外网）。
#
# 用法（在仓库根目录）：
#   deploy/sync-to-host.sh                 # 同步到 /opt/conduit
#   CONDUIT_HOST_DIR=/srv/conduit deploy/sync-to-host.sh
# 环境变量：DOCKER_HOST（远程宿主机时设置）、DOCKER（docker 命令，默认 docker）、CONDUIT_HOST_DIR（默认 /opt/conduit）
#
# 注意：仓库里的 deploy/ 仍是唯一来源；修改编排后重新执行本脚本同步。
# .env 含口令，会一起复制到宿主机目录（权限 600）。
set -euo pipefail

DOCKER=${DOCKER:-docker}
HOST_DIR=${CONDUIT_HOST_DIR:-/opt/conduit}
ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"
[[ -f deploy/.env ]] || { echo "缺少 deploy/.env" >&2; exit 1; }

NAME=conduit-sync-$$
cleanup() { $DOCKER rm -f "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT

# Git Bash 下禁止把 /opt/... 改写成 Windows 路径
export MSYS_NO_PATHCONV=1

$DOCKER run -d --name "$NAME" -v "$HOST_DIR:/dst" --entrypoint sleep eclipse-temurin:21.0.6_7-jre 300 >/dev/null
$DOCKER exec "$NAME" sh -c 'rm -rf /dst/emqx && mkdir -p /dst'
$DOCKER cp deploy/docker-compose.yml "$NAME:/dst/docker-compose.yml"
$DOCKER cp deploy/.env "$NAME:/dst/.env"
$DOCKER cp deploy/emqx "$NAME:/dst/emqx"
$DOCKER exec "$NAME" sh -c 'chmod 600 /dst/.env && ls -la /dst'
echo "==> 已同步到宿主机 $HOST_DIR"
