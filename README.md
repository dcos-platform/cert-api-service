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
