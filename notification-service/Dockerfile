# Build context is the repository root (see docker-compose.yml) so the Gradle wrapper and
# settings are available. Tests are skipped here: they need Docker themselves (Testcontainers).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY booking-service ./booking-service
COPY notification-service ./notification-service
RUN --mount=type=cache,id=dentline-gradle-home,sharing=locked,target=/root/.gradle \
    ./gradlew :notification-service:bootJar --no-daemon --no-configuration-cache -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 1001 app
COPY --from=build /workspace/notification-service/build/libs/*.jar /app/app.jar
USER app
EXPOSE 8082
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
