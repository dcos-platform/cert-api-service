# cert-api-service

Spring Boot API service for creating, renewing, revoking, and retrieving certificate records. Validates requests, persists metadata, and publishes lifecycle events to RabbitMQ. Part of the DCOS polyglot distributed systems project.

## Prerequisites

### PostgreSQL Database

This service requires PostgreSQL for integration tests. Before building or running tests, ensure PostgreSQL is available and running.

**Setting up the test database:**

```bash
# macOS (Homebrew)
brew services start postgresql@15

# Linux (systemd)
sudo systemctl start postgresql

# Docker
docker run -d -e POSTGRES_PASSWORD=postgres -p 5432:5432 postgres:15
```

**Create the test database:**

```bash
./scripts/setup-test-db.sh          # Linux/macOS
./scripts/setup-test-db.bat         # Windows
```

The setup script will verify the PostgreSQL connection and create the `cert_api_test` database if it doesn't exist. If the connection fails, the script will provide instructions on how to start PostgreSQL.

## Building and Running the Container

### Building the Image Locally

Build the container image using Docker:

```bash
docker build -t cert-api-service .
```

The multi-stage build uses a Temurin 21 JDK for compilation and a Temurin 21 JRE for the runtime, resulting in an optimized production image with minimal attack surface.

### Running the Container

The service requires PostgreSQL and RabbitMQ to operate. Start those services first, then run the container:

```bash
docker run -p 8080:8080 \
  -e DB_URL=jdbc:postgresql://host.docker.internal:5432/certdb \
  -e DB_USERNAME=certuser \
  -e DB_PASSWORD=certpassword \
  -e SPRING_RABBITMQ_HOST=host.docker.internal \
  cert-api-service
```

**Environment Variables:**

- `DB_URL`: PostgreSQL connection string (default: `jdbc:postgresql://localhost:5432/certdb`)
- `DB_USERNAME`: PostgreSQL username (default: `certuser`)
- `DB_PASSWORD`: PostgreSQL password (default: `certpassword`)
- `SPRING_RABBITMQ_HOST`: RabbitMQ host (default: `localhost`)
- `SPRING_RABBITMQ_PORT`: RabbitMQ port (default: `5672`)
- `SPRING_RABBITMQ_USERNAME`: RabbitMQ username (default: `guest`)
- `SPRING_RABBITMQ_PASSWORD`: RabbitMQ password (default: `guest`)

The service listens on port 8080 and exposes Swagger UI at `http://localhost:8080/swagger-ui.html` and OpenAPI docs at `http://localhost:8080/v3/api-docs`.
