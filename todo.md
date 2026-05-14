# Transflux - Microflow Orchestration Library
## Development Plan

### Project Overview

Transflux is a lightweight microflow orchestration library for automating state changes in business entities. This plan covers the road to a **smallest-useful-core 1.0 release** in six phases, followed by themed post-1.0 work.

The plan tracks high-level work items derived from `requirements.md`. Detailed design and per-feature breakdowns happen in feature-specific docs as we go.

---

## Versioning Strategy

- **0.x.y** — pre-1.0 phases. API is unstable; breaking changes are expected between phases.
- **1.0.0** — stable core. Semantic versioning applies from this point on.
- **1.x.0** — additive features only. Anything that would force a breaking change against the 1.0 contract is queued for 2.0.
- **2.0.0** — reserved for themes that cannot realistically remain additive: persistence, long-running/durable executions, and distributed execution. These touch the core operation/context contract (serializable contexts, resumable state, distributed identity/locking) in ways that 1.0 does not anticipate.

Patch releases (`x.y.z`) ship between minor releases for bug fixes and security updates.

### Release Policy

**No releases until 1.0.** All pre-1.0 work is internal — no Maven Central publishing, no GitHub releases, no public artifacts. The library is in active design and the surface is changing too fast for external consumption to be useful. This policy may be revisited mid-roadmap (likely around v0.4.0 or v0.5.0 when YAML DSL and triggers are in place) if a controlled preview release becomes valuable; the default remains "no release" until that decision is made explicitly.

---

## Phase 1: Core Foundation (v0.1.0)
*Target: Programmatic state machine API with paired state resolver/applier and core contracts.*

### 1.1 Project Setup & Infrastructure
- [x] **Maven Configuration**
  - [x] Java 21 source, Java 11 target; library compatible with Java 11+ JVMs.
  - [x] Core dependencies: SLF4J API 2.0.17, Jackson Core 2.18.0, Spock 2.3-groovy-4.0, Groovy 4.0.28, Logback 1.5.18 (test scope).
  - [x] Maven plugins: compiler, GMavenPlus, Surefire (Spock/Groovy), JaCoCo, source.

- [x] **Build Sanity Check (Temporary Scaffold)**
  - [x] Sample class + Spock spec exercising the full build path.
  - [x] Sample package removed once real components landed.

- [x] **Package Structure**
  - [x] `org.transflux.core` flat package established. Per `CLAUDE.md`, subpackages (state/transition/operation/context/exception) are aspirational and deferred.

### 1.2 Repository & Legal
- [x] GitHub repository, basic README, .gitignore.
- [x] Commit message conventions (Conventional Commits).
- [x] License selected and applied; LICENSE file present; license headers in source files.

### 1.3 Core Domain Model
- [x] **State Management**
  - [x] `State` interface with metadata support.
  - [x] `DefaultState` implementation (currently `StateImpl`).
  - [x] Required `id`, optional `name`/`description`.
  - [x] State validation during builder execution.

- [x] **Transition System**
  - [x] `Transition` interface with source/target states.
  - [x] `DefaultTransition` implementation (currently `TransitionDefImpl`/`TransitionImpl`).
  - [x] Transition validation logic.

### 1.4 Basic State Machine
- [x] **StateMachine Core**
  - [x] `StateMachine<T>` interface with generic entity support.
  - [x] `DefaultStateMachine` implementation (currently `StateMachineImpl`).
  - [x] State transition matrix validation, including ID uniqueness.
  - [x] No-op transition execution (operation execution lands in Phase 2).
  - [x] Basic error handling and validation.

- [x] **Remove Forced State Execution API**
  - [x] Removed from `StateMachine` interface, `StateMachineImpl`, and `StateMachineImplSpec`. Host owns initial-state placement (requirements §1.3).

- [x] **Programmatic Builder API**
  - [x] Fluent `StateMachineDef<T>` builder.
  - [x] State definition methods.
  - [x] Transition definition methods.
  - [x] Entity type binding.
  - [x] Validation during build.

