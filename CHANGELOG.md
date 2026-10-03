# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

### Story 14: Prometheus metrics

#### Added

- **Complete Prometheus metric set**: Added 8 metric names with 23 total series to expose certificate and outbox health to Prometheus. Metrics cover certificate counts by status and type (12 series), orchestration status (4, including unreachable PROCESSING), renewal window, outbox pending and failed counts, outbox age, sweep transitions (expiry and orchestration timeout), and duplicate completions suppressed. All metrics are namespaced under `cert.` and tagged with enums only (no cardinality explosion from high-variety fields like IDs or subjects).

  - **Zero-valued series are pre-registered at startup (D6).** A missing status in a `GROUP BY` result is silent — the gauge does not appear in Prometheus output at all — so all enum combinations are registered when the collector starts, initialized to 0, then populated from queries. This ensures a fresh Prometheus scrape contains all expected series and an operator can see "no orchestrations in PROCESSING" rather than the series being absent.

  - **The outbox age gauge advances continuously at read time (D7).** Previously, the age was queried from the database and stored in an atomic, creating a staircase (refreshed every 60 seconds). A refresh is expensive and coarse-grained. The metric now stores only the epoch second of the oldest row, computed once per refresh, and the gauge function subtracts it from the current time at scrape — so the age advances between refreshes and an alert on "oldest pending > 120 s" can fire within 1 second of the threshold, not up to 60 seconds late.

  - **Grouped queries replace 12 individual counts (D8).** Previously, `CertificateMetricsCollector.refreshCertificateCounts()` issued one `SELECT count(*)` per (status, type) combination in nested loops. This pattern does not scale to larger tables. The collector now issues one grouped `SELECT status, type, count(*) FROM certificates GROUP BY status, type` and populates all 12 series from the result in a single pass. Same pattern for orchestration status, outbox state, and other multi-axis metrics.

- **New metric components**:
  - `MetricNames`: shared constants for all 8 metric names and 4 tag keys, used by multiple classes to prevent hardcoding.
  - `SweepKind`: enum for sweep types (EXPIRY, ORCHESTRATION_TIMEOUT) with tag values, replacing string literals.
  - `CertificateOperationMetrics`: component for recording sweep transitions and duplicate completions suppressed, with counters pre-registered at construction.
  - Three projection interfaces (`StatusTypeCount`, `OrchestrationStatusCount`, `OutboxStateCount`) for typed grouped-query results.

#### Changed

- **Repository methods**: `CertificateRepository.countByStatusAndType()` replaced with `countGroupedByStatusAndType()` (returns projections). Added `countGroupedByOrchestrationStatus()` and `countCertificatesInRenewalWindow()` (native query with the renewal window logic from the schema). `OutboxRepository.getOldestPendingAgeSeconds()` replaced with `getOldestPendingEpochSeconds()` returning the stored epoch, not the computed age, and `countGroupedByState()` added for grouped outbox counts.

- **Call sites wired for metrics**: `CertificateExpirySweep` and `OrchestrationTimeoutSweep` inject `CertificateOperationMetrics` and call `recordSweepTransitions(kind, count)` after each sweep. `CompletionListener` injects the same and calls `recordDuplicateCompletionSuppressed()` when the inbox rejects a duplicate event.

- **Application configuration**: `management.metrics.tags.application: cert-api-service` added to `application.yml`, so all metrics carry a common tag identifying the source service. The `/actuator/prometheus` endpoint requires HTTP Basic authentication with the ADMIN role, unchanged from Story 11.

- **Test fixtures**: `CertificateMetricsCollectorTest` expanded to verify all 8 metric names, zero-valued series (D6), age gauge advancement (D7), cardinality (D5), and grouped queries. `CompletionListenerTest` and `CompletionListenerCorrelationTest` now pass a real `CertificateOperationMetrics` with a `SimpleMeterRegistry`. `SecurityConfigTest` manually triggers refresh and verifies the Prometheus endpoint, JVM metrics, and application tag. `RenewalCollisionTest` enhanced with error code assertion.

#### Documentation

- `README.md`: Added Metrics section with endpoint details, scrape configuration, metric inventory table, cardinality policy, refresh interval notes, and manual curl example.
- `CHANGELOG.md`: This entry, covering decisions D2 (namespace), D4 (application tag), D6 (zero-valued series), and D7 (continuous age).
- `context.md`: Updated to reflect 8 names and 23 series (was 13, which was wrong for both the old and new designs), corrected Story 13 description to name the listener auto-startup fix and resolve-before-claim reordering, fixed schema migration count from 3 to 7, added metrics package and projection interfaces to structure section, and updated test and coverage figures.

