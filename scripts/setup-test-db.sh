#!/bin/bash
set -e

# Defaults match the dcos-infra stack and the test configuration
# (src/test/resources/config/application.yml).
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_USER="${DB_USER:-dcos}"
DB_PASSWORD="${DB_PASSWORD:-changeme}"
DB_NAME="cert_api_test"

echo "Setting up test database..."
echo "Database Host: $DB_HOST"
echo "Database Port: $DB_PORT"
echo "Database User: $DB_USER"
echo "Database Name: $DB_NAME"
echo ""

# Check if PostgreSQL is running
if ! nc -z "$DB_HOST" "$DB_PORT" 2>/dev/null; then
    echo "ERROR: Could not connect to PostgreSQL at $DB_HOST:$DB_PORT"
    echo ""
    echo "To fix this issue, ensure PostgreSQL is running. You can start it with:"
    echo "  - The dcos-infra stack: cd ../dcos-infra && docker compose up -d"
    echo "  - On macOS (Homebrew): brew services start postgresql@16"
    echo "  - On Linux (systemd): sudo systemctl start postgresql"
    echo ""
    exit 1
fi

# Create the database if it doesn't exist
PGPASSWORD="$DB_PASSWORD" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d postgres -tc "SELECT 1 FROM pg_database WHERE datname = '$DB_NAME'" | grep -q 1 || \
PGPASSWORD="$DB_PASSWORD" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d postgres -c "CREATE DATABASE $DB_NAME;"

echo "Test database '$DB_NAME' is ready."
echo ""
echo "Database connection details:"
echo "  URL: jdbc:postgresql://$DB_HOST:$DB_PORT/$DB_NAME"
echo "  User: $DB_USER"
echo ""
echo "To run tests with this database:"
echo "  export DB_HOST=$DB_HOST DB_PORT=$DB_PORT DB_USER=$DB_USER DB_PASSWORD=<password>"
echo "  ./mvnw clean verify"