### 1.5 Core Contracts (API surface)
- [x] **State Resolver and State Applier**
  - [x] `StateResolver<T>` interface (read current state from entity).
    - [x] Class-based implementation.
    - [x] Lambda function (Java API).
    - [ ] SpEL expression (deferred to Phase 5 with rest of SpEL work; interface in place).
  - [x] `StateApplier<T>` interface (write new state to entity after successful transition).
    - [x] Class-based implementation.
    - [x] Lambda function (Java API).
    - [ ] SpEL property path (deferred to Phase 5).
  - [x] Builder wiring: `.withStateResolver(...)` and `.withStateApplier(...)` on `StateMachineDef<T>`.
  - [x] State machine invokes the applier exactly once on successful execution. Post-condition gating and `onComplete` listener ordering land with their respective phases.

- [x] **TransitionResult\<T\>**
  - [x] Shape matches requirements §2.1.4: success flag, target state, error, executed step IDs, compensated step IDs, `startedAt`/`completedAt` timestamps, derived `duration`. Per-step durations deferred to Phase 2.
  - [x] Documented failure semantics (business outcomes via result; validation errors thrown synchronously).

- [x] **Exception Hierarchy**
  - [x] `TransfluxException` base class (unchecked).
  - [x] `TransfluxValidationException` reparented under `TransfluxException`.
  - [x] `TransfluxReentrancyException` declared (will be raised by the runtime guard in Phase 2).

### 1.6 Spock Specifications
- [x] `StateMachineImplSpec` covers transition execution, state-applier invocation order, applier-skip when failing, and timestamp population.
- [x] `StateMachineDefImplSpec` covers state-resolver and state-applier wiring (null rejection, override-warn, build-time propagation).
- [x] `TransitionResultSpec` covers all factories, immutability, defensive copying, and `duration` derivation.
- [x] Spec coverage for builder validation paths and ID uniqueness.

---

## Phase 2: Operations, Steps & Conditions (v0.2.0)
*Target: Composite operations, entity-aware steps, the unified Condition Descriptor, and the runtime reentrancy guard.*

### 2.1 Operation Framework
- [ ] `OperationDef` interface for fluent operation definition (in flight on the branch).
- [ ] `Operation<T, C>` runtime interface — `execute(entity, context, transition)` returns `void`; results flow through the context (requirements §2.1.5).
- [ ] `SimpleOperation<T, C>` implementation.
- [ ] Operation lifecycle and execution order within a transition.
- [ ] Operation results documented as context-flowing (no `.input(...)` API; no domain return value).

### 2.2 Steps
- [ ] `Step<T, C>` interface — entity-aware, receives `(entity, context, transition)`. Reusable across operations.
- [ ] Step elevation as `SimpleOperation` targets (a step may be used directly as an operation).
- [ ] `CompositeOperation<T, C>` implementation.
- [ ] Sequential step execution within composite operations.
- [ ] Step-level error handling primitives (full compensation engine lands in Phase 4).
- [ ] `Transition.step(...)` family of methods (with and without explicit class) for in-operation step invocation.

### 2.3 Multi-Branch Conditional Operations
- [ ] Conditional step type within composite operations.
- [ ] Sequential branch evaluation, first-match-wins semantics.
- [ ] `default` fallback branch.
- [ ] Configurable behavior when no branch matches and no default is defined (warning vs. error).

### 2.4 Condition System
- [ ] **SpEL Integration**
  - [ ] Spring Expression Language 6.2.x dependency. Note: SpEL itself runs on Java 11+ even though `spring-context` does not — verify Java 11 compatibility of the SpEL JAR before locking the version.
  - [ ] SpEL expression evaluator with entity and context variable binding.
  - [ ] Expression caching.

- [ ] **Condition Framework**
  - [ ] `Condition<T>` interface.
  - [ ] `Predicate<T>`-style lightweight conditions.
  - [ ] Pre/Post condition wiring on transitions.
  - [ ] **Condition Descriptor** — the four-form grammar from requirements §3.6.1:
    - [ ] Reference (by ID).
    - [ ] Class-based (`Condition<T>` implementation).
    - [ ] Predicate-based.
    - [ ] Expression-based (SpEL).
  - [ ] Auto-ID derivation for inline expression-based conditions only.

### 2.5 Reentrancy Guard
- [ ] Runtime detection of reentrant transition attempts on the same `StateMachine<T>` instance for the same entity.
- [ ] Throw `TransfluxReentrancyException` with diagnostic context.
- [ ] Permit transitions on *different* entities from within an executing transition.
- [ ] Spock coverage for the guard.