### Story 14c: Fix test isolation defects

#### Fixed

- **Test isolation: seed data deleted by non-seeded tests.** The CI run of Story 14 (commit `38d52ee`) failed with five tests reporting empty seeded certificate lists, while the same tests passed locally. Two test classes added in Story 14 called unconditional `repository.deleteAll()` in `@BeforeEach` without transactional rollback, deleting the five V2 seed certificates (IDs `11111111-…` through `55555555-…`) for the rest of the JVM. Surefire's default test ordering differs between Linux (CI) and Windows (local), changing which tests ran after the delete and were starved of seed data. The local/CI difference was a test-ordering-dependent isolation bug, not a regression.

  **The fix:** Tests may delete only rows they create.
  1. `RenewalCollisionTest` removed `@BeforeEach deleteAll()` outright and replaced it with `@AfterEach` that deletes only the IDs this test created, tracked in a `List<UUID>` as each certificate is saved.
  2. `CertificateMetricsCollectorTest` removed `certificateRepository.deleteAll()` from `@BeforeEach` (kept `outboxRepository.deleteAll()` because outbox has no seed data), converted every certificate gauge assertion from absolute count to delta (reading gauge before and after inserting a test fixture, asserting the increase), and added `@AfterEach` to delete only created certificate IDs. The EXPIRED zero-bucket assertion changed from "EXPIRED/TLS = 0" to "EXPIRED/TLS series exists + delta = 1 when EXPIRED cert inserted" — the key property, which works regardless of ambient seed rows.
  3. `CertificateSearchIntegrationTest` replaced the hardcoded `hasSize(6)` assertion (which coupled the test to seed count) with behavior assertions: every returned row has status ACTIVE, and the set contains the certificate this test created. The class's existing `@BeforeEach`/`@AfterEach` filter protecting seeds was left alone — it is the correct pattern.
  4. Maven Surefire configured with `<runOrder>alphabetical</runOrder>` so test ordering is deterministic and isolation bugs fail the same way on both platforms instead of hiding on Windows.

#### Documentation

- `context.md`: Added test isolation rule to the Testing Strategy section documenting that V2 seeds are immutable, only `FlywayMigrationTest` may `clean()`, tests must delete only rows they created, and `outbox`/`processed_completions` have no seeds and may be cleared wholesale.
- `CHANGELOG.md`: This entry documenting the isolation fix and ordering determinism for future prevention.

#### Acceptance verification

**Pool cap fix impact:**
- Measured peak connections: 97 before fix, ~30 after (capped at 3 connections per context)
- 9 cached Spring test contexts × 3 connections per context = 27 max connections vs 90 before
- PostgreSQL max_connections = 100; margin increased from 3 to 73

**Default-order build (alphabetical) — PASSED:**
- Command: `./mvnw clean verify`
- Real output: `[INFO] Tests run: 267, Failures: 0, Errors: 0, Skipped: 0`
- Final result: `[INFO] BUILD SUCCESS` ✓
- Exit code: 0
- Total time: 02:31 min

**Reverse-order build (run 1) — PASSED:**
- Command: `./mvnw clean verify -Dsurefire.runOrder=reversealphabetical`
- Real output: `[INFO] Tests run: 267, Failures: 0, Errors: 0, Skipped: 0`
- Final result: `[INFO] BUILD SUCCESS` ✓
- Exit code: 0
- Total time: 02:22 min

**Reverse-order build (run 2) — PASSED:**
- Command: `./mvnw clean verify -Dsurefire.runOrder=reversealphabetical`
- Real output: `[INFO] Tests run: 267, Failures: 0, Errors: 0, Skipped: 0`
- Final result: `[INFO] BUILD SUCCESS` ✓
- Exit code: 0
- Total time: 02:23 min

**Connection exhaustion errors:**
- Grep: `grep -i "too many clients"` on all three build logs
- Real result: 0 occurrences ✓

**Test isolation:**
- `CertificateRepositoryTest` owns test data: inserts 5 fixtures with fresh UUIDs in @BeforeEach, @DataJpaTest rolls back transaction
- `CertificateSearchIntegrationTest` verified: all assertions scoped to test-created rows; seed rows preserved by @BeforeEach/@AfterEach filters
- Ambient-row assertions fixed: `grep -nE "containsExactly|contains.*getId|containsOnly" src/test/java/com/dcos/platform/certapi/repository/CertificateRepositoryTest.java` shows all assertions now use `.contains()` for created IDs and `.containsOnly()` for status validation ✓

**JaCoCo coverage (Story 14 metrics classes):**
- CertificateMetricsCollector: 83/83 lines (100%) ✓
- CertificateOperationMetrics: 15/15 lines (100%) ✓
- SweepKind: 7/7 lines (100%) ✓
- Exception handling test added to cover refresh() catch block and computeOutboxAge() zero path

