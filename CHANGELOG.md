# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

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
