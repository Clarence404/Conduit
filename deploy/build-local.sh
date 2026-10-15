#!/usr/bin/env bash
# 在本机构建 jar 与前端，再在 Docker 宿主机上只打"复制产物"的薄镜像并启动演示环境。
# 适用于 Docker 宿主机无法访问外网的情况：镜像内不再执行 mvn / npm ci，宿主机只需已有基础镜像
#（eclipse-temurin:21.0.6_7-jre、nginx:1.27.4-alpine、emqx/emqx:5.8.6 及 compose 里的其余镜像）。
#
# 产物来自当前提交（HEAD）的干净检出，未提交的修改和 .gitignore 中的本地配置
#（application-local.yml、simulator-local.properties 等）不会进入镜像。
#
# 用法（在仓库根目录）：
#   deploy/build-local.sh            # 构建并启动（含模拟器，即 --profile demo）
#   deploy/build-local.sh --no-up    # 只构建镜像
# 环境变量：
#   DOCKER_HOST  远程宿主机时设置，例如 tcp://<docker-host>:2375
#   DOCKER       docker 命令（默认 docker）
#   COMPOSE      compose 命令（默认 "docker compose"）
# 需要：git、JDK 21 上的 mvn、Node.js 22 / npm。
set -euo pipefail

DOCKER=${DOCKER:-docker}
COMPOSE=${COMPOSE:-"docker compose"}
UP=1
[[ "${1:-}" == "--no-up" ]] && UP=0

ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"
COMPOSE_FILE=deploy/docker-compose.yml
[[ -f deploy/.env ]] || { echo "缺少 deploy/.env，先执行 cp deploy/.env.example deploy/.env 并修改口令" >&2; exit 1; }

if ! mvn -v 2>/dev/null | grep -q "Java version: 21"; then
  echo "mvn 需要运行在 JDK 21 上（设置 JAVA_HOME 指向 JDK 21）" >&2
  exit 1
fi

WORK=$(mktemp -d)
SRC="$WORK/src"
cleanup() {
  git -C "$ROOT" worktree remove --force "$SRC" >/dev/null 2>&1 || rm -rf "$SRC"
  git -C "$ROOT" worktree prune
  rm -rf "$WORK"
}
trap cleanup EXIT

echo "==> 干净检出 $(git rev-parse --short HEAD)"
git worktree add --detach --quiet "$SRC" HEAD

echo "==> 构建后端 jar"
(cd "$SRC" && mvn -B -q -DskipTests package)

MQTT_PORT=$(grep -E '^MQTT_PORT=' deploy/.env | cut -d= -f2- || true)
echo "==> 构建前端（VITE_MQTT_PORT=${MQTT_PORT:-1883}）"
(cd "$SRC/conduit-ui" && npm ci --no-audit --no-fund --loglevel=error && VITE_MQTT_PORT=${MQTT_PORT:-1883} npm run build)

# 镜像名与 compose 默认命名一致（项目名 conduit），up --no-build 会直接使用它们
build_java() { # <镜像名> <jar 路径>
  local ctx="$WORK/img-$1"
  mkdir -p "$ctx"
  cp "$SRC/$2" "$ctx/app.jar"
  cat > "$ctx/Dockerfile" <<'EOF'
FROM eclipse-temurin:21.0.6_7-jre
WORKDIR /app
COPY app.jar app.jar
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
EOF
  echo "==> 镜像 $1"
  $DOCKER build -q -t "$1:latest" "$ctx" >/dev/null
}
build_java conduit-conduit-server conduit-server/target/conduit-server.jar
build_java conduit-conduit-transport conduit-transport/target/conduit-transport.jar
build_java conduit-simulator conduit-simulator/target/conduit-simulator-exec.jar

WEB="$WORK/img-web"
mkdir -p "$WEB"
cp -r "$SRC/conduit-ui/dist" "$WEB/dist"
cp "$SRC/conduit-ui/nginx.conf" "$WEB/nginx.conf"
cat > "$WEB/Dockerfile" <<'EOF'
FROM nginx:1.27.4-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY dist /usr/share/nginx/html
EOF
echo "==> 镜像 conduit-conduit-web"
$DOCKER build -q -t conduit-conduit-web:latest "$WEB" >/dev/null

# EMQX 镜像只在官方镜像上复制配置文件，不需要外网
echo "==> 镜像 conduit-emqx"
$COMPOSE -f "$COMPOSE_FILE" build -q emqx

if (( UP )); then
  echo "==> 启动"
  $COMPOSE -f "$COMPOSE_FILE" --profile demo up -d --no-build
  $COMPOSE -f "$COMPOSE_FILE" --profile demo ps
fi
