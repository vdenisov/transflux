# Transflux - Microflow Orchestration Library Requirements

## 1. Overview

Transflux is a lightweight microflow orchestration library designed to automate the coordination of state changes for business entities. The library focuses on the logic and execution of transitions themselves — handling dependencies, sequencing, error handling, and compensations during state changes — rather than just defining states or managing long-term processes.

### 1.1 Problem Statement

Many business entities can be modeled as finite-state machines, with different states attached to different lifecycle stages. However, most applications implement these state machines from scratch using plain Java code and custom abstractions, leading to:
- Unique low-level code that reduces reliability and scalability
- Difficulty in understanding, extending, and maintaining services
- Lack of standardization across different entity types

### 1.2 Solution Goals

Transflux aims to provide a standard framework for finite-state machine entities and associated transition workflows that is:
- Lightweight and non-imposing, easily integrating with existing codebases
- Embedded and fully local to the application instance
- Built on existing persistence models without requiring its own storage
- Capable of orchestrating complex transitions with error compensation (similar to the Saga pattern)
- Supportive of reusable components (steps, triggers, listeners)
- Comes with DSL support for both declarative and programmatic definitions

### 1.3 Non-Goals for 1.0

The following are explicitly **out of scope** for the 1.0 release. Several are tracked as Post-1.0 themes in §7.2; some are not on the roadmap at all. These non-goals shape the size and complexity of the 1.0 deliverable and should be cited whenever scope creep is proposed.

- **No persistence.** Transflux never owns or persists entity state. The host application is responsible for loading entities into memory, providing the framework with the means to read and apply state on a single in-memory instance, and persisting (or discarding) the result. Some transitions may be purely transient — "the road is the goal" — and the host is free to discard the entity post-transition.
- **No scheduler.** The library has no internal scheduler, timer, or background thread that polls for work or evaluates triggers automatically. All evaluation is host-initiated.
- **No automatic data-change detection.** Data-based triggers in 1.0 are evaluated only on explicit `processDataChange(...)` calls from the host. No ORM hooks, no field watchers.
- **No distributed coordination.** No clustering, no distributed locks, no cluster-aware triggers, no cross-node state synchronization. Transflux runs in a single JVM and treats entities as in-memory objects.
- **No long-running / durable executions.** A transition is an in-process operation that begins and completes (or fails and compensates) within a single JVM lifetime. There is no checkpoint/resume capability. Hot-swap of the state machine *definition* is supported (§2.7); a single execution still runs to completion atomically against the snapshot active at its start, regardless of any definition swaps that happen mid-execution.
- **No UI or workflow editor.** Visualization, diagrams, and editing tools are not part of the library deliverable. IDE plugin tooling is tracked separately in `ide-plugin-roadmap.md`.
- **No forced-state API.** With no persistence, the library cannot meaningfully "force" an entity into a state. The host is responsible for placing entities into an initial state through its own model (see §2.2.3).
- **No built-in observability backends.** Transflux exposes hooks (e.g., a `MetricsCollector` SPI) but does not ship with first-party Micrometer / OpenTelemetry integration in 1.0 (see §7.2).

## 2. Architecture

### 2.1 Core Contracts

The contracts in this section govern how Transflux interacts with the host application. They are deliberately minimal — the library's value is in orchestrating transitions, not in owning data, scheduling work, or coordinating across processes. These contracts apply equally to both DSLs.

#### 2.1.1 State Ownership

The host application owns the entity and its persistence. Transflux operates on an in-memory entity instance for the duration of a transition. The framework reads the current state via a host-supplied `StateResolver<T>` and applies the new state via a host-supplied `StateApplier<T>` (see §2.2.12).

Steps and operations may freely mutate the entity in-place for business reasons; such mutations are part of the host's domain model and are visible to the host immediately. The framework does not snapshot or roll back entity mutations on failure — recovery is the responsibility of user-defined compensation actions.

The `StateApplier<T>` is invoked **once**, after all post-conditions have passed (see §2.4 step 7). Until that point, the entity's state field (or whatever the resolver derives state from) holds the pre-transition value, even if other fields have been mutated by steps. The applier call is the moment the framework considers the transition committed.

#### 2.1.2 Thread Safety

A `StateMachine<T>` handle (see §2.7) is safe for concurrent use across threads and across entities. The library guarantees only that its own internal data structures are not corrupted under concurrent use, and that snapshot reads, `generation()`, and `replaceDefinition(...)` are coherent without host-side synchronisation.

Concurrent transitions on the **same entity** are the host's responsibility to serialize; the framework does not lock or queue per-entity work. Hosts that need single-writer semantics must enforce them externally (database row locks, application-level mutexes, single-threaded executors, etc.).

#### 2.1.3 Reentrancy

Reentrancy is **fail-fast**. Invoking a transition from within a listener, operation, step, or condition on the same `StateMachine<T>` snapshot for the same entity throws `TransfluxReentrancyException`. The guard keys on the snapshot the in-flight execution is running against (see §2.7) — a definition swap that produces a new snapshot does not lift the guard for any execution already in flight against the previous one. Triggering a transition on a *different* entity from within an executing transition is permitted (provided the host is prepared to handle the implications).

#### 2.1.4 TransitionResult

Transition execution (via `transitionTo(...)`, `processEvent(...)`, `processDataChange()`, and batch variants) returns a `TransitionResult<T>` describing the execution outcome:

- `boolean isSuccess()` — terminal outcome.
- `String getTargetState()` — final state (post-transition on success; pre-transition on rolled-back failure).
- `Throwable getError()` — present iff `isSuccess()` is `false`.
- `List<String> getExecutedStepIds()` — ordered list of steps that ran. Steps belonging to a nested operation are reported as **qualified paths** (`parent-op-id/child-step-id`, recursively for deeper nesting); top-level steps appear under their bare id. See §4.5.2 for nested-operation semantics.
- `List<String> getCompensatedStepIds()` — ordered list of compensations that ran (empty on success). Same qualified-path encoding as `getExecutedStepIds()`.
- Timing metadata (start/end timestamps; per-step durations).

Business outcomes (failed conditions, failed steps, post-condition violations) are reported through `TransitionResult` rather than thrown. **Configuration and validation errors** — invalid definitions, missing transitions, unknown states, illegal builder usage — throw `TransfluxValidationException` synchronously.

#### 2.1.5 Operation Result Mapping

`Operation<T, C>.execute(entity, context, transition)` returns `void`. Any data the operation produces flows back to the caller through the user-provided context (which the host populated before invocation and reads after the transition completes). `TransitionResult<T>` carries only execution metadata, not domain output. `Operation<T, C>` is a pure functional contract — the operation's identity (id, name, description) lives on its corresponding `*OperationDef` (see §2.2.5).

#### 2.1.6 Step Entity-Awareness

Steps are entity-aware. Every step receives `(entity, context, transition)` — the same signature as operations. A step may mutate the entity, derive data from it, read from the context, and write results back to the context. Steps are reusable across operations; they are not entity-agnostic context manipulators. Like `Operation<T, C>`, `Step<T, C>` is a pure functional contract — step ids are declared on the `step(id, ...)` registration on `StateMachineDef`, not on the `Step<T, C>` class itself.

This shapes the compensation contract too (see §2.2.11): a unified `Compensation<T, C>` interface receives `(entity, context)`, used by both operations and steps.

### 2.2 Core Components

#### 2.2.1 Component Identification

All components in Transflux (states, transitions, operations, steps, conditions, triggers, and others) carry a stable identifier so they can be referenced, looked up, and reported in diagnostics. The identifier lives on the **definition** — the configuration object that declares how the component behaves (e.g., `StateDef`, `TransitionDef`, `SimpleOperationDef`, `CompositeOperationDef`, the `step(id, ...)` and `condition(id, ...)` registrations on `StateMachineDef`, plus `ConditionDescriptor`). The runtime executables themselves (`Operation<T, C>`, `Step<T, C>`, `Condition<T, C>`) are pure functional contracts and do **not** carry identity — the same class can be registered any number of times under different ids. At runtime the framework carries internal **bound records** (`BoundOperation`, `BoundStep`, `BoundCondition`) that pair the pure executable with the framework-owned id; diagnostics and `executedStepIds` / `compensatedStepIds` pull from the bound side.

**Component ID:**
- **Required property** on every component definition.
- Must be unique within the component type (e.g., all state IDs must be unique within a state machine, all operation IDs must be unique within their scope).
- Used for internal referencing, component lookup, and programmatic access.
- Opaque strings; the library does not mandate a casing convention. Examples in this document use **kebab-case** for readability.

**Component Name:**
- **Optional property** on every component definition.
- Provides a human-readable description or display name.
- Used for documentation, user interfaces, and logging.
- Can contain spaces, special characters, and be more descriptive than IDs.

**Example:**
```yaml
states:
  - id: trial
    name: "Trial Subscription State"
  - id: active
    name: "Active Subscription State"
```

There is one narrow exception: **inline expression-based condition descriptors** are not required to specify an `id`. If the `id` is missing on a `ConditionDescriptor.ExpressionBased`, the descriptor is automatically assigned a unique identifier derived from the expression contents plus the path from the root of the state machine definition to the descriptor. All other descriptor forms — `Reference`, `InstanceBased`, `ClassBased`, `PredicateBased`, and explicit-id `ExpressionBased` — must declare an `id` explicitly.

#### 2.2.2 StateMachine

The central orchestrator that manages entity state transitions and coordinates all framework operations.

**Responsibilities:**
- Maintain the state transition matrix definition.
- Validate transition requests against defined rules.
- Execute transition operations and manage their lifecycle.
- Handle trigger evaluation and activation.
- Coordinate pre/post-conditions and listeners.
- Manage operation contexts and data flow.
- Invoke the configured `StateApplier<T>` to commit successful transitions.

#### 2.2.3 State

Represents individual states in the state machine with associated metadata and behavior.

States are characterized by their transition patterns rather than explicit types:
- **Initial states** have no incoming transitions and serve as entry points where entities begin their lifecycle.
- **Terminal states** have no outgoing transitions and represent final states in the entity lifecycle.
- All other states can have both incoming and outgoing transitions.

The host is responsible for placing entities into an initial state through its own model. The library trusts whatever value the configured `StateResolver<T>` returns; it does not provide an API to force an entity into an arbitrary state (this is a 1.0 non-goal — see §1.3).

**Properties:**
- State identifier and metadata.
- Valid outgoing transitions.
- Optional entry/exit listeners (see §2.2.10).

#### 2.2.4 Transition

Defines valid state changes and their associated operations, conditions, and triggers.

**Components:**
- Source and target states.
- Associated operation (optional).
- Pre-conditions (must be met **before** execution).
- Post-conditions (must be met **after** execution; violation triggers rollback / compensation).
- Triggers (manual, event-based, data-based).
- Transition-specific listeners (`onStart`, `onComplete`).
- Compensation strategies.

#### 2.2.5 Operation

Encapsulates the business logic executed during state transitions. `Operation<T, C>` is a **pure functional contract** with a single `execute(entity, context, transition)` method; it is identity-free at runtime — definition-side `SimpleOperationDef` and `CompositeOperationDef` carry the id, name, and description.

**Def-side flavours:**
- **`SimpleOperationDef`** — wires a single `Operation<T, C>` implementation (class or instance) into a transition.
- **`CompositeOperationDef`** — declares a multi-step operation with flow control. At build time the framework synthesizes the underlying `Operation<T, C>` that iterates the configured step list.

From the engine's view a transition always carries a single bound operation — composite vs. simple is purely a def-side distinction.

**Features:**
- Type safety (entity, context) with generics.
- Synchronous and asynchronous execution parts.
- Error handling and compensation strategies.
- Step-level granular control.

