# syntax=docker/dockerfile:1
# 构建：Maven + JDK 17
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline || true
COPY src ./src
RUN mvn -q -DskipTests package

# 运行：JRE + Python/Node（技能脚本）+ curl（健康检查）
FROM eclipse-temurin:17-jre-alpine
RUN apk add --no-cache curl python3 nodejs tzdata \
    && addgroup -g 1000 -S app && adduser -u 1000 -S app -G app
ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-Xms512m -Xmx1g"
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
RUN chown -R app:app /app
USER app
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=10 \
  CMD curl -fsS http://127.0.0.1:8080/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
