# syntax=docker/dockerfile:1
# ---- Build stage ----
# Maven image is used instead of the ./mvnw wrapper because the wrapper jar
# (.mvn/wrapper/maven-wrapper.jar) is gitignored and absent from the build context.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Resolve dependencies first so this layer is cached when only sources change.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
EXPOSE 8085
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