`Operation.execute` returns `void`; results flow through the context (see §2.1.5).

#### 2.2.6 Step

Individual executable units within operations. `Step<T, C>` is a **pure functional contract** that is **entity-aware** (see §2.1.6) and receives `(entity, context, transition)`; like `Operation<T, C>`, it does not carry identity at runtime — the id lives on the `step(id, ...)` registration on `StateMachineDef`.

**Characteristics:**
- Entity- and context-aware execution.
- Individual compensation strategies — a step may declare its own `Compensation<T, C>`.
- Reusable across different operations: the same `Step<T, C>` class can be registered under different ids and referenced from multiple composites.

A step is invoked either as a declared member of a `CompositeOperationDef` or dynamically through `transition.step("id")` from inside an executing operation. Both paths flow through the same internal dispatcher so step-id recording, timing, and compensation registration are uniform regardless of who initiated the step. Authoring a single-step composite (one step inside a `CompositeOperationDef`) is the way to use a step as a stand-alone operation target; there is no special elevation mechanism.

#### 2.2.7 Context

Manages shared state during transition execution.

**Responsibilities:**
- Shared data storage during operation execution.
- Type-safe data access and manipulation.
- The host populates the context before invocation; the host reads results after the transition completes.

Context access in concurrent (async) execution paths is governed by the rules in §4.5.3 — by default the sync and async paths share the same context reference; isolation is opt-in via the `ForkableContext` interface.

#### 2.2.8 Trigger System

Manages the various mechanisms for initiating state transitions.

**Trigger Types:**

- **ManualTrigger** — names an explicit invocation point. A manual trigger is more than syntactic noise: it provides a named handle that carries per-trigger metadata (descriptions, listener bindings, trigger-specific pre-conditions) which may differ from the transition's defaults. Useful for cases like "cancellation cron" — the cron itself runs in the host's scheduler, but the in-library `cancellation-cron` handle anchors the metadata and lets the catalog API discover it.

- **EventTrigger** — transitions initiated by host-published events. The host pushes events into the state machine via `processEvent(...)`; the framework matches them against registered triggers.

- **DataTrigger** — transitions initiated by the host calling `processDataChange(entity)`. The framework re-evaluates registered data-trigger conditions and fires any that match. **Transflux does not watch entity fields, hook into ORM change tracking, or run background evaluations** — data triggers are host-driven re-evaluation only in 1.0. Background watching is a Post-1.0 theme (see §7.2).

#### 2.2.9 Condition System

Provides validation and gating mechanisms for transitions. `Condition<T, C>` is a **pure functional contract** with a single `test(entity, context, transition)` method; condition ids live on the def-side authoring vocabulary — the `condition(id, ...)` registry on `StateMachineDef` and on the `ConditionDescriptor` carried by transition / branch / data-trigger attachment sites.

**Types:**
- **PreCondition** — validates transition eligibility before execution.
- **PostCondition** — validates successful transition completion. If a post-condition is not met, the transition is rolled back and registered compensation actions are executed.

A condition's authoring shape — reference, instance, class, predicate, or expression — is defined uniformly by the **Condition Descriptor** grammar (see §3.6.1 and §4.7).

#### 2.2.10 Listener System

Enables observation and reaction to state machine events.

**Listener Types:**
- **State entry/exit listeners** — fire when an entity enters or exits a particular state.
- **Transition start/complete listeners** — fire at the start and end of a transition (success or failure).

Both DSLs support both listener categories symmetrically.

#### 2.2.11 Compensation Engine

Manages error recovery and rollback operations.

**Features:**
- Stack-based compensation execution (LIFO).
- Exception-specific compensation strategies.
- **Unified `Compensation<T, C>` interface** across operations and steps; both forms receive `(entity, context)`. A compensation may be declared by class or returned dynamically from `getCompensation(entity, context)`.

#### 2.2.12 State Resolver and State Applier

The host wires two paired components into the state machine.

**`StateResolver<T>`** determines the current state of an entity. Because state can be a *computed* property (e.g., a contract may report "created" until its start date, then "started"), the resolver is a function — not necessarily a simple field accessor. Resolution approaches:

- **Dedicated class** implementing `StateResolver<T>`.
- **Lambda function** (Java API only).
- **SpEL expression** evaluating against the entity.

**`StateApplier<T>`** finalizes a successful transition by writing the new state to the entity. The applier is invoked **once**, after all post-conditions have passed (see §2.1.1). Application approaches mirror the resolver:

- **Dedicated class** implementing `StateApplier<T>`.
- **Lambda function** (Java API only).
- **SpEL property path** — the framework writes through the path (e.g., `"entity.status"` ⇒ `entity.setStatus(newState)`).

**Key Characteristics:**
- The resolver/applier pair is a property of the state machine itself, not of individual transitions or triggers.
- The resolver runs before transition eligibility checks; the applier runs immediately before `onComplete` listeners.
- Type-safe in Java; SpEL-typed in YAML.
- In the simplest case the resolver reads `entity.status` and the applier writes `entity.status`. In more complex cases, the resolver computes state from multiple fields while the applier still writes a single "current state" field that the resolver then prefers over its computed fallback. The library does not mandate any particular pairing.

### 2.3 Component Relationships

```
StateMachine
├── StateResolver + StateApplier (Class, Lambda, SpEL)
├── States (Initial, Terminal, Regular)
│   ├── Entry/Exit Listeners
│   ├── Transitions
│   │   ├── Operations (Simple, Composite)
│   │   │   ├── Steps
│   │   │   ├── Context
│   │   │   └── Compensations
│   │   ├── Conditions (Pre/Post)
│   │   ├── Triggers (Manual, Event, Data)
│   │   └── Listeners (onStart, onComplete)
├── Trigger System
└── Compensation Engine
```

### 2.4 Execution Flow

1. **Transition Request** — initiated by a manual API call, an event handed to the machine, or a `processDataChange(...)` invocation.
2. **State Resolution** — determine current entity state via the configured `StateResolver<T>`.
3. **Pre-condition Evaluation** — validate transition eligibility. On failure, return a `TransitionResult` with `isSuccess() == false`; no compensations run because no operation has executed.
4. **Listener Notification (start)** — notify registered `onStart` listeners and source-state `onExit` listeners.
5. **Operation Execution** — execute the associated business logic:
    - Sequential step execution.
    - Compensation registration **before each step runs**, so a step that throws partway through producing side effects still gets its compensation invoked. A step that needs completion-time state for its rollback writes that state into the entity or context during `execute` and reads it back in the compensation; the compensation closure captured before `execute` is invoked sees the same entity and context references the body of `execute` ran against.
    - Asynchronous part scheduling (if applicable).
6. **Post-condition Evaluation** — validate successful completion. On failure, run registered compensations in LIFO order; the entity's state field is **not** updated.
7. **State Application** — invoke the `StateApplier<T>` to write the new state to the entity. The transition is now considered committed.
8. **Listener Notification (complete)** — notify registered `onComplete` listeners and target-state `onEntry` listeners.

### 2.5 Error Handling and Compensation

- **Exception Propagation** — controlled exception handling with compensation triggers.
- **Compensation Stack** — LIFO execution of registered compensation actions.
- **Validation vs. runtime errors** — `TransfluxValidationException` is thrown for definition/lookup errors; all other failure modes (failed conditions, failed steps, post-condition violations, unhandled exceptions inside steps) are reported through `TransitionResult` after compensation has run.

### 2.6 Definition Sourcing

The YAML DSL needs bytes; Transflux does not impose where those bytes live. The framework owns parsing and validation; a host-supplied `DefinitionSource` SPI owns retrieval. This lets a host back state-machine definitions with classpath resources (the common default), the filesystem, a relational database, a Git repository, an object store, an HTTP API, or any composition of these — without requiring the framework to know about any of them.

#### 2.6.1 The `DefinitionSource` Contract

```java
public interface DefinitionSource {
    Optional<DefinitionResource> open(String identifier);
}

public final class DefinitionResource implements AutoCloseable {
    String identifier();         // canonical id, threaded into validation error messages
    InputStream bytes();         // the raw YAML
    Optional<Instant> lastModified();   // optional, used by Post-1.0 reload watchers
    Optional<String> etag();             // optional, used by Post-1.0 reload watchers
    @Override void close();
}
```

#### 2.6.2 Identifier Model

Identifiers passed to `open(...)` are **opaque, source-defined strings**. The framework imposes no path semantics — no relative-path resolution, no implicit `.yml` suffix, no slash interpretation. A `path:` (or `ref:`) field on an `imports:` entry, on `apiVersion: transflux/v1` documents, or anywhere else an external definition is referenced, is handed verbatim to the configured source.

This means hosts can choose URI-like schemes (`db://workflows/subscription`, `git://main/operations/payment.yml`), bare ids (`subscription`, `payment-flow`), or filesystem-style paths (`components/shared-components.yml`) — whichever fits the source. A `CompositeDefinitionSource` ships with the framework and routes by **scheme prefix**: `cp:` to a classpath source, `file:` to a filesystem source, anything else to host-registered sources.

#### 2.6.3 Ships-With Implementations

- **`ClasspathDefinitionSource`** — resolves identifiers against the JVM classpath. The default when a host wires no other source.
- **`FileSystemDefinitionSource(Path root)`** — resolves identifiers as paths under a configured root, with traversal-rejection (`..` segments) and symlink-policy controls.
- **`CompositeDefinitionSource`** — routes by scheme prefix or by ordered fallback, depending on configuration. Useful for "classpath defaults + DB overlay" or "DB primary, filesystem fallback."

Hosts implement their own for database / Git / remote sources.

#### 2.6.4 Error Reporting

Every validation error raised against a definition loaded through a `DefinitionSource` carries the resource's identifier in the message — both the directly-failing resource and the import chain that reached it. A condition descriptor failing inside `db://workflows/subscription` imported by `git://main/root.yml` surfaces as:

```
git://main/root.yml -> imports/db://workflows/subscription -> condition 'foo': ...
```

#### 2.6.5 Caching

The framework parses each loaded resource exactly once per `StateMachine` build. It does **not** cache parsed definitions across builds; a swap (§2.7) re-loads through the source on every call. Sources are free to cache bytes themselves; the framework treats every `open(...)` as a fresh request.

### 2.7 State Machine Handle

`StateMachine<T>` is the **host-facing handle**. The immutable, fully-built per-version data — states, transitions, registries, resolved bound steps and operations — lives in an internal `StateMachineSnapshot<T>` that the handle holds and atomically replaces. From the host's point of view, a `StateMachine<T>` is one logical entity over the lifetime of the JVM; what's behind it can change.

#### 2.7.1 Snapshot Semantics

- **Every external entry point** (`entity(...)`, `executeTransition(...)`, `processEvent(...)`, `processDataChange(...)`, `getTransition(...)`, `getState(...)`, `resolveCurrentState(...)`, catalog lookups) captures the current snapshot at the top of the call and runs against that snapshot for the duration of the call. A swap mid-call does not affect the in-flight call.
- **`TransitionView`** holds the snapshot it was constructed with; every callback into the view — `view.step(...)`, `view.operation(...)`, compensation drains, condition evaluations — resolves against that snapshot. An execution that started before the swap finishes against the pre-swap topology.
- **New invocations** after the swap see the new snapshot.

#### 2.7.2 Replacing the Definition

```java
public interface StateMachine<T> {
    long generation();
    long replaceDefinition(StateMachineDef<T> newDef);
    // ... existing API ...
}
```

`replaceDefinition(newDef)`:

