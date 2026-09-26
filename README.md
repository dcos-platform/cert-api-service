# cert-api-service

Spring Boot API service for creating, renewing, revoking, and retrieving certificate records. Validates requests, persists metadata, and publishes lifecycle events to RabbitMQ. Part of the DCOS polyglot distributed systems project.

## Prerequisites

### PostgreSQL Database

The service stores its data in the shared `dcos` database provided by the [dcos-infra](../dcos-infra) stack, under its own schema, `dcos_certificates`. Flyway creates and migrates that schema on startup, so no manual database setup is needed to run the service. This deliberately differs from the other DCOS services, which each use their own database: cert-api-service needs no `CREATE DATABASE` step and starts against a fresh dcos-infra stack.

The integration tests run against real PostgreSQL, never an in-memory database, using a dedicated `cert_api_test` database. Before building or running tests, make sure PostgreSQL is running (for example `docker compose up -d` in `../dcos-infra`), then create the test database:

```bash
./scripts/setup-test-db.sh          # Linux/macOS
scripts\setup-test-db.bat           # Windows
```

The setup script checks the PostgreSQL connection and creates `cert_api_test` if it doesn't exist. Its defaults match dcos-infra (`localhost:5432`, user `dcos`); override them with `DB_HOST`, `DB_PORT`, `DB_USER`, and `DB_PASSWORD`. The tests read the same variables. If the test database is unreachable, the tests stop with a "TEST DATABASE UNAVAILABLE" message that names this script.

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
  -e POSTGRES_HOST=host.docker.internal \
  -e POSTGRES_PASSWORD=<database password> \
  -e SPRING_RABBITMQ_HOST=host.docker.internal \
  cert-api-service
```

If the dcos-infra Adminer container already holds port 8080 on the host, map a different host port (for example `-p 8081:8080`).

**Environment Variables:**

- `POSTGRES_HOST`: PostgreSQL host (default: `localhost`)
- `POSTGRES_PORT`: PostgreSQL port (default: `5432`)
- `POSTGRES_DB`: Database name (default: `dcos`)
- `POSTGRES_USER`: PostgreSQL username (default: `dcos`)
- `POSTGRES_PASSWORD`: PostgreSQL password (default: dcos-infra's local default)
- `SPRING_RABBITMQ_HOST`: RabbitMQ host (default: `localhost`)
- `SPRING_RABBITMQ_PORT`: RabbitMQ port (default: `5672`)
- `SPRING_RABBITMQ_USERNAME`: RabbitMQ username (default: `guest`)
- `SPRING_RABBITMQ_PASSWORD`: RabbitMQ password (default: `guest`)

The service listens on port 8080 and exposes Swagger UI at `http://localhost:8080/swagger-ui.html` and OpenAPI docs at `http://localhost:8080/v3/api-docs`.
