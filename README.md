# Transflux

Transflux is a lightweight microflow orchestration library designed to automate and coordinate state changes for business entities. It focuses on orchestrating transitions (including step sequencing, pre- / post-conditions, triggers, error handling, and compensations), unlike long‑running workflow engines like Camunda or Flowable.

## Goals
- Lightweight, embeddable library that integrates easily with existing codebases
- No dedicated persistence: operates on your existing domain entities and persistence frameworks
- Reliable orchestration of complex transitions with compensations (Saga‑like)
- Reusable components: operations, steps, triggers, listeners
- Both programmatic and declarative (via YAML DSL) definitions

See requirements.md for the full vision and scope.

## Project Status
Phase 1 (Core Foundation) is in progress. Current repository contains temporary scaffolding to validate the build and test toolchain (Java + Spock/Groovy, logging, coverage). These temporary samples will be removed once core components are implemented.

## Build
- Prerequisites: Java 11+ (project sources target Java 21 language features with Java 11+ runtime compatibility), Maven 3.9+
- Run tests: `mvn -q -DskipTests=false clean test`
- Coverage report: target/site/jacoco/index.html

## Package Structure (initial)
- org.transflux.core – Core interfaces and implementations (to be implemented)
- org.transflux.core.state – State management (to be implemented)
- org.transflux.core.transition – Transition handling (to be implemented)
- org.transflux.core.operation – Operation execution (to be implemented)
- org.transflux.core.context – Context management (to be implemented)
- org.transflux.core.exception – Exception hierarchy (to be implemented)

## Contributing and Workflow
- Default branch: main
- Commit messages: follow Conventional Commits (e.g., `feat: add state validation`, `fix: correct transition check`). See instructions.md for details and optional enforcement.

## License
Apache License 2.0. See LICENSE for details.
