> Part of the [Transflux roadmap](../../todo.md). ✅ **Shipped** — verbatim record, nothing compressed.

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

