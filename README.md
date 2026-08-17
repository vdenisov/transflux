# Transflux

Transflux is a lightweight microflow orchestration library designed to automate and coordinate state changes for business entities. It focuses on orchestrating transitions (including step sequencing, pre- / post-conditions, triggers, error handling, and compensations), unlike long‑running workflow engines like Camunda or Flowable.

## Goals
- Lightweight, embeddable library that integrates easily with existing codebases
- No dedicated persistence: operates on your existing domain entities and persistence frameworks
- Reliable orchestration of complex transitions with compensations (Saga‑like)
- Reusable components: actions, conditions, triggers, listeners
- Both programmatic and declarative (via YAML DSL) definitions

See requirements.md for the full vision and scope.

## Project Status
Phases 1 through 3 are complete: the programmatic builder, paired `StateResolver` / `StateApplier`, `TransitionResult` with executed/compensated action paths and timing metadata, actions with conditions and compensations, manual / event / data triggers, and state, transition and action listeners are all in place. Async execution, the YAML DSL, and Spring integration are upcoming phases.

The project is in active design and the public API is unstable. **No releases are published before v1.0** — see `todo.md` for the phased roadmap.

## Build
- Prerequisites: JDK 17+ to build (enforced via Maven toolchains); the library compiles to Java 17 bytecode and is compatible with Java 17+ runtimes. Maven 3.9+.
- Run tests: `mvn -q clean test`
- Run a single spec: `mvn -q test -Dtest=StateMachineImplSpec`
- Coverage report: `target/site/jacoco/index.html`

## Package Structure
- `org.transflux.core` — entry point (`Transflux`), `StateMachine` / `StateMachineDef`, `ContextScope`, the `Identifiable` marker, and the `Preconditions` argument-precondition helpers.
- `org.transflux.core.state` — `State`, `StateDef`, the host-supplied `StateResolver` / `StateApplier` bridges, and the entry/exit listener surface (`StateListener`, its `StateListenerDef` builder, and the `StateChange` / `StatePhase` payload).
- `org.transflux.core.transition` — `Transition` (the read-only runtime view) and `ExecutingTransition` (the same transition plus the `run(...)` dispatch an action's body needs), `TransitionDef`, `TransitionResult`, `ProcessResult` (the outcome of `processEvent` / `processDataChange`), `ActionPath` (the qualified-id value carrier in `TransitionResult.executedPath` / `compensatedPath`), and the start/complete/error listener surface (`TransitionListener`, its `TransitionListenerDef` builder, and the `TransitionExecution` / `TransitionPhase` payload).
- `org.transflux.core.action` — `Action` (the single executable contract) and `ActionKind`, `Compensation`, `ContextMapper`, the def-side types (`ActionDef` and its `StepDef` / `OperationDef` forms, `ConditionalOperationDef`, `MapperDef`, `BranchDef`, `DefaultBranchDef`, `NoMatchBehavior`), and the start/complete/error listener surface (`ActionListener`, its `ActionListenerDef` builder, and the `ActionExecution` / `ActionPhase` payload).
- `org.transflux.core.condition` — `Condition` and `ConditionDescriptor`.
- `org.transflux.core.exception` — `TransfluxException` and its subclasses.
- `org.transflux.core.trigger` — `Trigger` (runtime catalog view) and its kinds `ManualTrigger` / `EventTrigger` / `DataTrigger`, with the def-side builders `ManualTriggerDef` / `EventTriggerDef` / `DataTriggerDef`. Manual triggers fire via `entity(e).fire(...)`; event and data triggers fire via the host-driven `entity(e).processEvent(...)` / `processDataChange(...)`.
- `org.transflux.core.impl` — framework-internal implementations: every `*Impl`, the `Registry` / `Component` lookup machinery, the bound-record / action-ref / mapper-ref infrastructure, the SpEL evaluation utilities (`ConditionResolver`, `SpelConditionEvaluator`, `ExpressionIdDerivation`), the runtime-internal `ExecutingTransitionImpl` and `TransitionImpl`, the `Loggers` holder declaring the logger tree, and the shared utilities (`ValidationUtils`, `ThrowingUtils`, `ReflectionUtils`). User code should not depend on this package directly.

## Logging

Transflux logs through SLF4J and ships no binding or configuration of its own — the host owns both.

**Logger names are virtual packages, not class names.** Implementation types are concentrated in `org.transflux.core.impl` so they can see each other package-privately without widening the public surface, which makes the real package structure useless for configuration: you would be choosing between "all of Transflux" and one class whose name may change between releases. The names below instead describe concerns, so a host can silence `org.transflux.execution` wholesale or `org.transflux.execution.action` alone. A single class routinely spans several of them.

| Logger | Covers |
| --- | --- |
| `org.transflux.build.lifecycle` | build phase boundaries; one completion line per build |
| `org.transflux.build.validation` | ref / context / cycle checks, id claims, definition-time overwrites |
| `org.transflux.build.registry` | scope population, parenting, flattening |
| `org.transflux.build.binding` | defs to bound records — what each id resolved to, and in which scope |
| `org.transflux.execution.transition` | transition lifecycle: start, applier, outcome |
| `org.transflux.execution.action` | per-action dispatch, nesting, call-site context mapping, id resolution |
| `org.transflux.execution.condition` | pre-, post- and branch-condition evaluation |
| `org.transflux.execution.compensation` | compensation capture and drain |
| `org.transflux.execution.listener` | observer failures |
| `org.transflux.trigger` | dispatch scans, filters, gates |

**Only leaves emit.** A name is either a grouping level or a logger, never both, so no line ever arrives from `org.transflux.build` or `org.transflux.execution` themselves — they exist purely so you can configure a subtree.

**Levels.**

| Level | What to expect | Volume |
| --- | --- | --- |
| ERROR | Nothing. Failures are returned on `TransitionResult` or thrown. | — |
| WARN | An observer threw and was swallowed; a compensation threw during a drain; a definition-time setter overwrote a previous value; a `WARN`-mode conditional matched nothing. | rare |
| INFO | Build completion, and the start of a compensation drain. **Never per transition.** | per build / per rollback |
| DEBUG | Per transition: outcome, condition results, the trigger scan and why each candidate was skipped, applier invocation. Per build: each bound component and what it resolved to. | O(transitions) |
| TRACE | Per action: entry and exit with the qualified path and the call-site mapping decision. Registry lookups. | O(actions) |

INFO staying off the per-transition path is the rule held hardest: a host running thousands of transitions a second did not ask for thousands of INFO lines, and the outcome is already on the returned `TransitionResult`.

**The framework never logs your entity or your context.** Ids, class names, states, and qualified paths only — at any level, including inside exception messages. The same rule covers a throwable's type rather than its message wherever the framework reports a failure of its own. A host that wants payloads in its logs does that through a listener, where the decision is the host's to make.

**Framework logging does not duplicate the listener SPI.** `ActionListener`, `TransitionListener` and `StateListener` already expose the execution trace, and a host that registers one controls its format, level, and cost. Framework logging covers what a listener cannot observe: trigger dispatch scans, condition evaluations, registry resolution, call-site mapping, compensation drains, and the build pipeline.

To see why a `processEvent(...)` fired nothing, raise `org.transflux.trigger` to DEBUG — the scan reports its candidate count and one line per candidate with the reason it was passed over.

## Contributing and Workflow
- Default branch: `main`.
- Commit messages: follow Conventional Commits (e.g., `feat: add state validation`, `fix: correct transition check`).

## License
Apache License 2.0. See LICENSE for details.
