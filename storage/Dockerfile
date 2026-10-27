# syntax=docker/dockerfile:1
# Build from the repository root:
#   docker build -f storage/Dockerfile -t reviews-storage .

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace/storage
COPY storage/.mvn/ .mvn/
COPY storage/mvnw storage/pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B dependency:go-offline
COPY storage/src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B -DskipTests package \
 && java -Djarmode=tools -jar target/storage-*.jar extract --layers --launcher --destination target/extracted

FROM eclipse-temurin:25-jre
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --no-create-home storage
WORKDIR /app
COPY --from=build /workspace/storage/target/extracted/dependencies/ ./
COPY --from=build /workspace/storage/target/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/storage/target/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/storage/target/extracted/application/ ./
USER 10001
# 8080: the API (with /livez and /readyz). 8081: actuator, for Spring Boot Admin.
EXPOSE 8080 8081
HEALTHCHECK --interval=5s --timeout=3s --start-period=60s --retries=3 \
  CMD curl -fsS http://localhost:8080/readyz || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
