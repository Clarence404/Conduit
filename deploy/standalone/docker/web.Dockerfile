# 前端镜像（nginx 托管构建好的 Vue 应用，并把 /api/ 反向代理到 conduit-server），构建上下文为仓库根目录。
# 构建参数：
#   BUILD_FROM      产物来源：prebuilt（默认，复制 deploy/standalone/artifacts/ui/）或 source（在镜像内 npm ci + npm run build，需联网）
#   VITE_MQTT_PORT  「连接信息」中显示的 MQTT 端口（source 用；prebuilt 的端口在打包时已固定）
ARG BUILD_FROM=prebuilt

# 预编译产物：deploy/package.sh 把 conduit-ui/dist 的内容放到 deploy/standalone/artifacts/ui/
FROM scratch AS prebuilt
COPY deploy/standalone/artifacts/ui /out/html

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
