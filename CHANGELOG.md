# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

### Story 7: Certificate search and pagination

#### Added

- **Dynamic filtering**: `CertificateSpecifications` provides composable JPA `Specification` factories for status, type, orchestration status, issuedBy, commonName substring matching, and expiring-before-date filtering. Each specification returns null when its filter is absent, enabling clean composition via `Specification.where(...).and(...)`.
- **Paginated list endpoint**: GET `/api/v1/certificates` supports `page` (0-indexed), `size`, and `sort` query parameters for pagination and sorting. Responses use the new `PageResponse<T>` generic record containing content, page number, page size, total elements, total pages, and boolean flags for first and last page.
- **Sort field allowlist**: `SortableField` enum restricts sorting to a fixed set (createdAt, expiresAt, issuedAt, commonName, subject, status, type), preventing arbitrary or injected sort parameters from reaching the database.
- **Expiring-soon endpoint**: GET `/api/v1/certificates/expiring` returns certificates expiring within a configurable window (default 30 days), supporting the same filtering and pagination as the general list endpoint for operational awareness.
- **Response record conversion**: `CertificateResponse` is now a record with 19 fields (including serialNumber, commonName, orchestrationStatus, renewalWindowDays, daysUntilExpiry computed at mapping time, revokedAt, revocationReason, revocationComment, requestedBy, correlationId, renewalCount, lastError, createdAt, updatedAt), replacing the previous POJO and providing cleaner immutability and string representation.
- **Comprehensive search tests**: `CertificateSpecificationsTest` (12 unit tests) covers null/present branches of each specification factory. `CertificateSearchIntegrationTest` (5 integration tests) exercises filtering by status, case-insensitive commonName substring search, paging boundary conditions, expiry window search, and sort ordering against real data.

#### Changed

- **CertificateRepository**: Now implements `JpaSpecificationExecutor<Certificate>` for dynamic filtering via `CertificateSpecifications`.
- **CertificateService**: `list()` method now accepts filter criteria (status, type, orchestration status, issuedBy, commonName, expiring window) and pagination parameters, returning a paginated `PageResponse<CertificateResponse>`.
- **CertificateController**: Exposes seven endpoints (up from five): POST `/api/v1/certificates` (create), GET `/api/v1/certificates` (list with filtering/paging/sorting), GET `/api/v1/certificates/{id}` (by id), GET `/api/v1/certificates/expiring` (expiring soon), POST `/api/v1/certificates/{id}/renew` (renew), POST `/api/v1/certificates/{id}/revoke` (revoke).

#### Design decision: specification composition and null returns

Each `CertificateSpecifications` factory method returns null when its filter is absent, allowing clean composition chains like `Specification.where(spec1).and(spec2).and(spec3)`. JPA's `and()` and `or()` methods handle null gracefully, composing only present predicates. This avoids building conditional chains in Java and keeps the filter logic close to the domain model.

#### Design decision: daysUntilExpiry as a computed field

`daysUntilExpiry` is not persisted; it is computed at mapping time from `expiresAt` and the current date. This avoids stale values in responses and keeps the entity lightweight while giving clients the information they need for expiry UI presentation.

### Story 6: Certificate creation

#### Fixed

- **RabbitMQ broker credentials**: The application's broker connection was hardcoded to the Spring Framework defaults (`guest`/`guest`), which did not match the infrastructure stack's configuration (`dcos`/`changeme`). This was a pre-existing configuration defect invisible until integration tests exercised the publish path. Broker connection now uses environment variables (`RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD`, `RABBITMQ_VHOST`) with defaults matching the infrastructure stack's published local development values in `dcos-infra/.env.example`, mirroring the existing pattern for database configuration. This unblocks event publishing in any environment and allows integration tests to exercise the real publish path.

#### Added

