# PROJECT CONTEXT (MODEL-FACING OVERVIEW)

This document provides AI models with an up-to-date understanding of the repository.
It describes what the project is, how it is structured, and what major components
currently exist. It does not contain rules or constraints; those live in claude.md.

## Project Overview

**cert-api-service** is a Java 21 Spring Boot service (version 3.3.2) that exposes a REST API for certificate lifecycle management. It handles creation, retrieval, renewal, and revocation of certificate metadata. The service persists data to PostgreSQL and publishes certificate lifecycle events to RabbitMQ for downstream processing. It is one component within the DCOS (distributed systems) polyglot architecture, alongside separate repositories for a Python orchestrator, a .NET admin service, a NestJS health service, and a Docker Compose infrastructure provider.

## Current Implementation State

Stories 1–15 are complete. Stories 1–14 deliver the core certificate lifecycle REST API with persistence, event publication, orchestrator integration, structured logging, and Prometheus metrics. Story 15 (System integration and external documentation) performs integration assessment against sibling services (cert-orchestrator, cert-admin, cert-health), documents findings and blockers, records architectural decisions (D1–D4), and produces three findings documents (ADMIN_FINDINGS.md, HEALTH_FINDINGS.md, INFRA_FINDINGS.md).

**Story 15 summary:**

- **cert-orchestrator-service:** Integration verified end-to-end in Story 12. Case-insensitive status comparison confirmed. No blockers.
- **cert-admin-service:** Four blockers identified: RabbitMQ credentials (`guest`/`PLACEHOLDER` vs. `dcos`/`changeme`), database/user mismatch (`cert_admin`/`postgres` vs. `dcos`/`dcos`), envelope field mismatch (PascalCase `CertificateId` vs. snake_case `certificate_id`), unbounded retry hazard (`requeue: true`). Integration test run: certificate created, event routed, envelope fix validated, but audit insert failed (schema not initialized). D1: cert-admin adapts to snake_case. D2: 322-message backlog purged. Fixes applied for assessment; all changes reverted. Defects recorded in ADMIN_FINDINGS.md.
- **cert-health-service:** `/expiring` integration unbuilt (verified via grep). No HTTP client to cert-api. D3: declare non-integration for 1.0.0 due to missing service-to-service credential story. Renewal requests published to unrouted queue. Documented in HEALTH_FINDINGS.md.
- **dcos-infra:** Three missing databases, delayed-message plugin absent, stale queues already absent from broker. Documented in INFRA_FINDINGS.md; no dcos-infra changes in scope for Story 15.

