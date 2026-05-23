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
- `org.transflux.core` — entry point (`Transflux`), `StateMachine` / `StateMachineDef`, `ContextScope`, the `Identifiable` marker, and the `Preconditions` argument-precondition helpers.
- `org.transflux.core.state` — `State`, `StateDef`, and the host-supplied `StateResolver` / `StateApplier` bridges.
- `org.transflux.core.transition` — `Transition`, `TransitionDef`, `TransitionResult`, and `StepPath` (the qualified-id value carrier in `TransitionResult.executedStepIds` / `compensatedStepIds`).
- `org.transflux.core.operation` — `Operation`, `Step`, `Compensation`, `ContextMapper`, and their def-side types (`SimpleOperationDef` / `CompositeOperationDef` / `StepDef` / `MapperDef` / `ConditionalStepDef` / `BranchDef` / `DefaultBranchDef` / `NoMatchBehavior`).
- `org.transflux.core.condition` — `Condition` and `ConditionDescriptor`.
- `org.transflux.core.exception` — `TransfluxException` and its subclasses.
- `org.transflux.core.impl` — framework-internal implementations: every `*Impl`, the `Registry` / `Component` lookup machinery, the bound-record / action-ref / mapper-ref infrastructure, the SpEL evaluation utilities (`ConditionResolver`, `SpelConditionEvaluator`, `ExpressionIdDerivation`), the runtime-internal `TransitionView`, and the shared utilities (`ValidationUtils`, `ThrowingUtils`, `ReflectionUtils`). User code should not depend on this package directly.

## Contributing and Workflow
- Default branch: `main`.
- Commit messages: follow Conventional Commits (e.g., `feat: add state validation`, `fix: correct transition check`).

## License
Apache License 2.0. See LICENSE for details.
