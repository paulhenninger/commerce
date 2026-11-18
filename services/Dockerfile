# One Dockerfile for every JVM service:  docker build -f services/Dockerfile --build-arg SERVICE=order .
#
# The build stage compiles every service once; it is identical whatever SERVICE
# is, so building ten images compiles the code a single time. Each service then
# runs on a slim Alpine JRE as an unprivileged user.
#
# Every base image is pinned to a version AND a digest: a re-pushed tag can't
# change what is built on. Dependabot proposes new pins; CI scans each image
# (Trivy). Java stays on its LTS line (25): CI fails if this file, build.gradle
# and ci.yml disagree.

FROM eclipse-temurin:25.0.5_8-jdk@sha256:83f4a7fc27df94dcc7b8de04d14cbbba8da14406f921e966969c3b09000aa507 AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
COPY platform ./platform
COPY services ./services
# Strip CRs in case of a Windows checkout (see .gitattributes). Dependencies are
# kept in a build cache between builds rather than baked into a layer.
RUN --mount=type=cache,target=/root/.gradle \
    sed -i 's/\r$//' gradlew && chmod +x gradlew && ./gradlew --no-daemon -q bootJar

FROM build AS extract
ARG SERVICE
# Unpack into Spring Boot's layers: a code-only release ships a small layer, not the whole jar.
RUN java -Djarmode=tools -jar services/${SERVICE}/build/libs/${SERVICE}.jar extract --layers --launcher --destination /extracted

FROM eclipse-temurin:25.0.5_8-jre-alpine@sha256:e1898fd6a11fc965a994d8ace186fd3585aeb636cf8f05f47a723fcfee0b0918
# Alpine security fixes published since the base image was built, then a
# system user with no shell and no home. Nothing else is installed: the
# healthcheck uses busybox wget, which Alpine has built in.
RUN apk upgrade --no-cache \
 && addgroup -S -g 10001 commerce \
 && adduser -S -u 10001 -G commerce -H -s /sbin/nologin commerce  && install -d -o 10001 -g 10001 -m 700 /var/lib/commerce
# /var/lib/commerce: state for a service that mounts a volume there (only the
# DEV controller does: its experiment runs and recovery ledger).
WORKDIR /app
# Root-owned and read-only to the app: it needs to read its code, never write it.
COPY --from=extract /extracted/dependencies/ ./
COPY --from=extract /extracted/spring-boot-loader/ ./
COPY --from=extract /extracted/snapshot-dependencies/ ./
COPY --from=extract /extracted/application/ ./
USER 10001:10001
# 8080 = API (reached only through an edge). 9091 = management (health,
# Prometheus): on the telemetry network only.
EXPOSE 8080 9091
# Readiness, not liveness: Compose's depends_on and the edges should wait for a
# service that can actually serve (its database reachable, migrations done).
HEALTHCHECK --interval=10s --timeout=3s --start-period=90s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:9091/actuator/health/readiness || exit 1
# Sized for a small container: the heap gets 60% of the memory limit, the rest is
# for metaspace, threads and buffers. Out of memory means exit and restart, not
# limp on.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-XX:+UseSerialGC", "-XX:+ExitOnOutOfMemoryError", \
            "-Djava.io.tmpdir=/tmp", "org.springframework.boot.loader.launch.JarLauncher"]
