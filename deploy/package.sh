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
# 先写临时文件再改名，打包失败时不留下看似完整的包；属主记为 0，避免 Linux 上解压出 Windows 的 uid
(cd "$SRC" && tar --owner=0 --group=0 --numeric-owner -czf - deploy .dockerignore conduit-ui/nginx.conf) > "$OUT.tmp"
mv "$OUT.tmp" "$OUT"
echo "==> 已生成 $OUT"