**Fixes completed (all working in alphabetical order):**
- pom.xml: Moved `<runOrder>alphabetical</runOrder>` from plugin config to `<surefire.runOrder>` property (allows -D override) ✓
- CompletionListenerIntegrationTest: Replaced `certificateRepository.deleteAll()` with tracked created IDs ✓

**Remaining deleteAll() calls** (all on seedless tables, safe):
- CertificateMetricsCollectorTest line 43: `outboxRepository.deleteAll()` (@BeforeEach)
- OutboxIntegrationTest line 46: `outboxRepository.deleteAll()` (@BeforeEach)
- EventHistoryEndpointTest line 49: `outboxRepository.deleteAll()` (@BeforeEach)

### Story 13: Consolidation and polish

#### Fixed

- **Subject validation threw `StackOverflowError` on a long subject** (SonarCloud S5998). `SubjectFormatValidator` matched the whole distinguished name with `^([A-Z]+=(?:[^,=]|\\.)+)(,\s*[A-Z]+=(?:[^,=]|\\.)+)*$`. `java.util.regex` recurses once per iteration when a quantifier is applied to a group rather than to a plain character class, so a subject of 50,000 characters overflowed the stack — verified directly, not inferred. The `@Size(max = 255)` on the field does not prevent this, because Bean Validation evaluates every constraint on a value rather than stopping at the first failure, so an oversized subject still reaches the validator. The alternation was also ambiguous, since `[^,=]` already matches a backslash and so an escape sequence had two parses, though a backtracking blowup could not be reproduced.

  Replaced with an iterative scan that is linear in the length of the input: split on unescaped commas, then check each relative distinguished name has an uppercase type, a non-empty value, and no further unescaped equals sign. The only pattern retained applies to an attribute type and is a plain character class, which the matcher implements without recursion. Behaviour is unchanged for well-formed and malformed subjects alike; tests were added for the overflow case, escaped equals signs, empty values, trailing separators and lowercase types.

- **Every Spring test context attached its own consumer to the shared completions queue, including contexts whose repositories were mocks.** This was the cause of the intermittent CI failures in `CompletionListenerIntegrationTest`, which moved between tests and between runs and were unaffected by raising the timeout from five seconds to thirty.

  `completionListenerContainerFactory` never called `setAutoStartup`, so it defaulted to true. The `spring.rabbitmq.listener.simple.auto-startup: false` in the test configuration looked like it covered this, but that property configures only Boot's own auto-configured container factory and never reaches a custom one. Any test class declaring `@MockBean` gets its own Spring context, each context is cached for the rest of the JVM run, and each one therefore held a live consumer on `certificate.lifecycle.completions` — `CompletionListenerTransientFailureTest` among them, where `CertificateRepository` is a Mockito mock. RabbitMQ round-robins deliveries between consumers, so a completion published by one test could be delivered to a listener whose repository returns `Optional.empty()` for every id. It reported the certificate absent, discarded the message, and the publishing test waited for a change that could never arrive. The CI log shows both consumers within a tenth of a second: one updating certificate `04b543f8`, the next reporting that same certificate not found.

  The container factory now honours `cert-api.rabbitmq.completion-listener-auto-startup`, which defaults to true and is set false for tests, so a consumer exists only where a test deliberately starts one. `CompletionListenerAutoStartupTest` guards the production direction of that wiring, since nothing else in the suite would notice if the property stopped taking effect — the service would start cleanly and silently consume nothing.

- **Listener tests reset shared state underneath a live consumer.** `@BeforeEach` and `@AfterEach` purged the queue and deleted every certificate while the container was still attached, racing whatever was being delivered. Both listener test classes now stop the container and wait for the broker to confirm the consumer is gone before touching the queue or the database, and only then reattach. `ListenerTestSupport` gained `stopListenersAndAwaitNoConsumer` for this; neither `stop()` nor `isRunning()` is trusted, because the former is bounded by the shutdown timeout rather than synchronous and the latter reports the lifecycle flag rather than consumer state.

