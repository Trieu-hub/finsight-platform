# syntax=docker/dockerfile:1
# ---- Build stage ----
# Maven image is used instead of the ./mvnw wrapper because the wrapper jar
# (.mvn/wrapper/maven-wrapper.jar) is gitignored and absent from the build context.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Resolve dependencies first so this layer is cached when only sources change.
# Runtime scope only, and the tests are not compiled: this image is a runtime artifact and CI
# runs the tests separately (`mvn verify`). Not a micro-optimisation — the test tree pulls
# kafka-streams' rocksdbjni, a 72 MB native jar, and a truncated download of it has already
# failed a build. (`-DexcludeScope=test` does not work: in Maven's scope hierarchy test is the
# widest, so the plugin refuses it with "this will exclude everything".)
COPY pom.xml .
RUN mvn -B -q dependency:go-offline -DincludeScope=runtime
COPY src ./src
RUN mvn -B -q clean package -Dmaven.test.skip=true

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-jammy
# curl is required by the container HEALTHCHECK (compose) to probe the Actuator
# readiness endpoint; the base JRE image does not ship an HTTP client.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
EXPOSE 8088
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