1. **Validates the new def in full.** All build-time checks (state graph coherence, condition resolution, composite refs, context compatibility, cycle detection, id uniqueness) run before any snapshot is constructed. A `TransfluxValidationException` thrown here leaves the current snapshot in place; the swap did not happen.
2. **Enforces entity-type compatibility.** The new def's `entityType()` must be `==` the current snapshot's `entityType()`. Replacing a `StateMachine<Foo>`'s definition with a `StateMachineDef<Bar>` — even a `Bar` that extends `Foo`, or a `Foo` subtype — is rejected with a `TransfluxValidationException`. The entity type is the handle's identity contract; widening or narrowing it would break every host call site that already holds the handle.
3. **Builds a new `StateMachineSnapshot<T>`** from the validated def.
4. **CAS-swaps** the snapshot reference. The previous snapshot's in-flight executions retain their reference and continue uninterrupted.
5. **Increments `generation()`** and returns the new generation number for the caller's diagnostics, audit logs, and metrics.

`generation()` starts at `1` after `build()` and increments by exactly `1` per successful swap; failed swaps do not increment it. Generation numbers are monotonic per-handle and are not comparable across handles.

#### 2.7.3 Use Cases

- **Java DSL hot-swap (1.0).** Host builds a fresh `StateMachineDef<T>` (manually, or by re-running its own configuration code against new inputs) and calls `sm.replaceDefinition(newDef)`. Useful for admin endpoints, blue/green topology testing, config-driven workflow changes, and integration tests that replay multiple topologies against a fixed entity.
- **YAML hot-swap (1.0).** Host loads YAML via its `DefinitionSource`, builds a `StateMachineDef<T>` via the YAML loader, and calls `sm.replaceDefinition(newDef)`. The mechanism is identical — YAML is just one source of defs.
- **Automatic watcher-driven reload (Post-1.0).** A `ReloadableDefinitionSource` extension (§7.2) exposes a change-notification hook; a host-supplied or framework-supplied driver calls `replaceDefinition` when the source reports a change.

#### 2.7.4 Concurrency Guarantees

- `replaceDefinition` is safe to call from any thread. Concurrent swaps are serialised internally; only one wins per generation increment.
- `generation()` returns a `long` that is a coherent read of the current generation.
- The handle imposes no host-side synchronisation requirement for ordinary reads.
- **In-flight isolation:** an execution started against generation N runs against generation N's snapshot regardless of how many swaps happen during it. The reentrancy guard (§2.1.3) still applies per-snapshot — an in-flight execution that reenters into the *handle* (rather than its own snapshot reference) is rejected the same way.

#### 2.7.5 What Replacing the Definition Does *Not* Do

- It does **not** migrate, freeze, redirect, or otherwise act on entities currently in states that the new def may have removed or renamed. The framework treats this as a host concern, identical to the equivalent in a host that does not use Transflux: if you change the meaning of "state X" or remove it, you owe your entities a migration story. Transflux's job is to make the swap atomic and to keep in-flight transitions correct against the topology they started under; everything else is application-level.
- It does **not** invalidate, drain, or wait for in-flight executions. Long-running stays a non-goal (§1.3).
- It does **not** notify listeners. Listener delivery is per-execution, not per-handle.

---

## 3. YAML-based DSL Specification

The YAML-based DSL provides a declarative approach to defining state machines, transitions, and operations. It supports modular definitions with imports and references for reusability.

### 3.1 Reusable Component Libraries

To eliminate duplication and promote reusability, Transflux supports shared component libraries that can be defined once and referenced multiple times across different state machines, operations, and transitions.

#### 3.1.1 Component Library Structure

```yaml
# components/shared-components.yml
apiVersion: transflux/v1
metadata:
  name: "Subscription Management Components"
  description: "Shared components for subscription management"

spec:
  # Shared Steps
  steps:
    - id: prepare-notifications
      name: "Prepare Notifications"
      class: com.example.steps.PrepareNotificationsStep
      description: "Prepare notification messages"
      
    - id: send-notifications
      class: com.example.steps.SendNotificationsStep
      description: "Send prepared notifications"
      
    - id: update-analytics
      name: "Update Analytics"
      class: com.example.steps.UpdateAnalyticsStep
      description: "Update analytics data"
      
    - id: activate-features
      class: com.example.steps.ActivateFeaturesStep
      description: "Activate related subscription features"
      
    - id: prepare-event-actor
      class: com.example.steps.PrepareEventActorStep
      description: "Prepare event actor for transition"
      
    - id: validate-prerequisites
      name: "Validate Prerequisites"
      class: com.example.steps.ValidatePrerequisitesStep
      description: "Validate operation prerequisites"
      compensation: com.example.compensations.ValidationCompensation
      
  # Shared Conditions
  conditions:
    - id: payment-method-valid
      name: "Payment Method Valid"
      class: com.example.conditions.PaymentMethodValidCondition
      description: "Verify payment method is valid"
        
    - id: subscription-features-activated
      class: com.example.conditions.FeaturesActivatedCondition
      description: "Verify all subscription features are activated"
      
    - id: business-hours
      name: "Business Hours Check"
      expression: "T(java.time.LocalTime).now().hour >= 9 && T(java.time.LocalTime).now().hour < 17"
      description: "Check if current time is within business hours"
      
  # Shared Triggers
  triggers:
    - id: payment-method-validated-event
      name: "Payment Method Validated"
      type: event
      event: PAYMENT_METHOD_VALIDATED
      filter:
        expression: "event.validation == 'CONFIRMED'"
        
    - id: data-priority-change
      type: data
      condition:
        expression: "entity.status == 'READY_FOR_ACTIVATION' && entity.priority > 5"
        
  # Shared Listeners
  listeners:
    - id: audit-start
      name: "Audit Start Listener"
      class: com.example.listeners.TransitionStartListener
      description: "Audit transition start"
      
    - id: audit-complete
      class: com.example.listeners.TransitionCompleteListener
      description: "Audit transition completion"
      
    - id: subscription-activated
      name: "Subscription Activated Listener"
      class: com.example.listeners.SubscriptionActivatedListener
      description: "Handle subscription activation events"
      config:
        async: true
        
  # Shared Operations
  operations:
    - id: notification-flow
      name: "Notification Flow"
      type: composite
      description: "Standard notification flow"
      steps:
        - id: prepare-notifications-step
          step: prepare-notifications
        - id: send-notifications-step
          step: send-notifications
          
    - id: analytics-update
      type: simple
      step: update-analytics
      description: "Standard analytics update"
      async:
        enabled: true
```

#### 3.1.2 Component Reference Grammar

Any field that expects a component (operation, step, condition, trigger, listener) accepts **either** a reference or an inline definition.

**1. String ID — a reference** to a component defined in the current file, in an imported library, or in the runtime component registry:

```yaml
operation: activate-subscription
preConditions:
  - payment-method-valid
```

**2. Inline block — a full component definition.** Inline definitions are first-class throughout the DSL; they are essential to keep simple cases readable (a lesson learned the hard way with overly-modularized BPMN dialects):

```yaml
operation:
  id: cancel-subscription
  type: simple
  class: com.example.operations.CancelSubscriptionOperation
  compensation: com.example.compensations.RefundPartialFeesCompensation
```

A long-form `ref:` is also accepted where a block is more natural:

```yaml
operation:
  ref: activate-subscription
```

**Rules:**
- All component IDs must be unique within their type across the current file and all imported definitions.
- An inline definition's `id` is **required**, except for inline expression-based conditions (the single auto-ID exception described in §2.2.1).
- Type discrimination for inline definitions: operations require a `type:` (`simple` | `composite`); steps and conditions infer type from the descriptor (`class` / `step` / `expression` / `predicate`).

#### 3.1.3 Component References in Context

Components from libraries can be referenced directly by their unique name:

```yaml
# In operations
operations:
  - id: activation-operation
    type: composite
    steps:
      - id: prepare-actor
        step: prepare-event-actor
        
      - id: validate
        step: validate-prerequisites
        
      - id: notifications
        operation: notification-flow
        
      - id: analytics
        operation: analytics-update

# In transitions
transitions:
  - id: draft-to-active
    from: draft
    to: active
    
    preConditions:
      - checkout-fulfilled
      - business-hours
      
    postConditions:
      - subscription-features-activated
      
    triggers:
      - checkout-event
      - data-priority-change
      
    listeners:
      onStart:
        - audit-start
      onComplete:
        - audit-complete
```

#### 3.1.4 Library Imports

Each `path:` value is an **opaque identifier** handed verbatim to the configured `DefinitionSource` (§2.6). The framework does not interpret it as a filesystem path, classpath resource, or URI — that's the source's job. A filesystem-style example reads naturally and is the most common default (`ClasspathDefinitionSource` interprets such strings as classpath resources, `FileSystemDefinitionSource` interprets them as paths under a configured root), but any string the configured source understands is valid: `cp:components/shared.yml`, `db://workflows/subscription/imports/payment`, `git://main/operations.yml`, and so on.

```yaml
apiVersion: transflux/v1

# Import component libraries
# All component IDs must be unique across all imported definitions.
# Each `path:` is an opaque identifier; the configured DefinitionSource (§2.6) resolves it.
imports:
- path: components/shared-components.yml
- path: components/subscription-specific-components.yml
- path: operations/subscription-operations.yml

stateMachine:
  id: subscription-state-machine
  name: "Subscription State Machine"
  entityType: com.example.Subscription
  
  transitions:
    - id: trial-to-active
      from: trial
      to: active
      operation: activate-subscription
      preConditions:
        - payment-method-valid
        - subscription-specific-validation
      triggers:
        - end-of-trial-cron
```

### 3.2 State Machine Definition

#### 3.2.1 Basic Structure

> Note: only a single state machine definition is allowed per file.

```yaml
# subscription-state-machine.yml
apiVersion: transflux/v1

imports:
- operations/subscription-operations.yml
- triggers/subscription-triggers.yml
- conditions/subscription-conditions.yml

stateMachine:
  id: subscription-state-machine
  name: "Subscription State Machine"
  version: 1.0.0
  description: "State machine for subscription lifecycle management"
  
  entityType: com.example.Subscription
  
  # State resolver — read the current state
  stateResolver:
    class: com.example.resolvers.SubscriptionStateResolver
    # Alternatives:
    #   expression: "entity.status"
  
  # State applier — finalize the transition by writing the new state
  stateApplier:
    class: com.example.appliers.SubscriptionStateApplier
    # Alternatives:
    #   expression: "entity.status"     # SpEL write-through property path
  
  states:
    - id: trial
      name: "Trial State"
      description: "Initial trial state"
      
    - id: active
      description: "Active subscription state"
      
    - id: suspended
      description: "Suspended subscription state"
      
    - id: cancelled
      description: "Cancelled subscription state"
      
    - id: expired
      description: "Expired subscription state"

  transitions:
    - id: trial-to-active
      name: "Trial to Active Transition"
      from: trial
      to: active
      operation: activate-subscription
      preConditions:
        - payment-method-valid
      triggers:
        - id: end-of-trial-cron
          type: manual
          description: "Invoked manually by an external cron job"
      
    - id: active-to-suspended
      from: active
      to: suspended
      # Inline composite operation
      operation:
        id: evaluate-suspension
        type: composite
        description: "Evaluate suspension handling with retry or notify branches"
        steps:
          - id: evaluate-risk
          - id: branch-on-risk
            type: conditional
            branches:
              - id: low-risk-retry
                condition:
                  expression: "@riskService.isLowRisk(entity.id)"
                steps:
                  - id: schedule-retry
                    class: com.example.steps.ScheduleRetryPaymentStep
              - id: default-notify
                steps:
                  - id: notify-user
                    class: com.example.steps.NotifyPaymentIssueStep
      triggers:
        - id: payment-failed
          type: data
          condition:
            predicate: com.example.triggers.PaymentFailedTrigger
      
    - id: suspended-to-cancelled
      from: suspended
      to: cancelled
      operation:
        id: cancel-subscription
        type: simple
        description: "Cancel subscription with compensation"
        class: com.example.operations.CancelSubscriptionOperation
        compensation: com.example.compensations.RefundPartialFeesCompensation
      preConditions:
        - no-recovery-7-days
      triggers:
        - id: cancellation-cron
          type: manual
          description: "Invoked manually by an external cron job"
      
    - id: active-to-expired
      from: active
      to: expired
```