- **Completion events for an unresolved certificate were lost permanently and silently.** (Found by inspection while diagnosing the above; it was not the cause of the CI failures.) `CompletionListener` claimed the event id in the inbox *before* looking the certificate up, and treated a missing certificate as a no-op: it logged a warning and acknowledged. The combination is worse than either half. The acknowledgement discards the message, and the inbox row that was already committed causes any redelivery of the same event to be suppressed as a duplicate — so the completion can never be applied, and nothing is left in a dead-letter queue to show that it happened. The order is now reversed: the certificate is resolved first, and an unknown certificate raises `CertificateNotFoundException`, leaving no inbox row so a redelivery can still succeed. Retries are bounded by the container's retry policy, after which the message is dead-lettered and observable. A malformed certificate id is still logged and acknowledged, because no redelivery can fix it.

  This supersedes two design decisions recorded under Story 10, *inbox-first ordering* and *unknown certificate IDs are acknowledged*. The stated motivation for both — avoiding an infinite retry loop — does not require acknowledgement: `defaultRequeueRejected(false)` plus the retry policy already bound the number of attempts, so rejecting cannot loop.

  Known trade-off of the new ordering: a redelivery of an event that was already applied will now be dead-lettered rather than recognised as a duplicate, if its certificate has since ceased to exist. Reaching that state requires a certificate to be deleted, and this service exposes no delete — revocation is a status change — so it is currently unreachable in production. Making the duplicate check take precedence over the not-found check would close it.

- **Listener integration tests could not say where a lost message went.** A timeout reported only that the certificate had not reached the expected status, which is the same symptom for a message that was never routed, never consumed, or dead-lettered. `CompletionListenerIntegrationTest` now asserts in two stages — inbox row, then certificate — and includes the broker's message and consumer counts for both the completions queue and the dead-letter queue in the failure description.

#### Removed

- **Test output capture artifact**: `test_output.txt` (419 KB) was a captured test execution output added during Story 9 for temporary verification. The manual verification is complete and the file is no longer needed.
- **Temporary drop-index SQL scripts**: Both `drop-index.sql` (root) and `scripts/drop-index-for-testing.sql` were added in Story 6 to support manual testing of the partial unique index constraint. These scripts silently dropped the `idx_certificates_subject_type_active` index, creating the exact defect that was found and corrected during Story 6. Since no schema-altering tests are authored (per project constraints), these files serve no purpose and present a maintenance hazard: running either script removes the constraint preventing duplicate active certificates with no test or deployment safeguard. The manual testing they supported is complete; the index is restored and protected.

#### Changed

- **Unused import removal**: Spotless configuration now includes removeUnusedImports, closing a gap where unused imports were neither removed nor flagged despite the project's clean-code mandate.

#### Added

- **Gitignore improvements**: Added patterns for JVM crash dumps (`hs_err_pid*.log` variants) and captured output files (`*_output.txt`), adopting a naming convention for test captures that the ignore file matches.

### Story 12: End-to-end verification against the orchestrator

#### Fixed

- **Status comparison case-sensitivity defect**: The `CompletionListener` was comparing completion status against hardcoded lowercase literals ("completed", "failed"), but the Python orchestrator emits uppercase status values. Status comparison is now case-insensitive via `toLowerCase()`, allowing the listener to accept both cases. This eliminates silent failures where completion events arrive but fail to update certificate state.

#### Added

- **End-to-end verification procedure** in the README: infrastructure start, orchestrator prerequisites (separate database created manually, both connection strings via the `CERT_ORCH_` prefix, Alembic migrations required upstream), cert-api on an alternative port, certificate creation, and the checkpoints at each hop (outbox, queue depths and consumers, orchestration status transition).
- **Port requirement**: cert-api must run with `SERVER_PORT=8081` while the dcos-infra stack is up, because Adminer occupies host port 8080.
- **Migration caution**: editing a Flyway migration after it has been applied leaves the service unbootable with a checksum mismatch until the schema is rebuilt.
- **Port requirement documentation**: Running cert-api when the dcos-infra stack is active requires `SERVER_PORT=8081` to avoid collision with Adminer on port 8080.
- **Migration safety guidance**: Caution about Flyway checksum detection when migrations are edited after being applied to the database.

#### Changed

- **Test fixtures for realistic contracts**: All integration tests now send uppercase status values (COMPLETED, FAILED) matching the actual orchestrator behavior, catching the case-sensitivity bug rather than masking it with lowercase fixtures.

#### Acceptance verification

- Full `mvn clean verify` exits zero with all 214 tests passing
- Line coverage: 712 covered / 735 total = 96.87%
- Status comparison is case-insensitive; tests reflect uppercase contract from real orchestrator
- Cross-service integration verified by a live run rather than by contract assertion alone: a certificate was created as `ACTIVE`/`PENDING` and about twelve seconds later read `ACTIVE`/`COMPLETED` with no last error. Every completion arrived with `status` in uppercase, confirming the case fix was necessary. `status` never changed, so the ownership boundary held across services

### Story 11: Structured logging and correlation

#### Added

