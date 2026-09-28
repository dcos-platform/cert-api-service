# PROJECT CONTEXT (MODEL-FACING OVERVIEW)

This document provides AI models with an up-to-date understanding of the repository.
It describes what the project is, how it is structured, and what major components
currently exist. It does not contain rules or constraints; those live in claude.md.

## Project Overview

**cert-api-service** is a Java 21 Spring Boot service (version 3.3.2) that exposes a REST API for certificate lifecycle management. It handles creation, retrieval, renewal, and revocation of certificate metadata. The service persists data to PostgreSQL and publishes certificate lifecycle events to RabbitMQ for downstream processing. It is one component within the DCOS (distributed systems) polyglot architecture, alongside separate repositories for a Python orchestrator, a .NET admin service, a NestJS health service, and a Docker Compose infrastructure provider.

## Current Implementation State

Stories 1–9 are complete. Story 6 (Certificate creation) implements the create endpoint with serial number generation, common name derivation, and requesting principal capture. Story 7 (Certificate search and pagination) adds dynamic filtering, sorting, and paginated listing with composable JPA specifications. Story 8 (Certificate lifecycle transitions) implements renewal and revocation with proper state validation, an expiry sweep, and a database consistency constraint. Story 9 (Lifecycle event publication) implements the transactional outbox pattern, a scheduled relay, snake_case serialization for the wire contract, and event history endpoint. The service now has:
- A REST controller exposing seven endpoints for certificate lifecycle operations: POST create (returns 201 with Location header), GET list with filtering/paging/sorting, GET by id, GET /expiring, POST renew, POST revoke. Search supports filtering by status, type, orchestration status, issuedBy, commonName substring, and expiry window; sorting is restricted to a fixed allow-list (createdAt, expiresAt, issuedAt, commonName, subject, status, type) to prevent injection
- A Flyway-managed PostgreSQL schema with three migrations. V3 (Story 6) enforces NOT NULL on serial_number, common_name, requested_by and adds a partial unique index on (subject, type) WHERE status = 'ACTIVE'
- A full `Certificate` entity with all fields non-nullable where required
- Enumerations: CertificateStatus (ACTIVE, EXPIRED, REVOKED), CertificateType (TLS, CLIENT, CA, CODE_SIGNING), OrchestrationStatus (PENDING, PROCESSING, COMPLETED, FAILED), RevocationReason (UNSPECIFIED, KEY_COMPROMISE, CA_COMPROMISE, AFFILIATION_CHANGED, SUPERSEDED, CESSATION_OF_OPERATION)
- Service layer implementing certificate creation with serial number generation (format DCOS-YYYY-<12hex>, collision retry), common name derivation from subject or request, and principal capture. `CertificateStateMachine` validates state transitions as pure logic. Search and list operations use composable JPA `Specification` predicates for status, type, orchestration status, issuedBy, commonName substring, and expiry window filtering
- Role-based access control (USER and ADMIN) at method level
- Request DTOs: `CertificateCreateRequest` (record for create), `RenewalRequest` (POJO for renewal with future expiry and optional window), `RevocationRequest` (POJO for revocation with required reason and optional comment)
- Response DTOs: `CertificateResponse` (record with 19 fields for individual certificate responses), `PageResponse<T>` (generic paginated response with content, page, size, totalElements, totalPages, first, last for list operations)
- Error codes enumeration and extended global exception handler returning 409 Conflict on duplicate or constraint violations
- Repository with serial number collision checking; implements `JpaSpecificationExecutor<Certificate>` for dynamic filtering via `CertificateSpecifications` factory
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
  - RabbitMqConfig: Topic exchange, dead-letter exchange/queue, consumer queues (orchestrator and admin), snake_case AMQP mapper, publisher confirms/returns
  - OpenApiConfig: OpenAPI 3.0 schema with basic authentication scheme