#### 3.2.2 State Configuration

```yaml
states:
  - id: active
    name: "Active State"
    description: "Active subscription state"
    # State entry/exit listeners (see §3.7)
    listeners:
      onEntry:
        - subscription-activated
      onExit:
        - subscription-deactivated
```

### 3.3 Transition Configuration

#### 3.3.1 Basic Transition

```yaml
transitions:
  - id: trial-to-active
    name: "Trial to Active Transition"
    from: trial
    to: active
    description: "Activate trial subscription"
    
    operation: activate-subscription
    
    preConditions:
      - payment-method-valid
        
    postConditions:
      - milestones-activated
    
    triggers:
      - id: end-of-trial-cron
        type: manual
        description: "Invoked manually by an external cron job"
        
      - id: external-activation
        type: data
        condition:
          class: com.example.triggers.SubscriptionActivatedTrigger
        
    listeners:
      onStart:
        - audit-start
      onComplete:
        - audit-complete
```

#### 3.3.2 Manual Triggers

A `type: manual` trigger names an explicit invocation point. Even when a transition could be invoked through the bare `stateMachine.transitionTo(...)` API, defining a named manual trigger carries value: per-trigger metadata, listener bindings, descriptions, and trigger-specific pre-conditions can be attached to the named handle and discovered via the catalog API. The trigger's name does **not** imply the library schedules anything — for example, `end-of-trial-cron` indicates that an external cron job invokes this trigger; the library does no scheduling itself (see §1.3 Non-Goals).

```yaml
triggers:
  - id: manual-cancel
    type: manual
    description: "User-initiated cancellation through the support portal"
    preConditions:
      - support-user-authorized
```

#### 3.3.3 Event Triggers

Event triggers fire in response to events that the host publishes into the state machine via `processEvent(...)`.

```yaml
triggers:
  - id: payment-method-validated-event
    type: event
    event: PAYMENT_METHOD_VALIDATED
    filter:
      expression: "event.validation == 'CONFIRMED'"
```

#### 3.3.4 Data Triggers

Data triggers fire when the host calls `processDataChange(entity)` and the trigger's condition matches the entity's current state.

> **Reminder:** Transflux does not watch entity fields, hook into ORM change tracking, or evaluate triggers automatically in 1.0 — data triggers are host-driven re-evaluation only (see §1.3 Non-Goals). The host's typical pattern is: update the entity → call `processDataChange(entity)` → framework evaluates registered data triggers and fires any matching transition.

A data trigger's condition follows the standard Condition Descriptor grammar (§3.6.1):

```yaml
triggers:
  # Class-based — full Condition<T> implementation
  - id: priority-change-class
    type: data
    condition:
      class: com.example.triggers.SubscriptionPriorityChangedCondition
      
  # Predicate-based — lighter-weight Predicate<T>-style class
  - id: payment-failed
    type: data
    condition:
      predicate: com.example.triggers.PaymentFailedTrigger
      
  # Expression-based — inline SpEL
  - id: priority-change-expression
    type: data
    condition:
      expression: "entity.status == 'READY_FOR_ACTIVATION' && entity.priority > 5"
      
  # Reference to a pre-defined condition (id shorthand)
  - id: ready-and-high-priority
    type: data
    condition: ready-and-high-priority-condition
```

### 3.4 Operation Definitions

#### 3.4.1 Simple Operation

A simple operation is either a single Java `Operation` implementation **or** a single Step elevated to operation-level usage.

```yaml
operations:
  # Class-backed simple operation
  - id: activate-subscription
    name: "Activate Subscription"
    description: "Activate a trial subscription"
    type: simple
    class: com.example.operations.ActivateSubscriptionOperation
    context:
      class: com.example.contexts.ActivationContext
  
  # Step elevated as operation
  - id: send-welcome-email
    type: simple
    step: send-welcome-email-step
```

#### 3.4.2 Composite Operation

```yaml
operations:
  - id: complex-activation
    type: composite
    description: "Complex activation with multiple steps"
    
    context:
      class: com.example.contexts.ComplexActivationContext
      
    steps:
      - id: prepare-event-actor
        type: step
      - id: validate-prerequisites
        # type: step is the default and can be omitted
      - id: lock-resources
        # Steps may be defined in-line
        class: com.example.operations.LockResourcesOperation
        description: "Lock resources for activation"
        
      - id: multi-branch-conditional
        type: conditional
        branches:
          - id: high-priority-branch
            condition:
              predicate: com.example.predicates.HighPriorityPredicate
            steps:
              - id: high-priority-processing
                class: com.example.steps.HighPriorityStep
              - id: urgent-notification
                class: com.example.steps.UrgentNotificationStep
                
          - id: medium-priority-branch
            condition:
              expression: "entity.priority >= 5 && entity.priority < 8"
            steps:
              - id: medium-priority-processing
                class: com.example.steps.MediumPriorityStep
                
          - id: vip-customer-branch
            condition:
              predicate: com.example.predicates.VipCustomerPredicate
            steps:
              - id: vip-processing
                class: com.example.steps.VipProcessingStep
              - id: account-manager-notification
                class: com.example.steps.AccountManagerNotificationStep
                
        default:
          steps:
            - id: standard-processing
              class: com.example.steps.StandardProcessingStep
            - id: standard-notification
              class: com.example.steps.StandardNotificationStep
              
      - id: finalize
        class: com.example.steps.FinalizeActivationStep
        
    errorHandling:
      - exception: com.example.exceptions.RecoverableException
        condition:
          predicate: com.example.predicates.RecoverableErrorPredicate
        compensation: com.example.compensations.RecoverableCompensation
        
      - exception: java.lang.Exception
        compensation: com.example.compensations.GeneralCompensation
        
    # Async part — anchored to a named sync step; runs concurrently from that point on
    async:
      enabled: true
      startBeforeStep: finalize     # OR: startAfterStep: last-business-step
      steps:
        - id: async-notifications
          class: com.example.steps.AsyncNotificationStep
        - id: external-integrations
          class: com.example.steps.ExternalIntegrationStep
```

> **Async semantics.** The async block is always anchored to a sync step. Two anchor forms are supported; exactly one must be specified.
>
> - `startBeforeStep: <stepId>` — async steps are scheduled when execution **reaches** the named sync step. Use this when the async work doesn't depend on the named step's results — for example, at a join point right after conditional branches merge (`stepA → if/else → stepD`), anchor `startBeforeStep: stepD` so the async kicks off as soon as the branches converge, regardless of which branch ran. This avoids duplicating identical async blocks at the end of each branch.
> - `startAfterStep: <stepId>` — async steps are scheduled when the named sync step **completes successfully**. Use this when the async work observes or notifies about the results of that step — for example, sending non-essential post-action notifications about completed business logic. The async work cannot run before the step it depends on.

#### 3.4.3 Multi-Branch Conditional Operations

Multi-branch conditional operations allow for complex decision-making with multiple predicates and a default fallback branch. They evaluate conditions in declaration order and execute the **first matching** branch, or the default branch if no conditions match.

```yaml
operations:
  - id: priority-based-routing
    type: composite
    description: "Route processing based on multiple priority conditions"
    
    steps:
      - id: multi-priority-routing
        type: conditional
        branches:
          - id: critical-priority
            condition:
              predicate: com.example.predicates.CriticalPriorityPredicate
            steps:
              - id: escalate-immediately
                class: com.example.steps.EscalateStep
              - id: notify-management
                class: com.example.steps.ManagementNotificationStep
              - id: expedited-processing
                class: com.example.steps.ExpeditedProcessingStep
                
          - id: high-priority
            condition:
              expression: "entity.priority >= 8 && entity.customerTier == 'PREMIUM'"
            steps:
              - id: priority-processing
                class: com.example.steps.PriorityProcessingStep
              - id: premium-notification
                class: com.example.steps.PremiumNotificationStep
                
          - id: vip-customer
            condition:
              predicate: com.example.predicates.VipCustomerPredicate
            steps:
              - id: vip-processing
                class: com.example.steps.VipProcessingStep
              - id: account-manager-alert
                class: com.example.steps.AccountManagerAlertStep
                
          - id: time-sensitive
            condition:
              expression: "entity.deadline.isBefore(T(java.time.LocalDate).now().plusDays(1))"
            steps:
              - id: urgent-processing
                class: com.example.steps.UrgentProcessingStep
                
          - id: business-hours
            condition:
              expression: "T(java.time.LocalTime).now().hour >= 9 && T(java.time.LocalTime).now().hour < 17"
            steps:
              - id: business-hours-processing
                class: com.example.steps.BusinessHoursProcessingStep
                
        default:
          steps:
            - id: standard-processing
              class: com.example.steps.StandardProcessingStep
            - id: standard-notification
              class: com.example.steps.StandardNotificationStep
            - id: queue-for-batch
              class: com.example.steps.QueueForBatchStep
```

**Execution Semantics:**
1. Branches are evaluated in the order they are defined.
2. The first branch whose condition evaluates to `true` is executed.
3. Once a branch is executed, no further conditions are evaluated.
4. If no branch conditions match, the `default` branch is executed.
5. If no `default` branch is defined and no conditions match, the outcome is configurable through the `NoMatchBehavior` enumeration on the conditional step:
   - **`WARN`** (default) — log a warning and continue. The conditional step itself completes without dispatching any inner steps; its id is recorded on `executedStepIds`.
   - **`SILENT`** — same as `WARN` but without logging. Suits the guard pattern (`if (cond) { ... }` with no `else`), where a no-match is the deliberate, expected outcome.
   - **`ERROR`** — raise a transition failure. The conditional step fails, the operation fails, and any registered compensations run.

### 3.5 Context and Data Mapping

#### 3.5.1 Context Definition

```yaml
contexts:
  - id: activation-context
    class: com.example.contexts.ActivationContext
```

#### 3.5.2 Context Usage Examples

Applications must populate the context before execution and read results after completion:

```java
// Application populates context before execution
ActivationContext context = new ActivationContext();
context.setSubscriptionId(entity.getId());
context.setPaymentMethodId(entity.getPaymentMethodId());
context.setActivatedBy("SYSTEM");

// Execute transition with context
TransitionResult<Subscription> result = stateMachine
    .entity(entity)
    .withContext(context)
    .transitionTo("active");

// Application reads results from context after execution
log.info("Activated subscription {} at {} with result {}",
    entity.getId(), context.getActivatedAt(), context.getActivationResult());
```

### 3.6 Conditions and Validators

#### 3.6.1 Condition Descriptor

Conditions appear in many places: pre/post conditions, conditional branch selectors, data-trigger gates, event-trigger filters. They share a single grammar — the **Condition Descriptor** — with five authoring forms. Four are expressible in both DSLs; the fifth (`InstanceBased`) attaches a pre-built `Condition<T, C>` instance and is Java-only — it has no YAML serialization because a live Java object cannot be expressed in YAML without a class-name handle (use the `class:` form for that).