- **Serial number generation**: `SerialNumberGenerator` produces numbers in the format `DCOS-<yyyy>-<12 uppercase hex>`, with collision retry logic bounded at 10 attempts. A fresh random value is generated on each retry.
- **Common name derivation**: `CommonNameExtractor` accepts an explicit common name from the request or derives it from the subject's CN RDN. Validation fails if neither is available.
- **Certificate state machine**: `CertificateStateMachine` is a pure logic class without framework dependencies, implementing the full transition table from the design document. Story 6 exercises the create (→ ACTIVE) and revoke (terminal from ACTIVE or EXPIRED) transitions. All transitions are testable in isolation.
- **Create request record**: `CertificateCreateRequest` is a Java record with complete field-level validation per the design document. Optional fields: `commonName` (derived from subject if absent), `renewalWindowDays` (default 30), and `correlationId`.
- **Principal capture**: The service receives the principal name as a method parameter from the controller, which extracts it from the security context. The service never reaches into the security framework, keeping it testable without security context.
- **Duplicate detection**: A `DataIntegrityViolationException` on the partial unique index is caught and translated to `409 Conflict` with `CERT_DUPLICATE_ACTIVE` error code.
- **Error code enumeration**: `ErrorCode` centralizes all error codes for client-side handling. Each exception handler populates the `code` field in the ProblemDetail.
- **Database migration V3**: Adds NOT NULL constraints to `serial_number`, `common_name`, and `requested_by`; backfills existing nulls with placeholder values marked for manual review; creates the partial unique index on `(subject, type) WHERE status = 'ACTIVE'`.
- **Comprehensive tests**: Unit tests for serial generation (format, uniqueness, retry), common name extraction (from request, from subject, CN parsing, quoting), and state machine (all transitions). Service tests for create with principal and serial capture, common name derivation, and collision retry. Controller slice tests verify Location header, authorization, validation errors, and authentication requirement. Integration tests against real PostgreSQL verify all three deferred fields persist and the partial unique index rejects true duplicates while permitting revoked and type-differing cases. Event publisher is stubbed in integration tests with `@MockBean` because creation currently publishes inside its transaction — a scoping decision, not an oversight. The outbox pattern work in a later story will decouple this, removing the broker dependency from integration tests. Test data cleanup preserves seeded rows (five certificates with IDs `1111...` through `5555...`) and removes rows created by tests.
- **Index validation test**: Proves the partial unique index is essential by dropping it and verifying that duplicates are allowed without it, then restoring it and confirming duplicates are rejected. Demonstrates the guard against race conditions that application-level check-then-insert cannot catch.

#### Changed

- **Certificate service**: `create()` now accepts a `CertificateCreateRequest` and principal name, populating serial number, common name (derived if needed), and requesting principal before persisting.
- **Repository**: Added `existsBySerialNumber()` finder for collision checking during serial generation retry.
- **Global exception handler**: Handles `DuplicateCertificateException` and `DataIntegrityViolationException`, returning 409 Conflict. All handlers now populate the `code` field.
- **Controller endpoints**: Create returns 201 with Location header and extracts principal from security context.
- **Endpoint HTTP methods** (breaking change for clients): Renew endpoint changed from `PUT /api/v1/certificates/{id}/renew` to `POST /api/v1/certificates/{id}/renew`. Revoke endpoint changed from `PATCH /api/v1/certificates/{id}/revoke` to `POST /api/v1/certificates/{id}/revoke`. These changes align the API with the design document's specification and the principle that state-changing operations should not use GET, but the change breaks existing clients that expect PUT/PATCH.

#### Restored

- **Deferred constraints**: NOT NULL on `serial_number`, `common_name`, and `requested_by`. Partial unique index preventing two active certificates for the same (subject, type).

#### Design decision: event publisher stubbing in integration tests

The creation integration tests stub `CertificateEventPublisher` because certificate creation publishes events inside its `@Transactional` method. This couples the tests to broker availability. Rather than add a RabbitMQ service to the CI pipeline now, we stub the publisher in these tests, keeping the database path entirely real and allowing the integration tests to pass without broker. The outbox pattern work in the event publication story (coming after all domain stories) will decouple event publishing from the transaction, removing this coupling. At that point, the broker can be removed from integration tests and the publisher can be exercised in its own story's tests.

#### Design decision: partial unique index is load-bearing

The partial unique index `idx_certificates_subject_type_active` on `(subject, type) WHERE status = 'ACTIVE'` was verified as essential through manual experiment: with the index present, the duplicate-rejection test passes; without it, duplicates are silently allowed. The index guards against race conditions that application-level check-then-insert cannot catch. No automated test mutates the schema by design — testing the index is a one-off verification that does not belong in the test suite.

#### Design decision: pipeline has no broker service

