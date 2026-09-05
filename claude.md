# CLAUDE RULES (GENERIC, REPO-WIDE)

These rules apply to all Claude models interacting with this repository.
Model-specific behavior is maintained outside this repository.

## Context Maintenance
- Whenever core project details change, context.md MUST be updated.
- Core changes include:
    - Directory structure changes
    - Major functionality changes
    - Architectural changes
    - Technology stack changes
    - Naming convention changes
    - Adding or removing major modules
- Updates must be complete and accurate.

## Code Quality Requirements
- Maximum cognitive complexity per function: **15**
- Maximum fanout: **5** (guideline; not enforced by static analysis; service classes coordinating several collaborators may exceed this)
- NO circular dependencies
- NO classes within classes (no nested classes in main sources; test sources exempt since JUnit's nested test classes and Spring's static test configuration classes are standard practice)
- All business logic MUST have unit tests
- All new code MUST reach **≥ 80% test coverage**
- Clean code principles are mandatory
- Remove unused imports
- No inline imports

## String Literals

Business logic must not contain hardcoded string literals. When a literal is used within a single class, extract it to a `private static final` constant in that class.

When the same literal is needed by more than one class, promote it to a shared constants holder — a `final` class with a private constructor. Do not use an interface of constants, because implementing types would inherit those constants into their own public API.

When a group of related string values represents a domain concept with a fixed set of members — lifecycle statuses, event types, routing keys, error codes — model it as an enum rather than as loose constants. Do not create an enum to hold a single value.

Values that vary by environment — database names, queue names, hostnames, credentials, profile names — belong in configuration files, not in constants. Extracting them into code removes the ability to vary them per environment.

The following are exempt, because inlining is clearer and is standard practice: log message templates, exception messages used in a single place, annotation attribute values, and test fixture data.

## Repository Rules
- Never commit code; the human developer handles commits.
- All code must follow established project conventions.
- All architectural constraints must be respected.

## Documentation Requirements
- Hand-written public methods must include clear, complete documentation.
- Generated accessors (Lombok-generated getters/setters and record accessors) are exempt, as they carry no behavior to document.
- Any major architectural decision must be reflected in context.md.

## Reporting Requirements
- Never report a build result, test result, or pipeline outcome unless it came from an actual run.
- Always quote real output when reporting a result; anything not verified must be labeled as unverified.
- Do not hallucinate test results, build logs, or execution metrics.

## General Expectations
- Maintain consistency across the project.
- Follow all constraints defined in this file and context.md.
