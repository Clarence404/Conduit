# 构建上下文为仓库根目录；.dockerignore 只放行 pom.xml 与 conduit-* Java 模块（不含 conduit-ui）
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
# 只构建 reactor 默认模块；不激活 e2e 等 profile，测试由 CI 负责
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21.0.6_7-jre
ARG MODULE
ARG JAR_NAME=${MODULE}
WORKDIR /app
COPY --from=build /src/${MODULE}/target/${JAR_NAME}.jar app.jar
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
