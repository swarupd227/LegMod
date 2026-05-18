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
# Use the JDK image (not JRE) so the Stage F Build & Test gate can
# invoke javac/maven via subprocess against the generated/migrated
# code. Alpine + Maven is awkward (musl-incompatible deps), so we
# stay on the Temurin Debian-slim variant and install Maven from APT.
FROM eclipse-temurin:21-jdk-jammy

ARG ATLAS_VERSION
LABEL org.opencontainers.image.title="atlas-gen-service" \
      org.opencontainers.image.description="Atlas Migrate · JAX-WS code generation" \
      org.opencontainers.image.vendor="Envestnet" \
      org.opencontainers.image.source="https://github.com/envestnet/atlas-migrate" \
      org.opencontainers.image.licenses="Proprietary" \
      org.opencontainers.image.version="${ATLAS_VERSION}"

# Maven for the Stage F Build & Test runner. We also install
# ca-certificates so HTTPS clones / dependency fetches work cleanly.
# --no-install-recommends keeps the image lean.
RUN apt-get update \
 && apt-get install -y --no-install-recommends maven ca-certificates wget \
 && rm -rf /var/lib/apt/lists/*

# Non-root runtime user. uid/gid 10001 sits outside the OS-managed
# range so it never collides with packages installed by the base image.
RUN groupadd -g 10001 atlas && useradd -u 10001 -g atlas -m -s /bin/bash atlas

# Install git so we can clone reference projects at build time.
RUN apt-get update \
 && apt-get install -y --no-install-recommends git \
 && rm -rf /var/lib/apt/lists/*

# Pre-warm the Maven local repository with everything spring-petclinic
# (springboot3 branch) needs — both runtime dependencies AND Maven
# plugins. Cloning the real project and running `mvn test-compile`
# downloads the exact closure used by the Stage F Build & Test gate,
# rather than guessing. Without this, mvn test in the customer source
# either hangs (network unavailable) or fails offline (missing
# plugin). With it, the gate runs offline and produces real
# pass/fail counts.
#
# After resolution we DELETE the cloned source so it doesn't bloat
# the image; only the populated ~/.m2/repository remains.
USER atlas:atlas
# Install a custom settings.xml that mirrors all external plugin
# repositories to Maven Central. petclinic-springboot3 declares
# spring-snapshots + spring-milestones as plugin repos; routing
# them through central avoids ~10s of latency per missing artifact
# when the gate runs.
COPY --chown=atlas:atlas m2-settings.xml /home/atlas/.m2/settings.xml

RUN mkdir -p /home/atlas/cache-build \
 && cd /home/atlas/cache-build \
 && git clone --depth=1 --branch=springboot3 \
      https://github.com/spring-projects/spring-petclinic.git petclinic \
 && cd petclinic \
 # `mvn test -DskipTests` is the right warm step: it loads (and so
 # downloads) the maven-surefire-plugin and every other lifecycle
 # plugin petclinic uses, but skips actually running test bodies.
 # After this, the Stage F Build & Test gate can run `mvn test`
 # against any petclinic-shaped customer source tree and produce
 # real pass/fail counts.
 && mvn -B test -DskipTests 2>&1 | tail -10 \
 && cd /home/atlas \
 && rm -rf /home/atlas/cache-build

WORKDIR /app
COPY --from=build --chown=atlas:atlas /app/target/*.jar app.jar

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