- **controller/**: REST API layer
  - CertificateController: Eight endpoints (POST create, GET list with filtering/paging/sorting, GET by id, GET /expiring, GET event history, POST renew, POST revoke) with OpenAPI annotations
  - SortableField: Enum restricting sort fields to a fixed allow-list (createdAt, expiresAt, issuedAt, commonName, subject, status, type), preventing arbitrary or injected sort parameters
- **domain/**: Persistent entity and enumerations
  - Certificate: JPA entity mapped to `certificates`. Uses Lombok @Getter/@Setter/@NoArgsConstructor, with no equals/hashCode/toString. Has @Version optimistic locking; Hibernate sets the creation and update timestamps
  - Outbox: JPA entity mapped to `outbox`. Models the transactional outbox table for lifecycle events: carries event_id (unique), aggregate_id (certificate id), event_type, routing_key, payload (serialized JSON string), state, attempt_count, last_error, created_at, sent_at
  - CertificateStatus: ACTIVE, EXPIRED, REVOKED
  - CertificateType: TLS, CLIENT, CA, CODE_SIGNING
  - OrchestrationStatus: PENDING, PROCESSING, COMPLETED, FAILED (orchestrator-owned axis; PROCESSING currently unreachable)
  - OutboxState: PENDING, SENT, FAILED (transactional outbox state machine)
  - RevocationReason: UNSPECIFIED, KEY_COMPROMISE, CA_COMPROMISE, AFFILIATION_CHANGED, SUPERSEDED, CESSATION_OF_OPERATION
- **dto/**: Request and response transfer objects
  - CertificateRequest: Input validation for create/renew operations; enforces required fields, future expiry, and valid certificate type
  - CertificateResponse: Record with 19 fields including serialNumber, commonName, orchestrationStatus, renewalWindowDays, daysUntilExpiry (computed at mapping time), revokedAt, revocationReason, revocationComment, requestedBy, correlationId, renewalCount, lastError, createdAt, updatedAt
  - OutboxEventResponse: Record for event history endpoint, carrying eventId, eventType, state, attempts, createdAt, sentAt
  - PageResponse<T>: Generic paginated response record (content, page, size, totalElements, totalPages, first, last)
- **event/**: Event publishing and outbox infrastructure
  - EventType: Enum with routing keys (cert.created, cert.renewed, cert.revoked, cert.expired)
  - CertificateEventPayload: Record carrying source, schema_version, certificate_id, subject, type, status
  - CertificateEventEnvelope: Record carrying event_id, certificate_id, occurred_at, payload (the wire contract)
  - OutboxEnqueueService: Builds envelopes, serializes with the AMQP mapper (snake_case), enqueues to outbox
  - OutboxRelay: Scheduled component claiming pending rows, publishing, marking sent; retries with attempt ceiling
- **exception/**: Custom exception types and global handler
  - CertificateNotFoundException: Thrown when a certificate lookup fails
  - CertificateStateException: Thrown when an operation is invalid for the certificate's current state (e.g., revoking an already-revoked cert)
  - GlobalExceptionHandler: @RestControllerAdvice that converts exceptions to RFC 7807 Problem Detail responses
- **repository/**: Data access
  - CertificateRepository: JpaRepository interface implementing JpaSpecificationExecutor<Certificate> for dynamic filtering, with custom finders (by status, by subject substring)
  - CertificateSpecifications: JPA Specification factory for composable filter predicates (status, type, orchestration status, issuedBy, commonName substring, expiringBefore), each returning null when its filter is absent so they compose via Specification.where(...).and(...)
  - OutboxRepository: JpaRepository interface for the transactional outbox table, with custom methods claimPending(batchSize) for claiming unpublished rows with pessimistic locking (FOR UPDATE SKIP LOCKED), and findByAggregateIdOrderByCreatedAtDesc(certificateId, pageable) for event history queries
- **service/**: Business logic
  - CertificateService: Orchestrates persistence and event publication; implements state validation and role-based authorization via @PreAuthorize annotations. Renewal validates new expiry is strictly later, updates only validity fields, and increments renewal count. Revocation sets revokedAt timestamp along with reason and comment.
  - CertificateStateMachine: Pure logic (no dependencies) implementing the full synchronous status state table (ACTIVE, EXPIRED, REVOKED). Validates renewal and revocation transitions; computes expiry transition.
  - CertificateExpirySweep: Scheduled component (disabled in tests) that sweeps ACTIVE certificates past expiry to EXPIRED status in batches, iterating and saving per certificate to respect optimistic locking.
- **validation/**: Custom validation annotation
  - ValidCertificateType: Constraint annotation for allowed certificate types
  - CertificateTypeValidator: Implementation of the constraint

**Database migrations** (src/main/resources/db/migration, Flyway):
- V1__create_schema_and_certificates.sql: creates schema `dcos_certificates` and the `certificates` table. Check constraints cover type, status, orchestration status, revocation reason, validity, renewal window. The serial number is unique. Indexes cover status, expires_at, common_name, (type, status), and orchestration_status. Revoked-consistency check is deferred to V4.
- V2__seed_demo_certificates.sql: seeds five fictional certificates with fixed ids `11111111-…` through `55555555-…`: service-alpha, service-bravo, dcos-root, build-signer, and service-echo (inside renewal window). `ON CONFLICT (id) DO NOTHING` makes re-run safe.
- V3__restore_deferred_constraints.sql: Adds NOT NULL to serial_number, common_name, requested_by with backfill. Creates partial unique index on (subject, type) WHERE status = 'ACTIVE'.
- V4__restore_revocation_consistency.sql: Backfills revokedAt (to now()) on any revoked row lacking a timestamp, clears revokedAt from non-revoked rows, then adds check `(status = 'REVOKED') = (revoked_at IS NOT NULL)` to enforce the consistency constraint at the database level.
- V5__create_outbox_table.sql: creates the `outbox` table with identity, event_id (unique), aggregate_id, event_type, routing_key, payload (TEXT—serialized JSON), state, attempt_count, last_error, created_at, sent_at. Includes a partial index over pending rows for efficient claiming and an index on aggregate_id for history queries.
- Not yet created: the processed-completions table (Story 10).

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
- repository/CertificateSpecificationsTest: Unit tests covering null/present branches of each specification factory (12 tests)
- CertificateControllerTest: WebMvcTest with a mocked service; covers the happy path, authorization, and validation errors (no database)
- CertificateServiceTest: unit tests with a mocked repository and event publisher (no database)
- CertificateSearchIntegrationTest: Integration tests covering filtering by status, case-insensitive common name search, paging boundaries, expiry window search, and sort ordering

## What Does Not Yet Exist

The following will arrive in future stories:
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

Stories 1–8 are complete: verified baseline and build toolchain, continuous integration, coverage and quality gate, container image, persistent schema and certificate model, certificate creation, certificate search and pagination, and certificate lifecycle transitions. The remaining stories are lifecycle event publication (9) and completion consumption and API hardening (10).

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
