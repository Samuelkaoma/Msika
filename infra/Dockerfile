# Builds and runs one Spring Boot module. Build context is the repository root:
#   docker build -f infra/Dockerfile --build-arg MODULE=zamsim .
FROM eclipse-temurin:21.0.13_9-jdk-noble AS build
ARG MODULE
WORKDIR /src
COPY . .
RUN ./gradlew --no-daemon ":${MODULE}:bootJar"

FROM eclipse-temurin:21.0.13_9-jre-noble
ARG MODULE
RUN useradd --system --no-create-home msika
USER msika
WORKDIR /app
COPY --from=build /src/platform/${MODULE}/build/libs/${MODULE}-*-SNAPSHOT.jar /app/app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
