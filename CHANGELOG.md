# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

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