### 2.6 Specifications
- [ ] Composite operation specs with step sequencing and parameterized data tables.
- [ ] Multi-branch conditional specs (each branch matched, no-match-with-default, no-match-no-default).
- [ ] Condition Descriptor specs for each form, including auto-ID derivation.
- [ ] Reentrancy guard specs.

---

## Phase 3: Triggers & Listeners (v0.3.0)
*Target: The 1.0 trigger set (Manual, Event, host-driven Data) and full listener parity between DSLs.*

### 3.1 Trigger Framework
- [ ] `Trigger` interface and base implementations.
- [ ] Trigger registration and lookup on transitions.
- [ ] Trigger catalog API on the state machine (enumerate triggers by name/type).

### 3.2 Manual Triggers
- [ ] `ManualTrigger` implementation.
- [ ] Per-trigger metadata: description, listener bindings, trigger-specific pre-conditions distinct from the transition's defaults.
- [ ] Invocation API (`stateMachine.entity(e).transitionTo(state, triggerId)`).

### 3.3 Event Triggers
- [ ] `EventTrigger` implementation.
- [ ] `processEvent(event, eventData)` API on the entity binding.
- [ ] Event filtering via expressions / predicate classes.
- [ ] Entity correlation (matching events to entities) for the in-process case.

### 3.4 Data Triggers (host-driven)
- [ ] `DataTrigger` implementation.
- [ ] `processDataChange()` API — host-initiated re-evaluation only.
- [ ] Data-trigger condition uses the standard Condition Descriptor grammar.
- [ ] Documented and tested non-goal: no field watching, no ORM hooks, no background polling (those are post-1.0).

### 3.5 Listeners
- [ ] **State Listeners**
  - [ ] `StateListener` interface (entry / exit).
  - [ ] Per-state and global registration.
  - [ ] Invocation in execution flow: source-state `onExit` at step 4; target-state `onEntry` at step 8 (requirements §2.4).

- [ ] **Transition Listeners**
  - [ ] `TransitionListener` interface (start / complete / error).
  - [ ] Per-transition and global registration (`onAnyTransitionStart`, etc.).
  - [ ] Async listener execution support (basic; full async work lands in Phase 4).

### 3.6 Specifications
- [ ] Trigger specs for each type, including catalog enumeration.
- [ ] Manual-trigger metadata override specs.
- [ ] Data trigger specs covering all four Condition Descriptor forms.
- [ ] Listener-ordering specs covering the execution flow.

### 3.7 Component Metadata Model
*Prerequisite for §3.5 Listeners — listener payloads, diagnostic logging, and (later) the YAML DSL all need a uniform way to read `name` and `description` off any framework def. The Phase 2 design deliberately deferred this until there was a real consumer.*
- [ ] Introduce a `Describable extends Identifiable` interface declaring `getName()` and `getDescription()` as default-`null` methods.
- [ ] Make `StateMachineDef`, `StateDef`, `TransitionDef`, `OperationDef` implement `Describable`; each `*DefImpl` overrides one or both methods to return its stored value.
- [ ] Add `StepDef<T, C>` (mandatory id, optional name/description) — mirroring `SimpleOperationDef`.
- [ ] Add `ConditionDef<T, C>` (mandatory id, optional name/description) covering the existing four authoring flavours (instance, class, predicate, expression).
- [ ] Add lambda-configurer overloads where step / condition registrations exist:
  - [ ] `StateMachineDef.step(String id, Consumer<StepDef<T, C>> configurer)`
  - [ ] `StateMachineDef.condition(String id, Consumer<ConditionDef<T, C>> configurer)`
  - [ ] `TransitionDef.preCondition(String id, Consumer<ConditionDef<T, C>> configurer)` and `postCondition(...)` mirror
- [ ] Existing flat overloads (`step(id, instance|class)`, `condition(id, instance|class|predicate|expression)`, the typed `preCondition` / `postCondition` overloads) stay as sugar for the no-metadata case.
- [ ] Listener payloads (§3.5) surface `id` + `name` + `description` from the relevant def — concrete shape pinned down alongside the `*Listener` interfaces.

---

## Phase 4: Async Operations & Error Handling (v0.4.0)
*Target: The compensation engine, async anchoring, and exception-specific recovery.*

### 4.1 Compensation Engine
- [ ] Unified `Compensation<T, C>` interface (entity + context) for both operations and steps.
- [ ] LIFO compensation stack management.
- [ ] Compensation registration as each step completes.
- [ ] Post-condition violation triggers full compensation; entity state is *not* applied.
- [ ] Compensation declared by class or returned dynamically from `getCompensation(entity, context)`.

