# PROJECT CONTEXT (MODEL-FACING OVERVIEW)

This document provides AI models with an up-to-date understanding of the repository.
It describes what the project is, how it is structured, and what major components
currently exist. It does not contain rules or constraints; those live in claude.md.

## Project Overview

**cert-api-service** is a Java 21 Spring Boot service (version 3.3.2) that exposes a REST API for certificate lifecycle management. It handles creation, retrieval, renewal, and revocation of certificate metadata. The service persists data to PostgreSQL and publishes certificate lifecycle events to RabbitMQ for downstream processing. It is one component within the DCOS (distributed systems) polyglot architecture, alongside separate repositories for a Python orchestrator, a .NET admin service, a NestJS health service, and a Docker Compose infrastructure provider.

## Current Implementation State

Stories 1–6 are complete. Story 6 (Certificate creation) implements the create endpoint with serial number generation, common name derivation, and requesting principal capture. The service now has:
- A REST controller exposing endpoints for certificate lifecycle operations. POST create returns 201 with Location header; renew and revoke are POST
- A Flyway-managed PostgreSQL schema with three migrations. V3 (Story 6) enforces NOT NULL on serial_number, common_name, requested_by and adds a partial unique index on (subject, type) WHERE status = 'ACTIVE'
- A full `Certificate` entity with all fields non-nullable where required
- Enumerations: CertificateStatus (ACTIVE, EXPIRED, REVOKED), CertificateType (TLS, CLIENT, CA, CODE_SIGNING), OrchestrationStatus (PENDING, PROCESSING, COMPLETED, FAILED), RevocationReason (UNSPECIFIED, KEY_COMPROMISE, CA_COMPROMISE, AFFILIATION_CHANGED, SUPERSEDED, CESSATION_OF_OPERATION)
- Service layer implementing certificate creation with serial number generation (format DCOS-YYYY-<12hex>, collision retry), common name derivation from subject or request, and principal capture. `CertificateStateMachine` validates state transitions as pure logic
- Role-based access control (USER and ADMIN) at method level
- Request DTOs: `CertificateCreateRequest` (record), `CertificateRequest` (POJO for renew/revoke)
- Response DTOs: `CertificateResponse` (POJO, used by all endpoints)
- Error codes enumeration and extended global exception handler returning 409 Conflict on duplicate or constraint violations
- Repository with serial number collision checking
- Event publisher for lifecycle events (not yet wired to outbox; Story 9)
- Global exception handling with RFC 7807 Problem Detail responses including error codes
- OpenAPI 3.0 documentation
- Comprehensive unit tests for serial generation, common name extraction, and state machine; service tests for create with principal and serial capture; controller slice tests including Location header and authorization

The schema enforces NOT NULL on `serial_number`, `common_name`, and `requested_by` (added in Story 6). A partial unique index on `(subject, type) WHERE status = 'ACTIVE'` prevents two active certificates for the same subject and type. The revocation consistency check is deferred to Story 8.

The build is reproducible: Maven 3.9.16 is pinned in the wrapper, so only a JDK is needed. Spotless with Google Java Format (AOSP style) runs in the verify phase. Lombok is applied to the `Certificate` entity using only `@Getter`, `@Setter`, and `@NoArgsConstructor`, never `@Data` or `@Builder` on entities. A root `lombok.config` enables `lombok.addLombokGeneratedAnnotation`, so JaCoCo excludes generated accessors from coverage. A root `.gitattributes` forces LF line endings for `*.sh` and `mvnw`.

The CI workflow (GitHub Actions) runs `./mvnw clean verify` against a health-checked PostgreSQL 16 service container (database `cert_api_test`). It then runs SonarQube Cloud analysis with the quality gate enforced, and builds the container image, which is pushed to ghcr.io only on pushes to main.

## Repository Structure

**Top level:**
```
.gitattributes (forces LF endings for *.sh and mvnw)
.gitignore
.dockerignore
.github/workflows/ci.yml (build + tests with PostgreSQL service, SonarQube gate, container image)
.mvn/wrapper/maven-wrapper.properties
mvnw, mvnw.cmd (Maven wrapper scripts)
lombok.config (marks generated code with @lombok.Generated for JaCoCo)
pom.xml
Dockerfile
CHANGELOG.md
LICENSE
README.md
claude.md
context.md
docs/ (local reference material, excluded from version control)
scripts/
  setup-test-db.sh
  setup-test-db.bat
src/
  main/java/com/dcos/platform/certapi/
  main/resources/
    application.yml
    db/migration/ (Flyway migrations)
  test/java/com/dcos/platform/certapi/
  test/resources/config/application.yml (test overrides)
```

