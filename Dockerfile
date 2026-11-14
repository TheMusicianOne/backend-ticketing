# Stage 1: build the jar
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Gradle files first, so dependencies are cached when only code changes
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN ./gradlew dependencies --no-daemon > /dev/null

COPY src ./src
RUN ./gradlew bootJar --no-daemon

# Stage 2: run it, with only what's needed
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN useradd --create-home appuser
USER appuser

COPY --from=build /workspace/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]