### 4.2 Exception-Specific Compensation
- [ ] `.onException(...)` / `.onAllExceptions()` builder DSL on composite operations.
- [ ] Exception matching by class hierarchy + optional predicate.
- [ ] Compensation chaining and ordering.

### 4.3 Async Operation Support
- [ ] `async` block on composite operations.
- [ ] **Anchor forms**: exactly one of
  - [ ] `startBefore(stepId)` — kick off when execution reaches the named sync step (join-point pattern).
  - [ ] `startAfter(stepId)` — kick off when the named sync step completes successfully (post-action notifications pattern).
- [ ] Configurable thread pool and queue capacity.
- [ ] Async result handling and callbacks.
- [ ] Async operation cancellation semantics.

### 4.4 Async Compensation
- [ ] Compensation of async branches.
- [ ] Coordination semantics when sync work fails while async work is still running.
- [ ] Timeout handling for async operations.

### 4.5 Specifications
- [ ] Compensation engine specs (LIFO order, exception routing, partial rollback).
- [ ] Async anchor specs for both `startBefore` and `startAfter`.
- [ ] Async-compensation specs.

---

## Phase 5: YAML DSL & Component System (v0.5.0)
*Target: The declarative DSL at parity with the Java DSL.*

### 5.1 YAML Processing Infrastructure
- [ ] Dependencies: SnakeYAML 2.4, Jackson YAML module (2.20.x, matching the core Jackson version), JSON Schema Validator 1.x current.
- [ ] JSON Schema for Transflux YAML format.
- [ ] Schema-based validation with line-number / context error reporting.
- [ ] IDE-support schema files for autocomplete (the schema itself; IDE plugin work is out of scope).

### 5.2 Component Library System
- [ ] `ComponentLibrary` — reusable definitions of steps, conditions, triggers, listeners, operations.
- [ ] Component identification rules per requirements §2.2.1 (mandatory `id`; expression-based conditions excepted).
- [ ] Component versioning / compatibility metadata.

### 5.3 Component Reference Grammar
- [ ] String-shorthand reference resolution (`operation: my-op`).
- [ ] Inline block definitions (`operation: { type: composite, ... }`) — first-class everywhere.
- [ ] Long-form reference (`{ ref: my-op }`) accepted in block contexts.
- [ ] Type discrimination rules for inline definitions.
- [ ] Circular reference detection.
- [ ] Component dependency graph.

### 5.4 Imports and Namespacing
- [ ] YAML import system with relative/absolute path resolution.
- [ ] Cross-file component references.
- [ ] Namespace collision detection (all IDs unique within type across imports).
- [ ] Circular import detection.

### 5.5 YAML DSL Parsing
- [ ] State machine definition parser.
- [ ] State, transition, operation, step, condition, trigger, listener parsers.
- [ ] Condition Descriptor parsing (the four forms).
- [ ] State resolver + state applier configuration (class or SpEL).
- [ ] Listener parity with the Java DSL (state entry/exit + transition start/complete).
- [ ] Validation against the JSON Schema.
- [ ] Conversion from YAML model to runtime `*Def` builders, then to runtime instances.

### 5.6 Specifications
- [ ] Parser specs for each top-level element.
- [ ] Reference Grammar specs (ref vs. inline; bare string vs. block).
- [ ] Import resolution specs.
- [ ] Schema validation error message specs.
- [ ] DSL parity check: a single non-trivial state machine expressed in both DSLs produces equivalent runtime instances.

---

## Phase 6: Integration, Polish & Release Prep (v0.6.0 → v1.0.0)
*Target: 1.0-grade integration, infrastructure, and documentation.*

### 6.1 Spring Integration (Optional)
- [ ] Target **Spring Boot 3.4.x** (Spring Framework 6.2.x). **Documented Java floor: the core library is Java 11+; the optional Spring integration requires Java 17+** because Spring 6 mandates Java 17. Document this split prominently in the README and in the Spring-integration section of the user guide.
- [ ] Spring Boot auto-configuration class.
- [ ] `@EnableTransflux` annotation.
- [ ] `TransfluxConfiguration` Spring binding with configuration properties.
- [ ] Automatic Spring-bean discovery for Transflux components (`Step`, `Condition`, `Trigger`, `Listener`, `Operation`).
- [ ] Profile-aware configuration support.