**Source code structure** (follows standard Maven layout):

- **CertApiServiceApplication**: Entry point with @SpringBootApplication
- **config/**: Configuration beans
  - SecurityConfig: Role-based access control, stateless authentication, in-memory user store (ADMIN and USER)
  - RabbitMqConfig: Topic exchange, queues, bindings, and message converter setup
  - OpenApiConfig: OpenAPI 3.0 schema with basic authentication scheme
- **controller/**: REST API layer
  - CertificateController: Five endpoints (POST create, GET list, GET by id, POST renew, POST revoke) with OpenAPI annotations
- **domain/**: Persistent entity and enumerations
  - Certificate: JPA entity mapped to `certificates`. Uses Lombok @Getter/@Setter/@NoArgsConstructor, with no equals/hashCode/toString. Has @Version optimistic locking; Hibernate sets the creation and update timestamps
  - CertificateStatus: ACTIVE, EXPIRED, REVOKED
  - CertificateType: TLS, CLIENT, CA, CODE_SIGNING
  - OrchestrationStatus: PENDING, PROCESSING, COMPLETED, FAILED (orchestrator-owned axis; PROCESSING currently unreachable)
  - RevocationReason: UNSPECIFIED, KEY_COMPROMISE, CA_COMPROMISE, AFFILIATION_CHANGED, SUPERSEDED, CESSATION_OF_OPERATION
- **dto/**: Request and response transfer objects
  - CertificateRequest: Input validation for create/renew operations; enforces required fields, future expiry, and valid certificate type
  - CertificateResponse: Maps from the domain entity; exposes the original seven fields, with type rendered as its enum name
- **event/**: Event publishing infrastructure
  - CertificateEvent: Immutable event payload with eventType, certificateId, subject, type, status, and occurredAt
  - CertificateEventPublisher: Component that publishes three event types (CREATED, RENEWED, REVOKED) to RabbitMQ
- **exception/**: Custom exception types and global handler
  - CertificateNotFoundException: Thrown when a certificate lookup fails
  - CertificateStateException: Thrown when an operation is invalid for the certificate's current state (e.g., revoking an already-revoked cert)
  - GlobalExceptionHandler: @RestControllerAdvice that converts exceptions to RFC 7807 Problem Detail responses
- **repository/**: Data access
  - CertificateRepository: JpaRepository interface with custom finders (by status, by subject substring)
- **service/**: Business logic
  - CertificateService: Orchestrates persistence and event publication; implements state validation and role-based authorization via @PreAuthorize annotations
- **validation/**: Custom validation annotation
  - ValidCertificateType: Constraint annotation for allowed certificate types
  - CertificateTypeValidator: Implementation of the constraint

**Database migrations** (src/main/resources/db/migration, Flyway):
- V1__create_schema_and_certificates.sql: creates schema `dcos_certificates` and the `certificates` table.
  - Check constraints cover type, status, orchestration status, revocation reason, validity, renewal window, and revoked-consistency.
  - The serial number is unique.
  - Indexes cover status, expires_at, common_name, (type, status), and orchestration_status.
- V2__seed_demo_certificates.sql: seeds five fictional certificates with fixed ids `11111111-…` through `55555555-…`: service-alpha, service-bravo, dcos-root, build-signer, and service-echo, which sits inside its renewal window. `ON CONFLICT (id) DO NOTHING` makes the insert safe to re-run.
- Not yet created: the outbox and processed-completions tables, and the partial unique index on active (subject, type). Each arrives with the story that uses it.

**Configuration file** (src/main/resources/application.yml):
- The datasource targets the shared `dcos` database through POSTGRES_HOST, POSTGRES_PORT, POSTGRES_DB, POSTGRES_USER, and POSTGRES_PASSWORD. The defaults match dcos-infra's .env.example
- Flyway manages schema `dcos_certificates` as both `schemas` and `default-schema`. Hibernate's `default_schema` matches, so entities carry no schema attribute
- Hibernate `ddl-auto: validate`; `open-in-view: false`
- No server port is set: the framework default is 8080, matching the container image
- RabbitMQ connection and exchange/routing-key configuration
- Logging level for the service package (INFO)
- Actuator endpoints exposed (health, info)
- Swagger UI path (/swagger-ui.html)

**Tests** (src/test/java and src/test/resources):
- Test strategy: real PostgreSQL on an external instance (dcos-infra locally, a service container in CI) with the dedicated `cert_api_test` database, created by scripts/setup-test-db.sh (.bat on Windows). The in-memory H2 database is removed and permanently excluded because the migrations use column types and index forms it does not support. Testcontainers is not used.
- src/test/resources/config/application.yml: test overrides layered over the main application.yml. Sets the datasource through DB_HOST, DB_PORT, DB_USER, and DB_PASSWORD, and enables Flyway clean for the test database only
- support/CertificateFixtures: shared test data factory with active(), expired(), revoked(), and inRenewalWindow() variants. Later stories extend it rather than duplicating setup
- support/RequiresTestDatabase and TestDatabaseAvailabilityExtension: probe the test database before any Spring context loads. If it is unreachable, the test fails with a plain "TEST DATABASE UNAVAILABLE" message naming the setup script
- CertApiServiceApplicationTest: boots the full context against the migrated database. With validate mode on, this proves the entity mapping matches the schema
- migration/FlywayMigrationTest: clean-then-migrate from empty, schema and table existence, seed data, seed re-run safety
- repository/CertificateRepositoryTest: @DataJpaTest against real data (seed retrieval, finders, fixture round trips)
- CertificateControllerTest: WebMvcTest with a mocked service; covers the happy path, authorization, and validation errors (no database)
- CertificateServiceTest: unit tests with a mocked repository and event publisher (no database)

## What Does Not Yet Exist

The following will arrive in future stories:
- **Search and pagination**: getAll() returns all certificates; paginated listing and filtering arrives with Story 7
- **Expiry sweep**: A scheduled task that transitions ACTIVE → EXPIRED when now() > expires_at, enqueuing CERTIFICATE_EXPIRED events (Story 8)
- **Transactional outbox**: Events are published inside the service transaction with no guaranteed delivery yet. The outbox table and OutboxRelay service arrive with Story 9
- **Completion consumer**: Nothing listens for orchestrator completions yet. The processed-completions table and CompletionListener arrive with Story 10

## Dependencies and Tooling

- **Java**: 21 (required)
- **Spring Boot**: 3.3.2
- **Maven**: 3.9.16 (via wrapper; no prerequisite installation needed)
- **Lombok**: 1.18.x (provided scope; used on the Certificate entity)
- **PostgreSQL driver**: runtime
- **Flyway**: flyway-core and flyway-database-postgresql (versions managed by Spring Boot)
- **RabbitMQ AMQP client**: Spring AMQP starter
- **Security**: Spring Security with basic authentication
- **Validation**: Jakarta Validation (Bean Validation 3.0)
- **OpenAPI**: springdoc-openapi 2.5.0 with Swagger UI
- **Code formatting**: Spotless 2.43.0 with Google Java Format 1.22.0 (AOSP style)
- **Coverage and analysis**: JaCoCo 0.8.15; SonarQube Cloud via sonar-maven-plugin 5.7.0.6970
- **Testing**: JUnit 5, Mockito, Spring Security Test, Spring AMQP Test, real PostgreSQL 16 (no H2)
- **Build tool plugin**: Spring Boot Maven Plugin for executable JAR

## Delivery Model

Work proceeds as a sequence of numbered stories. Each story branches from main, delivers its own tests, and is merged before the next story begins. Each story updates this document to reflect the new state of the repository.

Stories 1–6 are complete: verified baseline and build toolchain, continuous integration, coverage and quality gate, container image, persistent schema and certificate model, and certificate creation. The remaining stories are certificate search and pagination (7), certificate lifecycle transitions (8), lifecycle event publication (9), and completion consumption and API hardening (10).

## Intended Use of This Document

- AI models should read this file before performing reasoning or implementation tasks.
- This document always reflects the real, current state of the repository.
- When core changes occur (new modules, removed modules, directory changes, major features), this document must be updated to keep models aligned with the actual project structure.
- This document is strictly for AI model awareness and synchronization.

## Human-Facing Documentation

Human developers should refer to README.md for:
- Setup and prerequisites instructions
- Development workflow
- Tooling details
- Build and test commands
- Contribution guidelines
