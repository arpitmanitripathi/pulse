# Build stage: compile and package the jar (tests run in CI, not here)
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q package -DskipTests

# Run stage: JRE only, non-root user
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 pulse
WORKDIR /app
COPY --from=build /src/target/pulse-0.0.1-SNAPSHOT.jar app.jar
USER pulse
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