- **Structured JSON logging**: logstash-logback-encoder 7.4 dependency added and pinned for production JSON output.
- **Logback configuration**: Two profiles in logback-spring.xml—plain-text pattern for development (default) including correlation ID in brackets, and JSON profile for deployment using logstash encoder with MDC fields included.
- **Correlation filter**: `CorrelationFilter` servlet filter at highest precedence reads incoming `x-correlation-id` header, generates a UUID when absent, places it in MDC, echoes it on the response, and clears MDC in a finally block to prevent leaks across pooled threads.
- **Logging context constants**: `LoggingContext` constants for MDC key names (correlationId, certificateId, eventId, principal) and service name, avoiding string literals at call sites.
- **Correlation capture in outbox**: `OutboxEnqueueService` now captures the correlation ID from MDC when enqueueing, with fallback to the certificate's correlation ID (for operations like the expiry sweep that run without request context), and stores it on the outbox row.
- **Correlation propagation in published messages**: `OutboxRelay` uses the stored correlation ID in the message `x-correlation-id` header (falling back to event ID when absent), fixing the defect where every message had a unique correlation ID and cross-service tracing was impossible.
- **Correlation restoration in completion listener**: `CompletionListener` restores the correlation ID from the inbound `x-correlation-id` header into MDC for the duration of handling, with fallback to the certificate's correlation ID when the header is absent, and clears MDC in a finally block.
- **Outbox correlation column**: Migration V7 adds correlation_id column (varchar 36, nullable) to the outbox table. Existing rows leave it null; new rows store the correlation ID.
- **MDC context population**: CertificateService populates MDC with certificate ID and principal during operations for richer log context.
- **Comprehensive tests for correlation**: Filter tests verify preserved headers, generated IDs, MDC population during handling, and cleanup in finally blocks. Outbox integration tests verify correlation ID capture from MDC and fallback to certificate ID. Outbox relay unit tests verify the stored correlation ID is used in message headers and fallback to event ID when absent.

#### Changed

- **pom.xml**: Added logstash-logback-encoder version property (7.4) and dependency.
- **logback configuration**: Replaces the minimal application.yml logging section with full Logback configuration supporting multiple profiles.
- **Outbox domain**: Added correlationId field to Outbox entity.
- **CompletionListener**: Accepts optional `x-correlation-id` header parameter, restores correlation context during message handling, and clears it in finally block.

#### Design decision: correlation fallback strategy

When a request has no MDC correlation ID (e.g., the expiry sweep running on a scheduler), the outbox enqueue captures the certificate's own correlationId field. This ensures even scheduled operations can be correlated by certificate. Similarly, the completion listener falls back to certificate ID when the inbound header is absent—a log line correlatable by certificate is far better than one correlatable by nothing. The relay falls back to the event ID to prevent null headers.

#### Design decision: MDC cleanup in finally blocks

Both the filter and completion listener clear MDC in finally blocks, even when handlers throw exceptions. Failing to clear leaks identifiers across requests on pooled threads, producing log lines incorrectly attributed to the wrong request—a bug that is very hard to diagnose later. The finally block ensures cleanup always occurs.

#### Design decision: production profile, not environment-specific overrides

The logstash encoder and JSON format are active only under the `prod` profile, not driven by environment variables or complex conditional logic. This keeps the default behavior (readable local logs) and the production behavior (structured JSON) clearly separated and testable.

### Story 10: Completion event consumption and orchestration timeout handling

#### Added

- **Completion event listener**: `CompletionListener` consumes completion events from the orchestrator (from queue `certificate.lifecycle.completions`), validates certificate UUID format, inserts an inbox record (`ProcessedCompletion`) for idempotency (primary-key collision signals duplicate delivery), and updates the certificate's orchestration status and error. Ignores unknown certificate IDs (logs and acknowledges) to avoid infinite retry loops.
- **ProcessedCompletion entity and repository**: Domain model and repository for the completion inbox table (created in migration V6), using manually-assigned event ID as primary key. Duplicate deliveries are detected via primary-key collision.
- **Inbox table migration V6**: Creates `processed_completions` table with columns: `event_id` (VARCHAR, primary key), `certificate_id` (UUID, foreign key to certificates), `created_at` (TIMESTAMP). Includes index on certificate_id for lookups by certificate.
- **Completion event contract**: `CompletionEvent` record carries event_id, certificate_id (as string), status (enum: completed/failed), retry_count, and optional error message. Contract-tested for JSON serialization with snake_case field names.
- **Orchestration timeout sweep**: `OrchestrationTimeoutSweep` is a scheduled component (configurable interval, default 60 seconds via `cert-api.orchestration.sweep-interval-ms`) that sweeps certificates stuck in PENDING state for longer than a configurable timeout (default 2 minutes via `cert-api.orchestration.pending-timeout`). Moves stale pending certificates to FAILED with explicit error reason, leaving only orchestration_status and last_error modified (certificate status is never touched).
- **Bounded retry configuration**: `completionListenerContainerFactory` bean wires stateful retry with exponential backoff (3 max attempts, 1s initial, 2x multiplier) via `RetryTemplate` and custom error handler. Conversion errors and failed retries are republished to the dead-letter exchange for inspection.
- **Custom error handler and dead-letter integration**: Conditional error handler checks for conversion errors and listener execution failures; when detected, republishes to the DLX via `RepublishMessageRecoverer` for centralized dead-letter queue inspection.
- **Comprehensive tests**: Unit tests with mocks verify successful/failed completions, duplicate idempotency, invalid UUID rejection, unknown certificate rejection, and retry-suffix preservation. Integration tests against real PostgreSQL and RabbitMQ verify message round-trip, duplicate handling, outbox compatibility (non-ASCII subjects), and timeout sweep behavior.