Stories 1–12 deliver the core certificate lifecycle REST API with persistence, event publication, orchestrator integration, and structured logging. Story 13 (Consolidation and polish) refines the implementation with auto-startup configuration for the completion listener (to silence double-attachment of RabbitMQ consumers to shared queues across cached Spring test contexts), fixes StackOverflowError in OptimisticLockingRetryListener during high-contention certificate updates, and reorders completion processing to resolve the certificate before claiming event idempotency to suppress redelivery on resolution failure. Story 14 (Prometheus metrics) adds observability via Micrometer: exposes `/actuator/prometheus` (ADMIN-only) with 8 metric names producing 23 series covering certificate counts (status × type) and outbox health; uses scheduled aggregation with atomics to avoid per-scrape database load; pre-creates all series to ensure zero values appear. The case-insensitive status comparison fix in Story 12 is verified and tested; the end-to-end integration with the Python orchestrator has been verified in a live run (created ACTIVE/PENDING, then ACTIVE/COMPLETED within seconds; `status` never changed). Story 6 (Certificate creation) implements the create endpoint with serial number generation, common name derivation, and requesting principal capture. Story 7 (Certificate search and pagination) adds dynamic filtering, sorting, and paginated listing with composable JPA specifications. Story 8 (Certificate lifecycle transitions) implements renewal and revocation with proper state validation, an expiry sweep, and a database consistency constraint. Story 9 (Lifecycle event publication) implements the transactional outbox pattern, a scheduled relay, snake_case serialization for the wire contract, and event history endpoint. Story 10 (Orchestrator completion consumption) implements the CompletionListener, ProcessedCompletion inbox for idempotency, OrchestrationTimeoutSweep scheduled job, dead-letter recovery, and comprehensive tests reaching ≥80% coverage on new code. Story 11 (Structured logging and correlation) implements structured JSON logging for production, a servlet filter for correlation ID injection and MDC population, correlation ID capture in the outbox for cross-service tracing, and restoration in the completion listener. Story 12 (End-to-end verification against the orchestrator) fixes the known defect in status comparison (case-insensitive handling of orchestrator's uppercase status values), adds unit tests for the case-sensitivity fix covering lowercase, mixed-case, and null statuses, and documents the procedure for the polyglot integration with the Python orchestrator. The service now has:
- A REST controller exposing seven endpoints for certificate lifecycle operations: POST create (returns 201 with Location header), GET list with filtering/paging/sorting, GET by id, GET /expiring, POST renew, POST revoke. Search supports filtering by status, type, orchestration status, issuedBy, commonName substring, and expiry window; sorting is restricted to a fixed allow-list (createdAt, expiresAt, issuedAt, commonName, subject, status, type) to prevent injection
- A Flyway-managed PostgreSQL schema with seven migrations. V1 creates the schema and certificates table; V2 seeds demo data; V3 (Story 6) enforces NOT NULL on serial_number, common_name, requested_by and adds a partial unique index on (subject, type) WHERE status = 'ACTIVE'; V4 (Story 8) ensures revocation consistency; V5 (Story 9) creates the outbox table; V6 (Story 10) creates the processed_completions inbox; V7 (Story 11) adds correlation_id to outbox
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
  - RabbitMqConfig: Topic exchange, dead-letter exchange/queue, consumer queues (orchestrator and admin), snake_case AMQP mapper, publisher confirms/returns. The custom `completionListenerContainerFactory` honours `cert-api.rabbitmq.completion-listener-auto-startup` (default true; false for tests). This property is the only thing that governs the completion listener's startup: Spring Boot's `spring.rabbitmq.listener.simple.auto-startup` configures Boot's auto-configured factory and never reaches a custom one. Without it, every cached test Spring context — including those whose repositories are mocks — attached its own consumer to the shared completions queue, and RabbitMQ round-robin delivered test messages to listeners that could not act on them
  - OpenApiConfig: OpenAPI 3.0 schema with basic authentication scheme
  - MetricsConfig: Micrometer gauge factory and scheduled component registration for CertificateMetricsCollector
- **logging/**: Structured logging and correlation
  - LoggingContext: Constants for MDC key names (correlationId, certificateId, eventId, principal, service name)
  - CorrelationFilter: Servlet filter establishing correlation ID at the earliest point in request handling, populating MDC, echoing on the response, and clearing the context in a finally block to avoid leaks on pooled threads
- **controller/**: REST API layer
  - CertificateController: Eight endpoints (POST create, GET list with filtering/paging/sorting, GET by id, GET /expiring, GET event history, POST renew, POST revoke) with OpenAPI annotations
  - SortableField: Enum restricting sort fields to a fixed allow-list (createdAt, expiresAt, issuedAt, commonName, subject, status, type), preventing arbitrary or injected sort parameters
- **domain/**: Persistent entities and enumerations
  - Certificate: JPA entity mapped to `certificates`. Uses Lombok @Getter/@Setter/@NoArgsConstructor, with no equals/hashCode/toString. Has @Version optimistic locking; Hibernate sets the creation and update timestamps
  - Outbox: JPA entity mapped to `outbox`. Models the transactional outbox table for lifecycle events: carries event_id (unique), aggregate_id (certificate id), event_type, routing_key, payload (serialized JSON string), state, attempt_count, last_error, created_at, sent_at
  - ProcessedCompletion: JPA entity mapped to `processed_completions`. Inbox record for completion event idempotency: carries event_id (primary key, varchar 255 to store retry suffixes), certificate_id (UUID), processed_at (auto-generated timestamp)
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
- **event/**: Event publishing, outbox infrastructure, and completion consumption
  - EventType: Enum with routing keys (cert.created, cert.renewed, cert.revoked, cert.expired)
  - CertificateEventPayload: Record carrying source, schema_version, certificate_id, subject, type, status
  - CertificateEventEnvelope: Record carrying event_id, certificate_id, occurred_at, payload (the wire contract)
  - OutboxEnqueueService: Builds envelopes, serializes with the AMQP mapper (snake_case), captures the correlation ID from MDC (or falls back to the certificate's correlation ID if no request context exists, such as during the expiry sweep), enqueues to outbox with the correlation ID stored on the row
  - OutboxRelay: Scheduled component claiming pending rows, publishing with the stored correlation ID in the message header (falling back to event ID when absent), marking sent; retries with attempt ceiling; runs without request context, so correlation ID comes from the stored column rather than MDC
  - CompletionEvent: Record carrying eventId, certificateId, status (lowercase "completed"/"failed"), retryCount, error from orchestrator
  - CompletionListener: RabbitMQ consumer for certificate.lifecycle.completions queue; restores the correlation ID from the inbound x-correlation-id header (or falls back to the certificate's own correlation ID) into MDC for the duration of handling; validates and applies completion status updates to certificates; clears MDC in a finally block. Resolves the certificate *before* claiming the event id in the ProcessedCompletion inbox, so an unresolved completion leaves no inbox row to suppress its own redelivery; an unknown certificate raises CertificateNotFoundException and is dead-lettered after the container's bounded retries rather than acknowledged, while a malformed certificate id is logged and acknowledged because no redelivery can fix it. Idempotency comes from the inbox insert reporting zero affected rows (INSERT ... ON CONFLICT DO NOTHING), not from a caught constraint violation
- **exception/**: Custom exception types and global handler
  - CertificateNotFoundException: Thrown when a certificate lookup fails
  - CertificateStateException: Thrown when an operation is invalid for the certificate's current state (e.g., revoking an already-revoked cert)
  - GlobalExceptionHandler: @RestControllerAdvice that converts exceptions to RFC 7807 Problem Detail responses
- **repository/**: Data access
  - CertificateRepository: JpaRepository interface implementing JpaSpecificationExecutor<Certificate> for dynamic filtering, with custom finders (by status, by subject substring)
  - CertificateSpecifications: JPA Specification factory for composable filter predicates (status, type, orchestration status, issuedBy, commonName substring, expiringBefore), each returning null when its filter is absent so they compose via Specification.where(...).and(...)
  - OutboxRepository: JpaRepository interface for the transactional outbox table, with custom methods claimPending(batchSize) for claiming unpublished rows with pessimistic locking (FOR UPDATE SKIP LOCKED), and findByAggregateIdOrderByCreatedAtDesc(certificateId, pageable) for event history queries
  - ProcessedCompletionRepository: JpaRepository interface for the processed_completions inbox table; primary key is event_id (String) to allow retry suffixes
- **service/**: Business logic
  - CertificateService: Orchestrates persistence and event publication; implements state validation and role-based authorization via @PreAuthorize annotations. Renewal validates new expiry is strictly later, updates only validity fields, and increments renewal count. Revocation sets revokedAt timestamp along with reason and comment.
  - CertificateStateMachine: Pure logic (no dependencies) implementing the full synchronous status state table (ACTIVE, EXPIRED, REVOKED). Validates renewal and revocation transitions; computes expiry transition.
  - CertificateExpirySweep: Scheduled component (disabled in tests) that sweeps ACTIVE certificates past expiry to EXPIRED status in batches, iterating and saving per certificate to respect optimistic locking.
  - OrchestrationTimeoutSweep: Scheduled component that sweeps certificates stuck in PENDING orchestration state for longer than the configured timeout (default 2 minutes). Moves stale pending certificates to FAILED with reason "orchestration timed out"; only modifies orchestration_status and last_error, never the certificate's own status.
- **metrics/**: Prometheus metrics and observability (Story 14)
  - CertificateMetricsCollector: Scheduled component that polls certificate and outbox tables in the background, computing aggregate counts (certificates by status × type, outbox by state), storing results in ConcurrentHashMaps, and exposing them via Micrometer gauges and counters. Implements 8 metric names producing 23 series with zero-value pre-registration. Refresh interval configurable via cert-api.metrics.refresh-interval (default 60000 ms).
  - StatusTypeCount, OrchestrationStatusCount, OutboxStateCount: JPA projection interfaces for grouped aggregation queries (count by dimension), used by the metrics collector to fetch batch results from the database
- **validation/**: Custom validation annotation
  - ValidCertificateType: Constraint annotation for allowed certificate types
  - CertificateTypeValidator: Implementation of the constraint

**Database migrations** (src/main/resources/db/migration, Flyway):
- V1__create_schema_and_certificates.sql: creates schema `dcos_certificates` and the `certificates` table. Check constraints cover type, status, orchestration status, revocation reason, validity, renewal window. The serial number is unique. Indexes cover status, expires_at, common_name, (type, status), and orchestration_status. Revoked-consistency check is deferred to V4.
- V2__seed_demo_certificates.sql: seeds five fictional certificates with fixed ids `11111111-…` through `55555555-…`: service-alpha, service-bravo, dcos-root, build-signer, and service-echo (inside renewal window). `ON CONFLICT (id) DO NOTHING` makes re-run safe.
- V3__restore_deferred_constraints.sql: Adds NOT NULL to serial_number, common_name, requested_by with backfill. Creates partial unique index on (subject, type) WHERE status = 'ACTIVE'.
- V4__restore_revocation_consistency.sql: Backfills revokedAt (to now()) on any revoked row lacking a timestamp, clears revokedAt from non-revoked rows, then adds check `(status = 'REVOKED') = (revoked_at IS NOT NULL)` to enforce the consistency constraint at the database level.
- V5__create_outbox_table.sql: creates the `outbox` table with identity, event_id (unique), aggregate_id, event_type, routing_key, payload (TEXT—serialized JSON), state, attempt_count, last_error, created_at, sent_at. Includes a partial index over pending rows for efficient claiming and an index on aggregate_id for history queries.
- V6__create_processed_completions_table.sql: creates the `processed_completions` table with event_id (primary key, varchar 255 to store retry suffixes), certificate_id (UUID), and processed_at (auto-generated timestamp). Enables idempotency for completion event consumption via primary-key collision detection.
- V7__add_correlation_id_to_outbox.sql: adds correlation_id column (varchar 36, nullable) to the `outbox` table. Existing rows leave it null; the relay uses only it for new rows.

**Configuration file** (src/main/resources/application.yml):
- The datasource targets the shared `dcos` database through POSTGRES_HOST, POSTGRES_PORT, POSTGRES_DB, POSTGRES_USER, and POSTGRES_PASSWORD. The defaults match dcos-infra's .env.example
- Flyway manages schema `dcos_certificates` as both `schemas` and `default-schema`. Hibernate's `default_schema` matches, so entities carry no schema attribute
- Hibernate `ddl-auto: validate`; `open-in-view: false`
- No server port is set: the framework default is 8080, matching the container image. **For local development when the dcos-infra stack is running**: set `SERVER_PORT=8081` to avoid collision with Adminer (which occupies 8080). Spring Boot's relaxed binding accepts the environment variable without configuration change.
- RabbitMQ connection and exchange/routing-key configuration
- Logging level for the service package (INFO)
- Actuator endpoints exposed: health, info, prometheus. Micrometer Prometheus registry automatically enabled by `micrometer-registry-prometheus` on the classpath.
- Metrics refresh interval: 60000 ms (configurable via `cert-api.metrics.refresh-interval`)
- Swagger UI path (/swagger-ui.html)

**Logging** (src/main/resources/logback-spring.xml):
- Two profiles: a plain-text profile for development (default) and a JSON profile for deployment (prod)
- The plain-text pattern includes the correlation ID in brackets for local readability
- The JSON profile uses the logstash logback encoder with MDC fields included, producing parseable JSON for log aggregation services
- The correlation ID and other context fields (certificateId, eventId, principal) are populated in MDC and appear in all log lines without call-site intervention

**Tests** (src/test/java and src/test/resources):
- Test strategy: real PostgreSQL on an external instance (dcos-infra locally, a service container in CI) with the dedicated `cert_api_test` database, created by scripts/setup-test-db.sh (.bat on Windows). The in-memory H2 database is removed and permanently excluded because the migrations use column types and index forms it does not support. Testcontainers is not used.
- src/test/resources/config/application.yml: test overrides layered over the main application.yml. Sets the datasource through DB_HOST, DB_PORT, DB_USER, and DB_PASSWORD, and enables Flyway clean for the test database only
- **Test isolation rules**: Seed certificates (IDs `11111111-…` through `55555555-…`) are immutable and shared across tests. A test deletes only rows it creates, tracked in a `List<UUID>` populated as each row is saved. No test may assert on a row it did not create, unless the same test created the schema state that produced it (e.g., `FlywayMigrationTest` asserts on seed rows because it calls `migrate()`). Only `FlywayMigrationTest` calls `flyway.clean()`. The `outbox` and `processed_completions` tables have no seed data and may be cleared wholesale in `@BeforeEach` or `@AfterEach`. Surefire is configured with `<runOrder>alphabetical</runOrder>` to make test ordering deterministic across platforms and surface isolation bugs consistently
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

Future stories may include:
- **API hardening**: Additional validation, rate limiting, or other defensive measures

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
- **Logging**: logstash-logback-encoder 7.4 for structured JSON output
- **Code formatting**: Spotless 2.43.0 with Google Java Format 1.22.0 (AOSP style)
- **Coverage and analysis**: JaCoCo 0.8.15; SonarQube Cloud via sonar-maven-plugin 5.7.0.6970
- **Testing**: JUnit 5, Mockito, Spring Security Test, Spring AMQP Test, real PostgreSQL 16 (no H2)
- **Build tool plugin**: Spring Boot Maven Plugin for executable JAR

## Delivery Model

Work proceeds as a sequence of numbered stories. Each story branches from main, delivers its own tests, and is merged before the next story begins. Each story updates this document to reflect the new state of the repository.

Stories 1–14 are complete. The case-insensitive status comparison fix (Story 12) is tested against uppercase status values from the actual Python orchestrator (267 tests, 97.46% line coverage). The integration was verified in a live run against the orchestrator, and the README documents the procedure, the `SERVER_PORT=8081` requirement for local runs, and the Flyway checksum caution. Prometheus metrics (Story 14) expose certificate and outbox lifecycle health via `/actuator/prometheus` (ADMIN-only), with 8 metric names producing 23 series covering all combinations of status and type and outbox states, pre-registered to ensure zero values appear in scrape output.

## Polyglot Integration Status (Story 15)

**Summary:** cert-api's integration with the DCOS platform is partially verified and partially deferred.

| Service | Status | Reference | Action |
|---------|--------|-----------|--------|
| cert-orchestrator-service | Verified | ORCHESTRATOR_FINDINGS.md | Complete; no action. |
| cert-admin-service | Attempted, blockers found | ADMIN_FINDINGS.md | Defects in cert-admin's config and code; not in cert-api scope. D1, D2 decisions recorded. |
| cert-health-service | Assessed, no integration | HEALTH_FINDINGS.md | D3 decision: non-integration for 1.0.0; deferred to service-credential story. |
| dcos-infra | Assessed, gaps found | INFRA_FINDINGS.md | Missing databases and plugin; belongs to infra team. |

**Architectural Decisions (Story 15):**

- **D1 (Envelope field naming):** cert-admin to adapt to snake_case `certificate_id` at envelope root. cert-api's wire contract is verified against the orchestrator and carries no change. Recommendation: one-line fix in cert-admin's `LifecycleEventConsumer.cs:78`.
- **D2 (Message backlog):** 322-message backlog on `cert.lifecycle.events` (measured 2026-10-03 ~14:12 UTC) purged before cert-admin integration test in Story 15. Messages are development artifacts referring to stale certificate states. Decision executed.
- **D3 (cert-health `/expiring` integration):** Declare non-integration for 1.0.0. Requires service credential story not yet planned. cert-health is coherent without it. Recommendation: defer to future story when OAuth2/JWT is available.
- **D4 (Stale queues):** Already satisfied. The four stale queues mentioned in Story 15's assessment are gone from the broker.

## Release & Versioning (Story 16)

**Current version:** 1.0.0 (in preparation; tag and verification pending)

**Version scope:** `cert-api-service` 1.0.0 marks the first release of this service. The version applies to this repository only; it does **not** imply the platform (DCOS) is at 1.0.0.

**Release criteria (being met in Story 16):**
- Version number bumped in pom.xml
- CI workflow supports building on release tags (`cert-api-X.Y.Z` pattern)
- Docker image labels version with `org.opencontainers.image.version`
- Service verified consumable against the orchestrator from the published image `ghcr.io/dcos-platform/cert-api-service:1.0.0`

**Release tag convention:** Annotated tags follow pattern `cert-api-X.Y.Z` (e.g., `cert-api-1.0.0`). CI publishes images on:
- Main branch merges: `ghcr.io/dcos-platform/cert-api-service:<commit SHA>`
- Release tags: `ghcr.io/dcos-platform/cert-api-service:X.Y.Z` and `:latest`

**Published image location:** `ghcr.io/dcos-platform/cert-api-service`

**Documentation and traceability:** CHANGELOG.md records release dates and contents. This context.md is updated with each release. Detailed verification procedures are in README.md § Verification with the Published Container Image.

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
