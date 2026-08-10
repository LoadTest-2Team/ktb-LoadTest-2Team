# syntax=docker/dockerfile:1.7

# 자주 바뀌지 않는 빌드 도구와 의존성을 먼저 캐싱
FROM maven:3.9.11-eclipse-temurin-25 AS dependencies
WORKDIR /workspace
COPY pom.xml .

## maven 의존성 다운로드
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp dependency:go-offline


FROM dependencies AS builder
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipTests package

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

# 애플리케이션은 권한이 제한된 전용 사용자로 실행한다.
RUN groupadd --system --gid 10001 app && \
    useradd --system --uid 10001 --gid app --home-dir /app --shell /usr/sbin/nologin app && \
    mkdir -p /app/uploads && \
    chown app:app /app/uploads

# 빌드 스테이지의 실행 가능한 JAR만 런타임 이미지에 포함한다.
COPY --from=builder --chown=app:app /workspace/target/*.jar /app/app.jar

USER app

EXPOSE 5001 5002
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=85.0", "-jar", "app.jar"]
CMD []