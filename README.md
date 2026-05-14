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
Phase 1 (Core Foundation) is essentially complete: programmatic state machine builder, paired `StateResolver` / `StateApplier`, `TransitionResult` with executed/compensated step lists and timing metadata, and the `TransfluxException` hierarchy are all in place. Operations, conditions, triggers, listeners, and the YAML DSL are upcoming phases.

The project is in active design and the public API is unstable. **No releases are published before v1.0** — see `todo.md` for the phased roadmap.

## Build
- Prerequisites: JDK 21+ to build (enforced via Maven toolchains); the library compiles to Java 11 bytecode and is compatible with Java 11+ runtimes. Maven 3.9+.
- Run tests: `mvn -q clean test`
- Run a single spec: `mvn -q test -Dtest=StateMachineImplSpec`
- Coverage report: `target/site/jacoco/index.html`

## Package Structure
- `org.transflux.core` — entry point (`Transflux`), `StateMachine` / `StateMachineDef`, the `Identifiable` marker, and shared internal utilities (`ValidationUtils`, `ThrowingUtils`, `ReflectionUtils`).
- `org.transflux.core.state` — `State`, `StateDef`, their `*Impl`s, and the host-supplied `StateResolver` / `StateApplier` bridges.
- `org.transflux.core.transition` — `Transition`, `TransitionDef`, their `*Impl`s, `TransitionResult`, and the runtime-internal `TransitionView`.
- `org.transflux.core.operation` — `Operation`, `Step`, their def-side types (`SimpleOperationDef` / `CompositeOperationDef`), and the bound-record infrastructure.
- `org.transflux.core.condition` — `Condition`, `ConditionDescriptor`, and the SpEL-backed evaluation utilities.
- `org.transflux.core.exception` — `TransfluxException` and its subclasses.

## Contributing and Workflow
- Default branch: `main`.
- Commit messages: follow Conventional Commits (e.g., `feat: add state validation`, `fix: correct transition check`).

## License
Apache License 2.0. See LICENSE for details.
