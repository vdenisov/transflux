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
Phases 1 through 5 are complete: the programmatic builder, paired `StateResolver` / `StateApplier`, `TransitionResult` with executed/compensated action paths and timing metadata, actions with conditions and compensations, manual / event / data triggers, and state, transition and action listeners are all in place. Phase 4 added the compensation engine with exception-specific routing (`forException(...)`), forked members (`fork(...)`, fire-and-forget, with per-branch rollback), async listeners on the same executor, the framework's logging baseline together with the shipped `ExecutionLogging` listeners, and per-owner disabling of global listeners. Phase 4b interrupted it and shipped first: every position that holds an ordered list of actions — a declarative container, a choice's branch, its default branch, and a transition's body — now admits one member grammar, so a member may carry a call-site mapper, be declared in place as a step, an operation or a choice, and be forked in any of those forms. Phase 5 added the YAML DSL: a definition written in YAML, split into documents that import each other and read through a `DefinitionSource` the host chooses, loads to the same `StateMachineDef` the Java builder produces, and a running state machine can be handed a new definition with `replaceDefinition(...)`. Spring integration and release preparation are Phase 6.

The project is in active design and the public API is unstable. **No releases are published before v1.0** — see `todo.md` for the phased roadmap.

## Build
- Prerequisites: JDK 17+ to build (enforced via Maven toolchains); the library compiles to Java 17 bytecode and is compatible with Java 17+ runtimes. Maven 3.9+.
- Run tests: `mvn -q clean test`
- Run a single spec: `mvn -q test -pl transflux-core -Dtest=StateMachineImplSpec`
- Coverage report, per module: `transflux-core/target/site/jacoco/index.html` and `transflux-yaml/target/site/jacoco/index.html`

## YAML Definitions
The YAML DSL lives in its own module, `org.transflux:transflux-yaml`, which depends on `transflux-core` (nothing is published before 1.0, so for now it is built from this repository). The only dependency it adds on top of the core module's is SnakeYAML.

A loader reads a root document and everything it imports through a `DefinitionSource`, and returns a definition that is not built yet - the host can complete it in Java before `build()`:

```java
YamlDefinitionLoader loader = YamlDefinitionLoader.builder(new ClasspathDefinitionSource()).build();
StateMachine<Subscription> sm = loader.load("subscription.transflux.yml", Subscription.class).build();
```

The loader caches nothing, so reloading is loading again and replacing the definition. A document the loader refuses throws `DefinitionLoadException` (naming the file, line and declaration), and a definition that does not build throws from `replaceDefinition`; in both cases the running definition stays as it was:

```java
long generation = sm.replaceDefinition(loader.load("subscription.transflux.yml", Subscription.class));
```

`ClasspathDefinitionSource`, `FileSystemDefinitionSource` and `CompositeDefinitionSource` ship with the module; a database or a Git repository is a `DefinitionSource` the host writes itself (`requirements.md` §2.6).

**Editor support.** The format has a JSON Schema, published at `https://vdenisov.github.io/transflux/schema/transflux-v1.schema.json` and shipped in the jar as `org/transflux/yaml/transflux-v1.schema.json`. Name your documents `*.transflux.yml` and map that pattern to the schema in the editor, or put the schema on the document's first line:

```yaml
# yaml-language-server: $schema=https://vdenisov.github.io/transflux/schema/transflux-v1.schema.json
```

The schema is for autocomplete and early feedback only; the loader is the validator, and it also checks what a schema cannot see - classes, expressions, imports and duplicate ids.