The CI pipeline does not include a RabbitMQ service. Integration tests for certificate creation (this story) stub the event publisher, so they pass without a broker. When event publishing becomes the subject of a later story, the broker service will be added to the pipeline at that time. This avoids keeping a service running in CI that nothing has responsibility for testing yet.

#### Design decision: no test alters database schema

Tests may insert, update, and delete rows. Creating or dropping tables, indexes, or constraints inside a test is forbidden — it makes results order-dependent and leaves shared state broken on failure. The partial unique index is verified separately, outside the test suite.

#### Design decision: request record

`CertificateCreateRequest` is authored as a record per code conventions.

### Story 5: Persistent schema and expanded certificate model

#### Added

- **Migration-managed schema**: Flyway now owns the database schema. The first migration creates the `dcos_certificates` schema and the `certificates` table, with check constraints on type, status, orchestration status, revocation reason, validity dates, and renewal window, plus a unique serial number and indexes for common lookups.
- **Seed data**: A second migration loads five fictional certificates with fixed identifiers (`11111111-…` to `55555555-…`), so tests and demos can reference them reliably. The insert is safe to re-run.
- **Full certificate model**: The certificate entity now carries serial number, common name, orchestration status, renewal window, revocation detail, requesting principal, correlation id, last error, renewal count, and creation/update timestamps. New enumerations: `CertificateType`, `OrchestrationStatus`, `RevocationReason`.
- **Real-database tests**: Integration tests run against real PostgreSQL. A migration test rebuilds the schema from empty and checks the seed data. A context test boots the whole application with schema validation on. Repository tests read real data.
- **Shared test fixture**: `CertificateFixtures` builds valid certificates in the active, expired, revoked, and renewal-window states for all tests to reuse.
- **Clear failure when the test database is down**: Instead of a raw connection error, tests stop with a "TEST DATABASE UNAVAILABLE" message that names the setup script to run.
- **Pipeline database**: The CI build job now starts a health-checked PostgreSQL 16 service with the test database, so database tests run in the pipeline as they do locally.
- **Coverage accuracy**: A root `lombok.config` marks Lombok-generated accessors so JaCoCo excludes them. Coverage now reflects hand-written code only.
- **Line endings**: A root `.gitattributes` forces LF endings for shell scripts and `mvnw`, which previously failed on Linux when checked out with Windows line endings.

#### Changed

- **Database connection**: The service now targets the shared `dcos` database from dcos-infra, configured through `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB`, `POSTGRES_USER`, and `POSTGRES_PASSWORD`. The old `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` settings pointed at a database and credentials that existed nowhere, and they are gone.
- **Schema validation instead of generation**: Hibernate now validates the entity mapping against the migrated schema (`ddl-auto: validate`) instead of altering the schema. The application refuses to start if they disagree.
- **Open session in view disabled**.
- **Test database setup scripts**: Defaults now match the dcos-infra stack and the test configuration (user `dcos`, password taken from `DB_PASSWORD`). The Windows script's default handling was also corrected.

#### Removed

- **H2 in-memory database**: Removed from the build. The migrations use PostgreSQL column types and index forms that H2 does not support, so tests run only against real PostgreSQL.

#### Design decision: deferred constraints

The first migration makes three columns nullable (serial_number, common_name, requested_by) and omits the revoked-consistency check, deferring their enforcement to the stories that populate them:
- Story 6 (certificate creation) will add NOT NULL to serial_number, common_name, requested_by and the unique constraint on serial_number.
- Story 8 (lifecycle transitions) will add the check constraint that ties REVOKED status to revoked_at presence.

This boundary allows the current service code to create and revoke certificates immediately, with both operations succeeding against a real database, while the schema evolves alongside the feature implementations that complete them.

### Story 4: Container image build and publish

#### Added

- **Container Image Definition**: Multi-stage Dockerfile using Temurin 21 JDK for build and Temurin 21 JRE for runtime, optimizing image size and security
- **Dependency Layer Caching**: Build stage layers ordered to maximize Docker cache efficiency — wrapper and build file resolved before source code copy, so dependency downloads do not re-run on source changes
- **Non-root Runtime User**: Container runs as unprivileged `app` user, reducing attack surface and passing security scanning policies
- **OCI Metadata Labels**: Image tagged with source repository URL and commit SHA for production traceability
- **Build Context Optimization**: .dockerignore file excludes build output, version control, IDE files, and documentation to minimize build context size and upload overhead
- **Container Registry Publishing**: GitHub Actions workflow extended with Docker login, build, and conditional push to GitHub Container Registry (ghcr.io)
- **Pull Request Build Verification**: Image builds on pull requests without pushing, proving the Dockerfile is valid before merge
- **Trunk Push Publishing**: Image publishes to ghcr.io on pushes to main, tagged with commit SHA and moving `latest` tag for production consumption
- **Documentation**: README updated with local build and container run instructions, including all required environment variables

