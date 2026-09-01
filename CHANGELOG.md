# Changelog

All notable changes to this project will be documented in this file.

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