## Package Structure
- `org.transflux.core` — entry point (`Transflux`), `StateMachine` / `StateMachineDef`, `ContextScope`, `ListenerDef` (the surface shared by the three listener defs), `ComponentFactory` (how a class a definition names becomes an instance), and the `Preconditions` argument-precondition helpers.
- `org.transflux.core.state` — `State`, `StateDef`, the host-supplied `StateResolver` / `StateApplier` bridges, and the entry/exit listener surface (`StateListener`, its `StateListenerDef` builder, and the `StateChange` / `StatePhase` payload).
- `org.transflux.core.transition` — `Transition` (the read-only runtime view) and `ExecutingTransition` (the same transition plus the `run(...)` / `fork(...)` dispatch an action's body needs), `TransitionDef`, `TransitionResult`, `ProcessResult` (the outcome of `processEvent` / `processDataChange`), `ActionPath` (the qualified-id value carrier in `TransitionResult.executedPath` / `compensatedPath`), and the start/complete/error listener surface (`TransitionListener`, its `TransitionListenerDef` builder, and the `TransitionExecution` / `TransitionPhase` payload).
- `org.transflux.core.action` — `Action` (the single executable contract) and `ActionKind`, `ActionSequence` (the member grammar every ordered action list shares — a container, a choice's branch, its default branch, and a transition's body), `Compensation`, `ContextMapper`, the def-side types (`ActionDef` and its `StepDef` / `OperationDef` forms, `ChoiceDef`, `MapperDef`, `BranchDef`, `DefaultBranchDef`, `NoMatchBehavior`, `CompensationRouteDef`, `ForkableContext`, `AsyncRejectionPolicy`), and the start/complete/error listener surface (`ActionListener`, its `ActionListenerDef` builder, and the `ActionExecution` / `ActionPhase` payload).
- `org.transflux.core.condition` — `Condition` and `ConditionDescriptor`.
- `org.transflux.core.exception` — `TransfluxException` and its subclasses.
- `org.transflux.core.trigger` — `Trigger` (runtime catalog view, reporting every transition it is attached to) and its kinds `ManualTrigger` / `EventTrigger` / `DataTrigger`, with the def-side builders `ManualTriggerDef` / `EventTriggerDef` / `DataTriggerDef`. A trigger is declared on a transition, or registered once on the state machine and attached by id wherever it is wanted. Manual triggers fire via `entity(e).fire(...)`; event and data triggers fire via the host-driven `entity(e).processEvent(...)` / `processDataChange(...)`.
- `org.transflux.core.logging` — the shipped logging listeners: `ExecutionLogging` configures and creates them, and `StateMachineDef.withExecutionLogging(...)` attaches all three.
- `org.transflux.yaml` (module `transflux-yaml`) — the loader: `YamlDefinitionLoader` reads a YAML definition through a `DefinitionSource` into a `StateMachineDef`, and reports a document it refuses as a `DefinitionLoadException` naming the identifier, line, column and enclosing declarations.
- `org.transflux.yaml.source` (module `transflux-yaml`) — where definition documents come from: the `DefinitionSource` SPI and its `DefinitionResource`, plus `ClasspathDefinitionSource`, `FileSystemDefinitionSource` (with its `SymlinkPolicy`) and `CompositeDefinitionSource`, which asks an ordered list of sources — only those declaring the identifier's prefix, or all of them when it has none.
- `org.transflux.core.impl` — framework-internal implementations: every `*Impl` — including `StateMachineImpl`, the handle a host holds, and the `StateMachineSnapshot` it hands out per call, which is what `StateMachine.replaceDefinition(...)` swaps — the `Registry` / `Component` lookup machinery, the bound-record / action-ref / mapper-ref infrastructure, the SpEL evaluation utilities (`ConditionResolver`, `SpelConditionEvaluator`, `ExpressionIdDerivation`), the runtime-internal `ExecutingTransitionImpl` and `TransitionImpl`, the `Loggers` holder declaring the logger tree, and the shared utilities (`ValidationUtils`, `ThrowingUtils`). User code should not depend on this package directly.

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
| `org.transflux.execution.async` | executor lifecycle; forked-member submission and outcome |
| `org.transflux.execution.listener` | observer failures |
| `org.transflux.trigger` | dispatch scans, filters, gates |
| `org.transflux.yaml.source` | which definition source answered an identifier, and where a miss looked |
| `org.transflux.yaml.parse` | which documents were read, from where |
| `org.transflux.yaml.binding` | which definition each document became; type arguments left to the runtime check |

**The execution trace is a separate subtree.** `org.transflux.trace.state`, `.transition` and `.action` carry only what the shipped logging listeners write, and only when a host attached them — `withExecutionLogging(...)` for all three globally, or `ExecutionLogging.atLevel(...).stateListener()` (and its siblings) on a single owner. Silencing `org.transflux.execution` leaves a trace you asked for untouched. Every line goes out at the level you chose, and carries ids and paths only: the entity appears as the label you supply with `withEntityLabel(...)`, the context only with `withContext()`, durations only with `withTimings()`, and a failure as its type, never its message. The attached listeners claim eight ids - `transflux-log-state-entry`, `transflux-log-state-exit`, `transflux-log-transition-start`, `transflux-log-transition-complete`, `transflux-log-transition-error`, `transflux-log-action-start`, `transflux-log-action-complete` and `transflux-log-action-error` (`ExecutionLogging.*_LISTENER_ID`) - which an owner names in `disableGlobalListener(s)` to keep the trace off itself.

**Only leaves emit.** A name is either a grouping level or a logger, never both, so no line ever arrives from `org.transflux.build` or `org.transflux.execution` themselves — they exist purely so you can configure a subtree.

**Levels.**

| Level | What to expect | Volume |
| --- | --- | --- |
| ERROR | Nothing. Failures are returned on `TransitionResult` or thrown. | — |
| WARN | An observer threw and was swallowed; a compensation threw during a drain, or a compensation route's guard did; a forked member failed or was never started; a definition-time setter overwrote a previous value; a compensation route is provably unreachable; a forked member may share its context; a `WARN`-mode choice matched nothing. | rare |
| INFO | Build completion, executor lifecycle, and the start of a compensation drain. **Never per transition.** | per build / per rollback |
| DEBUG | Per transition: outcome, pre- and post-condition results, the trigger scan and why each candidate was skipped, applier invocation. Per build: phase boundaries, registry scoping, and each bound component and what it resolved to. | O(transitions) |
| TRACE | Per action: entry and exit with the qualified path, the call-site mapping decision, and which scope a dispatched id was claimed by. Per forked member: its submission and how it obtained its context. Per branch condition: the evaluated value. | O(actions) |

INFO staying off the per-transition path is the rule held hardest: a host running thousands of transitions a second did not ask for thousands of INFO lines, and the outcome is already on the returned `TransitionResult`.

**The framework never logs your entity or your context.** Ids, class names, states, and qualified paths only — at any level, including inside exception messages. The same rule covers a throwable's type rather than its message wherever the framework reports a failure of its own. A host that wants payloads in its logs does that through a listener, where the decision is the host's to make.

**Framework logging does not duplicate the listener SPI.** `ActionListener`, `TransitionListener` and `StateListener` already expose the execution trace, and a host that registers one controls its format, level, and cost. Framework logging covers what a listener cannot observe: trigger dispatch scans, condition evaluations, registry resolution, call-site mapping, compensation drains, and the build pipeline.

Both levels of `org.transflux.execution.condition` are worth knowing apart: DEBUG gives pre- and post-conditions, one line per transition, while a choice's branch selectors sit at TRACE because they run once per branch per action.

To see why a `processEvent(...)` fired nothing, raise `org.transflux.trigger` to DEBUG — the scan reports its candidate count and one line per candidate with the reason it was passed over.

## Contributing and Workflow
- Default branch: `main`.
- Commit messages: follow Conventional Commits (e.g., `feat: add state validation`, `fix: correct transition check`).

## License
Apache License 2.0. See LICENSE for details.