#### Changed

- **GitHub Actions Permissions**: Job now granted `packages: write` permission to enable container registry publishing
- **Docker Actions**: Workflow uses maintained Docker actions (setup-buildx, login, build-push) with GitHub Actions cache for efficient builds
- **Registry Login Gating**: Registry login only runs on trunk pushes to main, avoiding authentication overhead and token use on pull requests
- **Third-party Action Pinning**: Docker actions pinned to immutable commit hashes instead of mutable version tags (setup-buildx-action to 8d2750c68a, login-action to c94ce9fb46, build-push-action to ca052bb54a) to prevent supply-chain attacks from compromised tags

### Story 3: Coverage and SonarQube quality gate

#### Added

- **Code Coverage Measurement**: JaCoCo Maven plugin integrated with two executions (prepare-agent during initialization, report during verify phase), generating XML coverage reports consumed by the scanner
- **SonarQube Cloud Analysis**: Sonar Maven plugin pinned to version 5.7.0.6970, configured to report to the hosted SonarQube Cloud service with project and organisation keys for dcos-platform
- **Quality Gate Enforcement**: Analysis configured to wait for quality gate result and fail the build when the gate fails, preventing merges of code that violates quality policies
- **Workflow Optimizations**: Full git history now fetched during checkout (fetch-depth: 0) to enable accurate blame attribution and new-code detection; SonarQube scanner cache added to avoid re-downloading analysis components on every run
- **Separation of Concerns**: Analysis step runs independently after clean verify, allowing build failures to be distinguished from analysis failures in the pipeline log

#### Changed

- **GitHub Actions Workflow**: CI pipeline now includes dedicated analysis step with environment-based SONAR_TOKEN secret injection; checkout performs full clone for accurate SonarQube analysis
- **POM Configuration**: Added sonar.projectKey, sonar.organization, sonar.host.url, and sonar.coverage.jacoco.xmlReportPaths properties for centralized scanner configuration

### Story 2: Continuous integration — build and test

#### Added

- **GitHub Actions Workflow**: Automated CI pipeline triggered on pull requests to main and pushes to main, ensuring code quality gates run on all incoming changes
- **Build Verification**: Workflow executes clean Maven verify on Java 21 with Temurin distribution and caches Maven dependencies for faster builds
- **Artifact Collection**: Surefire test reports are automatically uploaded when the build fails, enabling diagnosis without local reproduction

#### Changed

- **Build Automation**: The build now runs in the GitHub Actions pipeline on every PR and push to main, replacing manual self-assessment for determining story completion
- **Concurrency**: Workflow uses concurrency groups to cancel superseded runs, preventing redundant executions when PRs are updated frequently

## [0.0.1] - 2026-08-30

### Story 1: Verified baseline and build toolchain

#### Changed

- **Java Version**: Upgraded from Java 17 to Java 21 LTS for improved language features and long-term support
- **Code Formatting**: Added Spotless Maven plugin with Google Java Format (AOSP style) to maintain consistent code style across the project
- **Build Reproducibility**: Added Maven wrapper pinned to Maven 3.9.16 to ensure consistent builds across different environments without requiring machine-specific setup
- **Dependency Management**: Added Lombok at provided scope for annotation processing support in the IDE and during compilation

#### Added

- **Database Setup**: Added test database setup scripts for PostgreSQL (`scripts/setup-test-db.sh` for Unix-like systems and `scripts/setup-test-db.bat` for Windows) with clear error messages when database connections fail
- **Documentation**: Added Prerequisites section in README.md documenting PostgreSQL requirement and setup instructions for the test database

#### Verified

- Clean Maven build with `mvn clean verify` on Java 21
- All 16 unit and integration tests pass with no skipped tests
- Spotless formatting check passes on Java 21 with Google Java Format (AOSP style)
- Maven wrapper enables reproducible builds without system-wide Maven installation