```yaml
# 1. Reference to a pre-defined condition (string shorthand)
preConditions:
  - payment-method-valid

# 2. Inline class-based — a full Condition<T> implementation
preConditions:
  - condition:
      class: com.example.conditions.PaymentMethodValidCondition

# 3. Inline predicate-based — a Predicate<T>-style class (lighter than Condition<T>;
#    useful for stateless boolean tests without DI or rich failure metadata)
preConditions:
  - condition:
      predicate: com.example.predicates.PaymentMethodValidPredicate

# 4. Inline expression — SpEL evaluated against the entity (and context where applicable)
preConditions:
  - condition:
      expression: "entity.paymentMethodId != null"

# 5. (Java DSL only) InstanceBased — attach a pre-built Condition<T, C> instance under an explicit id.
#    Useful when the host already holds a configured instance (e.g., from a DI container) and wants
#    to wire it through the same descriptor pipeline as the class/predicate forms.
```

**Resolution rules:**
- When a list element is a **bare string**, it is interpreted as form 1 (reference).
- When it is a **block**, it must contain exactly one of `class`, `predicate`, `expression`, or `ref` (long-form reference).

**Form comparison:**
- A **`Condition<T, C>` instance** (form 5, Java DSL only) is the right choice when the host already has a configured `Condition` (DI-wired, holds runtime state) and wants to attach it to a single site without re-routing through the `StateMachineDef.condition(id, ...)` registry.
- A **`Condition<T, C>`** implementation class (form 2) is the full-featured *class-shaped* form: it can hold injected dependencies, return rich failure metadata (error codes, messages), and is the appropriate choice for reusable, framework-aware conditions instantiated by the framework or DI container.
- A **`Predicate<T>`** (form 3) is the minimal shape — a simple boolean test. Useful for stateless conditions where rich metadata is unnecessary.
- An **expression** (form 4) is for one-off inline logic that doesn't justify a Java class.
- A **reference** (form 1) shares a single definition across many transitions.

The same descriptor grammar is reused everywhere a condition is accepted: pre/post conditions, conditional branch selectors, data-trigger gates.

#### 3.6.2 Pre/Post Conditions

```yaml
conditions:
  - id: checkout-fulfilled
    class: com.example.conditions.CheckoutFulfilledCondition
      
  - id: milestones-activated
    class: com.example.conditions.MilestonesActivatedCondition
    
  - id: business-hours
    expression: "T(java.time.LocalTime).now().isAfter(T(java.time.LocalTime).of(9, 0)) and T(java.time.LocalTime).now().isBefore(T(java.time.LocalTime).of(17, 0))"
```

### 3.7 Listeners and Hooks

Both DSLs support state entry/exit listeners and transition start/complete listeners.

```yaml
# State entry/exit listeners — attached to the state definition
states:
  - id: active
    listeners:
      onEntry:
        - subscription-activated
      onExit:
        - subscription-deactivated

# Transition listeners — attached to the transition definition
transitions:
  - id: trial-to-active
    listeners:
      onStart:
        - audit-start
      onComplete:
        - audit-complete

# Global listeners — apply to all transitions or all states
listeners:
  transitionListeners:
    - transition: "*"
      onComplete:
        - class: com.example.listeners.TransitionAuditListener
        
  stateListeners:
    - state: "*"
      onEntry:
        - class: com.example.listeners.StateAuditListener
```

### 3.8 Global Configuration

```yaml
config:
  # Async settings
  async:
    threadPoolSize: 10
    queueCapacity: 100
    
  # Metrics settings
  metrics:
    enabled: true
    flowLabel: subscription-management
    
  # Logging settings
  logging:
    level: INFO
    includeContext: true
    includeTimings: true
```

### 3.9 Expression Language Support

Transflux uses SpEL (Spring Expression Language) for inline expression evaluation in conditions, filters, and computed state.

```yaml
conditions:
  # Simple field access
  - id: status-ready
    expression: "entity.status == 'READY'"
    
  # Method calls (with DI integration when available)
  - id: checkout-fulfilled
    expression: "@checkoutService.isCheckoutFulfilled(entity.checkoutUid)"
    
  # Date/time conditions
  - id: business-hours
    expression: |
      T(java.time.LocalTime).now().isAfter(T(java.time.LocalTime).of(9, 0)) && 
      T(java.time.LocalTime).now().isBefore(T(java.time.LocalTime).of(17, 0))
      
  # Collection operations
  - id: all-milestones-active
    expression: "entity.milestones.![state].contains('INACTIVE') == false"
    
  # Conditional logic
  - id: priority-based-validation
    expression: |
      entity.priority > 8 ? 
        @validationService.strictValidation(entity) : 
        @validationService.basicValidation(entity)

# Data-based triggers with SpEL expression evaluation
triggers:
  - id: ready-and-high-priority
    type: data
    condition:
      expression: "entity.status == 'PENDING' && entity.priority > 5"

  - id: pending-and-expedited
    type: data
    condition:
      # Expedite if pending and the request is marked as expedited in context
      expression: "entity.status == 'PENDING' && (context?.expedited ?: false)"
```

---

## 4. Java-based Builder DSL Specification

The Java-based builder DSL provides a programmatic, type-safe approach to defining state machines, transitions, and operations. It emphasizes fluent interfaces, compile-time safety, and IDE support.

### 4.1 Reusable Component Registry

To eliminate duplication and promote reusability, the Java DSL supports a component registry that lets components be defined once and referenced across state machines, operations, and transitions.

#### 4.1.1 Component Registry Structure

```java
@Component
public class SubscriptionComponentRegistry implements ComponentRegistry {
    
    // Shared Steps
    @RegisterStep("prepare-notifications")
    public PrepareNotificationsStep prepareNotificationsStep() {
        return new PrepareNotificationsStep();
    }
    
    @RegisterStep("send-notifications")
    public SendNotificationsStep sendNotificationsStep() {
        return new SendNotificationsStep();
    }
    
    @RegisterStep("update-analytics")
    public UpdateAnalyticsStep updateAnalyticsStep() {
        return new UpdateAnalyticsStep();
    }
    
    @RegisterStep("activate-milestones")
    public ActivateMilestonesStep activateMilestonesStep() {
        return new ActivateMilestonesStep();
    }
    
    @RegisterStep("prepare-event-actor")
    public PrepareEventActorStep prepareEventActorStep() {
        return new PrepareEventActorStep();
    }
    
    @RegisterStep("validate-prerequisites")
    public ValidatePrerequisitesStep validatePrerequisitesStep() {
        return new ValidatePrerequisitesStep();
    }
    
    // Shared Conditions
    @RegisterCondition("payment-method-valid")
    public PaymentMethodValidCondition paymentMethodValidCondition() {
        return new PaymentMethodValidCondition();
    }
    
    @RegisterCondition("milestones-activated")
    public MilestonesActivatedCondition milestonesActivatedCondition() {
        return new MilestonesActivatedCondition();
    }
    
    @RegisterCondition("business-hours")
    public BusinessHoursCondition businessHoursCondition() {
        return new BusinessHoursCondition();
    }
    
    // Shared Triggers
    @RegisterTrigger("payment-method-validated-event")
    public EventTrigger paymentMethodValidatedEvent() {
        return EventTrigger.builder()
            .event(Event.PAYMENT_METHOD_VALIDATED)
            .filterBy("subscriptionId", entity -> ((Subscription) entity).getId())
            .build();
    }
    
    @RegisterTrigger("data-priority-change")
    public DataTrigger dataPriorityChangeTrigger() {
        return DataTrigger.builder()
            .evaluateEntity(entity -> {
                Subscription s = (Subscription) entity;
                return "READY_FOR_ACTIVATION".equals(s.getStatus())
                    && s.getPriority() > 5;
            })
            .build();
    }
    
    // Shared Listeners
    @RegisterListener("audit-start")
    public TransitionStartListener auditStartListener() {
        return new TransitionStartListener();
    }
    
    @RegisterListener("audit-complete")
    public TransitionCompleteListener auditCompleteListener() {
        return new TransitionCompleteListener();
    }
    
    @RegisterListener("subscription-activated")
    public SubscriptionActivatedListener subscriptionActivatedListener() {
        return new SubscriptionActivatedListener();
    }
    
    // Shared Operations
    @RegisterOperation("notification-flow")
    public CompositeOperation notificationFlowOperation() {
        return compositeOperation("notification-flow")
            .step("prepare-notifications", "prepare-notifications")
            .step("send-notifications", "send-notifications")
            .build();
    }
    
    @RegisterOperation("analytics-update")
    public SimpleOperation analyticsUpdateOperation() {
        return simpleOperation("analytics-update")
            .step("update-analytics")
            .async(true)
            .build();
    }
}
```

#### 4.1.2 Component References

Components from the registry can be referenced directly by their unique name. All component IDs must be unique across all registered components:

```java
// In operations
CompositeOperation activationOperation = compositeOperation("activation-operation")
    .step("prepare-actor", "prepare-event-actor")
    .step("validate", "validate-prerequisites")
    .step("notifications", "notification-flow")
    .step("analytics", "analytics-update")
    .build();

// In transitions
draftActiveTransition
    .addPreCondition("checkout-fulfilled")
    .addPreCondition("business-hours")
    .addPostCondition("milestones-activated")
    .addTrigger("checkout-event")
    .addTrigger("data-priority-change")
    .onStart("audit-start")
    .onComplete("audit-complete");
```

#### 4.1.3 Registry Configuration and Injection

```java
@Configuration
@EnableTransflux
public class TransfluxConfig {
    
    @Bean
    public ComponentRegistry subscriptionComponentRegistry() {
        return new SubscriptionComponentRegistry();
    }
    
    @Bean
    public StateMachine<Subscription> subscriptionStateMachine(ComponentRegistry registry) {
        return Transflux.defineStateMachine()
            .forEntityType(Subscription.class)
            .withComponentRegistry(registry)
            // ... state machine definition using component references
            .build();
    }
}

// Alternative programmatic registration
ComponentRegistry registry = ComponentRegistry.builder()
    .registerStep("prepare-notifications", PrepareNotificationsStep.class)
    .registerStep("send-notifications", SendNotificationsStep.class)
    .registerStep("update-analytics", UpdateAnalyticsStep.class)
    .registerCondition("payment-method-valid", PaymentMethodValidCondition.class)
    .registerCondition("milestones-activated", MilestonesActivatedCondition.class)
    .registerListener("audit-start", TransitionStartListener.class)
    .registerListener("audit-complete", TransitionCompleteListener.class)
    .build();

StateMachine<Subscription> stateMachine = Transflux.defineStateMachine()
    .forEntityType(Subscription.class)
    .withComponentRegistry(registry)
    .build();
```

### 4.2 Core API Structure

#### 4.2.1 StateMachine Definition

```java
StateMachine<Subscription> subscriptionStateMachine = Transflux.defineStateMachine()
    .forEntityType(Subscription.class)
    .withName("subscription-state-machine")
    .withVersion("1.0.0")
    
    // State resolver — read the current state
    .withStateResolver(SubscriptionStateResolver.class)
    // Alternatives:
    //   .withStateResolver(entity -> entity.getStatus())     // lambda
    //   .withStateResolver("entity.status")                  // SpEL
    
    // State applier — finalize the transition by writing the new state
    .withStateApplier(SubscriptionStateApplier.class)
    // Alternatives:
    //   .withStateApplier((entity, newState) -> entity.setStatus(newState))
    //   .withStateApplier("entity.status")                   // SpEL property path
    
    // Define states
    .state("trial")
        .withDescription("Initial trial state")
        .transitionsTo("active")
        .end()
        
    .state("active")
        .withDescription("Active subscription state")
        .transitionsTo("suspended", "expired")
        .end()
        
    .state("suspended")
        .withDescription("Suspended subscription state")
        .transitionsTo("cancelled")
        .end()
        
    .state("cancelled", "expired")
        .end()
        
    .build();

// Alternative compact syntax
StateMachine<Subscription> compactStateMachine = Transflux.defineStateMachine()
    .forEntityType(Subscription.class)
    .withStateResolver("entity.status")
    .withStateApplier("entity.status")
    .states(
        state("trial").transitionsTo("active"),
        state("active").transitionsTo("suspended", "expired"),
        state("suspended").transitionsTo("cancelled"),
        state("cancelled", "expired")
    )
    .build();
```