### 6.2 Component Factory SPI
- [ ] `ComponentFactory` interface with generic type support.
- [ ] Reflection-based fallback when no DI framework is available.
- [ ] Named component registration and retrieval.
- [ ] Custom factory function registration.
- [ ] Circular dependency detection within component graphs.
- [ ] YAML DSL integration: instantiation from `class:` references.

### 6.3 Observability Hooks
- [ ] `MetricsCollector` SPI (no shipped Micrometer integration in 1.0).
- [ ] Hook points: transition start/complete/error, step start/complete, compensation execution, trigger evaluation.
- [ ] Consistent SLF4J logging with predictable logger names.
- [ ] Configurable flow labels for metric separation.

### 6.4 1.0 Dependency Baseline Refresh

Phase 1.1 captured the dependency versions present in the repo when bootstrapping. Before 1.0, bump to the target 1.0 baseline:

- [ ] **Jackson Core** 2.18.0 → **2.20.x** (staying on the 2.x line; Jackson 3 migration is queued as a Post-1.0 / 2.x theme).
- [ ] **Spock** 2.3-groovy-4.0 → **2.4-groovy-4.0**.
- [ ] **Groovy** 4.0.28 → latest 4.0.x.
- [ ] **SLF4J** 2.0.17 → latest 2.0.x.
- [ ] **Logback** (test scope) 1.5.18 → latest 1.5.x.
- [ ] Maven plugin versions audited and aligned with current Maven 3.9.x recommendations.
- [ ] Confirm SpEL 6.2.x JAR runs on Java 11 (per §2.4 note) and pin the exact patch version.
- [ ] Update `pom.xml` and re-run the full Spock specification suite after each bump to catch behavioral regressions.

### 6.5 CI/CD and Quality Infrastructure
- [x] Basic GitHub Actions workflow (build + test).
- [ ] Code-quality gates: SpotBugs, Checkstyle, PMD.
- [ ] Security vulnerability scanning.
- [ ] Code-coverage reporting (JaCoCo + Codecov).
- [ ] Required status checks for PRs; branch protection on `main`.
- [ ] Dependabot for dependency updates.
- [ ] Issue / PR / bug-report templates.
- [ ] `CONTRIBUTING.md`.
- [ ] Pre-commit hook configuration.

### 6.6 Maven Central Publishing
- [ ] Complete POM metadata (name, description, URL, licenses, developers, SCM).
- [ ] Distribution management configuration.
- [ ] Source jar and Javadoc jar plugins.
- [ ] GPG signing configuration.
- [ ] Sonatype OSSRH account and group-ID verification.
- [ ] Release automation (version bumping, tagging, changelog, deployment).
- [ ] GitHub releases with artifacts.

### 6.7 Documentation
- [ ] Complete README (badges, install snippets, hello-world example).
- [ ] Getting-started guide.
- [ ] Architecture overview (mirrors `requirements.md` §2 but reader-oriented).
- [ ] Configuration reference.
- [ ] Best-practices / patterns guide (when to use simple vs. composite operations, manual vs. event vs. data triggers, etc.).
- [ ] Migration guide template for breaking changes (will be reused at 2.0).
- [ ] Complete API Javadoc.
- [ ] Example applications: simple state machine, complex workflow, Spring Boot integration.

### 6.8 Release Engineering
- [ ] Semantic versioning policy document.
- [ ] Backward-compatibility policy.
- [ ] Release notes template.
- [ ] Community infrastructure: GitHub Discussions, SECURITY.md, code of conduct.

### 6.9 1.0 Quality Gates
- [ ] Spock specification coverage ≥ 80% for core packages.
- [ ] No critical or high-severity security findings.
- [ ] Performance baseline established (basic benchmarks; not a 1.0 feature, but a baseline to detect regressions).
- [ ] API surface review and sign-off.
- [ ] Load test of representative workflow.
- [ ] Documentation completeness verification.

---

## v1.0.0 Release

The first stable release. Semantic versioning applies from this point on.