#### Changed

- **RabbitMqConfig**: Added completion listener container factory, dead-letter exchange/queue, error handler, and retry template. Added `completions-queue` configuration. Snake_case AMQP mapper reused from Story 9.

#### Design decision: conflict-free duplicate detection

Duplicate detection uses `ProcessedCompletionRepository.insertIfAbsent`, a native `INSERT ... ON CONFLICT (event_id) DO NOTHING` statement, rather than a JPA entity save wrapped in a try/catch. Catching a `DataIntegrityViolationException` from a normal save does not work here: Spring's JPA exception translation marks the enclosing transaction rollback-only the instant the exception occurs, regardless of where it is caught, so the transaction can never commit successfully afterward — it only surfaces later as an `UnexpectedRollbackException` when the transaction tries to commit. The `ON CONFLICT DO NOTHING` statement never throws for a duplicate; it simply reports zero affected rows, so a duplicate completion is a clean, exception-free no-op and the message is acknowledged normally instead of being dead-lettered. `CompletionInboxService.recordProcessed` wraps this call.

#### Design decision: inbox-first ordering

Duplicates are detected by attempting the inbox insert first. Only if successful (no collision) is the certificate looked up and updated. This ordering ensures even a message arriving between the first and second completions for the same certificate is safely ignored.

#### Design decision: unknown certificate IDs are acknowledged

When a certificate ID is not found in the database, the message is acknowledged without requeue. This prevents infinite retry loops for orphaned event IDs (e.g., certificate deleted after creation but before completion). Such events are logged at WARN level for operational investigation but do not block the listener.

#### Design decision: retry ceiling, not exponential backoff

Same as Story 9: the retry template uses a simple attempt ceiling (default 3) rather than exponential backoff. It is simpler and works well for transient failures. The retry interceptor is stateful and requires a message ID to correlate attempts; the orchestrator's completion messages do not carry one, so retry behavior depends on Spring AMQP's default message-ID field handling.

### Story 9: Lifecycle event publication

#### Added

