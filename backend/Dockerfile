# syntax=docker/dockerfile:1
# 构建：Maven + JDK 17
FROM maven:3.9-eclipse-temurin-17 AS build
# 可选：国内 Maven 镜像，例如 https://maven.aliyun.com/repository/public
ARG MAVEN_MIRROR_URL=""
WORKDIR /app
RUN mkdir -p /root/.m2 \
    && if [ -n "$MAVEN_MIRROR_URL" ]; then \
         printf '%s\n' \
           '<settings>' \
           '  <mirrors>' \
           '    <mirror>' \
           '      <id>mirror</id>' \
           '      <mirrorOf>central</mirrorOf>' \
           "      <url>${MAVEN_MIRROR_URL}</url>" \
           '    </mirror>' \
           '  </mirrors>' \
           '</settings>' > /root/.m2/settings.xml; \
       fi
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline || true
COPY src ./src
# Lombok 配置必须进入构建上下文，否则 @Qualifier 不会复制到构造器参数（多 TaskExecutor 启动失败）
COPY lombok.config .
RUN mvn -q -DskipTests package

# 运行：JRE + Python/Node（技能脚本）+ curl（健康检查）
# 用 Debian 多架构基础镜像：eclipse-temurin:17-jre-alpine 无 arm64 manifest，Apple Silicon 无法构建
FROM node:22-bookworm-slim
RUN apt-get update \
    && apt-get install -y --no-install-recommends openjdk-17-jre-headless python3 curl tzdata \
    && rm -rf /var/lib/apt/lists/*
ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-Xms512m -Xmx1g"
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
RUN chown -R node:node /app
USER node
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=10 \
  CMD curl -fsS http://127.0.0.1:8080/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