**1.0 contract summary:**
- Programmatic and YAML DSLs at parity.
- Core abstractions: `StateMachine`, `State`, `Transition`, `Operation`, `Step`, `Context`, `Condition`, `Trigger` (Manual / Event / host-driven Data), `Listener` (state entry/exit + transition start/complete), `Compensation`.
- Paired `StateResolver<T>` + `StateApplier<T>` (class / lambda / SpEL forms).
- Condition Descriptor grammar (reference, class, predicate, expression).
- Multi-branch conditional operations.
- LIFO compensation engine with exception-specific routing.
- Async operations anchored via `startBefore` / `startAfter`.
- Spring auto-configuration (optional) + manual wiring via `ComponentRegistry`.
- `MetricsCollector` SPI hook (no shipped backend integration).

---

## Post-1.0 — Additive Themes (1.x line)

Ordering between themes will depend on demand. None of these should require breaking changes against the 1.0 contract.

### Trigger Expansion
- [ ] **Timer / Cron Triggers**
  - [ ] `TimerTrigger` implementation.
  - [ ] Quartz Scheduler 2.3.x integration.
  - [ ] Timezone / DST handling.
  - [ ] Timer persistence considerations (will interact with the persistence theme on the 2.x line).
- [ ] **Signal Triggers**
  - [ ] `SignalTrigger` for framework-wide signals.
  - [ ] Signal broadcasting, subscription, predicate matching.
  - [ ] Cross-state-machine signal coordination.
- [ ] **Automatic Data-Change Detection**
  - [ ] Field-watcher infrastructure.
  - [ ] ORM-hook integration adapters (Hibernate listener, JPA, etc.).
  - [ ] Efficient change detection algorithms.

### Observability Integration
- [ ] Micrometer 1.12.x integration (first-party `MetricsCollector` implementation).
- [ ] OpenTelemetry 1.32.x tracing (span creation, context propagation, sampling).
- [ ] Structured logging with MDC and correlation IDs.
- [ ] Health-check framework (state machine, triggers, thread pools, dependencies).
- [ ] Grafana dashboard templates and example alerting rules.

### DI Framework Expansion
- [ ] **Google Guice 7.0.x** integration: `TransfluxGuiceModule`, `@TransfluxComponent`, scope management.
- [ ] **CDI / Weld SE 5.1.x** integration: `TransfluxExtension`, bean definitions, Jakarta EE compatibility.
- [ ] **Dagger 2.48.x** integration: compile-time components, annotation processing, multibinding.
- [ ] Framework-agnostic `DIContainer` abstraction with adapters.
- [ ] Specs for each integration; cross-framework benchmark / parity checks.

### Testing Framework (separate artifact)
- [ ] `transflux-test` (or similar) module.
- [ ] `TestStateMachine<T>` wrapper with transition-path recording.
- [ ] Context snapshot capture at transition points.
- [ ] Step-level execution tracking.
- [ ] AssertJ-inspired fluent assertion API (`TransfluxAssertions`):
  - [ ] State assertions.
  - [ ] Transition assertions.
  - [ ] Context assertions.
  - [ ] Operation / compensation assertions.
- [ ] Test data builders for entities and contexts.
- [ ] Integration with Spock, JUnit, TestNG.

### Resilience Patterns
- [ ] Resilience4j 2.1.x integration.
- [ ] Configurable retry strategies (exponential backoff, jitter).
- [ ] Circuit breaker pattern.
- [ ] Rate limiting.
- [ ] Graceful degradation strategies.

### Advanced DSL Features
- [ ] YAML anchors / aliases support.
- [ ] Template-based component definitions and inheritance.
- [ ] Parameterized components.
- [ ] Hot reload in development mode.
- [ ] Dynamic runtime reconfiguration (blue/green with rollback).

### Plugin System
- [ ] Plugin interface and extension points.
- [ ] Plugin discovery and loading.
- [ ] Plugin lifecycle management.
- [ ] Plugin dependency resolution.
- [ ] Built-in plugins (subject to demand): message-queue integration, REST API for external triggers, alerting integrations.

---

## Post-1.0 — Breaking Themes (2.x line)

These themes alter the core operation/context contract enough that they cannot realistically remain additive. Bundling them into a single 2.0 release (vs. a series of 1.x with breaking sub-releases) preserves semver integrity.

### Persistence
- [ ] Pluggable persistence layer for state-machine definitions.
- [ ] Transition history auditing.
- [ ] Entity state persistence and recovery.
- [ ] Implications: serializable contexts may become a 1.x-soft requirement; full enforcement is 2.0.

