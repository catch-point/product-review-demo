# syntax=docker/dockerfile:1
# Build from the repository root (it reuses the storage project's Maven wrapper):
#   docker build -f storage/admin/Dockerfile -t reviews-admin .

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace/storage
COPY storage/.mvn/ .mvn/
COPY storage/mvnw ./
COPY storage/admin/pom.xml admin/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B -f admin/pom.xml dependency:go-offline
COPY storage/admin/src/ admin/src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B -f admin/pom.xml -DskipTests package \
 && java -Djarmode=tools -jar admin/target/admin-*.jar extract --layers --launcher --destination admin/target/extracted

FROM eclipse-temurin:25-jre
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --no-create-home admin
WORKDIR /app
COPY --from=build /workspace/storage/admin/target/extracted/dependencies/ ./
COPY --from=build /workspace/storage/admin/target/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/storage/admin/target/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/storage/admin/target/extracted/application/ ./
USER 10001
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --start-period=60s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
