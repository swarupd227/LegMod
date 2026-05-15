# syntax=docker/dockerfile:1.7
#
# Atlas Migrate · gen-service
# JAX-WS code generation
#
# Hardened runtime: non-root user (uid 10001), OpenContainers labels,
# JVM container-awareness with a 75% heap cap, and a healthcheck wired
# at /actuator/health so orchestrators can treat readiness uniformly.

ARG ATLAS_VERSION=0.1.0

# -------- build stage --------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

# -------- runtime stage --------
FROM eclipse-temurin:21-jre-alpine

ARG ATLAS_VERSION
LABEL org.opencontainers.image.title="atlas-gen-service" \
      org.opencontainers.image.description="Atlas Migrate · JAX-WS code generation" \
      org.opencontainers.image.vendor="Envestnet" \
      org.opencontainers.image.source="https://github.com/envestnet/atlas-migrate" \
      org.opencontainers.image.licenses="Proprietary" \
      org.opencontainers.image.version="${ATLAS_VERSION}"

# Non-root runtime user. uid/gid 10001 sits outside the OS-managed
# range so it never collides with packages installed by the base image.
RUN addgroup -S -g 10001 atlas && adduser -S -u 10001 -G atlas atlas

WORKDIR /app
COPY --from=build --chown=atlas:atlas /app/target/*.jar app.jar
USER atlas:atlas

# Java 21 enables container-aware heap sizing by default. The explicit
# MaxRAMPercentage caps the JVM at 75% of the cgroup limit so GC +
# native memory have room. Override via JAVA_TOOL_OPTIONS per-deployment.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8087

# Spring Boot Actuator exposes /actuator/health on every service. Use
# busybox wget (ships with the alpine JRE) — curl isn't installed.
# Generous start-period because cold Spring Boot startup is ~30s.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:8087/actuator/health | grep -q '"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]