### Long-Running / Durable Executions
- [ ] Checkpoint and resume capabilities.
- [ ] Progress tracking and monitoring.
- [ ] Suspend / resume semantics for the transition lifecycle.
- [ ] Distributed transaction support.
- [ ] BPMN interoperability considerations.

### Distributed Execution
- [ ] Cluster-wide locking primitives.
- [ ] Distributed state-machine coordination.
- [ ] Cluster-aware triggers (event de-duplication, leader election).
- [ ] Cross-node entity identity and dispatch.
- [ ] Failure handling and recovery in distributed environments.

### Jackson 3 Migration
- [ ] Migrate from Jackson 2.20.x to Jackson 3.x.
- [ ] Package rename: `com.fasterxml.jackson.*` → `tools.jackson.*` across all parsing code.
- [ ] Verify SnakeYAML / Jackson YAML 3.x interoperability.
- [ ] Audit all `ObjectMapper` and `YAMLMapper` usages for API changes.
- [ ] Document Jackson 3 as a breaking change for users who pin Jackson on their classpath. Bundle with the other 2.x breaking themes to avoid double migration disruption.

---

## Technical Implementation Notes

### Java Baseline

- **Core library**: Java 21+ to build (toolchain enforced); **Java 11+** target. Compiles to Java 11 bytecode via `<release>11</release>`.
- **Optional Spring integration**: Java 17+ runtime (Spring 6 mandates Java 17). The split is documented as a known constraint, not a workaround.

### Core Dependencies (1.0 Target Baseline)
- **SLF4J 2.0.x** (latest) — logging.
- **Jackson 2.20.x** — JSON / YAML data binding (staying on the 2.x line for 1.0; Jackson 3 migration is a 2.x post-1.0 item).
- **SnakeYAML 2.4** — YAML parsing (Phase 5).
- **Spring Expression Language 6.2.x** — SpEL for conditions, applier paths, expression-based conditions. Java 11 compatibility of the SpEL JAR to be verified during Phase 6.4 dependency refresh.

### Optional Integrations (1.0)
- **Spring Boot 3.4.x** + **Spring Framework 6.2.x** — auto-configuration; Java 17+ runtime required for this integration.

### Testing (1.0)
- **Spock Framework 2.4-groovy-4.0** + **Groovy 4.0.x** — BDD-style specifications.
- **Logback 1.5.x** (test scope).

### Build & Quality
- **Maven 3.9.x**.
- **JaCoCo** — coverage.
- **SpotBugs**, **Checkstyle**, **PMD** — static analysis (Phase 6.5).

### Deferred Dependencies (Post-1.0)
- Micrometer 1.15.x+ / 2.x (observability theme).
- OpenTelemetry 1.45.x+ (observability theme).
- Quartz Scheduler 2.5.x (timer-trigger theme).
- Resilience4j 2.3.x (resilience theme).
- Spring JMS 6.2.x (event-transport extensions).
- Google Guice 7.x, Weld SE 6.x, Dagger 2.51+ (DI expansion theme).
- Testcontainers 1.21.x (testing framework or persistence theme).
- Jackson 3.x (breaking — bundled with the 2.x release; see below).

---

## Release Cadence

- **Pre-1.0 phases**: ~6–8 weeks per phase.
- **1.x minor releases**: as themes complete.
- **Patch releases**: as needed for critical bugs.
- **LTS**: revisit policy after 1.0 ships.

## Phase Completion Criteria

Each phase must meet the following before the next phase starts:
- [ ] All planned features implemented and tested.
- [ ] Spock specification coverage ≥ 80% for new code.
- [ ] No critical security findings against new code.
- [ ] `requirements.md` and `todo.md` updated to reflect any in-flight scope changes.
- [ ] Migration notes for breaking changes (pre-1.0 only; post-1.0 changes follow semver).

## 1.0 Release Readiness

- [ ] All Phase 1–6 tasks completed.
- [ ] 1.0 quality gates (§6.9) satisfied.
- [ ] Documentation complete.
- [ ] Release notes prepared.
- [ ] Migration guide template ready (for future 2.0).
- [ ] Code audit completed against the rewritten `requirements.md` (forced-state revert, `TransitionResult` shape verification, etc.).

---

*This plan supersedes earlier 15-phase drafts. Detailed feature design lives in feature-specific docs as we go; `requirements.md` remains the canonical high-level spec.*