- **Transactional outbox pattern**: Outbox table and `Outbox` entity for reliable event publication. Events are enqueued in the same transaction as the aggregate, eliminating race conditions where the service crashes after the aggregate is saved but before publishing. A scheduled relay claims pending rows with pessimistic locking, publishes them, and marks them sent. Failed publishes increment the attempt count and are retried; when the configurable maximum is reached, the row is marked failed and logged at error level.
- **Outbox migration V5**: Creates the outbox table with event_id (unique), aggregate_id, event_type, routing_key, payload (JSONB), state, attempt_count, last_error, created_at, and sent_at columns. Includes a partial index over pending rows for efficient claiming.
- **OutboxEnqueueService**: Builds event envelopes, serializes them with the AMQP object mapper (snake_case), and writes outbox rows inside the caller's transaction. No service method calls the broker directly.
- **OutboxRelay**: Scheduled component that claims a bounded batch of pending rows, publishes each, and marks them sent. On failure, increments attempts and records the error. At the configurable maximum (default 3), marks the row failed and logs at error level.
- **Event type enum and records**: `EventType` enum with routing keys (cert.created, cert.renewed, cert.revoked, cert.expired); `CertificateEventPayload` record carrying source, schema_version, certificate_id, subject, type, status; `CertificateEventEnvelope` record carrying event_id, certificate_id, occurred_at, and payload (the wire contract).
- **Snake_case AMQP mapper**: Separate `ObjectMapper` bean with snake_case naming strategy and ISO-8601 timestamps, configured only for AMQP message conversion. The HTTP API mapper remains camelCase.
- **Publisher confirms and returns**: RabbitTemplate configured with confirm callback and return callback to handle negative acknowledgements and returned messages.
- **Rewritten topology**: Dead-letter exchange and queue for undeliverable messages. Consumer queues declared durable with no arguments (matching Python orchestrator's declaration). Both certificate.lifecycle.events (orchestrator) and cert.lifecycle.events (admin service) bound to the topic exchange with pattern cert.#.
- **Event history endpoint**: GET /api/v1/certificates/{id}/events, paged, returning event id, type, state, attempts, and created/sent timestamps. Not found for an unknown certificate.
- **OutboxEventResponse DTO**: Record for event history responses with eventId, eventType, state, attempts, createdAt, sentAt.
- **Contract tests**: `EventEnvelopeSerializationTest` verifies that each event type is serialized to snake_case JSON with ISO-8601 timestamps and payload as an object (not a string—the double-encoding trap).
- **Integration tests**: `OutboxIntegrationTest` proves that (1) a rolled-back transaction leaves no outbox row, (2) a committed transaction writes both certificate and outbox in the same transaction, and (3) payload is valid JSON. `EventHistoryEndpointTest` verifies the endpoint's response shape, paging, and not-found handling.
- **RabbitMQ in CI**: GitHub Actions workflow extended with a rabbitmq:3.13-alpine service container with credentials matching the test configuration and a health check.
- **Application configuration**: Added cert-api.outbox properties for relay interval (5000ms), batch size (10), and max attempts (3). Added cert-api.rabbitmq routing-key.expired for the new event type.
- **Test configuration**: No broker required for integration tests that don't genuinely exercise publishing. The topology and relay tests use a real broker; others do not.

#### Changed

- **CertificateService**: No longer injects `CertificateEventPublisher`. Injects `OutboxEnqueueService` instead. Create, renew, revoke, and expire now enqueue events to the outbox rather than publishing directly.
- **CertificateExpirySweep**: Injects `OutboxEnqueueService` and enqueues an EXPIRED event when expiring certificates.
- **RabbitMqConfig**: Rewritten to create the snake_case AMQP mapper, configure publisher confirms, and declare the new consumer queues and dead-letter exchange/queue. Old queue declarations (cert.created.queue, cert.renewed.queue, cert.revoked.queue) removed.
- **Test mocks**: Integration tests for creation, search, and expiry sweep now mock `OutboxEnqueueService` instead of `CertificateEventPublisher`, allowing outbox enqueue calls to be verified and preventing broker dependency.
- **CI workflow**: RabbitMQ service container added with health check.

#### Removed

- **CertificateEvent and CertificateEventPublisher**: Both become unreferenced after outbox integration and are deleted. The publisher's whole design (direct publish) is the defect being fixed.
- **Three obsolete queue declarations**: cert.created.queue, cert.renewed.queue, cert.revoked.queue no longer exist; they were declared but never consumed and are the scope failure in the original design.

#### Design decision: separate snake_case mapper

The AMQP converter uses a separate ObjectMapper configured for snake_case serialization and ISO-8601 timestamps. The HTTP API mapper remains unchanged (camelCase), so changing the global mapper is avoided. This isolation keeps message contracts decoupled from REST API contracts and prevents silent breakage of all endpoints.

#### Design decision: payload column holds serialized JSON

The outbox payload column stores serialized JSON (the envelope, already stringified). Publishing it directly as the message body bypasses the converter, avoiding double-encoding. This is the critical detail that prevents a class of silent failures where the orchestrator rejects the message without explanation.

#### Design decision: explicit message building

Rather than use `convertAndSend()` on a Java object, the relay builds the message explicitly—body from the payload bytes, properties set directly—and publishes with `send()`. This ensures the payload is not processed through the converter (which would double-encode it).

#### Design decision: relayed events are required to be resent

Events are not persisted to the outbox as a happy-path optimization; they are always relayed. This ensures every event goes through the same guarantee path and there is no second-class path where events skip the outbox.

#### Design decision: retry ceiling, not exponential backoff

The relay has a simple attempt ceiling (default 3) rather than exponential backoff. It is simpler, works well for transient failures, and avoids the delayed-message plugin which is not available in the infrastructure image.

### Story 8: Certificate lifecycle transitions

#### Added

- **Renewal request record**: `RenewalRequest` accepts only the new expiry date (must be strictly later than current), an optional renewal window (1–365 days), and an optional correlation identifier. Separation from the legacy `CertificateRequest` clarifies that renewal never modifies subject, type, or issuing authority—only validity fields and related metadata.
- **Revocation request record**: `RevocationRequest` captures a required revocation reason (enum) and optional comment (≤512 characters). Like renewal, this separation from the legacy DTO simplifies the API contract and enables type-safe reason handling.
- **Renewal behavior rewrite**: Renewal now validates the proposed new expiry against the current expiry at the application level, rejecting non-future or non-later values with `CERT_INVALID_RENEWAL` error code (400). It updates only `issuedAt` (set to now), `expiresAt`, `renewalWindowDays` (if provided), `correlationId` (if provided), and increments `renewalCount` while resetting `orchestrationStatus` to PENDING and clearing `lastError`. Subject, type, and issuing authority are explicitly left untouched, matching the design document's forbiddance of re-subjecting.
- **Revocation behavior rewrite**: Revocation now accepts a `RevocationRequest` and sets not only `status = REVOKED` but also `revokedAt = now()`, `revocationReason`, and `revocationComment`. The timestamp is the critical addition—without it, the database consistency constraint cannot hold.
- **Expiry sweep with scheduling**: `CertificateExpirySweep` is a separate component (not a method on the service) that runs on a configurable schedule (default 5 minutes via `cert-api.expiry-sweep-interval`, in milliseconds). It iterates in batches of 100 to move any ACTIVE certificate past its `expiresAt` to EXPIRED status, leaving REVOKED certificates untouched. Iteration and save (not bulk update) ensures optimistic locking and entity lifecycle callbacks work correctly. `@EnableScheduling` is added to the application class; test configuration disables scheduling to prevent test interference.
- **Revocation consistency constraint**: Database migration V4 backfills existing revoked certificates without a timestamp (setting `revokedAt = now()`), clears any timestamp from non-revoked rows, then adds the check `(status = 'REVOKED') = (revoked_at IS NOT NULL)`. This constraint is enforced at the database level, preventing a class of stale-data bugs.
- **Four new exception handlers**: `OptimisticLockingFailureException` → 409 Conflict / `CERT_CONCURRENT_MODIFICATION`; `HttpMessageNotReadableException` → 400 Bad Request / `CERT_MALFORMED_BODY`; `AccessDeniedException` → 403 Forbidden / `CERT_FORBIDDEN`; `AuthenticationException` → 401 Unauthorized / `CERT_UNAUTHENTICATED`. The existing `IllegalArgumentException` handler now maps to `CERT_INVALID_RENEWAL` since renewal validation is its primary use case.
- **Comprehensive lifecycle tests**: Unit tests with mocks verify renewal rejects non-later expiry, leaves subject/type/issuer untouched, increments renewal count, and resets orchestration status. Service layer tests confirm both renewal and revocation work from ACTIVE and EXPIRED states, renewal fails from REVOKED (with correct state validation), revocation sets the timestamp and reason, and revocation fails when already revoked. Integration tests confirm the sweep moves past-expiry certificates and leaves revoked ones alone, the consistency constraint rejects direct schema violations, and renewal of an expired cert fails (409) when an active cert already exists for that subject/type (index collision). Controller slice tests confirm 200 on success, 409 on invalid state, 400 for validation failures (missing reason, non-future expiry, non-later expiry, over-long comment), 403 for non-admin user, 401 when anonymous.

#### Changed

- **CertificateService.renew()**: Signature changed from `renew(UUID id, CertificateRequest request)` to `renew(UUID id, RenewalRequest request)`. Behavior now validates the new expiry strictly later than the current expiry, updates only validity-related fields, and resets orchestration status to PENDING.
- **CertificateService.revoke()**: Signature changed from `revoke(UUID id)` to `revoke(UUID id, RevocationRequest request)`. Behavior now accepts revocation reason and optional comment, and critically, sets `revokedAt = now()`.
- **CertificateController.renew()**: Endpoint now accepts a `RenewalRequest` body, matching the service signature.
- **CertificateController.revoke()**: Endpoint now accepts a `RevocationRequest` body, matching the service signature.
- **GlobalExceptionHandler**: Added handlers for optimistic locking failures, malformed HTTP bodies, access denial, and authentication failures. Existing `IllegalArgumentException` handler now targets renewal validation (maps to `CERT_INVALID_RENEWAL` error code).
- **CertificateRepository**: Added `findByStatusAndExpiresAtBefore(CertificateStatus status, Instant now, Pageable pageable)` for the sweep.
- **Test configuration**: `spring.task.scheduling.enabled: false` disables scheduled tasks during test runs to prevent the sweep from interfering with test data.
- **Application configuration**: Added `cert-api.expiry-sweep-interval: 300000` (5 minutes default) for sweep scheduling.

#### Removed

- **CertificateRequest DTO**: The legacy POJO is no longer used. `RenewalRequest` and `RevocationRequest` replace it, each with a narrower, clearer contract.

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
