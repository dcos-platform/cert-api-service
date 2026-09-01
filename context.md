# PROJECT CONTEXT (MODEL-FACING OVERVIEW)

This document provides AI models with an up-to-date understanding of the repository.
It describes what the project is, how it is structured, and what major components
currently exist. It does not contain rules or constraints; those live in claude.md.

## Project Overview

**cert-api-service** is a Java 21 Spring Boot service (version 3.3.2) that exposes a REST API for certificate lifecycle management. It handles creation, retrieval, renewal, and revocation of certificate metadata. The service persists data to PostgreSQL and publishes certificate lifecycle events to RabbitMQ for downstream processing. It is one component within the DCOS (distributed systems) polyglot architecture, alongside separate repositories for a Python orchestrator, a .NET admin service, a NestJS health service, and a Docker Compose infrastructure provider.

## Current Implementation State

Story 1 is complete. The core service is operational with:
- A single REST controller exposing five endpoints for certificate lifecycle operations
- A domain model with Certificate entity and three-value CertificateStatus enumeration (ACTIVE, EXPIRED, REVOKED)
- A service layer implementing business logic with state validation
- Role-based access control (USER and ADMIN roles) at the method level
- Request and response data transfer objects with request validation
- A repository for data persistence
- An event publisher that broadcasts lifecycle events to RabbitMQ
- Global exception handling with RFC 7807 Problem Detail responses
- Custom validation for certificate types (TLS, CLIENT, CA, CODE_SIGNING)
- Baseline unit tests for the controller and service layers
- OpenAPI 3.0 documentation via springdoc-openapi

The build is reproducible: Maven 3.9.16 is pinned in the wrapper, requiring only a JDK (no prerequisite Maven installation). Code formatting is enforced: Spotless with Google Java Format (AOSP style) runs in the verify phase, failing the build if formatting violations are found. Lombok is present at provided scope but not yet applied to any class.

## Repository Structure

**Top level:**
```
.gitignore
.mvn/wrapper/maven-wrapper.properties
mvnw, mvnw.cmd (Maven wrapper scripts)
pom.xml
CHANGELOG.md
LICENSE
README.md
claude.md
context.md
scripts/
  setup-test-db.sh
  setup-test-db.bat
src/
  main/java/com/dcos/platform/certapi/
  main/resources/
  test/java/com/dcos/platform/certapi/
  test/resources/
```

**Source code structure** (follows standard Maven layout):

- **CertApiServiceApplication**: Entry point with @SpringBootApplication
- **config/**: Configuration beans
  - SecurityConfig: Role-based access control, stateless authentication, in-memory user store (ADMIN and USER)
  - RabbitMqConfig: Topic exchange, queues, bindings, and message converter setup
  - OpenApiConfig: OpenAPI 3.0 schema with basic authentication scheme
- **controller/**: REST API layer
  - CertificateController: Five endpoints (POST create, GET list, GET by id, PUT renew, PATCH revoke) with OpenAPI annotations
- **domain/**: Persistent entity and enumeration
  - Certificate: JPA entity with UUID id, subject, type, status, issuedAt, expiresAt, issuedBy, and optimistic locking via @Version
  - CertificateStatus: Enumeration with ACTIVE, EXPIRED, REVOKED
- **dto/**: Request and response transfer objects
  - CertificateRequest: Input validation for create/renew operations; enforces required fields, future expiry, and valid certificate type
  - CertificateResponse: Maps from domain entity; carries all certificate metadata
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

**Configuration file** (src/main/resources/application.yml):
- Database connection to PostgreSQL (configurable via environment variables)
- Hibernate DDL auto set to "update"
- RabbitMQ connection and exchange/routing-key configuration
- Logging level for the service package (INFO)
- Actuator endpoints exposed (health, info)
- Swagger UI path (/swagger-ui.html)

**Tests** (src/test/java and src/test/resources):
- CertificateControllerTest: WebMvcTest with mocked service; covers happy path, authorization, and validation errors
- CertificateServiceTest: Unit tests with mocked repository and event publisher
- Test database uses H2 in-memory database (src/test/resources/application.yml)

## What Does Not Yet Exist

The following will arrive in future stories:
- **Database migrations**: No Flyway or Liquibase configuration; schema is currently managed via Hibernate DDL auto
- **Transactional outbox**: No durable event delivery mechanism; events are published in-transaction without guaranteed delivery
- **Certificate state machine**: Only three states (ACTIVE, EXPIRED, REVOKED); no formal state machine library or complex transitions
- **Pagination and filtering**: getAll() returns all certificates; no cursor-based pagination, search, or advanced filtering
- **Completion consumer**: No service listening for completion events from downstream systems
- **Continuous integration**: No GitHub Actions, GitLab CI, or other CI/CD pipeline
- **Test coverage reporting**: No code coverage metrics or enforcement (codecov, jacoco, etc.)
- **Static analysis**: No SpotBugs, SonarQube, or similar quality gates
- **Container image definition**: No Dockerfile
- **Database schema documentation**: No ER diagram or migration scripts

## Dependencies and Tooling

- **Java**: 21 (required)
- **Spring Boot**: 3.3.2
- **Maven**: 3.9.16 (via wrapper; no prerequisite installation needed)
- **Lombok**: 1.18.x (provided scope, not yet used)
- **PostgreSQL driver**: runtime
- **RabbitMQ AMQP client**: Spring AMQP starter
- **Security**: Spring Security with basic authentication
- **Validation**: Jakarta Validation (Bean Validation 3.0)
- **OpenAPI**: springdoc-openapi 2.5.0 with Swagger UI
- **Code formatting**: Spotless 2.43.0 with Google Java Format 1.22.0 (AOSP style)
- **Testing**: JUnit 5, Mockito, Spring Security Test, Spring AMQP Test, H2 database
- **Build tool plugin**: Spring Boot Maven Plugin for executable JAR

## Delivery Model

Work proceeds as a sequence of numbered stories. Each story branches from main, delivers its own tests, and is merged before the next story begins. Story 1 (Verify baseline and build toolchain) is complete. Each story updates this document to reflect the new state of the repository.

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
