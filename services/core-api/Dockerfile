# The core API image. The production profile also runs its one-shot migrations container from this
# image, with a different entry point (com.servora.Migrate) and the database owner's credentials;
# the API itself connects only as the unprivileged runtime role.

FROM eclipse-temurin:25.0.4_7-jdk-noble AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN sh mvnw -q -B dependency:go-offline
COPY src src
RUN sh mvnw -q -B -DskipTests package \
    && cp target/servora-core-api-*.jar /app.jar

FROM eclipse-temurin:25.0.4_7-jre-noble
# Security updates published since the base image was built.
RUN apt-get update \
    && apt-get upgrade -y --no-install-recommends \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --home-dir /app servora
WORKDIR /app
COPY --from=build /app.jar /app/app.jar
USER 10001
EXPOSE 8080
# Migrations run in their own container, so the API never needs the database owner's password.
ENV SPRING_FLYWAY_ENABLED=false
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
