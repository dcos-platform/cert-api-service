# Build stage
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /build
COPY .mvn .mvn/
COPY mvnw pom.xml ./
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw dependency:resolve -DskipTests
COPY src ./src
RUN ./mvnw clean package -DskipTests -q

# Runtime stage
FROM eclipse-temurin:21-jre
ARG BUILD_COMMIT_SHA
RUN groupadd -r app && useradd -r -g app app
WORKDIR /app
COPY --from=builder /build/target/*.jar app.jar
RUN chown -R app:app /app
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
LABEL org.opencontainers.image.source="https://github.com/dcos-platform/cert-api-service"
LABEL org.opencontainers.image.revision="${BUILD_COMMIT_SHA}"