#### 4.2.2 Advanced State Configuration

```java
StateMachine<Subscription> stateMachine = Transflux.defineStateMachine()
    .forEntityType(Subscription.class)
    
    .state("active")
        .withDescription("Active subscription state")
        .withMetadata("displayName", "Active")
        
        // State entry/exit listeners
        .onEntry(SubscriptionActivatedListener.class)
        .onEntry(NotificationListener.class, config -> config
            .property("template", "subscription-activated")
            .property("async", true))
        .onExit(SubscriptionDeactivatedListener.class)
        
        .transitionsTo("suspended", "expired")
        .end()
        
    .build();
```

### 4.3 Transition Configuration

```java
// Get transition reference
Transition<Subscription, SubscriptionContext> trialActiveTransition =
    stateMachine.getTransition("trial", "active");

// Configure transition
trialActiveTransition
    .withName("trial-to-active")
    .withDescription("Activate trial subscription")
    
    // Set operation
    .withOperation(ActivateSubscriptionOperation.class)
        .usingContext(SubscriptionContext.class)
    
    // Pre/post conditions
    .addPreCondition(PaymentMethodValidCondition.class)
    .addPreCondition("billing-ready", this::billingReady)
    .addPostCondition(SubscriptionFeaturesActivatedCondition.class)
    
    // Triggers
    .addManualTrigger()
    .addEventTrigger(Event.PAYMENT_CONFIRMED)
    .addDataTrigger(SubscriptionActivatedTrigger.class)
    
    // Listeners
    .onStart(TransitionStartListener.class)
    .onComplete(TransitionCompleteListener.class)
    .onError(TransitionErrorListener.class);
```

### 4.4 Operation Definitions

#### 4.4.1 Simple Operation

```java
public class ActivateSubscriptionOperation
        implements Operation<Subscription, SubscriptionContext> {
    
    @Inject private BillingService billingService;
    @Inject private SubscriptionFeaturesService featuresService;
    
    @Override
    public void execute(Subscription subscription, SubscriptionContext context,
                        Transition<Subscription, SubscriptionContext> transition) {
        validateSubscription(subscription.getId());
        
        // Execute steps via transition
        transition.step("prepare-billing-actor", PrepareBillingActorStep.class);
        transition.step("validate-payment-method", ValidatePaymentMethodStep.class);
        
        // Results flow back through the context (see §2.1.5)
        context.setActivatedAt(Instant.now());
        context.setSubscriptionStatus(SubscriptionStatus.ACTIVE);
    }
    
    @Override
    public Class<? extends Compensation<Subscription, SubscriptionContext>> getCompensation() {
        return SubscriptionActivationCompensation.class;
    }
    
    // Alternatively, return the compensation directly at execution time
    @Override
    public Compensation<Subscription, SubscriptionContext> getCompensation(
            Subscription subscription, SubscriptionContext context) {
        return new SubscriptionActivationCompensation();
    }
}

// Configure operation on transition
trialActiveTransition
    .setOperation(ActivateSubscriptionOperation.class)
    .usingContext(SubscriptionContext.class)
    .withCompensation(SubscriptionActivationCompensation.class)
    .withAsync(async -> async
        .enabled(true)
        .startBefore("finalize")                 // OR: .startAfter("last-business-step")
        .steps("notifyExternalSystems", "updateAnalytics"))
    .end();
```

#### 4.4.2 Composite Operation (Declarative Style)

```java
trialActiveTransition.setOperation(
    compositeOperation("complex-subscription-activation")
        .withDescription("Complex subscription activation with multiple steps")
        .usingContext(ComplexSubscriptionContext.class)
        
        // Sequential steps
        .step("prepare-billing-actor", PrepareBillingActorStep.class)
        
        .step("validate-payment-method", ValidatePaymentMethodStep.class)
            .withCompensation(PaymentValidationCompensation.class)
        
        // Multi-branch conditional
        .conditional("subscription-tier-routing")
            .branch("premium-tier")
                .condition(PremiumTierPredicate.class)
                .step("premium-tier-processing", PremiumTierStep.class)
                .step("vip-notification", VipNotificationStep.class)
            .end()
            
            .branch("standard-tier")
                .condition(s -> "STANDARD".equals(s.getTier()) && s.getPriority() >= 5)
                .step("standard-tier-processing", StandardTierStep.class)
            .end()
            
            .branch("enterprise-customer")
                .condition(EnterpriseCustomerPredicate.class)
                .step("enterprise-processing", EnterpriseProcessingStep.class)
                .step("account-manager-notification", AccountManagerNotificationStep.class)
            .end()
            
            .defaultBranch()
                .step("basic-processing", BasicProcessingStep.class)
                .step("standard-notification", StandardNotificationStep.class)
            .end()
        .end()
        
        .step("finalize", FinalizeSubscriptionActivationStep.class)
        
        // Error handling
        .onException(RecoverableException.class)
            .matching(e -> e.getCode() == RECOVERABLE_ERROR)
            .compensateWith(RecoverableCompensation.class)
        .onAllExceptions()
            .compensateWith(GeneralCompensation.class)
        
        // Async part — anchored to a sync step. Use startBefore for join-point kickoff,
        // startAfter when the async work depends on the named step completing first.
        .async()
            .startBefore("finalize")             // OR: .startAfter("last-business-step")
            .step("async-notifications", AsyncNotificationStep.class)
            .step("external-integrations", ExternalIntegrationStep.class)
        .end()
        
    .end()
);
```

> **Async semantics.** Exactly one anchor must be specified; the Java DSL mirrors the YAML form (§3.4.2). Use `startBefore(stepId)` to kick off async work at a join point — common when conditional branches converge on a downstream step. Use `startAfter(stepId)` when the async work must observe the results of the named step (e.g., post-action notifications about completed business logic).

#### 4.4.3 Multi-Branch Conditional Operations

Branches are evaluated in declaration order; the first branch whose condition matches is executed. If no branch matches and a `defaultBranch()` is defined, it runs; otherwise the conditional step's behavior is controlled by `.onNoMatch(NoMatchBehavior)` — `WARN` (default; log + complete without dispatching inner steps), `SILENT` (complete without logging, suiting the guard pattern), or `ERROR` (fail the transition). See §3.4.3 for full semantics — the Java API mirrors them exactly.

### 4.5 Context Usage in Transitions

#### 4.5.1 Context Data

The host is responsible for populating context before execution and reading results after completion. There is no `.input(...)` builder method — all data flows through the context.

```java
// Define transition with context
trialActiveTransition
    .setOperation(ActivateSubscriptionOperation.class)
    .usingContext(SubscriptionContext.class)
    .end();

// Application usage
public void activateSubscription(Subscription entity) {
    // Populate context before execution
    SubscriptionContext context = new SubscriptionContext();
    context.setSubscriptionId(entity.getId());
    context.setPaymentMethodId(entity.getPaymentMethodId());
    context.setActivatedBy("SYSTEM");

    // Execute transition with context
    TransitionResult<Subscription> result = stateMachine
        .entity(entity)
        .withContext(context)
        .transitionTo("active");

    // Read results from context after execution
    if (result.isSuccess()) {
        entity.setActivatedTimestamp(context.getActivatedAt());
        entity.setSubscriptionStatus(context.getSubscriptionResult().getStatus());
    }
}
```

#### 4.5.2 Nested Operations and Call-Site Context Mapping

A `CompositeOperation` member may be either a `Step` or another `Operation`. Both kinds are nestable, recursively, and obey the same call-site grammar. The same grammar is exposed imperatively through `TransitionView` so a simple `Operation`'s body can dispatch reusable steps or nested operations with the same five forms.

Context mapping is a **call-site** property, not a callee property. A reusable child step or operation declares only the context type it needs (e.g., `Step<T, PaymentCtx>`); each caller is responsible for projecting its own context onto that type. One child + N callers + N small mappers — not one child preconfigured with N caller-specific mappers. This keeps reusable components callee-agnostic and lets a single mapper bridge a parent / child pair across many call sites.

##### 4.5.2.1 Definition Surface

`CompositeOperationDef` exposes a uniform `(memberId, [mapperSpec])` grammar for both `.step(...)` and `.operation(...)` by-id members:

| Form | Composite member call site | `TransitionView` call site |
|---|---|---|
| Pass-through | `.step("id")` / `.operation("id")` | `view.step("id")` / `view.operation("id")` |
| Mapper by registered id | `.step("id", "mapperId")` | `view.step("id", "mapperId")` |
| Inline `Function<C, ?>` | `.step("id", parent -> child)` | `view.step("id", parent -> child)` |
| Inline `ContextMapper<C, ?>` | `.step("id", mapperInstance)` | `view.step("id", mapperInstance)` |

Inline-registration forms (`.step("id", Step<T, C>)`, `.operation("id", Operation<T, C>)`, and their class variants) define a new member typed against the composite's own `C` and always run pass-through — by definition the member shares the composite's context type, so no boundary mapping is needed.

The `ContextMapper<P, N>` interface:

```java
public interface ContextMapper<P, N> {
    N mapTo(P parent);                                // parent → child (input)
    default void mapFrom(P parent, N child) {}        // child → parent (output); default no-op
}
```

`mapFrom` defaults to a no-op so "read-only" mappers are a one-method override or, equivalently, a plain `Function<P, N>` at the call site.

##### 4.5.2.2 Mapper Registry

`MapperDef` is a first-class reusable component, registered on `StateMachineDef` alongside `step`, `condition`, and `operation`:

```java
sm.mapper("payment-from-order", OrderCtx.class, PaymentCtx.class, new OrderToPaymentMapper());
sm.mapper("payment-from-order", OrderCtx.class, PaymentCtx.class, OrderToPaymentMapper.class);
sm.mapper("payment-from-order", OrderCtx.class, PaymentCtx.class,
    order -> new PaymentCtx(order.total(), order.currency()));   // Function form, mapFrom = no-op
```

The mandatory `Class<P>` / `Class<N>` tokens let the build pipeline verify that the mapper's parent type is assignable from the caller's context and the mapper's child type matches the called member's required context. Inline `Function` and inline `ContextMapper` at the call site cannot be reliably introspected at build time (generic erasure); their alignment is checked at first dispatch.

##### 4.5.2.3 Worked Example — One Child, N Callers

A reusable `ChargeCard` operation knows only `PaymentCtx`. Three different parent flows each have their own context shape and call into the same child via three different mappers, registered once on the state machine:

```java
sm.operation("charge-card", PaymentCtx.class, new ChargeCardOperation());

sm.mapper("payment-from-order",   OrderCtx.class,  PaymentCtx.class,
    o -> new PaymentCtx(o.total(), o.currency()));
sm.mapper("payment-from-refund",  RefundCtx.class, PaymentCtx.class,
    r -> new PaymentCtx(r.amount().negate(), r.currency()));
sm.mapper("payment-from-subscr",  SubscrCtx.class, PaymentCtx.class,
    s -> new PaymentCtx(s.nextCharge(), s.currency()));

// Three call sites in three different composites — the child has no knowledge of any of them:
orderComposite .operation("charge-card", "payment-from-order");
refundComposite.operation("charge-card", "payment-from-refund");
subscrComposite.operation("charge-card", "payment-from-subscr");
```

The same `(memberId, [mapperSpec])` grammar applies to steps:

```java
sm.step("validate-address", AddressCtx.class, new ValidateAddressStep());
sm.mapper("address-from-order",  OrderCtx.class,  AddressCtx.class, OrderCtx::shippingAddress);

orderComposite.step("validate-address", "address-from-order");
```

##### 4.5.2.4 Build-Time Type Compatibility

For every by-id member declared inside a composite, the build pipeline checks:

- **Pass-through (no mapper):** the called member's required context type must be assignable from the caller's context type (`memberCtx.isAssignableFrom(callerCtx)`; `Object`-typed members always pass through). Otherwise a `TransfluxValidationException` is raised at `build()` time with a message pointing the user to supply a mapper.
- **Mapper by id:** the registered mapper's `parentType` must be assignable from the caller's context and its `childType` must be assignable to the called member's required context. Mismatches are rejected at build time.
- **Inline `Function<C, ?>` or `ContextMapper<C, ?>`:** type-checked at the source where the lambda or class is declared (Java compile-time generics); runtime alignment is enforced at first dispatch when the user-supplied value is invoked.

##### 4.5.2.5 Identity, Uniqueness, and Visibility

Component identifiers are unique **across the entire state machine** — uniqueness is a global property regardless of nesting depth. Two sibling composites cannot independently host an inline component with the same id under two different payloads; one must be renamed. The same instance or the same class registered under the same id in multiple places is treated idempotently and does not trigger a collision.

**Visibility, however, is lexical.** A component inline-declared inside a composite (`composite.step("foo", new FooStep())` and friends) is reachable only from inside that composite's lexical subtree — its own action refs, its conditional branches, and any imperative `view.step("foo")` / `view.operation("foo")` issued while that composite is on the call stack. Sibling composites cannot resolve another composite's inline ids by reference: doing so raises a build-time error ("unknown step id in scope"). SM-level (root) registrations are reachable from every composite via the parent-chain walk.

Two practical consequences:

- Promoting an inline nested registration to a top-level reusable component (or inlining a top-level one) is a payload-preserving refactor and produces no silent override. Global uniqueness blocks an accidental shadow at promotion time; lexical visibility blocks an accidental shadow at inlining time.
- "Inline" vs. "top-level" *is* a visibility scope — not merely a definition-site convenience. An inline component is intentionally private to its composite. To expose a component to multiple callers, register it at SM level (or in the appropriate `forContext(...)` scope, which still lands at root).

External addressability — using a component id as the target of a YAML `ref:` descriptor, a registry lookup, or any other cross-state-machine handle — applies only to root-registered components. Inline composite members have no externally-stable name; their id is meaningful only within their lexical scope.

At runtime, every composite owns a `Registry` whose parent is the enclosing scope's registry (root in 2.6.6; a process-wide registry once Phase 6.2 lands). Resolution walks the chain local-first and is flattened at the end of state-machine construction so by-id lookups are a single map operation thereafter. The framework never relies on the parent-chain walk at runtime hot paths.

##### 4.5.2.6 Result Reporting

`TransitionResult.getExecutedStepIds()` and `getCompensatedStepIds()` (see §2.1.4) report steps belonging to nested operations as **qualified paths** of the form `parent-op-id/child-step-id`, recursively for deeper nesting. Top-level steps appear under their bare id. The qualified form preserves the structural distinction between a step that ran at the top level and a step that ran inside a nested operation, even when the same step id is reused across parents. The same encoding applies whether the nested operation was dispatched by a composite executor or imperatively via `view.operation("id"[, mapperSpec])`.

##### 4.5.2.7 Failure and Compensation

A nested operation's failure surfaces as if it were a member failure of the enclosing parent at that position; the parent's error-handling and compensation rules apply. Mapper failures are attributed to the side of the boundary the parent owns:

- **`mapTo` failure** (parent → child) is a **parent failure**. The child never starts and no child member ids are recorded for this position.
- **`mapFrom` failure** (child → parent) is also a **parent failure** — the boundary belongs to the parent. The child completed successfully and its compensations are *not* invoked; the parent's error-handling kicks in as if the parent itself raised the writeback failure.

The same attribution applies to class-based mappers, instance-based mappers, inline `Function`, and inline `ContextMapper` at the call site.

Compensations registered by a *synchronously-executed* nested operation are pushed onto the **enclosing parent's** LIFO compensation stack as the child runs. When the parent unwinds, child compensations interleave correctly with sibling steps — there is one stack per synchronous execution path, not one per nesting level.

A nested operation hosted inside an `async` block is a different story: the async branch owns its own LIFO compensation stack, independent from the enclosing transition's sync stack and from sibling async branches. The branch's stack accumulates compensations from the async root and from any operations nested below it; on failure (or timeout, or external cancellation), only that branch's stack unwinds. This decouples async rollback from sync rollback entirely — sync work failing while an async branch is still running does not drain the async branch's stack, and an async-branch failure does not trigger sync compensation. Surfacing of async outcomes into `TransitionResult` follows the standard async result-handling rules.

##### 4.5.2.8 Condition Scope

Pre- and post-conditions declared on a nested-operation **call site** (the parent's `.preCondition(...)` / `.postCondition(...)` chained to a member declaration) evaluate against the **parent's** context — they are caller-side gating decisions about whether to invoke the member at all.

Pre- and post-conditions declared **inside** the nested operation's own def (the child composite's own conditions) evaluate against the **child's** context, populated by the mapper (or pass-through, when no mapper is supplied).

##### 4.5.2.9 Void-Context Caller

A transition with `Void` context (or a composite typed `<T, Void>`) cannot pass-through to any member that requires a non-`Void` context. A mapper from `Void` to a populated child context is expressible but rare — the mapper's `mapTo(null)` would have to fabricate the child shape from nothing — and is flagged at build time only if the mapper is registered with `Void.class` as `parentType`. The common case is to lift the caller's context type or to attach the child member to a sibling transition that carries a populated context.

#### 4.5.3 Async Context Handling

An `async` block introduces a concurrency boundary: the branch runs on a separate thread from the enclosing sync path and from sibling async branches. The host owns the context type, so Transflux does not impose a one-size-fits-all concurrency model on it. Two opt-in paths exist for hosts that want isolation; a documented shared-reference fallback covers the rest.

##### 4.5.3.1 ForkableContext (per-branch isolation, same context type)

Hosts that want each async branch to run against an isolated copy of the existing context implement `ForkableContext`:

```java
public interface ForkableContext<C> {
    C fork();   // produce an isolated context for an async branch
}
```

Runtime rule: at the async-branch boundary, if the context implements `ForkableContext`, the branch receives `context.fork()`; otherwise it receives the same reference held by the enclosing sync path (see §4.5.3.3). The host owns the copy strategy — deep, shallow, copy-on-write, or anything else appropriate to the context shape. Transflux does not perform reflective deep-copy; the failure modes (lazy proxies, transient fields, singletons captured by reference, framework-managed handles) make implicit reflection a worse default than explicit host control.

For a JSON-friendly POJO context, Transflux ships an optional `JacksonForkableContext` adapter that implements `fork()` via a Jackson round-trip. Hosts with weirder shapes write their own.

##### 4.5.3.2 ContextMapper on `async` (full isolation, different context type)

When an async branch needs a distinctly-shaped context — e.g., a notification subflow that needs only an order id and a customer email — declare a mapper at the `async` call site using the same call-site grammar as nested operations (§4.5.2.1). The mapper is supplied positionally, exactly as for sync members:

```java
.async()
    .startAfter("finalize")
    .step("send-receipt", "notification-from-order")     // by registered mapper id
.end()

// or with an inline projection at the call site:
.async()
    .startAfter("finalize")
    .step("send-receipt", parent ->
        new AsyncNotificationCtx(parent.getOrderId(), parent.getCustomerId()))
.end()
```

`mapTo` runs once on the enclosing sync thread before the async branch is submitted; the constructed context is what the branch sees. `mapFrom` is **not** supported on async blocks, because async results do not merge back synchronously into the parent context — supplying a full `ContextMapper` with a non-default `mapFrom` on an async call site is rejected at definition time. Surfacing of async outcomes follows the result-handling design in §4.10 rather than the mapper pattern.

##### 4.5.3.3 Shared-reference fallback and definition-time warning

When neither `ForkableContext` nor a context mapper is declared, the async branch receives the same context reference as the enclosing sync path. This is a legitimate design choice for branches that only read from context — common cases include post-action notifications, audit logging, and any work fired off after the last sync step where the context is effectively read-only by then.

To prevent silent sharing, the framework emits a definition-time **warning** (not an error) when an async block is declared on a context type that does not implement `ForkableContext` and that does not declare a mapper. The warning identifies the operation and links to this section. Hosts that intend to share — explicitly — can suppress it via standard logging configuration.

##### 4.5.3.4 Memory-Model Guarantees

Transflux guarantees, at the async-branch submission boundary:

- All writes the enclosing path performed *before* submission are visible to the async branch (happens-before via the executor submission).
- Writes performed by the enclosing path *after* submission are **not** synchronized with the branch and may or may not be observed.
- Symmetrically, writes the async branch performs are not synchronized back into the enclosing path.

These guarantees apply to both shared-reference and `ForkableContext` modes. In `ForkableContext` mode the second and third points are moot for the branch's own writes, since each side mutates a distinct object — but the host's `fork()` implementation is responsible for the snapshot itself being self-consistent (e.g., not capturing references to mutable nested objects it expects to remain stable).

##### 4.5.3.5 Sibling Async Branches

Multiple async branches under the same `async` block follow the same rules pairwise: each branch independently obtains its context per §4.5.3.1 / §4.5.3.2 / §4.5.3.3. `ForkableContext.fork()` is invoked once per branch, not once per `async` block.

### 4.6 Steps

#### 4.6.1 Step Definition

Steps are entity-aware and receive `(entity, context, transition)`:

```java
public class PrepareEventActorStep
        implements Step<Subscription, ActivationContext> {
    
    @Inject private EventActorService eventActorService;
    
    @Override
    public void execute(Subscription subscription, ActivationContext context,
                        Transition<Subscription, ActivationContext> transition) {
        EventActor eventActor = eventActorService.createEventActor(
            subscription.getId(), "SYSTEM");
        context.setEventActor(eventActor);
    }
    
    @Override
    public Compensation<Subscription, ActivationContext> getCompensation() {
        return (entity, ctx) -> eventActorService.removeEventActor(
            ctx.getEventActor().getId());
    }
}

public class ValidatePrerequisitesStep
        implements Step<Subscription, ActivationContext> {
    
    @Override
    public void execute(Subscription subscription, ActivationContext context,
                        Transition<Subscription, ActivationContext> transition) {
        boolean isValid = performValidation(subscription, context);
        context.setValidationResult(isValid);
        context.setValidatedAt(Instant.now());
    }
}
```

#### 4.6.2 Step Configuration

```java
compositeOperation("complex-operation")
    .step("validate-prerequisites", ValidatePrerequisitesStep.class)
        .withCompensation(ValidationCompensation.class)
        
    // Step with custom error handling
    .step("risky-step", RiskyStep.class)
        .onException(SpecificException.class)
            .compensateWith(SpecificCompensation.class)
        .onException(Exception.class)
            .compensateWith(GeneralCompensation.class)
    .end();
```

### 4.7 Conditions and Validators

#### 4.7.1 Condition Definition

