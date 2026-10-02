# syntax=docker/dockerfile:1
# Stage 1: build. Dependencies are resolved in their own layer so a source-only change reuses the
# cached ~200MB of jars instead of re-downloading them.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src src
RUN mvn -q -B -DskipTests package && mv target/risk-engine-*.jar app.jar

# Stage 2: runtime. JRE-only image, non-root user, nothing but the jar -- no compiler and no Maven,
# a fraction of the build image's size and attack surface.
# glibc-based (Ubuntu) rather than Alpine/musl: the ONNX Runtime JNI library ships glibc-built only.
FROM eclipse-temurin:17-jre-jammy
# The base image is rebuilt less often than Ubuntu publishes security patches, so they are applied here.
RUN apt-get update && apt-get upgrade -y && rm -rf /var/lib/apt/lists/*
RUN groupadd -r app && useradd -r -g app -M -s /usr/sbin/nologin app && mkdir -p /data/attachments && chown app:app /data/attachments
WORKDIR /app
COPY --from=build /build/app.jar app.jar
USER app
ENV ATTACHMENTS_STORAGE_DIR=/data/attachments
# Container-aware heap: 60% of the cgroup limit for the Java heap leaves the rest for metaspace,
# thread stacks and the native ONNX Runtime allocations, which live outside the heap.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