```java
@Component
public class CheckoutFulfilledCondition implements Condition<Subscription> {
    
    @Inject private CheckoutService checkoutService;
    
    @Override
    public boolean evaluate(Subscription subscription) {
        return checkoutService.isCheckoutFulfilled(subscription.getCheckoutUid());
    }
}

@Component
public class MilestonesActivatedCondition implements Condition<Subscription> {
    
    @Inject private MilestonesService milestonesService;
    
    @Override
    public boolean evaluate(Subscription subscription) {
        return milestonesService.getMilestones(subscription.getId())
            .stream()
            .allMatch(m -> m.getState() == MilestoneState.ACTIVE);
    }
}

// Lambda-based (form 3 / form 4 equivalent on the Java side)
trialActiveTransition
    .addPreCondition("checkout-fulfilled",
        s -> checkoutService.isCheckoutFulfilled(s.getCheckoutUid()))
    .addPostCondition("milestones-activated",
        s -> milestonesService.getMilestones(s.getId())
            .stream().allMatch(m -> m.getState() == MilestoneState.ACTIVE));

// Predicate-based (lighter than full Condition<T>)
trialActiveTransition
    .addPreCondition(PaymentMethodValidPredicate.class);

// Expression-based — SpEL string
trialActiveTransition
    .addPreCondition("entity.paymentMethodId != null");
```

The four authoring forms above (reference, full `Condition<T>`, `Predicate<T>`, SpEL expression) map exactly to the four YAML-expressible forms of the Condition Descriptor (§3.6.1). The Java DSL additionally accepts a fifth `InstanceBased` form — `.addPreCondition("id", existingConditionInstance)` — for attaching a pre-built `Condition<T, C>` instance under an explicit id without routing through the `StateMachineDef.condition(...)` registry.

#### 4.7.2 Advanced Condition Configuration

```java
trialActiveTransition
    .addPreCondition(CheckoutFulfilledCondition.class)
    
    // Condition with custom error message and error code
    .addPreCondition("business-hours", this::isBusinessHours, condition -> condition
        .withErrorMessage("Transitions only allowed during business hours")
        .withErrorCode("BUSINESS_HOURS_VIOLATION"));
```

### 4.8 Listeners and Hooks

#### 4.8.1 Listener Definition

```java
@Component
public class TransitionAuditListener
        implements TransitionListener<Subscription, ?> {
    
    @Inject private AuditService auditService;
    
    @Override
    public void onTransition(Subscription subscription,
                             Transition<Subscription, ?> transition,
                             Object context) {
        if (transition.isStarted()) {
            auditService.logTransitionStart(subscription, transition);
        } else {
            auditService.logTransitionComplete(subscription, transition);
        }
    }
}
```

#### 4.8.2 Listener Registration

```java
// Transition listeners (start and complete)
trialActiveTransition
    .onStart(ActivationStartListener.class)
    .onComplete(ActivationCompleteListener.class);

// State entry/exit listeners (attached to the state — see §4.2.2)

// Global transition listeners
stateMachine
    .onAnyTransitionStart(TransitionAuditListener.class)
    .onAnyTransitionComplete(TransitionAuditListener.class);

// Global state listeners
stateMachine
    .onAnyStateEntry(StateAuditListener.class)
    .onAnyStateExit(StateAuditListener.class);
```

### 4.9 Execution and Usage

#### 4.9.1 Manual Transition Execution

```java
// Basic transition execution
TransitionResult<Subscription> result = stateMachine
    .entity(subscription)
    .transitionTo("active");

// Transition with context — the host prepares the context object
SubscriptionContext context = new SubscriptionContext();
context.setSource("API");
context.setUserId(currentUser.getId());

TransitionResult<Subscription> result = stateMachine
    .entity(subscription)
    .withContext(context)
    .transitionTo("active");

// Selecting a specific named transition (when multiple transitions
// share source/target — e.g., different triggers)
TransitionResult<Subscription> result = stateMachine
    .entity(subscription)
    .transitionTo("active", "trial-to-active");
```

#### 4.9.2 Event and Trigger Processing

```java
// Process an event
stateMachine
    .entity(subscription)
    .processEvent(Event.CHECKOUT_FULFILLED, eventData);

// Process a host-driven data change — re-evaluates data triggers
stateMachine
    .entity(subscription)
    .processDataChange();
```

#### 4.9.3 Batch Operations

```java
List<TransitionResult<Subscription>> results = stateMachine
    .entities(subscriptions)
    .withContextFactory(s -> new ActivationContext(s.getId()))
    .transitionTo("active");
```

### 4.10 Configuration and Integration

#### 4.10.1 Framework Configuration

```java
TransfluxConfiguration config = TransfluxConfiguration.builder()
    .asyncThreadPoolSize(10)
    .asyncQueueCapacity(100)
    .metricsEnabled(true)
    .flowLabel("subscription-management")
    .build();

Transflux transflux = Transflux.create(config);
```

#### 4.10.2 Spring Integration

```java
@Configuration
@EnableTransflux
public class TransfluxConfig {
    
    @Bean
    public TransfluxConfiguration transfluxConfiguration() {
        return TransfluxConfiguration.builder()
            .metricsEnabled(true)
            .build();
    }
    
    @Bean
    public StateMachine<Subscription> subscriptionStateMachine() {
        return Transflux.defineStateMachine()
            .forEntityType(Subscription.class)
            // ... state machine definition
            .build();
    }
}

// Usage in service
@Service
public class SubscriptionService {
    
    @Inject private StateMachine<Subscription> subscriptionStateMachine;
    
    public void activate(Subscription subscription, SubscriptionContext context) {
        TransitionResult<Subscription> result = subscriptionStateMachine
            .entity(subscription)
            .withContext(context)
            .transitionTo("active");
            
        if (!result.isSuccess()) {
            throw new ActivationException(result.getError());
        }
    }
}
```

#### 4.10.3 Metrics and Observability

```java
@Component
public class CustomMetricsCollector implements MetricsCollector {
    
    @Override
    public void recordTransitionStart(String stateMachine, String transition) {
        // Custom metrics logic
    }
    
    @Override
    public void recordTransitionComplete(String stateMachine, String transition,
                                         Duration duration) {
        // Custom metrics logic
    }
}

// Configuration
TransfluxConfiguration config = TransfluxConfiguration.builder()
    .metricsCollector(CustomMetricsCollector.class)
    .flowLabel("subscription-management")
    .build();
```

---

## 5. Non-Functional Requirements

### 5.1 Performance
- Minimal overhead for simple transitions.
- Optimized trigger evaluation and matching.

### 5.2 Observability
- Pluggable `MetricsCollector` SPI exposing success/failure counts, timing histograms, step-level timings.
- Configurable logging with predictable logger names.
- Custom flow labels for metric separation.
- First-party Micrometer / OpenTelemetry integrations are post-1.0 (see §7.2).

### 5.3 Maintainability
- Clear separation of concerns.
- Reusable component design.
- Comprehensive documentation and examples.
- Consistent API patterns across the two DSLs.

## 6. Integration Requirements

### 6.1 Dependency Injection

For 1.0, Transflux supports:
- **Spring** integration (optional dependency) — automatic Spring-bean discovery for Transflux components and `@EnableTransflux` auto-configuration.
- **Manual wiring** via the `ComponentRegistry` SPI (see §4.1) — for environments without a DI framework, or for embedding Transflux in non-Spring applications.

Additional DI frameworks (Guice, CDI / Weld, Dagger 2) are deferred to a Post-1.0 theme (see §7.2). The framework-agnostic abstraction proposed in earlier drafts is part of that same Post-1.0 theme; in 1.0, Spring and manual wiring share a minimal `ComponentFactory` SPI without a multi-framework abstraction layer.

### 6.2 Class Instance Factory System

A minimal component factory SPI that:
- Resolves named components from the registry.
- Falls back to reflection-based instantiation when no DI framework is available.
- Allows registration of custom factory functions for specialized component creation.
- Detects circular dependencies in component graphs.

YAML DSL integration with the factory:
- Component instantiation from YAML `class:` references.
- Constructor parameter injection from YAML configuration where supported by the underlying DI framework.
- Named component registration from YAML component libraries.

The richer multi-framework abstraction (Guice / CDI / Dagger integration, framework-agnostic adapter pattern) is part of the Post-1.0 DI Expansion theme.

---

## 7. Roadmap

### 7.1 v1.0 Scope

The 1.0 release is the **smallest useful core** of Transflux: a programmatic and YAML DSL for defining state machines with conditions, operations, steps, triggers (manual, event, host-driven data), listeners, and compensations — running in a single JVM, against host-owned in-memory entities.

In-scope capabilities:

- **Core abstractions** — `StateMachine`, `State`, `Transition`, `Operation` (Simple and Composite), `Step`, `Context`, `Condition` (Pre/Post), `Trigger` (Manual, Event, host-driven Data), `Listener` (state entry/exit, transition start/complete), `Compensation`.
- **State resolver + applier** — class, lambda (Java only), and SpEL forms.
- **Both DSLs at parity** — programmatic builder and YAML DSL cover the same surface area, including listener types and condition descriptor forms.
- **Component library + registry** — reusable component definitions with imports (YAML) and a Java-side `ComponentRegistry`.
- **Condition descriptor grammar** — class, predicate, expression, reference.
- **Multi-branch conditional operations** — sequential branch evaluation with default fallback.
- **Compensation engine** — LIFO stack, unified `Compensation<T, C>` interface, exception-specific compensation strategies.
- **Optional Spring integration** — auto-configuration, `@EnableTransflux`, Spring-bean component discovery.
- **Manual wiring fallback** — `ComponentRegistry` SPI.
- **Basic metrics hooks** — pluggable `MetricsCollector` interface (no shipped Micrometer integration in 1.0).
- **Testing** — Spock specifications against the library itself. A dedicated `TestStateMachine` harness with AssertJ-style assertions is post-1.0 and ships as a separate artifact.

### 7.2 Post-1.0 Themes

The themes below are deferred to one or more post-1.0 releases. They are grouped by intent rather than by sequence — actual ordering and version assignment lives in `todo.md`.

- **Persistence** — pluggable state-machine definition storage, transition history auditing, entity state persistence and recovery.
- **Distributed Execution** — clustering, distributed locks, cluster-aware triggers, cross-node coordination, failure handling in distributed environments.
- **Trigger Expansion** — `TimerTrigger` / cron-based triggers (with Quartz Scheduler), `SignalTrigger` for framework-wide signals, automatic data-change detection (background watching, ORM integration).
- **Long-Running / Durable Executions** — checkpoint and resume, async-first operations, progress tracking, distributed transaction support, BPMN compatibility considerations.
- **DI Framework Expansion** — Guice, CDI (Weld), Dagger 2 integrations; framework-agnostic DI abstraction; matrix-tested compatibility.
- **Observability** — first-party Micrometer metrics, OpenTelemetry tracing, structured logging with MDC, health-check framework, dashboard templates.
- **Testing Framework** — `TestStateMachine` harness, transition-path recording, AssertJ-style assertion DSL — shipped as a separate artifact (`transflux-test` or similar).
- **IDE Tooling** — JetBrains and VSCode plugins (syntax highlighting, cross-language navigation, validation, visualization). Tracked separately in `ide-plugin-roadmap.md` and likely a separate repository.
- **Advanced DSL Features** — YAML anchors / template inheritance, parameterized components.
- **Watcher-driven definition reload** — `ReloadableDefinitionSource` extension to `DefinitionSource` (§2.6) exposing a change-notification hook; a built-in driver that polls or subscribes to the source and calls `replaceDefinition` (§2.7) when the source signals a change. The manual swap primitive ships in 1.0; this Post-1.0 work is the convenience layer that makes reload automatic. Includes file-watcher and database-change-feed drivers as ships-with options.
- **Resilience Patterns** — Resilience4j integration, configurable retry strategies, circuit breakers, exponential backoff.
- **Plugin System** — extension points, plugin discovery and loading, plugin lifecycle management; built-in plugins for database persistence, message-queue integration, REST API for external triggers, and monitoring/alerting.
