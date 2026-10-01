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

**Components are shared, so host-implemented contracts must tolerate concurrent invocation.** An `Action`, `Condition`, `ContextMapper`, `Compensation` or listener reaches the runtime as an instance — supplied directly in the Java DSL, or produced by the factory from a YAML `class:` reference — and one object normally serves every call site that reaches it; a host-supplied instance may be shared more widely than the framework can see. The framework makes no promise that a given invocation gets an object to itself.

Invocations can overlap in time for two independent reasons: a host driving two transitions concurrently (which this section already permits), and a forked member (§4.5.3), which runs on another thread while the transition that spawned it carries on. Implementations must therefore be stateless, or thread-safe about whatever state they keep.

**The entity is not synchronised across a fork either.** A branch receives the same entity reference the transition holds, and the framework adds no locking around it — the rule above applies. Context is the one thing a host can have isolated automatically, and only by asking (§4.5.3.1).

#### 2.1.3 Reentrancy

Reentrancy is **fail-fast**. Invoking a transition from within a listener, operation, step, or condition on the same `StateMachine<T>` for the same entity throws `TransfluxReentrancyException`. The guard keys on the **handle** (see §2.7), not on the snapshot the in-flight execution is running against: a replacement that installs a new snapshot mid-execution does not lift the guard, because the reentrant call reaches the same handle whichever version answers it. Triggering a transition on a *different* entity from within an executing transition is permitted (provided the host is prepared to handle the implications).

**A forked member may not drive the state machine that forked it, for any entity.** This is a separate and stricter rule, and it is categorical: an action running on a branch that calls `transitionTo`, `fire`, `processEvent`, `processDataChange` or `executeTransition` on the spawning machine is rejected with the same `TransfluxReentrancyException`, whether or not the entity is the one under transition, and whether or not that transition has finished. A forked member is fire-and-forget (§4.5.3.6), so a transition driven from one would report its outcome to nobody — the result would be discarded and a failure would surface only as a log line. Work that has to drive the machine belongs on the synchronous path, or on an executor the host owns and watches. Other state machines are unaffected: only the one that spawned the branch is closed to it.

The guard the two rules use is deliberately not the same mechanism. The reentrancy check above is about one thread's call stack, which is why it permits a different entity; the fork ban is about work that has left the transition's timeline entirely, which is why it does not.

#### 2.1.4 TransitionResult

Direct transition execution (via `transitionTo(...)` and `fire(...)`) returns a `TransitionResult<T>` describing the execution outcome. The host-driven trigger entry points `processEvent(...)` and `processDataChange(...)` instead return a `ProcessResult<T>`, because they may match no trigger at all and fire nothing: `ProcessResult` exposes `fired()` (whether any trigger matched), `firedTriggerId()` (the matched trigger, or `null`), and `result()` (an `Optional<TransitionResult<T>>` carrying the fired transition's outcome). `fired() == true` does **not** imply success — a matched trigger can fire a transition that then fails. The `TransitionResult<T>` carried inside a fired `ProcessResult` (and returned directly by the other entry points) describes the execution outcome:

- `boolean isSuccess()` — terminal outcome.
- `String getSourceStateId()`, `getTargetStateId()`, `getTransitionId()` — the transition that was attempted. The target is the transition's declared target whatever the outcome; on a failure the entity is still in the source state, since the applier never ran.
- `T getEntity()` — the entity the transition ran against.
- `Throwable getError()` — present iff `isSuccess()` is `false`.
- `List<ActionPath> getExecutedPath()` — ordered list of executed actions, in invocation order. Every action is recorded, whichever form it was authored in, and it is recorded **before** it runs: an action that throws still appears on the list, since it did execute, and its entry precedes any sub-entries it produced. Nested entries are reported as **qualified paths** (`parent-id/child-id`, recursively for deeper nesting); top-level entries appear under their bare id. See §4.5.2 for nesting semantics.
- `List<ActionPath> getCompensatedPath()` — ordered list of compensations that ran (empty on success), in the LIFO order they unwound. Any action may declare a compensation, so this list carries the same kind of entries as `getExecutedPath()` and uses the same qualified-path encoding.
- Timing metadata: `getStartedAt()`, `getCompletedAt()`, `getDuration()`. Per-action durations are not on the result; an action listener receives each one on its payload (§2.2.10).

Business outcomes (failed conditions, failed actions, post-condition violations) are reported through `TransitionResult` rather than thrown. The one exception is a `java.lang.Error`, which is rolled back and rethrown rather than reported (§2.2.11). **Configuration and validation errors** — invalid definitions, missing transitions, unknown states, illegal builder usage — throw `TransfluxValidationException` synchronously.

**What the framework itself refuses mid-transition has its own type**, `TransfluxExecutionException`, so that a result's error — and a compensation route (§2.2.11) — can tell "the framework rejected this" from "the definition is broken" and from "host code threw". It has three subtypes, each carrying the ids a host would otherwise parse out of a message: `TransfluxConditionException` (`getConditionId()`, `getRole()` — `PRE_CONDITION` or `POST_CONDITION` — and `getTransitionId()`), `TransfluxNoMatchException` (`getChoiceId()`, for a choice declared `onNoMatch(ERROR)`), and `TransfluxContextException` (`getSubjectId()` — the action, or the listener for an async notification whose context would not fork — for a context crossing only runtime can check: an incompatible pass-through from an action's body, a call-site mapper producing `null` or the wrong type, a `ForkableContext` forking to `null`). None of them extends `TransfluxValidationException`. A refused fork under `FAIL` stays the JDK's `RejectedExecutionException`.

#### 2.1.5 Action Result Mapping

`Action<T, C>.execute(entity, context, transition)` returns `void`. Any data an action produces flows back to the caller through the user-provided context (which the host populated before invocation and reads after the transition completes). `TransitionResult<T>` carries only execution metadata, not domain output. `Action<T, C>` is a pure functional contract — identity (id, name, description) lives on its corresponding def (see §2.2.5).

#### 2.1.6 Action Entity-Awareness

Actions are entity-aware. Every action receives `(entity, context, transition)`. An action may mutate the entity, derive data from it, read from the context, and write results back to the context. Actions are reusable: the same class or instance can be registered under multiple ids and referenced from multiple call sites, and they are not entity-agnostic context manipulators. `Action<T, C>` carries no identity of its own — ids are declared at the registration or declaration site, not on the class.

This shapes the compensation contract too (see §2.2.11): a unified `Compensation<T, C>` interface receives `(entity, context)`, and any action may declare one.

### 2.2 Core Components

#### 2.2.1 Component Identification

All components in Transflux (states, transitions, actions, conditions, triggers, and others) carry a stable identifier so they can be referenced, looked up, and reported in diagnostics. The identifier lives on the **definition** — the configuration object that declares how the component behaves (e.g., `StateDef`, `TransitionDef`, `StepDef`, `OperationDef`, the `step(id, ...)` and `condition(id, ...)` registrations on `StateMachineDef`, plus `ConditionDescriptor`). The runtime executables themselves (`Action<T, C>`, `Condition<T, C>`) are pure functional contracts and do **not** carry identity — the same class can be registered any number of times under different ids. At runtime the framework carries internal **bound records** (`BoundAction`, `BoundCondition`) that pair the pure executable with the framework-owned id; diagnostics and `executedPath` / `compensatedPath` pull from the bound side.

**Component ID:**
- **Required property** on every component definition.
- Must be unique within its namespace, and every namespace is state-machine-wide. States, transitions, triggers and listeners each have their own; actions (in every authoring form), conditions and mappers share one. An action declared inline is still unique across the state machine — what is scoped is its *visibility* (§4.5.2.5), not its id.
- Used for internal referencing, component lookup, and programmatic access.
- Opaque strings; the library does not mandate a casing convention. Examples in this document use **kebab-case** for readability.

**Component Name and Description:**
- **Optional properties** on every component definition except a condition's, which carries neither (below).
- The name is a human-readable display name; the description says what the component is for.
- Used for documentation, user interfaces, and logging.
- Can contain spaces, special characters, and be more descriptive than IDs.

**Conditions carry an id and nothing else.** A condition has no name and no description in either DSL: nothing at runtime represents a condition beyond its id — there is no condition catalog and no payload carrying one — so there is nowhere for either to be read from. A rejection is identified by the ids on `TransfluxConditionException`.

**Example:**
<!-- corpus: identification -->
```yaml
states:
  - id: trial
    name: "Trial Subscription State"
  - id: active
    name: "Active Subscription State"
```

There is one narrow exception: **inline expression-based condition descriptors** are not required to specify an `id`. If the `id` is missing on a `ConditionDescriptor.ExpressionBased`, the descriptor is automatically assigned a unique identifier derived from the expression contents plus the path from the root of the state machine definition to the descriptor. All other descriptor forms — `Reference`, `InstanceBased`, `PredicateBased`, explicit-id `ExpressionBased`, and YAML's `class:` — must declare an `id` explicitly.

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

**Metadata.** A state machine carries an optional id, name, description and version, set on the definition and reported by the built `StateMachine` (`null` where none was given). The version is an opaque string the framework never parses or compares; it is there for a host that loads definitions from an external source (§2.6) and needs to know which one a machine is running. All four belong to the definition rather than to the handle: after a replacement (§2.7) the machine reports the new definition's, and replacing does not compare ids — the entity type is the handle's only identity contract. The framework logs id and version when a definition is built and when one is swapped in.

#### 2.2.3 State

Represents individual states in the state machine with associated metadata and behavior.

States are characterized by their transition patterns rather than explicit types:
- **Initial states** have no incoming transitions and serve as entry points where entities begin their lifecycle.
- **Terminal states** have no outgoing transitions and represent final states in the entity lifecycle.
- All other states can have both incoming and outgoing transitions.

The host is responsible for placing entities into an initial state through its own model. The library trusts whatever value the configured `StateResolver<T>` returns; it does not provide an API to force an entity into an arbitrary state (this is a 1.0 non-goal — see §1.3).

**Properties:**
- State identifier and metadata.
- Optional entry/exit listeners (see §2.2.10).

A state does not own its transitions: a transition names the states it leaves and enters (§2.2.4).

#### 2.2.4 Transition

Defines valid state changes and their associated operations, conditions, and triggers.

**Components:**
- Source and target states, both of which must be declared states; a transition naming an undeclared one fails the build. A transition is declared on the state machine, beside the states, rather than inside its source state, so a definition reads as a list of states and a list of edges in both DSLs.
- A body: an ordered list of actions (optional, §2.2.5.1).
- Pre-conditions (must be met **before** execution).
- Post-conditions (must be met **after** execution; violation triggers rollback / compensation).
- Triggers (manual, event-based, data-based).
- Transition-specific listeners (`onStart`, `onComplete`, `onError`).

A transition declares no compensation of its own; rollback belongs to the actions in its body (§2.2.11).

**Two runtime types, split by capability.** `Transition` is the read-only view: the transition's id, source state, and target state, stable for the lifetime of the state machine and carrying nothing that can run work or change anything. It carries no context type — it describes topology, the same kind of view `State` and `Trigger` are. `ExecutingTransition<T, C>` extends it with the `run(...)` / `fork(...)` dispatch surface (§2.2.6) and exists only while a transition is in flight, because dispatch needs what only a live execution has: the entity and context under transition, the executed-path recorder, the compensation stack, and the scope an id resolves against.

The split is the enforcement mechanism for a rule that runs through the rest of this document. An action's body receives the executing type. Everything that observes an execution rather than driving it — pre- and post-conditions, a branch condition, a data trigger's gate, and all three listener categories — receives the read-only type, so code positioned where dispatched work could not be rolled back has no way to express the dispatch. Where that rule is restated below (§2.2.9, §2.2.10, §3.4.3), it is this typing that carries it.

#### 2.2.5 Action

The unit of work executed during state transitions. `Action<T, C>` is a **pure functional contract** with a single `execute(entity, context, transition)` method plus an optional `getCompensation(entity, context)`; it is identity-free at runtime, and the definition side carries the id, name, and description. The third parameter is the `ExecutingTransition<T, C>` of §2.2.4 — an action's body is the only place the framework hands out the dispatching type.

**Two authoring forms, mutually exclusive:**
- **`StepDef`** — *imperative*: a Java body, supplied as an `Action<T, C>` instance. It binds no children, though it may dispatch other actions by id while it runs.
- **`OperationDef`** — *declarative*: an ordered list of members, where declaration order **is** execution order. There is no Java body; at build time the framework synthesizes the `Action<T, C>` that walks the members. `ChoiceDef` is a variant whose ordering rule is "first matching branch" rather than "all, in order".

**Vocabulary:** a *step* is an imperative action, an *operation* is a declarative one. The distinction is what the author wrote, not what the runtime does — both forms execute through one path (§2.4) and are dispatched identically. The authored form travels with the action as an `ActionKind` and surfaces in diagnostics so a message can name the thing the way its author wrote it.

**A transition's body is an ordered list of actions**, in either form and in any mix — the same member grammar a declarative container and a choice's branch carry (§2.2.5.1). There is no elevation mechanism and no need for a single-member wrapper, and no wrapper either when a transition does several things: the members sit directly on it, in the order written. What the transition carries beyond that list — states, conditions, triggers, the commit — is around the list rather than in it, so the transition is not itself an action: it holds no id in the action namespace, no compensation of its own and no action listeners. Rolling the body back as a unit is a matter of declaring it as one.

**Features:**
- Type safety (entity, context) with generics.
- Synchronous execution, and forked members that the enclosing container does not wait for.
- Error handling and compensation strategies.
- Granular control through nesting: an action may contain or dispatch others, recursively.

`Action.execute` returns `void`; results flow through the context (see §2.1.5).

##### 2.2.5.1 The Member Grammar

Four things hold an ordered list of actions: a declarative container, a choice's branch, its default branch, and a transition's body. Every one admits the same ways of filling a position — reference an action by id, optionally through a call-site mapper (§4.5.2), or declare one in place as a step, an operation or a choice. In Java that invariant is one self-typed interface, `ActionSequence<T, C, SELF>`, which the four def types extend, so each member form is declared exactly once and a chain keeps the concrete type it started on; it is also the type a generic caller walking a member list is written against. Every verb has a forked twin, because whether the enclosing sequence waits for a member is a property of the position rather than of the action named there (§4.4.2).

What differs between the four is what the enclosing thing *is*, not what a member may be. A container is also an action, so it carries an id, a context type, compensation and listeners. A transition carries its states, conditions, triggers and the state commit, and is not an action at all. **A branch is not an operation**: it has a condition, belongs to its choice, is not independently referenceable, and is not an action — so it shares the member grammar without sharing the contract, which is why a branch has no compensation of its own and no id in the action namespace.

#### 2.2.6 Action Invocation

An action is invoked in one of two ways: as a declared member of an ordered list — a transition's body, a declarative container, or a choice's branch — or dynamically through `transition.run("id")` — or `transition.fork("id")`, which hands it to the executor instead of waiting — from inside another action's body. Both flow through the same internal path, so id recording, timing, nesting, and compensation registration are uniform regardless of who initiated the invocation.

A dispatch site names a callee and nothing more. Which form the callee was authored in is a property of *its* registration rather than of the call, which is why there is a single `run(...)` verb at every reference position instead of one per form. The `step(...)` and `operation(...)` verbs appear only where an action is being *declared*, because there the form is being chosen at that site.

#### 2.2.7 Context

Manages shared state during transition execution.

**Responsibilities:**
- Shared data storage during operation execution.
- Type-safe data access and manipulation.
- The host populates the context before invocation; the host reads results after the transition completes.

Context access in concurrent execution paths is governed by the rules in §4.5.3 — by default a forked member shares the enclosing context reference; isolation is opt-in, either by implementing `ForkableContext` or by mapping at the fork's call site.

**Null context for Object-typed components from Void callers.** A transition declared with `Void.class` context (see §4.5.2.8) rejects any non-null firing value at the dispatch boundary. A composite member or imperative `view.run` call inside that transition may still pass through to an `Object.class`-typed reusable component — the build-time pass-through check admits this unconditionally, since `Object.class` components are by definition context-agnostic. At runtime, the component's context parameter receives `null`. Component bodies registered under `Object.class` are expected to tolerate a `null` context; the canonical reason to register under `Object.class` is "this component ignores the context," which the null-tolerance follows from.

#### 2.2.8 Trigger System

Manages the various mechanisms for initiating state transitions.

**Trigger Types:**

- **ManualTrigger** — names an explicit invocation point. A manual trigger is more than syntactic noise: it provides a named handle that carries per-trigger metadata (descriptions, trigger-specific pre-conditions) which may differ from the transition's defaults. Useful for cases like "cancellation cron" — the cron itself runs in the host's scheduler, but the in-library `cancellation-cron` handle anchors the metadata and lets the catalog API discover it. Reacting to a specific trigger does not require binding listeners to it: a transition listener reads the firing trigger off its payload (see §2.2.10).

- **EventTrigger** — transitions initiated by host-published events. The host pushes events into the state machine via `processEvent(...)`; the framework matches them against registered triggers.

- **DataTrigger** — transitions initiated by the host calling `entity(e).processDataChange()`. The framework re-evaluates the data triggers on transitions leaving the entity's current state, in declaration order, and fires the first whose gate holds. **Transflux does not watch entity fields, hook into ORM change tracking, or run background evaluations** — data triggers are host-driven re-evaluation only in 1.0. Background watching is a Post-1.0 theme (see §7.2).

**A trigger is declared on a transition or registered once and attached to several.** A registration on the state-machine definition claims the trigger's id and carries everything the trigger is — its kind, its metadata, a manual trigger's pre-conditions, an event trigger's event and filter, a data trigger's gate, and the context type it was declared against; a transition then attaches it by id, and the build checks that context against each transition it is attached to. It stays **one trigger**: the catalog lists it once, reporting every transition it is attached to, and a transition listener's payload names it as the origin whichever of them ran — the payload carries the transition beside it. Firing a shared manual trigger selects the attachment leaving the entity's current state, so a host fires `manual-cancel` without knowing whether the entity is `active` or `suspended`; two attachments of one manual trigger leaving the same state would make that choice ambiguous and fail the build. Registering a trigger nothing attaches is not an error — a component library may register what a given definition does not use, and the catalog reports it with no transitions. Within one transition, dispatch scans the triggers it declared in place before the ones it attaches, and a transition may not attach the same trigger twice. The same holds for an event or data trigger, and there it is a deliberate uniformity rather than a deduction: its filter or gate is one object, evaluated identically at both attachments, so neither can choose between them, and the one thing that still could — a firing context one transition accepts and the other refuses — is not made to carry that weight. Context eligibility decides between *different* trigger ids, not between two attachments of one. What stays legal is two *different* triggers leaving one state, which is what first-match in declaration order decides. A trigger declared in place on a transition claims its id state-machine-wide like any other and is visible to that transition alone: nothing else can attach it, and sharing one means registering it.

#### 2.2.9 Condition System

Provides validation and gating mechanisms for transitions. `Condition<T, C>` is a **pure functional contract** with a single `test(entity, context, transition)` method, whose third parameter is the read-only `Transition` of §2.2.4: deciding whether work runs is not doing work, and the parameter is there so that one condition registered under a single id can tell apart the transitions it is attached to. Condition ids live on the def-side authoring vocabulary — the `condition(id, ...)` registrations on `StateMachineDef` and `ContextScope`, and the `ConditionDescriptor` carried by every attachment site: a transition's pre- and post-conditions, a manual trigger's pre-conditions, a branch, and a data trigger's gate.

**Types:**
- **PreCondition** — validates transition eligibility before execution.
- **PostCondition** — validates successful transition completion. If a post-condition is not met, the transition is rolled back and registered compensation actions are executed.

A condition's authoring shape — reference, instance, predicate, or expression — is defined uniformly by the **Condition Descriptor** grammar (see §3.6.1 and §4.7). YAML adds a fifth, `class:`, which the factory resolves into the instance form; the Java DSL takes the instance directly and so has no class shape of its own.

#### 2.2.10 Listener System

Enables observation and reaction to state machine events.

**Listener Types:**
- **State entry/exit listeners** — fire when an entity enters or exits a particular state.
- **Transition start/complete/error listeners** — fire when a transition starts, when it completes successfully, and when it fails.
- **Action start/complete/error listeners** — fire when an individual action starts, returns, and throws, at every nesting depth.

Both DSLs support all three listener categories symmetrically.

The three categories differ in what they can tell the host. A state listener answers "this entity reached this state"; a transition listener answers "this unit of work ran, and here is its outcome"; an action listener answers what happened *inside* that unit of work — which actions ran, in what order, under which context, and which one threw. The last is the capture a metrics collector cannot give: counters and timings answer "how often" and "how long", not "what did this particular invocation see". Recording individual payloads when they match a condition — "where does this null come from?", "how does this user reach this end state?" — is what action listeners exist for.

**Listeners observe; they do not gate.** An exception thrown by a listener is caught and logged; it does not fail the transition, does not trigger compensation, does not suppress the listeners registered after it, and does not appear in the `TransitionResult`. This holds at every hook, and it is deliberate: no hook is positioned where failing the transition would be honest. The start and exit hooks run before any step could have been compensated; the complete and entry hooks run after the `StateApplier` has already committed; the transition's error hook runs after compensation is finished. The action hooks are the one position that sits in the middle of a live execution, which makes the rule stricter rather than weaker there: failing on an observer's behalf would compensate work the transition itself had no complaint about. Rejecting a transition is a pre-condition's job (§2.2.9).

**Registration and ordering.** A listener attaches either to a single owner — a state, through `onEntry` / `onExit`; a transition or an action, through `onStart` / `onComplete` / `onError` — or to every owner of that kind, through the `onAnyStateEntry` / `onAnyStateExit` / `onAnyTransitionStart` / `onAnyTransitionComplete` / `onAnyTransitionError` / `onAnyActionStart` / `onAnyActionComplete` / `onAnyActionError` registrations on the state-machine definition. At each hook the owner's own listeners run first, in declaration order, followed by the global ones, also in declaration order. Every listener carries a required id per §2.2.1; listener ids form a single namespace, shared by all three categories and unique across the state machine.

**The id names the listener, not the attachment.** A listener is either declared in place at a hook or registered once on the state-machine definition — under its category, with the context type it was written against — and attached by id wherever it is wanted. Attaching claims nothing, so one registered listener sits on any number of owners and hooks, and everything declared on it, `withAsync` included, holds at every one of them; it is not additive either, so one hook attaching the same listener twice fails the build rather than notifying it twice; the build checks its context type against each owner it is attached to. A registration spanning every transition or every action — attached at one of the state-machine-wide hooks — takes `Object`, and one declared against a narrower context is refused there rather than at whichever owner it would first break on. The same listener may be attached to an owner and registered as a global at once: disabling it on that owner (below) then suppresses the global delivery only, because an owner's own listeners always receive everything. A listener declared in place claims its id state-machine-wide and is visible only to the definition it is declared on — that owner's other hooks may attach it by id, in either order, which is how one listener serves `onStart`, `onComplete` and `onError` without three ids; the state-machine-wide hooks count as one owner between them. It is not visible to the owner's members or to anything else, the rule an inline action's id already follows (§4.5.2.5), and a reference from outside is a build error that says to register it.

**An action listener attaches to the action, not to the call site.** It is declared on the action's own definition and fires at every invocation of that action — as a member of a transition's body, as a container member, as a choice's branch member, and when another action's body dispatches it by id. Which observers an action has is a property of the action, exactly as its compensation is; a by-id reference therefore carries no listener attachment of its own, and needs none. A transition's body is not itself an action (§2.2.5.1), so nothing notifies for it: the first action notification of a transition is its first member's, and there is no root node above that to observe.

**Complete and error partition the outcomes.** Exactly one of them follows every start notification, and neither occurs without one. This holds for transitions and for actions alike. Two consequences follow. A transition rejected by a pre-condition notifies nothing at all — it never reached the start hook, which §2.4 places after the pre-conditions, and the host already learns of the rejection from the returned `TransitionResult`. And a completion listener never has to check whether the transition actually worked, because a failure reaches the error hook instead. For actions the error hook fires at *every* enclosing level as the failure propagates outwards, each reporting the same throwable: a container whose member threw did fail, and the whole subtree failed with it.

**Transition listeners receive the outcome and the origin.** At the complete and error hooks the payload carries the same `TransitionResult` the caller receives, so a listener sees the executed path, the compensated path, the error, and the timings without the host having to thread them through. The payload also names the trigger that caused the execution, or reports none when the host invoked the transition directly — that is how a listener reacts to one invocation path (`cancellation-cron` but not `manual-cancel`) without listeners being bound to triggers individually.

**An action listener's outcome carries a duration.** At its complete and error hooks the payload reports how long the action's body ran — measured around the body alone, so the action's own start listeners are not counted in it, and computed before notification, so it is right for an async listener too. The start hook carries none.

**An action listener receives the action's own context.** Where the call site maps the context (§4.5.2), the listener sees the mapped child context — the very object the action's body is handed — not the enclosing transition's. Its payload also carries the qualified path of the invocation, which is the same value that invocation contributes to `executedPath`, so a listener can line its record up against the reported tree without reconstructing the nesting; and the form the action was authored in, which is how a listener filtering noise tells a declarative container from an imperative leaf.

**Context typing splits the categories.** A transition declares exactly one context type, and so does an action, so a listener attached to either receives that type directly. A state does not: it can be entered from transitions carrying different context types, so a state listener takes the firing context as `Object` (possibly `null`). Registrations that span every transition, or every action, are the same case and likewise take `Object`. The rule of thumb: needing a typed context means writing a transition or action listener; needing only to know that an entity entered or left a state means writing a state listener, which is then free to treat whatever context it is handed generically — serialising it into an audit trail, for instance.

**The transition handed to a listener is read-only.** A listener's payload carries the responsible transition as the read-only `Transition` of §2.2.4 — its id, source, and target, and no way to dispatch — for the same reason a data trigger's gate receives that type: work dispatched outside a live execution would produce side effects whose compensations could never run. For an action listener the reason is narrower and sharper: it runs *inside* a live execution, so work it dispatched would interleave into the executed path and the compensation stack as though the observed action had dispatched it. Because the restriction is carried by the type rather than by a check, a listener that tries has nothing to compile against.

**A listener may run asynchronously.** Declaring `withAsync()` on a listener's def — or `withAsync(policy)` to choose what a refused submission does — hands each of its notifications to the state machine's executor at the hook rather than invoking it there, so the transition does not wait for it. It is the same executor forked members use (§3.8), and a definition declaring an async listener builds one. Everything above still holds — the listener observes, and whatever it throws is logged and swallowed, now on whichever thread ran it — and four things change.

- *No ordering.* Notifications are submitted in declaration order, interleaved with the synchronous listeners at the same hook, but run whenever the pool runs them: an async `onComplete` may be handled before the same transition's `onStart`. A listener that needs order stays synchronous.
- *Its own context where the context allows it.* A context implementing `ForkableContext` is forked on the notifying thread, once per async listener per notification; any other context is shared, as with a forked member that does not map (§4.5.3). The entity is always shared. A `fork()` that throws or returns `null` loses that one notification with a warning — unlike at a fork site, it cannot fail the transition, because a listener never gates.
- *It may not drive the machine.* An async listener runs under the same ban as a forked branch: it may not start a transition on the state machine that notified it, for any entity — stricter than a synchronous listener, which may drive a different entity — because what it started would report to nobody.
- *A refused submission loses the notification or waits.* `DROP` (the default, independent of the machine's own default) loses it with a warning; `BLOCK` waits for capacity and needs the pool the framework builds; `CALLER_RUNS` runs it on the notifying thread under the same ban. `FAIL` is refused where it is declared: at `onComplete` or on entry the applier has already committed, and there is no transition left to fail. After the state machine is closed a `BLOCK` notification is lost with a warning rather than failing anything — unless it is notified from a thread still running one of the machine's branches, which runs it inline, the same answer `BLOCK` gives on a branch thread before `close()`.

Volume is set by the hooks rather than by the listener count, and the categories differ by orders of magnitude. A transition notifies its start, its source state's exit, its outcome and its target state's entry — at most four submissions for a listener attached to all of them. An action notifies twice per *invocation*, at every nesting depth: a global async action listener submits twice for every action a transition runs, and a body dispatching an action a thousand times in a loop submits two thousand notifications from one transition. That fills the default queue, and under `DROP` loses the overflow. A high-volume action listener belongs on the synchronous path, or declares `CALLER_RUNS` or `BLOCK` when losing notifications is not acceptable.

**An owner may turn its global listeners off.** A state, a transition and an action each suppress the state-machine-wide listeners of its own category: `disableGlobalListener(id)` names one, repeatably, `disableGlobalListeners(id...)` names several at once (and refuses to name none), and `disableAllGlobalListeners()` takes them all and wins whichever order the two were declared in. The motivating case is an action whose context carries sensitive data, running in a state machine whose host registered global listeners the action knows nothing about — the author replaces the generic observation with a listener of its own that records a redacted line. It is a convenience rather than a security control: an application that has to guarantee what a listener never sees writes its own global listener and sanitises there, and nothing the framework ships competes with that. Six rules, sized to that bar.

- *An owner's own listeners always receive everything.* Attaching a listener to an owner is the consent, and it is also how the owner replaces what it just turned off, with whatever configuration it wants and through the same DSL the globals use.
- *Suppression is total, not payload withholding.* A disabled global is not notified at all; notifying it context-free would produce a second record of the same execution. The payload records are therefore unchanged by this feature.
- *The category follows the owner*, with no cross-category reach: all global action listeners on an action, all global transition listeners on a transition, all global state listeners on a state. A transition's context still reaches a global state listener unless that state disables it too.
- *A disable covers exactly the def it is declared on, never its children.* An operation that disables its global action listeners does not disable its members'; a transition that disables its global transition listeners does not touch the actions it runs or the states it moves between. Inheriting would make an action's observers depend on its call site, which the attachment rule above rules out.
- *The named form is a deny-list and fails open.* A global listener registered next year is not covered by a list written today. That is a consequence of the shape, not an oversight, and it is why the bar is "good enough and predictable".
- *An unknown id fails the build.* It must name a state-machine-wide listener of the owner's own category — an id naming another category's, or one attached only to an owner, is rejected — because a typo in a deny-list silently protects nothing. A listener that is *both* attached to an owner and registered state-machine-wide may be named: the disable then suppresses the global delivery and leaves the owner's own, which is the case the rule above describes. Declaring the same id twice is a no-op.


#### 2.2.11 Compensation Engine

Manages error recovery and rollback operations.

**Features:**
- Stack-based compensation execution (LIFO).
- **A single `Compensation<T, C>` interface**, receiving `(entity, context)`. **Any action may declare one**, in either authoring form.
- **Exception-specific routing** — an action may declare a different rollback for each kind of failure it expects, and the framework selects among them once the failure is known.
- The compensation is captured **before** the action runs and pushed onto the rollback stack at that point, so an action that throws partway through producing side effects still has its rollback registered (§2.4 step 5). What is pushed is the action's whole routing table, since which entry of it applies depends on a failure that has not happened yet.

There are three authoring channels. An imperative action can return its compensation dynamically from `getCompensation(entity, context)`, which sees the same entity and context references `execute` will run against. Any action's *definition* can declare one statically through `withCompensation(...)` — the only channel open to a declarative container, which has no Java object to hang the dynamic hook on. And any action's definition can declare **routes** through `forException(...)`, each answering for one kind of failure, optionally narrowed by a guard over the failure itself.

**Exactly one compensation runs for one action**, and it is the first of those three that answers for the failure at hand: a matching route, then the declared fallback, then the dynamic hook. Two consequences follow from that order. A declared fallback suppresses the dynamic hook entirely, because it answers every failure anyway and consulting both would put the same qualified path on `compensatedPath` twice; routes alone do *not* suppress it, because a route that misses has said nothing about this failure and does not get to veto on the action's behalf. And when nothing answers at all, the action is simply not rolled back and does not appear on `compensatedPath` — that list reports what actually ran, not what was eligible to.

**Routing rules.** A route answers when the failure is an instance of its declared exception type (subclasses included) and its guard, where it declared one, accepts. Routes are tried in declaration order and the first match wins — the rule a choice's branches follow (§3.4.3), and the one a Java `catch` chain follows. An unguarded route on a broad type therefore shadows every narrower route declared after it, and the build warns about the shadowed one where it can prove the shadowing; where the earlier route carries a guard it cannot prove anything, since that guard may reject exactly the cases the later route wants, so only the provable case is reported. A warning and not an error: the shape is legal, and an author who wants it that way is not doing anything the framework has to forbid. The guard is consulted only once the type has matched, so it receives the failure already narrowed to that type, and a guard that throws counts as a non-match rather than as a second failure — the drain is already unwinding one, and letting another escape would lose it. Note that the failure a route is matched against is **the one that ended the transition**: the same throwable for every entry on the rollback stack, not necessarily one this action threw, since most of what unwinds never threw anything at all.

**A `java.lang.Error` rolls back too, and is then rethrown.** An `AssertionError` or a `LinkageError` is raised on a perfectly healthy JVM, and leaving the side effects of completed actions in place because of one is a worse outcome than running their compensations. So an `Error` drains the stack and notifies the error hooks exactly as an exception does — routes match it like any other failure — but it is never converted into a `TransitionResult`: once the rollback is done it propagates to the caller. One case is exempt. A `VirtualMachineError` (out of memory, an internal error) propagates at once with no rollback and no notification, because driving rollback handlers through network calls on a JVM that can no longer be trusted is more dangerous than abandoning them; a route declared for one is rejected at definition time, since it could never match. The same rule holds on a forked branch, where the rethrown `Error` ends the worker thread as it always did. Wherever the framework *contains* a failure rather than acting on it — a listener, a compensation that throws mid-drain, a route guard — an `Error` is contained exactly as an exception is, with the same warning: an observer's `AssertionError` fails nothing, and one thrown by a compensation does not cost the entries beneath it their rollback. Only a `VirtualMachineError` escapes those seams.

At rollback each compensation receives **the context its action ran against** — the mapped child context where the call site mapped one (§4.5.2), not the enclosing transition's. That is what makes the compensation's contract, "the same references `execute` saw", hold at a mapped call site.

**A transition declares no compensation of its own.** A compensation belongs to an action: it is pushed under that action's qualified path and reported under it on `compensatedPath`, and a transition's body is a member list, not an action — it has no path to report under, and transition ids live in a different namespace from action ids. "Undo whatever this transition did, last" is one wrapping `operation` carrying the compensation, which brings a real id and a real path with it.

Container compensation is **additive, not a replacement**: a container's own compensation and its members' all run. Because the container is pushed on entry, before it dispatches anything, LIFO unwinding drains its members first and the container last. One consequence is worth stating plainly, since it reads like a bug in a stack trace otherwise: a container's compensation runs even when its first member fails immediately, before the container itself did anything of its own.

#### 2.2.12 State Resolver and State Applier

The host wires two paired components into the state machine.

**`StateResolver<T>`** determines the current state of an entity. Because state can be a *computed* property (e.g., a contract may report "created" until its start date, then "started"), the resolver is a function — not necessarily a simple field accessor. Resolution approaches:

- **Lambda function**, which is the idiomatic form: `e -> e.getStatus().name()`.
- **Dedicated class** implementing `StateResolver<T>`, supplied as an instance.

**`StateApplier<T>`** finalizes a successful transition by writing the new state to the entity. The applier is invoked **once**, after all post-conditions have passed (see §2.1.1). Application approaches mirror the resolver:

- **Lambda function**: `(e, s) -> e.setStatus(MyState.valueOf(s))`.
- **Dedicated class** implementing `StateApplier<T>`, supplied as an instance.

Both are host-supplied bridges and the Java DSL ships no sugar around either — no class-name form and no SpEL property path. The YAML DSL, which has no lambda to write, accepts a class name or a SpEL expression for each (§3.2.1). Where the entity's state is an enum, the two lambdas above are the canonical pairing, and they are honest about where the bidirectional binding lives.

**Key Characteristics:**
- The resolver/applier pair is a property of the state machine itself, not of individual transitions or triggers.
- The resolver runs before transition eligibility checks; the applier runs immediately before `onComplete` listeners.
- Type-safe in Java; SpEL-typed in YAML.
- In the simplest case the resolver reads `entity.status` and the applier writes `entity.status`. In more complex cases, the resolver computes state from multiple fields while the applier still writes a single "current state" field that the resolver then prefers over its computed fallback. The library does not mandate any particular pairing.

### 2.3 Component Relationships

```
StateMachine
├── StateResolver + StateApplier (instance or lambda; class or SpEL in YAML)
├── Global Listeners (state, transition, action)
├── States
│   ├── Entry/Exit Listeners
│   ├── Transitions
│   │   ├── Body: ordered Actions (step, operation, choice; run or forked)
│   │   │   ├── Nested Actions
│   │   │   ├── Context
│   │   │   ├── Compensations
│   │   │   └── Listeners (onStart, onComplete, onError)
│   │   ├── Conditions (Pre/Post)
│   │   ├── Triggers (Manual, Event, Data)
│   │   └── Listeners (onStart, onComplete, onError)
├── Trigger System
└── Compensation Engine
```

### 2.4 Execution Flow

1. **Transition Request** — initiated by a manual API call, an event handed to the machine, or a `processDataChange(...)` invocation.
2. **State Resolution** — determine current entity state via the configured `StateResolver<T>`.
3. **Pre-condition Evaluation** — validate transition eligibility. On failure, return a `TransitionResult` with `isSuccess() == false`; no compensations run because no action has executed, and no listener is notified because the transition never started.
4. **Listener Notification (start)** — notify registered `onStart` listeners and source-state `onExit` listeners; an async listener (§2.2.10) is submitted here rather than run.
5. **Action Execution** — execute the associated business logic. Every action, at every nesting depth, runs through one path in a fixed order:
    1. capture its compensation — the routing table its definition declared, with `getCompensation(entity, context)` folded in as the fallback where the definition left that slot open (§2.2.11) — and push it onto the rollback stack;
    2. record its qualified path on the executed path;
    3. push its id onto the nesting stack;
    4. execute;
    5. pop the nesting stack.
   Capturing before execution means an action that throws partway through producing side effects still has its rollback invoked; an action needing completion-time state writes that state into the entity or context during `execute` and reads it back in the compensation, which sees the same references `execute` ran against. Recording before execution means an action that throws still appears on `executedPath` — it did run, and its compensation is on the other list. Pushing the nesting stack for every action means anything it dispatches is qualified beneath it. The action's own listeners (§2.2.10) are notified from inside its nesting scope, so each notification's path is the action's own; where the call site maps the context, a mapper's `mapFrom` runs only once those notifications are closed, since a `mapFrom` failure is the parent's and must not turn the child's completion into an error as well. A forked member is submitted at its position in the member list and the container moves straight on to the next one, so what step 5 owns is the moment forked work *starts*, never the moment it finishes (§4.5.3.6).
6. **Post-condition Evaluation** — validate successful completion. On failure, run registered compensations in LIFO order; the entity's state field is **not** updated.
7. **State Application** — invoke the `StateApplier<T>` to write the new state to the entity. The transition is now considered committed.
8. **Listener Notification (complete)** — notify registered `onComplete` listeners and target-state `onEntry` listeners; an async listener is submitted here rather than run.

A transition that fails at any point from step 5 onwards notifies registered `onError` listeners instead of `onComplete`, after its compensations have run. Steps 4 and 8 pair up: every start notification is followed by exactly one of the two terminal ones (see §2.2.10).

### 2.5 Error Handling and Compensation

- **Exception Propagation** — controlled exception handling with compensation triggers.
- **Compensation Stack** — LIFO execution of registered compensation actions.
- **Exception routing** — each entry on the stack picks its rollback from the failure that ended the transition: the first route whose exception type and guard both hold, otherwise the action's unconditional compensation (§2.2.11). An entry that has nothing to answer with is skipped and stays off `compensatedPath`.
- **Validation vs. runtime errors** — `TransfluxValidationException` is thrown for definition/lookup errors; all other failure modes (failed conditions, failed actions, post-condition violations, unhandled exceptions inside an action body) are reported through `TransitionResult` after compensation has run. Two things are thrown instead: `TransfluxReentrancyException` (§2.1.3), raised before the execution starts, and a `java.lang.Error`, rethrown after the rollback (§2.2.11).

### 2.6 Definition Sourcing

The YAML DSL needs bytes; Transflux does not impose where those bytes live. The framework owns parsing and validation; a host-supplied `DefinitionSource` SPI owns retrieval. This lets a host back state-machine definitions with classpath resources (the common default), the filesystem, a relational database, a Git repository, an object store, an HTTP API, or any composition of these — without requiring the framework to know about any of them.

#### 2.6.1 The `DefinitionSource` Contract

```java
@FunctionalInterface
public interface DefinitionSource {
    Optional<DefinitionResource> open(String identifier);
    default Set<String> prefixes() { return Set.of(); }   // what it answers to in a composite
}

public final class DefinitionResource implements AutoCloseable {
    public DefinitionResource(String identifier, InputStream bytes);
    public DefinitionResource(String identifier, InputStream bytes,
                              String location, Instant lastModified, String etag);

    String identifier();                // what error messages name the document by
    InputStream bytes();                // the raw YAML, read once
    String location();                  // nullable; where the answering source found it, for people reading an error
    Instant lastModified();             // nullable; for Post-1.0 reload watchers
    String etag();                      // nullable; for Post-1.0 reload watchers
    @Override void close();             // closes the stream
}
```

The identifier names what was asked for; the location names where it was found — a path, a URL, a table and key — which matters once a composite scans several sources for one identifier. An empty `Optional` means the source has no such document, and only that. A document that exists but cannot be read, or an identifier the source refuses, is thrown — a failure is never reported as a miss. The SPI and the ships-with sources live in `transflux-yaml`, package `org.transflux.yaml.source`; nothing in the core module consumes them.

#### 2.6.2 Identifier Model

Identifiers passed to `open(...)` are **opaque, source-defined strings**. The framework imposes no path semantics — no relative-path resolution, no implicit `.yml` suffix, no slash interpretation. An `imports:` entry (§3.1.4), and the root identifier a host passes to the loader, is handed verbatim to the configured source.

Hosts can therefore choose URI-like schemes (`db://workflows/subscription`, `git://main/operations/payment.transflux.yml`), bare ids (`subscription`, `payment-flow`), or filesystem-style paths (`components/shared-components.transflux.yml`) — whichever fits the source.

#### 2.6.3 Ships-With Implementations

- **`ClasspathDefinitionSource`** — the identifier is a classpath resource name, looked up through the thread context class loader current at construction, or a class loader the host passes. The default when a host wires no other source. Prefix `cp:`; location is the resource URL; no change metadata.
- **`FileSystemDefinitionSource(Path root[, SymlinkPolicy])`** — the identifier is a path relative to `root`. One that is absolute, drive-rooted, or carries a `..` segment anywhere is refused with a `TransfluxValidationException` rather than reported missing. Symbolic links beneath the root follow `SymlinkPolicy`: `WITHIN_ROOT` (the default) follows a link only when its target stays under the root, `FOLLOW` follows every link, `REJECT` refuses any path passing through one, a Windows junction included, or through any other special entry such as another reparse point. The root itself may be a link under every policy. Prefix `file:`; location is the file's path; reports its modification time.
- **`CompositeDefinitionSource.of(sources...)`** — an ordered list of sources, routed by the prefixes each declares.

Hosts implement their own for database / Git / remote sources.

**Composite routing.** The shipped sources declare the prefixes above and take others through `withPrefixes(...)` — none at all included — so two filesystem sources can share `file:` while one also answers to `config:` and the other to `home:`. A host's own source declares its prefixes by overriding `prefixes()`; one written as a lambda declares none.

| Identifier | Asked of | Each receives |
| --- | --- | --- |
| starts with a prefix some source declares | the sources declaring it, in list order | the identifier with its own longest matching prefix stripped |
| anything else | every source, in list order | the identifier verbatim |

The first resource found wins, and reports the whole identifier with the answering source's location. A matched prefix whose sources all miss is a miss — the rest are not asked, or `db:x` could quietly be served from elsewhere. A source that throws ends the search. Prefixes are literal strings, not parsed schemes: `C:x` or `urn:x` is unprefixed unless some source declared `C:` or `urn:`. A source that needs its scheme gets it through the identifier — `http:https://example.com` reaches an `http:` source as `https://example.com`. A composite declares no prefixes of its own, so a nested one takes part in the unprefixed scan only.

**The order is a trust decision.** An earlier source shadows a later one, so a writable directory listed ahead of the classpath overrides what the application ships. A definition is code (§3.9); the framework accepts that the host sources definitions only from places it trusts and orders them accordingly.

#### 2.6.4 Error Reporting

Every validation error raised against a definition loaded through a `DefinitionSource` names the resource it is in and the import chain that reached it. A condition descriptor failing inside `db://workflows/subscription`, imported by `git://main/shared.transflux.yml`, which the root `git://main/root.transflux.yml` imports, surfaces as:

```
git://main/root.transflux.yml -> git://main/shared.transflux.yml -> db://workflows/subscription:12:9: condition 'foo': ...
```

An error the loader raises is a `DefinitionLoadException`, a `TransfluxValidationException` whose message leads with where the problem is written — the importers, root first, then `identifier:line:column: declaration path: problem`, the source's location beside the identifier when it differs — and which carries each part as an accessor, the chain as `importChain()`. A rejection thrown by a definition call the loader makes on behalf of an entry is reported at that entry's line; one refusing an id as already taken also names where the load first declared it, anywhere in the import graph. A failure raised later, by the host's `build()` or `replaceDefinition(...)`, carries the build's own message: the loader returns a definition and never builds one. That message names no file or line; it names what it refuses by id, and where the check knows it, by the path to it - `transition 't' > choice 'c' > branch 'b' references branch condition 'x', which is not a registered condition` - so an author finds it by searching for the id.

#### 2.6.5 Caching

The loader parses each resource exactly once per load. It does **not** cache parsed definitions across loads; a swap (§2.7) re-loads through the source on every call. Sources are free to cache bytes themselves; the framework treats every `open(...)` as a fresh request.

### 2.7 State Machine Handle

`StateMachine<T>` is the **host-facing handle**. The immutable, fully-built per-version data — states, transitions, registries, resolved bound steps and operations — lives in an internal `StateMachineSnapshot<T>` that the handle holds and atomically replaces. From the host's point of view, a `StateMachine<T>` is one logical entity over the lifetime of the JVM; what's behind it can change.

#### 2.7.1 Snapshot Semantics

- **Every external entry point** (`entity(...)`, `executeTransition(...)`, `resolveCurrentState(...)`, the trigger catalog's `getTriggers(...)` / `getTrigger(...)`, and the metadata getters) captures the current snapshot at the top of the call and runs against that snapshot for the duration of the call. The `EntityBinding` that `entity(...)` returns is such a capture: its `transitionTo(...)`, `fire(...)`, `processEvent(...)` and `processDataChange(...)` run against the snapshot `entity(...)` captured. A swap mid-call does not affect the in-flight call.
- **The executing transition** holds the snapshot it was constructed with; every callback into it — `run(...)` dispatch, compensation drains, condition evaluations — resolves against that snapshot. An execution that started before the swap finishes against the pre-swap topology.
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
2. **Enforces entity-type compatibility.** The new def's entity type must be `==` the handle's. Replacing a `StateMachine<Foo>`'s definition with a `StateMachineDef<Bar>` — even a `Bar` that extends `Foo`, or a `Foo` subtype — is rejected with a `TransfluxValidationException` naming both classes. The entity type is the handle's identity contract; widening or narrowing it would break every host call site that already holds the handle. This is why **every definition must declare its entity type**: `Transflux.defineStateMachine(Class)` does it for you, `forEntityType(...)` does it on the no-argument form, and `build()` refuses a definition that did neither.
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
- **One definition object is built by one thread at a time.** `replaceDefinition` is safe from any thread, and concurrent swaps on one state machine serialise; what is not safe is handing the same `StateMachineDef` to two builds at once, whether through `build()` or through two state machines' replacements. A definition carries per-build scaffolding, so two builds of one definition would overwrite each other's. Build a fresh definition per state machine, or serialise the builds.
- **In-flight isolation:** an execution started against generation N runs against generation N's snapshot regardless of how many swaps happen during it. That includes an `EntityBinding` obtained from `entity(...)`: it runs on the snapshot it captured, however late its terminal method is invoked.
- The reentrancy guard and the ban on driving the machine from a forked branch (both §2.1.3) key on the **handle**, so a swap does not open either of them: an execution in flight against generation N still rejects a reentrant call that arrives after the swap to N+1, and a branch forked under generation N may not drive generation N+1 either.

#### 2.7.5 What Replacing the Definition Does *Not* Do

- It does **not** migrate, freeze, redirect, or otherwise act on entities currently in states that the new def may have removed or renamed. The framework treats this as a host concern, identical to the equivalent in a host that does not use Transflux: if you change the meaning of "state X" or remove it, you owe your entities a migration story. Transflux's job is to make the swap atomic and to keep in-flight transitions correct against the topology they started under; everything else is application-level.
- It does **not** invalidate, drain, or wait for in-flight executions. Long-running stays a non-goal (§1.3).
- It does **not** notify listeners. Listener delivery is per-execution, not per-handle.
- It does **not** re-configure the executor. See §2.7.6.

#### 2.7.6 Executor Ownership

The executor forked members and async listeners run on belongs to the **handle**, not to a snapshot, and the first definition that needs one configures it for the handle's lifetime.

- A definition needs an executor when it declares a forked member, an async listener, `withAsyncPool(...)`, or `withAsyncExecutor(...)`. If the handle has none at that point, it takes one — a pool of its own, or the host's executor — exactly as `build()` would have. A generation-1 definition that forks nothing therefore leaves the handle without an executor, and a generation-2 definition that forks gets one at the swap.
- A later definition's async configuration is **reported and ignored**: a pool of a different size, or a different host executor, is logged as ignored and the one in force keeps running. A host that has to resize builds a new handle.
- `close()` shuts down exactly one executor, and only when the framework built it. A host-supplied executor is left running, as always. Work forked after a close meets a refusal from the shut-down pool, answered by the declared `AsyncRejectionPolicy`.
- **A closed state machine refuses a replacement.** Closing ends a handle's life: a definition installed afterwards would either declare async work against no executor at all — a refusal no policy can answer, because nothing refused it — or have a pool built for it that nothing would ever shut down. A host that needs another definition builds another handle.
- A definition that introduces `BLOCK` where the pool in force was built without a fair queue is honoured, and the mismatch is logged: fairness is decided when the pool is created, from the definition that created it.
- `BLOCK` against a host-supplied executor still fails rather than degrading (§4.5.3): a definition declaring it is refused at the swap when the executor in force is the host's, the same way `build()` refuses one declaring both.

The alternative — an executor per snapshot — was rejected. A snapshot is never explicitly retired, so its pool would either be shut down at the swap, losing a branch forked by an in-flight pre-swap transition and contradicting §2.7.1, or be retained for every generation the handle ever ran.

---

## 3. YAML-based DSL Specification

The YAML-based DSL provides a declarative approach to defining state machines, transitions, and operations. It supports modular definitions with imports and references for reusability.

### 3.1 Documents and Component Libraries

Components are declared once and referenced wherever they are needed — across operations, transitions and state machines. A document that declares components and no state machine is a **library**; a state machine document imports libraries and may declare components of its own in the same sections.

#### 3.1.1 Document Structure

Every document has one shape:

<!-- corpus: none -->
```yaml
apiVersion: transflux/v1        # required in every document

imports:                        # optional (§3.1.4)
  - components/shared-components.transflux.yml

steps: [ ... ]                  # seven component sections, each optional, in any document
operations: [ ... ]
choices: [ ... ]
conditions: [ ... ]
mappers: [ ... ]
triggers: [ ... ]
listeners: [ ... ]

stateMachine: { ... }           # the root document only (§3.2)
```

The document handed to the loader is the **root** and must carry `stateMachine:`; an imported document must not. Everything a state machine configures about itself — its global listeners (§3.7) and its `config:` (§3.8) included — sits inside `stateMachine:`, so a library has nothing to say about either. A library names no entity type: its classes are checked against the root's `entityType` when the root is loaded. Unknown keys are errors at every level, and name the keys allowed where they were found; a document is documented with YAML comments.

A document is plain YAML: exactly one document per resource, no anchors or aliases, no merge keys (`<<`), no tags beyond the core schema's (`!!str`, `!!int` and the like), and no key written twice in one mapping. Each is refused at its line rather than interpreted. Reuse is what imports and registrations are for (§3.1.4); YAML-level templating is a Post-1.0 theme (§7.2).

**Naming, and the schema editors read.** A document is named `*.transflux.yml` by convention. The loader reads any identifier (§2.6.2); the suffix is what lets an editor find the schema by file name. The format's JSON Schema — JSON Schema 2020-12 — is published at `https://vdenisov.github.io/transflux/schema/transflux-v1.schema.json` and ships in the `transflux-yaml` jar as `org/transflux/yaml/transflux-v1.schema.json`. An editor maps the `*.transflux.yml` pattern to it, or a document names it on its first line: `# yaml-language-server: $schema=https://vdenisov.github.io/transflux/schema/transflux-v1.schema.json`. The schema is for editing; the loader is the validator. The schema admits everything the loader accepts, with one exception: a bare `true` or `false` where text is expected, and a quoted boolean or number, are flagged although the loader reads them — no author writes one on purpose, and admitting them would put `true` / `false` into every id's autocomplete. It cannot see what only loading shows: a class that does not load or does not fit its position (§3.1.5), an expression that does not parse, an import that is missing or circular, an id declared twice, and the YAML features refused above.

A library:

<!-- corpus: components/shared-components -->
```yaml
# components/shared-components.transflux.yml
apiVersion: transflux/v1

steps:
  - id: prepare-notifications
    name: "Prepare Notifications"
    description: "Prepare notification messages"
    class: com.example.steps.PrepareNotificationsStep

  - id: send-notifications
    class: com.example.steps.SendNotificationsStep

  - id: charge-card
    class: com.example.steps.ChargeCardStep
    context: com.example.contexts.BillingContext
    compensation: com.example.compensations.RefundCompensation
    listeners:
      onError:
        - charge-audit

  - id: validate-prerequisites
    name: "Validate Prerequisites"
    class: com.example.steps.ValidatePrerequisitesStep
    compensation: com.example.compensations.ValidationCompensation

# Declarative containers; members run in declaration order (§3.4.2)
operations:
  - id: notification-flow
    name: "Notification Flow"
    actions:
      - run: prepare-notifications
      - run: send-notifications

# First-matching-branch actions (§3.4.3)
choices:
  - id: tier-routing
    branches:
      - id: premium
        condition:
          expression: "customerTier == 'PREMIUM'"
        actions:
          - run: notification-flow
    onNoMatch: SILENT

# A condition carries an id and nothing else — no name, no description (§2.2.1)
conditions:
  - id: payment-method-valid
    class: com.example.conditions.PaymentMethodValidCondition

  - id: high-priority
    predicate: com.example.predicates.HighPriorityPredicate

  - id: business-hours
    expression: "T(java.time.LocalTime).now().hour >= 9 && T(java.time.LocalTime).now().hour < 17"

mappers:
  - id: billing-from-activation
    parentType: com.example.contexts.ActivationContext
    childType: com.example.contexts.BillingContext
    class: com.example.mappers.BillingFromActivationMapper     # OR  mapTo: "<expression>" (§3.5)

triggers:
  - id: manual-cancel
    name: "Manual Cancellation"
    type: manual
    preConditions:
      - support-user-authorized

  - id: payment-method-validated-event
    type: event
    event: PAYMENT_METHOD_VALIDATED
    filter:
      expression: "#event.validation == 'CONFIRMED'"

  - id: data-priority-change
    type: data
    condition:
      expression: "status == 'READY_FOR_ACTIVATION' && priority > 5"

# One pool for all three listener categories. The category is read off the interface the class
# implements; `type: state | transition | action` is required only when it implements several.
listeners:
  - id: audit-start
    name: "Audit Start Listener"
    class: com.example.listeners.TransitionStartListener

  - id: charge-audit
    class: com.example.listeners.ChargeAuditListener

  - id: subscription-activated
    class: com.example.listeners.SubscriptionActivatedListener
    async: true
    onRejection: CALLER_RUNS    # optional: DROP (default), BLOCK or CALLER_RUNS; FAIL is refused
```

`steps:`, `operations:` and `choices:` are the three **declaration** forms of one concept: a step is an imperative action, an operation is a declarative one, a choice is the declarative variant that runs its first matching branch (§2.2.5). All three register into one id namespace and are referenced the same way, so nothing downstream needs to know which section an id came from. There is no section for "a step used as an operation": any action attaches wherever an action is accepted, so an alias whose only content is a reference has nothing to add.

Any registered component may carry `context:`, the context type it was written against — the typed registration of §4.2. Without one it is registered against `Object` and must tolerate any context, `null` included (§2.2.7).

#### 3.1.2 Component Reference Grammar

Any position that expects a component accepts **either** a reference or a declaration in place. This is the YAML spelling of the split the Java DSL makes with its verbs (§2.2.6): a **reference** names a component declared elsewhere and says nothing about the form it was authored in; a **declaration** brings a new one into existence at that position. Inline declarations are first-class throughout the DSL; they are essential to keep simple cases readable (a lesson learned the hard way with overly-modularized BPMN dialects).

**1. Conditions, triggers and listeners — a string references, a block declares.**

<!-- corpus: condition-and-trigger-references -->
```yaml
preConditions:
  - payment-method-valid                  # reference
  - id: has-payment-method                # declaration
    class: com.example.conditions.HasPaymentMethodCondition

triggers:
  - manual-cancel                         # reference — the same trigger may sit on several transitions
  - id: end-of-trial-cron                 # declaration
    type: manual
```

**2. Actions — a verb-keyed entry in an `actions:` list.** A bare string cannot carry the distinction where every entry is already a block, so each one is keyed by the verb that names what it does, and that key carries the id. The four verbs are the Java DSL's, unchanged:

<!-- corpus: none -->
```yaml
actions:
  - run: charge-card                # reference — the callee's form is its own business
    mapper: billing-from-order      # optional call-site mapper (§3.5)

  - step: lock-resources            # declaration, imperative
    class: com.example.actions.LockResourcesStep

  - operation: notify-flow          # declaration, declarative
    actions:
      - run: send-notifications

  - choice: tier-routing            # declaration, first-matching-branch
    branches: [ ... ]
```

Why a verb rather than inferring from content: `id` would otherwise mean two opposite things. Under a reference it *resolves* an id declared elsewhere; under a declaration it *claims* one in the state-machine-wide namespace (§2.2.1). Inferring which from the presence of sibling keys makes forgetting `class:` degrade silently into a reference, and makes "unknown action id" and "id already claimed" indistinguishable to a validator that has to guess the author's intent. The verb states it, and — as in the Java DSL — lets a reader tell the two apart without reading the arguments. An `actions:` list is the only position an action occupies: a transition's body is one (§3.3.1), so there is no single-action key and no string shorthand.

**3. Mappers — a string references, a block is the mapper itself.** A call site's `mapper:` takes a registered id, or a block holding `class:` or `mapTo:` (§3.5). The block form claims no id: it is the YAML spelling of passing a mapper instance at the call site, which has none in Java either.

**What an inline declaration is visible to.** Every inline declaration claims its id state-machine-wide — moving it never forces a rename — but is *visible* only where it was declared:

| Declared inline | Visible to |
| --- | --- |
| an action (`step:` / `operation:` / `choice:`) | the enclosing container's subtree (§4.5.2.5); a choice's branches share one scope |
| a listener | the other hooks of the same owner, in either order — one listener serves `onStart`, `onComplete` and `onError` under one id; the state machine's `onAny*` hooks are one owner |
| a trigger | its transition alone, so nothing else can reference it |
| a condition | the position it is declared at |

A reference that reaches for an inline declaration from outside is an error that says so — *declared inline on transition 't'; register it under `listeners:` to share it* — rather than an unknown id. What is shared is registered.

**Class-valued keys.** Where a key's value can only ever be a class, it is the bare class name: `context:`, `compensation:`, and a route's `exception:`. `class:` appears as a key in two roles only — as the defining key of a component entry, and as one alternative among several (`class` / `expression` on a state resolver, a filter or a guard; `class` / `predicate` / `expression` on a condition). A bare string is therefore an id wherever a pool exists to resolve it and a class name where none does; the two never share a position.

**Rules:**
- Ids are unique within their namespace across the root document and everything it imports, and every namespace is state-machine-wide (§2.2.1): states, transitions, triggers and listeners have one each; actions, conditions and mappers share one.
- A declaration's `id` is **required**, except on an inline expression-based condition (the single auto-id exception of §2.2.1) and on a call-site mapper block.
- An entry in an `actions:` list carries **exactly one** of `run`, `step`, `operation` or `choice` — the same "exactly one of" rule a condition block obeys for `class` / `predicate` / `expression`.
- Actions carry no `type:` discriminator; the declaring verb names the form. `type:` survives on triggers, where `manual` / `event` / `data` are genuinely different kinds of one component, and on a listener whose class leaves its category ambiguous.
- Listeners and `disableGlobalListeners:` belong to an action's *declaration*. On a `run:` entry both are errors: a reference carries the callee's listeners with it (§3.7).

#### 3.1.3 Component References in Context

<!-- corpus: references-in-context -->
```yaml
operations:
  - id: activation-operation
    actions:
      - run: prepare-event-actor      # a step
      - run: validate-prerequisites   # a step
      - run: notification-flow        # an operation — same spelling
      - run: tier-routing             # a choice — same spelling

stateMachine:
  transitions:
    - id: draft-to-active
      from: draft
      to: active

      actions:
        - run: activation-operation

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

Each entry is an **opaque identifier** handed verbatim to the configured `DefinitionSource` (§2.6). The framework does not interpret it as a filesystem path, classpath resource, or URI — that's the source's job. A filesystem-style example reads naturally and is the most common default (`ClasspathDefinitionSource` interprets such strings as classpath resources, `FileSystemDefinitionSource` interprets them as paths under a configured root), but any string the configured source understands is valid: `cp:components/shared.transflux.yml`, `db://workflows/subscription/imports/payment`, `git://main/operations.transflux.yml`, and so on.

<!-- corpus: imports -->
```yaml
apiVersion: transflux/v1

imports:
  - components/shared-components.transflux.yml
  - components/subscription-specific-components.transflux.yml
  - operations/subscription-operations.transflux.yml

stateMachine:
  id: subscription-state-machine
  name: "Subscription State Machine"
  entityType: com.example.Subscription

  transitions:
    - id: trial-to-active
      from: trial
      to: active
      actions:
        - run: activate-subscription
      preConditions:
        - payment-method-valid
        - subscription-specific-validation
      triggers:
        - end-of-trial-cron
```

A library may import libraries. Imports are read depth first, each before the document importing it. Each resource is read once per load, keyed by the identifier the `imports:` entry wrote, so two import paths arriving at the same library are legal and declare its components once; two identifiers a source resolves to one document are two documents to the loader, since identifiers are opaque. A missing import, one the source refuses, a circular import, and an imported document that carries `stateMachine:` are errors at the line that wrote them, naming the import chain (§2.6.4).

#### 3.1.5 Classes Named by a Document

A class name in a document is loaded through the class loader the host gives the loader — the thread context class loader current when the loader was built, by default — and a class whose instance the definition needs is created through a `ComponentFactory` (§6.2): its accessible no-argument constructor, by default, or whatever the host's factory hands out. A class that cannot be loaded, is not of the type its position takes, or cannot be instantiated is an error at the line that named it.

The loader then checks what javac would have checked for a Java host, as far as the class itself declares it:

| The class declares | Checked |
| --- | --- |
| concrete type arguments on the position's interface, directly or through a superclass | at load: the entity argument is `entityType` or a supertype of it (§4.1); a context argument is exactly the context the position runs against; a mapper's are exactly its `parentType` and `childType` |
| a type variable there — a generic class named raw | at runtime: the framework's context checks at dispatch; an entity mismatch surfaces as a `ClassCastException` from inside the component |

A component registered without `context:` is registered against `Object`, as the untyped Java registration is, and its context argument is not checked. What is declared on it — its compensation, its listeners, a trigger's conditions — runs against `Object` and is checked against it, as the Java configurer it maps to would check it; so is a transition or action listener registered without `context:`, since the untyped Java registration takes one typed against `Object`.

### 3.2 State Machine Definition

#### 3.2.1 Basic Structure

> Note: a document defines at most one state machine, and only the root document defines one (§3.1.1).

<!-- corpus: state-machine -->
```yaml
# subscription-state-machine.transflux.yml
apiVersion: transflux/v1

imports:
  - operations/subscription-operations.transflux.yml
  - triggers/subscription-triggers.transflux.yml
  - conditions/subscription-conditions.transflux.yml

stateMachine:
  # All four are optional, and are what the built StateMachine reports
  id: subscription-state-machine
  name: "Subscription State Machine"
  description: "State machine for subscription lifecycle management"
  version: 1.0.0

  entityType: com.example.Subscription    # required; must be the class the host loads the document for

  # State resolver — read the current state
  stateResolver:
    class: com.example.resolvers.SubscriptionStateResolver
    # OR  expression: "status"

  # State applier — finalize the transition by writing the new state (optional, §2.2.12)
  stateApplier:
    class: com.example.appliers.SubscriptionStateApplier
    # OR  expression: "status"            # an assignable property path

  # listeners: { ... }                    # state-machine-wide listeners (§3.7)
  # config: { ... }                       # executor and trace logging (§3.8)

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
      actions:
        - run: activate-subscription
      preConditions:
        - payment-method-valid
      triggers:
        - id: end-of-trial-cron
          type: manual
          description: "Invoked manually by an external cron job"

    - id: active-to-suspended
      from: active
      to: suspended
      # The body is a member list like any other (§3.1.2): reference, declare, or mix
      actions:
        - run: evaluate-risk
        - choice: branch-on-risk
          branches:
            - id: low-risk-retry
              condition:
                expression: "@riskService.isLowRisk(id)"
              actions:
                - run: schedule-retry-payment
          default:
            actions:
              - step: notify-user
                class: com.example.actions.NotifyPaymentIssueStep
      triggers:
        - id: payment-failed
          type: data
          condition:
            id: payment-has-failed
            predicate: com.example.triggers.PaymentFailedPredicate

    - id: suspended-to-cancelled
      from: suspended
      to: cancelled
      actions:
        - step: cancel-subscription
          description: "Cancel subscription with compensation"
          class: com.example.actions.CancelSubscriptionStep
          compensation: com.example.compensations.RefundPartialFeesCompensation
      preConditions:
        - no-recovery-7-days
      triggers:
        - manual-cancel                   # registered once, attached here and below (§3.3.2)

    - id: active-to-cancelled
      from: active
      to: cancelled
      triggers:
        - manual-cancel

    - id: active-to-expired
      from: active
      to: expired
```

**Metadata.** `id`, `name`, `description` and `version` are optional and land on the runtime `StateMachine`, which reports `null` for whichever was not given. `version` is an opaque string the framework never parses or compares — a semantic version, a commit hash and a timestamp are all fine. It exists for the host that loads definitions from an external source (§2.6) and needs to know which one a machine is running: all four belong to the *definition*, so after a replacement (§2.7) the machine reports the new definition's, and the framework logs id and version when a definition is built or swapped in.

**Resolver and applier expressions** are YAML's stand-in for the two lambdas a Java host writes (§2.2.12). Both evaluate against the entity as root. The resolver's result is the state id — an enum contributes its `name()`, anything else its `toString()`. The applier's expression is an assignment *target*: the framework assigns the new state id through it, and SpEL's standard conversion turns the string into an enum-typed property. Anything computed, or any pairing the two expressions cannot say, is a class.

#### 3.2.2 State Configuration

<!-- corpus: state -->
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
    # Turn off state-machine-wide state listeners for this state (§3.7)
    disableGlobalListeners: [state-audit]
```

### 3.3 Transition Configuration

#### 3.3.1 Basic Transition

<!-- corpus: transition -->
```yaml
transitions:
  - id: trial-to-active
    name: "Trial to Active Transition"
    description: "Activate trial subscription"
    from: trial
    to: active

    # The context type a firing must supply. Omitted: any context, or none.
    # java.lang.Void: the transition rejects a non-null context (§4.5).
    context: com.example.contexts.ActivationContext

    # The body: an ordered member list in the grammar of §3.1.2. Optional.
    actions:
      - run: activate-subscription
      - run: send-welcome-email
        fork: true

    preConditions:
      - payment-method-valid

    postConditions:
      - milestones-activated

    triggers:
      - end-of-trial-cron                 # a registered trigger, by id

      - id: external-activation           # declared in place
        type: data
        condition:
          id: subscription-activated-externally
          class: com.example.triggers.SubscriptionActivatedCondition

    listeners:
      onStart:
        - audit-start
      onComplete:
        - audit-complete
```

A transition declares no compensation of its own (§2.2.11): rolling the body back as a unit is one wrapping `operation:` carrying the `compensation:`.

#### 3.3.2 Manual Triggers

A `type: manual` trigger names an explicit invocation point. Even when a transition could be invoked through the bare `stateMachine.transitionTo(...)` API, defining a named manual trigger carries value: per-trigger metadata, descriptions, and trigger-specific pre-conditions can be attached to the named handle and discovered via the catalog API, and a transition listener can single the trigger out by reading it off its payload (§2.2.10). The trigger's name does **not** imply the library schedules anything — for example, `end-of-trial-cron` indicates that an external cron job invokes this trigger; the library does no scheduling itself (see §1.3 Non-Goals).

<!-- corpus: manual-trigger -->
```yaml
triggers:
  - id: manual-cancel
    type: manual
    description: "User-initiated cancellation through the support portal"
    preConditions:
      - support-user-authorized
```

A trigger registered under the top-level `triggers:` may be attached to several transitions and remains one trigger (§2.2.8). For a manual one that is the point: `manual-cancel` on both `active → cancelled` and `suspended → cancelled` lets the host fire it without knowing which state the entity is in, and the framework takes the attachment leaving the current state. Attaching one trigger, whatever its kind, to two transitions leaving the *same* state is an error (§2.2.8).

#### 3.3.3 Event Triggers

Event triggers fire in response to events that the host publishes into the state machine via `processEvent(...)`.

<!-- corpus: event-trigger -->
```yaml
triggers:
  - id: payment-method-validated-event
    type: event
    event: PAYMENT_METHOD_VALIDATED
    filter:
      expression: "#event.validation == 'CONFIRMED'"
      # OR  class: com.example.triggers.ConfirmedValidationFilter
```

A `filter:` is not a condition (§3.6.1): it judges the event, so it has no id, cannot be referenced, and cannot reference a registered condition. Its class implements `BiPredicate<Object, T>` over `(event, entity)` or `Predicate<Object>` over the event alone, and not both; its expression sees the event as `#event` (§3.9).

#### 3.3.4 Data Triggers

Data triggers fire when the host calls `entity(e).processDataChange()` and the trigger's condition matches the entity's current state.

> **Reminder:** Transflux does not watch entity fields, hook into ORM change tracking, or evaluate triggers automatically in 1.0 — data triggers are host-driven re-evaluation only (see §1.3 Non-Goals). The host's typical pattern is: update the entity → call `entity(e).processDataChange()` → framework evaluates the data triggers on transitions leaving the current state, in declaration order, and fires the first whose gate holds.

A data trigger's `condition:` follows the standard Condition Descriptor grammar (§3.6.1):

<!-- corpus: data-triggers -->
```yaml
triggers:
  # Class-based — full Condition<T, C> implementation
  - id: priority-change-class
    type: data
    condition:
      id: priority-changed
      class: com.example.triggers.SubscriptionPriorityChangedCondition

  # Predicate-based — a lighter BiPredicate<T, C>-style class
  - id: payment-failed
    type: data
    condition:
      id: payment-has-failed
      predicate: com.example.triggers.PaymentFailedPredicate

  # Expression-based — inline SpEL; the one form that may omit its id
  - id: priority-change-expression
    type: data
    condition:
      expression: "status == 'READY_FOR_ACTIVATION' && priority > 5"

  # Reference to a registered condition
  - id: ready-and-high-priority
    type: data
    condition: ready-and-high-priority-condition
```

### 3.4 Action Definitions

There is one runtime concept — an action — declared in one of two forms (§2.2.5). A **step** is imperative: a Java class implementing `Action`, with no bound children. An **operation** is declarative: an ordered `actions:` list, where declaration order *is* execution order and the framework synthesizes the executable that walks it. A **choice** is the declarative variant whose ordering rule is "first matching branch" rather than "all, in order".

Every form attaches anywhere an action is accepted — as a member of a transition's body, of an operation, or of a choice's branch. None needs a wrapper to reach a position another can occupy.

Whatever its form, an action's declaration — registered or in place — accepts the same keys around its own content: `name`, `description`, `context` (§3.5.1), `compensation` and `errorHandling` (§3.4.2), `listeners` and `disableGlobalListeners` (§3.7), and `onRejection`, which is what a refused submission does when this action is reached through a fork (§3.8).

#### 3.4.1 Steps

A step is declared under `steps:` and names a class. It binds no children, though its body is free to dispatch other actions by id while it runs.

<!-- corpus: steps -->
```yaml
steps:
  - id: activate-subscription
    name: "Activate Subscription"
    description: "Activate a trial subscription"
    class: com.example.actions.ActivateSubscriptionStep
    context: com.example.contexts.ActivationContext

  - id: send-welcome-email
    class: com.example.actions.SendWelcomeEmailStep
```

#### 3.4.2 Operations

An operation is declared under `operations:` and carries an `actions:` list. Each entry is verb-keyed per §3.1.2 — `run:` to reference, `step:` / `operation:` / `choice:` to declare — and the list runs in declaration order.

A reference says nothing about the form of the thing it names: `run: notification-flow` reaches an operation and `run: charge-card` reaches a step, spelled identically, because which form the callee was authored in is a property of *its* declaration rather than of the call.

<!-- corpus: operations -->
```yaml
operations:
  - id: complex-activation
    description: "Complex activation with several actions"
    context: com.example.contexts.ComplexActivationContext

    actions:
      - run: prepare-event-actor
      - run: validate-prerequisites

      # A reference may carry a context mapper at the call site (§3.5.2)
      - run: charge-card
        mapper: billing-from-activation

      # Declared in place; the `step:` verb makes this one imperative
      - step: lock-resources
        class: com.example.actions.LockResourcesStep
        description: "Lock resources for activation"
        listeners:
          onError:
            - lock-failure-audit

      # Declared in place, against a context of its own, mapped into at this position (§3.5.1)
      - operation: notify-flow
        context: com.example.contexts.NotificationContext
        mapper: notification-from-activation
        actions:
          - run: prepare-notifications
          - run: send-notifications

      - choice: priority-routing
        branches:
          - id: high-priority-branch
            condition:
              id: is-high-priority
              predicate: com.example.predicates.HighPriorityPredicate
            actions:
              - step: high-priority-processing
                class: com.example.actions.HighPriorityStep
              - step: urgent-notification
                class: com.example.actions.UrgentNotificationStep

          - id: medium-priority-branch
            condition:
              expression: "priority >= 5 && priority < 8"
            actions:
              - step: medium-priority-processing
                class: com.example.actions.MediumPriorityStep

          - id: vip-customer-branch
            condition: is-vip-customer          # a registered condition
            actions:
              - run: vip-processing
              - step: account-manager-notification
                class: com.example.actions.AccountManagerNotificationStep

        default:
          actions:
            - step: standard-processing
              class: com.example.actions.StandardProcessingStep
            - step: standard-notification
              class: com.example.actions.StandardNotificationStep

      - step: finalize
        class: com.example.actions.FinalizeActivationStep

      # Forked members: submitted at this position, and not waited for
      - run: async-notifications
        fork: true

      - run: external-integrations
        fork: true
        mapper: notification-from-activation
        onRejection: CALLER_RUNS            # this position's answer to a refused submission (§3.8)

      # The flag rides on a declaring entry too, so a one-off group can be
      # declared where it is forked rather than registered and referenced
      - operation: reconciliation-sweep
        fork: true
        actions:
          - run: refresh-projections
          - run: emit-audit-record

    # Unconditional rollback for this operation — the fallback when no route below answers
    compensation: com.example.compensations.GeneralCompensation

    errorHandling:
      - exception: com.example.exceptions.RecoverableException
        guard:
          class: com.example.predicates.RecoverableErrorPredicate
        compensation: com.example.compensations.RecoverableCompensation

      - exception: com.example.exceptions.GatewayException
        guard:
          expression: "statusCode == 503"
        compensation: com.example.compensations.ReconcileLaterCompensation
```

> **Error handling is the routing table of §2.2.11.** Each `errorHandling:` entry is one route: `exception:` is the failure type it answers for, the optional `guard:` narrows it, and `compensation:` is what it runs. Entries are tried in declaration order and the first match wins, so the list is ordered the way a Java `catch` chain is. A `guard:` is not a condition (§3.6.1): it judges the *failure*, never the entity or the context, so it has no id and no reference form. Its class implements `Predicate` over the declared exception type; its expression is evaluated with the failure as root (§3.9). The unconditional fallback is the owner's own `compensation:` key, exactly as in the Java DSL — there is deliberately no separate "for every exception" entry spelling, since an entry on `java.lang.Exception` and the `compensation:` key would then be two ways to say the same thing with an ordering question between them. The block is accepted on any action, not only on an operation.

> **Forking is a per-member flag, not a block.** `fork: true` on a member entry means the member is submitted where it appears in the `actions:` list and the members after it do not wait for it. There is deliberately no `fork:` block with an `enabled` key and an anchor — a position in an ordered list already says when work starts, and the two anchor forms an earlier design proposed ("when execution reaches x", "when x completes successfully") are just the positions before and after `x`. A forked member accepts the same `mapper:` key any other member does; what it may not rely on is a mapper that writes back (§4.5.3.2), and a `mapFrom:` written in its call-site block is refused (§3.5.2).

> **`fork` names the act; `async` names the machinery.** The key is `fork:` and not `async:` because what the flag says is that *this member* is forked — it becomes a branch with its own context and its own rollback stack (§4.5.3). Where branches run is a separate question with its own answer: `config.async:` (§3.8) configures the executor, matching `withAsyncPool(...)` and `withAsyncExecutor(...)` on the Java side. `ForkableContext` sits on the fork side of that line, because a context is a branch's own; `AsyncRejectionPolicy` sits on the async side, because it answers for the executor rather than for the member — the same four answers apply to any work handed to it. A listener's `async: true` (§3.7) keeps `async` on purpose: an observer that does not block is not a forked member — it has no context of its own, no rollback stack, and appears on no path.

> **The flag is orthogonal to the verb, which is why YAML needs one of it and Java needs eight.** `fork: true` sits beside `run:`, `step:`, `operation:` or `choice:` alike, so every member form is forkable with no second spelling. Java cannot do that: `fork` and its declaring siblings must be distinct method names, because a mapper and a configurer are indistinguishable to javac at an implicitly-typed lambda. The four verbs `run` / `step` / `operation` / `choice` therefore each have a twin — `fork` / `forkStep` / `forkOperation` / `forkChoice` (§4.4.2) — and a mapper is passed positionally rather than under a key. The models are the same; only the spelling differs, and the mapper resolves this direction because a YAML document cannot hold a lambda.

#### 3.4.3 Choices

A choice allows for complex decision-making with multiple conditions and a default fallback branch. It evaluates its branches' conditions in declaration order and executes the **first matching** branch, or the default branch if none match. It is a declarative action like any other, so it may be declared in place with the `choice:` verb (as below) or registered under `choices:` and referenced with `run:`.

<!-- corpus: choices -->
```yaml
operations:
  - id: priority-based-routing
    description: "Route processing based on multiple priority conditions"

    actions:
      - choice: multi-priority-routing
        onNoMatch: ERROR                  # only consulted when there is no `default:`
        branches:
          - id: critical-priority
            condition:
              id: is-critical-priority
              predicate: com.example.predicates.CriticalPriorityPredicate
            actions:
              - step: escalate-immediately
                class: com.example.actions.EscalateStep
              - step: notify-management
                class: com.example.actions.ManagementNotificationStep
              - step: expedited-processing
                class: com.example.actions.ExpeditedProcessingStep

          - id: high-priority
            condition:
              expression: "priority >= 8 && customerTier == 'PREMIUM'"
            actions:
              - step: priority-processing
                class: com.example.actions.PriorityProcessingStep
              - step: premium-notification
                class: com.example.actions.PremiumNotificationStep

          - id: vip-customer
            condition: is-vip-customer
            actions:
              - step: vip-processing
                class: com.example.actions.VipProcessingStep
              - step: account-manager-alert
                class: com.example.actions.AccountManagerAlertStep

          - id: time-sensitive
            condition:
              expression: "deadline.isBefore(T(java.time.LocalDate).now().plusDays(1))"
            actions:
              - step: urgent-processing
                class: com.example.actions.UrgentProcessingStep

          - id: business-hours
            condition: business-hours
            actions:
              - step: business-hours-processing
                class: com.example.actions.BusinessHoursProcessingStep
```

A branch carries an `id`, a `condition:` in the descriptor grammar (§3.6.1) and an `actions:` list; `default:` carries the list alone. A branch is not an action (§2.2.5.1), so neither accepts `compensation:`, `listeners:` or a `context:` — those belong to the choice, or to the members.

**Execution Semantics:**
1. Branches are evaluated in the order they are defined.
2. The first branch whose condition evaluates to `true` is executed.
3. Once a branch is executed, no further conditions are evaluated.
4. If no branch conditions match, the `default` branch is executed.
5. If no `default` branch is defined and no conditions match, the outcome is set by `onNoMatch:` on the choice — the `NoMatchBehavior` enumeration in Java:
   - **`WARN`** (default) — log a warning and continue. The choice itself completes without dispatching any inner actions; its id is recorded on `executedPath`.
   - **`SILENT`** — same as `WARN` but without logging. Suits the guard pattern (`if (cond) { ... }` with no `else`), where a no-match is the deliberate, expected outcome.
   - **`ERROR`** — raise a transition failure. The choice fails, the enclosing action fails, and any registered compensations run.

### 3.5 Context and Data Mapping

#### 3.5.1 Context Types

A context is a plain host class, and `context:` names it — a bare class name at every position, never an id: there is no pool of contexts, because nothing about a context is declared beyond its class.

| Position | `context:` means |
| --- | --- |
| a transition | the type a firing must supply (§3.3.1) |
| a registered component — any kind | the type it was written against (§3.1.1) |
| an inline `step:` / `operation:` / `choice:` | the type *this* action runs against, where that differs from the enclosing list's |

An inline declaration comes in three shapes, as it does in Java (§4.5.2). Without `context:` it inherits the enclosing list's context. With `context:` alone it declares its own and receives the enclosing one as it is, which requires the declared type to accept it — widening is fine, narrowing is a build error. With `context:` and `mapper:` it declares its own and the mapper produces it at this position.

#### 3.5.2 Mappers

A mapper turns the context at a call site into the one the callee runs against (`mapTo`), and may write results back when the callee returns (`mapFrom`). Mapping is a property of the call site, never of the callee (§4.5.2).

<!-- corpus: mappers -->
```yaml
mappers:
  # Class-based — a ContextMapper<P, N>, for a mapping that needs logic
  - id: billing-from-activation
    name: "Billing from Activation"
    parentType: com.example.contexts.ActivationContext
    childType: com.example.contexts.BillingContext
    class: com.example.mappers.BillingFromActivationMapper

  # Expression-based — a read-only projection
  - id: notification-from-activation
    parentType: com.example.contexts.ActivationContext
    childType: com.example.contexts.NotificationContext
    mapTo: "new com.example.contexts.NotificationContext(subscriptionId, activatedBy)"

  # Expression-based, writing back when the callee returns
  - id: payment-from-activation
    parentType: com.example.contexts.ActivationContext
    childType: com.example.contexts.PaymentContext
    mapTo: "new com.example.contexts.PaymentContext(subscriptionId, paymentMethodId)"
    mapFrom:
      chargeId: "chargeId"                          # parent.chargeId          <-  child.chargeId
      activationResult: "result.status"             # parent.activationResult  <-  child.result.status
      chargedAt: "#parent.chargedAt ?: completedAt"
```

A `mapTo:` expression is evaluated with the **parent context as root** and must produce the child context (§3.9); the entity is not in scope, since a mapper never sees one.

`mapFrom:` is a map of assignments, applied in document order once the callee has returned. Each **key** is an assignable property path on the parent context, written through the way a state applier's expression is (§3.2.1), with the same type conversion. Each **value** is an expression evaluated with the **child context as root**, with the parent available as `#parent` for the occasional merge. It assigns *into* the parent rather than producing a new one, and that is not a limitation of the spelling: by the time a callee returns, the parent context is referenced by the host that will read results off it, by every compensation already on the rollback stack and by the enclosing actions, so a replacement object would carry values nothing looks at. The parent therefore needs writable properties, as it does for a Java `mapFrom`. `mapFrom:` requires `mapTo:` beside it; a write-back with logic in it is a class. At a forked call site no write-back runs (§4.5.3.2). Where the loader can see one — a `mapFrom:` in the call-site block beside `fork: true` — it refuses it at its line; a registered mapper's `mapFrom:`, or a class that overrides `mapFrom`, is written elsewhere and is simply not applied there.

At a call site, `mapper:` takes any of three forms. The two block forms need no `parentType` / `childType`: the position already fixes both.

<!-- corpus: call-site-mappers -->
```yaml
actions:
  - run: charge-card
    mapper: billing-from-activation                       # a registered mapper

  - run: charge-card
    mapper:
      class: com.example.mappers.BillingFromActivationMapper

  - run: send-receipt
    mapper:
      mapTo: "new com.example.contexts.ReceiptContext(subscriptionId)"
      # mapFrom: { ... }                                  # accepted here too
```

#### 3.5.3 Context Usage Examples

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
    .transitionTo("active", context);

// Application reads results from context after execution
log.info("Activated subscription {} at {} with result {}",
    entity.getId(), context.getActivatedAt(), context.getActivationResult());
```

### 3.6 Conditions and Validators

#### 3.6.1 Condition Descriptor

A condition appears at five positions: a transition's pre- and post-conditions, a manual trigger's pre-conditions, a choice's branch, and a data trigger's gate. They share a single grammar — the **Condition Descriptor** — with five authoring forms, three of which both DSLs express. The other two are each one DSL's way of naming a condition object: `InstanceBased` attaches a pre-built `Condition<T, C>` and is Java-only, since a live object has no YAML serialization; `ClassBased` names a class and is YAML-only, since the Java DSL holds the object already and the factory (§6.2) is what turns a class name into one.

<!-- corpus: condition-descriptor -->
```yaml
preConditions:
  # 1. Reference to a registered condition
  - payment-method-valid

  # 2. Inline class-based — a full Condition<T, C> implementation
  - id: payment-method-present
    class: com.example.conditions.PaymentMethodPresentCondition

  # 3. Inline predicate-based — a BiPredicate<T, C>-style class (lighter than Condition<T, C>;
  #    useful for stateless boolean tests over (entity, context)). A class implementing
  #    Predicate<T> is accepted for entity-only tests; the context is then ignored. A class
  #    implementing both is refused, as a Java call passing it would be ambiguous.
  - id: payment-method-current
    predicate: com.example.predicates.PaymentMethodCurrentPredicate

  # 4. Inline expression — SpEL (§3.9); the one form that may omit its id
  - expression: "paymentMethodId != null"

  # 5. (Java DSL only) InstanceBased — attach a pre-built Condition<T, C> instance under an explicit id.
```

**Resolution rules:**
- A **string** is form 1, a reference. A **block** is an inline declaration and carries exactly one of `class`, `predicate` or `expression`.
- An inline declaration carries an `id`, which it claims in the namespace actions and mappers share (§2.2.1); only the expression form may omit it, and is then given one derived from the expression and its position.
- In a list position (`preConditions:`, `postConditions:`) each element is a descriptor. In a single position (`condition:` on a branch or a data trigger) the key's value is one.
- A condition carries **no `name:` or `description:`**, registered or inline — both keys are errors. Nothing at runtime represents a condition beyond its id, so there would be nowhere for them to go; a condition is documented with a comment.

**Form comparison:**
- A **`Condition<T, C>` instance** (form 5, Java DSL only) is the right choice when the host already has a configured `Condition` (DI-wired, holds runtime state) and wants to attach it to a single site without re-routing through the `StateMachineDef.condition(id, ...)` registry.
- A **`Condition<T, C>` class name** (form 2, YAML only) is how a document points at that same full-featured shape — one that holds injected dependencies and sees the transition it is evaluated for. The factory resolves it to an instance before registration, so it reaches the runtime as form 5 does.
- A **`BiPredicate<T, C>`** (form 3) is the minimal shape — a simple boolean test over `(entity, context)`.
- An **expression** (form 4) is for one-off inline logic that doesn't justify a Java class.
- A **reference** (form 1) shares a single definition across many positions.

Two keys look like conditions and are not, because neither judges `(entity, context)`: an event trigger's `filter:` judges the event (§3.3.3), and a compensation route's `guard:` judges the failure (§3.4.2). Both take `class` or `expression`, neither takes an id, and neither can reference a registered condition.

#### 3.6.2 Registered Conditions

<!-- corpus: registered-conditions -->
```yaml
conditions:
  - id: checkout-fulfilled
    class: com.example.conditions.CheckoutFulfilledCondition

  - id: milestones-activated
    class: com.example.conditions.MilestonesActivatedCondition
    context: com.example.contexts.ActivationContext

  - id: business-hours
    expression: "T(java.time.LocalTime).now().isAfter(T(java.time.LocalTime).of(9, 0)) and T(java.time.LocalTime).now().isBefore(T(java.time.LocalTime).of(17, 0))"
```

### 3.7 Listeners and Hooks

All three listener categories (§2.2.10) are written the same way: the owner carries a `listeners:` block keyed by its hooks, and each hook holds a list whose entries are a reference to a registered listener or a declaration in place (§3.1.2).

| Owner | Hooks |
| --- | --- |
| a state | `onEntry`, `onExit` |
| a transition | `onStart`, `onComplete`, `onError` |
| an action's declaration — registered or inline, in any form | `onStart`, `onComplete`, `onError` |
| the state machine | `onAnyStateEntry`, `onAnyStateExit`, `onAnyTransitionStart`, `onAnyTransitionComplete`, `onAnyTransitionError`, `onAnyActionStart`, `onAnyActionComplete`, `onAnyActionError` |

<!-- corpus: listeners -->
```yaml
listeners:                                # the pool (§3.1.1)
  - id: state-audit
    class: com.example.listeners.StateAuditListener
  - id: subscription-activated
    class: com.example.listeners.SubscriptionActivatedListener
    async: true

steps:
  - id: charge-card
    class: com.example.steps.ChargeCardStep
    context: com.example.contexts.BillingContext
    listeners:
      onError:
        - id: charge-audit                # declared in place
          class: com.example.listeners.ChargeAuditListener

stateMachine:
  # State-machine-wide listeners: they fire for every owner of their category,
  # after that owner's own
  listeners:
    onAnyStateEntry:
      - state-audit
    onAnyTransitionComplete:
      - id: transition-audit
        class: com.example.listeners.TransitionAuditListener
        async: true
        onRejection: CALLER_RUNS
    onAnyTransitionError:
      - transition-audit                  # the same listener, reused by this owner's other hook
    onAnyActionError:
      - action-failure-audit

  states:
    - id: active
      listeners:
        onEntry:
          - subscription-activated
        onExit:
          - subscription-deactivated

  transitions:
    - id: trial-to-active
      from: trial
      to: active
      context: com.example.contexts.BillingContext    # the context charge-card is registered against
      listeners:
        onStart:
          - id: activation-audit
            class: com.example.listeners.ActivationAuditListener
        onComplete:
          - activation-audit
        onError:
          - activation-audit
      actions:
        - run: charge-card                # charge-audit fires here, and at every other call site
```

**A listener entry** declared in place carries `id`, `class`, optional `name` / `description`, and the two async keys: `async: true`, and `onRejection:` — `DROP` (the default), `BLOCK` or `CALLER_RUNS`; `FAIL` is refused (§2.2.10), and `onRejection:` without `async: true` is an error. Its category is fixed by the hook it sits under, its class must implement that category's interface, and its context is its owner's — there is no `context:` key, as there is no context parameter on the Java configurer it maps to. A registered listener has no hook to read a category from, so it is read off the interface its class implements; a class implementing more than one names the category it is registered under with `type: state | transition | action`. A registered transition or action listener may carry `context:`; a state listener may not, since it is handed whichever context the transition carries (§2.2.10).

**The id names the listener, not the attachment** (§2.2.10). A registered listener may sit under any number of hooks on any number of owners, the state machine's own included, and whatever it declares — `async:` too — holds at each. A listener declared in place is visible to its owner's other hooks and to nothing else, which is what `activation-audit` and `transition-audit` do above; one class serving several hooks tells them apart by the phase in its payload.

**An action listener attaches to the action, not to the call site.** It is written on the action's declaration and fires wherever that action is invoked, so a `run:` entry carries the callee's listeners with it and accepts none of its own — `listeners:` on a `run:` entry is an error that names the action whose declaration it belongs on. The same holds for `disableGlobalListeners:`.

**Turning state-machine-wide listeners off.** A state, a transition and an action's declaration each accept `disableGlobalListeners:`, suppressing the state-machine-wide listeners of the owner's own category under the rules of §2.2.10:

<!-- corpus: disabling-global-listeners -->
```yaml
transitions:
  - id: cancel
    from: active
    to: cancelled
    disableGlobalListeners: [transition-audit]    # these; an empty list is an error

steps:
  - id: capture-payment
    class: com.example.steps.CapturePaymentStep
    disableGlobalListeners: true                  # all of them
    listeners:
      onStart:
        - id: capture-redacted
          class: com.example.listeners.RedactedCaptureListener
```

### 3.8 Global Configuration

`config:` sits inside `stateMachine:` — it configures this state machine, and a library has none.

<!-- corpus: global-configuration -->
```yaml
stateMachine:
  config:
    # Where forked members and async listeners run, and what happens when the queue is full
    async:
      threadPoolSize: 16       # both sizes or neither; neither is the current default:
      queueCapacity: 160       # 2 x processors threads (at least 4), 10 slots per thread
      onRejection: DROP        # OR: FAIL, BLOCK, CALLER_RUNS

    # The shipped logging listeners, attached globally
    logging:
      level: INFO              # every trace line's level; DEBUG when omitted
      includeContext: false    # true writes the context's toString() into the trace
      includeTimings: true     # durations on transition and action outcome lines
```

> **The `async` block configures this state machine's executor**, mapping to `withAsyncPool(...)` and `withAsyncRejectionPolicy(...)` on the Java `StateMachineDef` (§4.10.1); a block that sizes nothing — `async: {}` — is `withAsyncPool()`. The two sizes are given together or not at all, as the Java forms take them, and `async:` or `logging:` written with no value is refused rather than read as absent, since the block's presence is what it says. The default sizing scales with the processors available to the JVM, because forked work mostly waits on I/O and a fixed number is wrong for both a two-vCPU container and a large host. The formula in the comments above is the current choice, not a contract: a pool logs the sizes it was built with, and a host that depends on specific numbers states them. It is per state machine rather than process-wide because the pool's lifecycle is the state machine's: `StateMachine.close()` shuts down a pool the framework built. A host that would rather share one executor across several machines supplies it in Java through `withAsyncExecutor(...)`; there is no YAML spelling for that, since a YAML document cannot name a live object. A definition that forks nothing and declares no async listener builds no pool, whatever this block says — with one exception, which is that declaring the block *is* such a statement. A fork written inside a Java body (§4.5.2.1) is invisible to every definition-time walk, so asking for a pool is how a definition whose only forks are imperative says that it forks at all.

> **The `logging` block attaches the shipped logging listeners; it is not framework logging.** It maps to `withExecutionLogging(...)` in Java, which attaches a state, a transition and an action listener to every owner, writing to the `org.transflux.trace.*` subtree at the level given. The framework's own diagnostics never log a payload and are configured through the logging backend alone (§4.7); this block is the host asking for a trace of its own execution, so `includeContext: true` is the host's call to put its own context in its own logs. It stays off by default, and the expected pattern for a flow that wants payloads in only a few places is to leave the global trace context-free and attach a context-logging listener to those owners. Java adds an entity label — `withEntityLabel(Order::getId)` — which YAML has no spelling for, since a document cannot supply a function.

> **`onRejection` here is the default, not the whole story.** It applies to async work that declares nothing of its own. Three parties can speak for one submission, and the most specific wins: the position that forks it, the component's own declaration, then this default. Some work knows how it must be treated — an audit listener, a write that must never be lost — and declares it on itself: `onRejection:` on the component's declaration, `ActionDef.withAsyncRejectionPolicy(...)` in Java. Some work does not — a notification step is droppable in one flow and not in another — and the position that forks it says so: `onRejection:` beside the `fork:` flag on a `run:` entry, `fork(id, policy)` in Java. On a declaring entry the two coincide, since an inline declaration is its own call site. `BLOCK` is the one value that needs a pool the framework built: waiting for capacity happens inside the rejection handler the framework installs, and a host's executor is not the framework's to reconfigure. Declaring `BLOCK` anywhere alongside `withAsyncExecutor(...)` fails the build.

### 3.9 Expression Language Support

Transflux uses SpEL (Spring Expression Language) wherever a document says `expression:` or `mapTo:`.

**In a condition, the entity is the evaluation root**, so its properties are written bare: `status == 'READY'`, not `entity.status`. Everything else is a named variable: `#context` (the firing context, possibly `null`), `#transition` (the read-only `Transition`), and `#entity` — the root again under a name, for the one thing a bare property cannot say, which is passing the whole entity to a method: `@validationService.validate(#entity)`. The other positions bind what their Java counterpart is handed, and nothing more:

| Position | Root | Variables | Result |
| --- | --- | --- | --- |
| a condition, at any of its five positions (§3.6.1) | the entity | `#context`, `#transition`, `#entity` | boolean |
| an event trigger's `filter:` (§3.3.3) | the entity | `#event`, `#context`, `#entity` | boolean |
| `stateResolver:` (§3.2.1) | the entity | `#entity` | the state id |
| `stateApplier:` (§3.2.1) | the entity | — | none: the expression is assigned to |
| a mapper's `mapTo:` (§3.5.2) | the parent context | — | the child context |
| a mapper's `mapFrom:` entry (§3.5.2) | the child context | `#parent` | the value assigned to the entry's key, a property path on the parent |
| a route's `guard:` (§3.4.2) | the failure | — | boolean |

**An expression is parsed where it is declared**, in either DSL: a malformed one is refused by the def call that declares it — at its line, in a document — rather than at its first evaluation. It is still evaluated only when the position runs.

**A definition is code.** Expressions are evaluated with SpEL's full feature set — type references such as `T(java.time.LocalTime)` included, which the examples below depend on — and every `class:` key instantiates a class by name. Loading a definition from an external source is therefore loading code, and the `DefinitionSource` (§2.6) is a trust boundary the host owns.

**`@name` needs a resolver.** A bean reference such as `@checkoutService` resolves through the dependency-injection integration of §6.1. Without one, the expression still parses, and fails when it is first evaluated.

<!-- corpus: expressions -->
```yaml
conditions:
  # Simple field access
  - id: status-ready
    expression: "status == 'READY'"

  # Method calls (with DI integration when available)
  - id: checkout-fulfilled
    expression: "@checkoutService.isCheckoutFulfilled(checkoutUid)"

  # Date/time conditions
  - id: business-hours
    expression: |
      T(java.time.LocalTime).now().isAfter(T(java.time.LocalTime).of(9, 0)) &&
      T(java.time.LocalTime).now().isBefore(T(java.time.LocalTime).of(17, 0))

  # Collection operations
  - id: all-milestones-active
    expression: "milestones.![state].contains('INACTIVE') == false"

  # Conditional logic
  - id: priority-based-validation
    expression: |
      priority > 8 ?
        @validationService.strictValidation(#entity) :
        @validationService.basicValidation(#entity)

# Data-based triggers with SpEL expression evaluation
triggers:
  - id: ready-and-high-priority
    type: data
    condition:
      expression: "status == 'PENDING' && priority > 5"

  - id: pending-and-expedited
    type: data
    condition:
      # Expedite if pending and the request is marked as expedited in context.
      expression: "status == 'PENDING' && (#context?.expedited ?: false)"
```

---

## 4. Java-based Builder DSL Specification

The Java-based builder DSL provides a programmatic, type-safe approach to defining state machines, transitions, and operations. It emphasizes fluent interfaces, compile-time safety, and IDE support.

### 4.1 Reusable Components

A component is declared once on the state-machine definition and referenced by id wherever it is needed. This is the Java counterpart of a YAML library's sections (§3.1.1): the same seven kinds, the same namespaces (§2.2.1), and the same rule that what is shared is registered while what is used once may be declared in place.

#### 4.1.1 Registrations

```java
StateMachineDef<Subscription> def = Transflux.defineStateMachine(Subscription.class)

    // Steps — an instance, or a configurer when the step wants metadata, compensation or listeners
    .step("prepare-notifications", new PrepareNotificationsStep())
    .step("send-notifications", new SendNotificationsStep())
    .step("charge-card", BillingContext.class, s -> s
        .using(new ChargeCardStep())
        .withCompensation(new RefundCompensation())
        .onError("charge-audit"))                           // a registered listener, by id

    // Operations and choices — declarative, so always a configurer
    .operation("notification-flow", Object.class, op -> op
        .run("prepare-notifications")
        .run("send-notifications"))
    .choice("tier-routing", Object.class, c -> c
        .branch("premium", b -> b
            .conditionExpression("customerTier == 'PREMIUM'")
            .run("notification-flow"))
        .onNoMatch(NoMatchBehavior.SILENT))

    // Conditions
    .condition("payment-method-valid", new PaymentMethodValidCondition())
    .condition("high-priority", (subscription, context) -> subscription.getPriority() > 8)
    .condition("business-hours", "T(java.time.LocalTime).now().hour >= 9")

    // Mappers
    .mapper("billing-from-activation", ActivationContext.class, BillingContext.class,
            new BillingFromActivationMapper())

    // Triggers — one verb per kind, each a configurer over that kind's def
    .manualTrigger("manual-cancel", t -> t
        .withName("Manual Cancellation")
        .preCondition("support-user-authorized"))
    .eventTrigger("payment-method-validated-event", t -> t
        .onEvent("PAYMENT_METHOD_VALIDATED")
        .filterExpression("#event.validation == 'CONFIRMED'"))
    .dataTrigger("data-priority-change", t -> t
        .conditionExpression("status == 'READY_FOR_ACTIVATION' && priority > 5"))

    // Listeners — one verb per category; an instance, or a configurer for metadata and withAsync
    .stateListener("subscription-activated", l -> l
        .using(new SubscriptionActivatedListener())
        .withAsync(AsyncRejectionPolicy.CALLER_RUNS))
    .transitionListener("audit-start", new TransitionStartListener())
    .actionListener("charge-audit", BillingContext.class, new ChargeAuditListener());
```

Every kind has a typed form taking the context class the component was written against, and the same registrations are available grouped under `forContext(Class<C>, scope -> ...)` (§4.2). A trigger or listener registered without one is registered against `Object` and attaches anywhere.

**Every position that accepts a host executable is contravariant in the entity type.** An `Action`, `Condition`, `Compensation`, the `BiPredicate` / `Predicate` condition forms, the three listener interfaces, and `StateResolver` / `StateApplier` are all taken as `? super T` — on these registrations, on the inline `ActionSequence` forms, and on `using(...)`, `withCompensation(...)` and the listener hooks. So one component written against an interface that several entity types share registers on a machine for each of them, as the same instance, rather than having to be a generic class. The context type stays invariant: widening it would make a component's declared context a claim about what it accepts rather than what it runs against, which is what the call-site check in §4.5.2 rests on. Nothing about how a call site is written changes, because an implicitly-typed lambda infers `T` from a `? super T` target exactly as it did from `T`.

#### 4.1.2 References

```java
def.state("draft", s -> s
        .onExit("subscription-deactivated"))                // a registered state listener
    .transition("draft-to-active", "draft", "active", ActivationContext.class, t -> t
        .preCondition("checkout-fulfilled")
        .preCondition("business-hours")
        .postCondition("milestones-activated")
        .addTrigger("payment-method-validated-event")       // a registered trigger
        .addTrigger("data-priority-change")
        .onStart("audit-start")                             // a registered transition listener
        .onComplete("audit-complete")
        .run("prepare-event-actor")
        .run("charge-card", "billing-from-activation")      // a registered action through a registered mapper
        .run("notification-flow"));
```

Attaching by id claims nothing, so the same trigger or listener may be attached any number of times (§2.2.8, §2.2.10): `addTrigger("manual-cancel")` on two transitions is one trigger reported with both, and the one-argument `onStart(id)` — beside the two-argument form that declares a listener in place — attaches a registered listener, or one the same owner declared at another of its hooks. The state-machine-wide hooks take the same one-argument form (`onAnyTransitionStart("audit-start")`). The build checks a registered trigger's or listener's context type against every owner it is attached to, exactly as it checks a typed step against its call sites (§4.5.2).

**Sharing across state machines** needs nothing beyond the language: a method that takes the definition and registers into it.

```java
final class SubscriptionComponents {
    static <T extends Subscription> void register(StateMachineDef<T> def) { /* the registrations of §4.1.1 */ }
}
```

#### 4.1.3 Component Registry

A `ComponentRegistry` is the dependency-injected form of the same thing, planned with the DI integration of §6: a bundle of components a container discovers, which populates exactly the registrations above — so a definition built from a registry, one built by hand and one loaded from YAML are indistinguishable once built. A registry attached to several state machines parents each one's root scope, which is how one set of components serves a whole process without being registered per machine.

```java
@Component
public class SubscriptionComponentRegistry implements ComponentRegistry {

    @RegisterStep("prepare-notifications")
    public PrepareNotificationsStep prepareNotificationsStep() {
        return new PrepareNotificationsStep();
    }

    @RegisterCondition("payment-method-valid")
    public PaymentMethodValidCondition paymentMethodValidCondition() {
        return new PaymentMethodValidCondition();
    }

    @RegisterListener("audit-start")
    public TransitionStartListener auditStartListener() {
        return new TransitionStartListener();
    }
}

@Configuration
@EnableTransflux
public class TransfluxConfig {

    @Bean
    public StateMachine<Subscription> subscriptionStateMachine(ComponentRegistry registry) {
        return Transflux.defineStateMachine(Subscription.class)
            .withComponentRegistry(registry)
            // ... the state machine, referencing the registry's components by id
            .build();
    }
}
```

The sketch covers the kinds a factory method can return as an object — steps, conditions, mappers, listeners. The def-shaped kinds (operations, choices, triggers) exist only inside their configurers, and how a registry contributes one is settled with the registry itself.

### 4.2 Core API Structure

#### 4.2.1 StateMachine Definition

Every DSL method takes a `String` id. Ids referenced from more than one call site are worth naming once, which a constant holder does without the framework needing to know about it:

```java
final class SubState {
    static final String TRIAL = "trial";
    static final String ACTIVE = "active";
    static final String SUSPENDED = "suspended";
    static final String CANCELLED = "cancelled";
    static final String EXPIRED = "expired";
}

final class SubTransition {
    static final String TRIAL_TO_ACTIVE = "trial-to-active";
    static final String ACTIVE_TO_SUSPENDED = "active-to-suspended";
    static final String ACTIVE_TO_EXPIRED = "active-to-expired";
    static final String SUSPENDED_TO_CANCELLED = "suspended-to-cancelled";
}

StateMachine<Subscription> subscriptionStateMachine = Transflux.defineStateMachine(Subscription.class)
    .withId("subscription-state-machine")
    .withName("Subscription State Machine")
    .withVersion("1.0.0")             // all three optional, and reported by the built StateMachine

    // State resolver — read the current state
    .withStateResolver(entity -> entity.getStatus().name())
    // Alternative: an instance of a dedicated StateResolver<Subscription>
    //   .withStateResolver(new SubscriptionStateResolver())

    // State applier — finalize the transition by writing the new state
    .withStateApplier((entity, newState) -> entity.setStatus(Status.valueOf(newState)))
    // Alternative: an instance of a dedicated StateApplier<Subscription>
    //   .withStateApplier(new SubscriptionStateApplier())

    // Define states.
    .state(SubState.TRIAL, s -> s.withDescription("Initial trial state"))
    .state(SubState.ACTIVE, s -> s.withDescription("Active subscription state"))
    .state(SubState.SUSPENDED, s -> s.withDescription("Suspended subscription state"))
    .state(SubState.CANCELLED)
    .state(SubState.EXPIRED)

    // Define transitions: id, source state, target state.
    .transition(SubTransition.TRIAL_TO_ACTIVE, SubState.TRIAL, SubState.ACTIVE, t -> {})
    .transition(SubTransition.ACTIVE_TO_SUSPENDED, SubState.ACTIVE, SubState.SUSPENDED, t -> {})
    .transition(SubTransition.ACTIVE_TO_EXPIRED, SubState.ACTIVE, SubState.EXPIRED, t -> {})
    .transition(SubTransition.SUSPENDED_TO_CANCELLED, SubState.SUSPENDED, SubState.CANCELLED, t -> {})

    .build();

// Host-side firing takes the same ids.
TransitionResult<Subscription> result = subscriptionStateMachine
    .entity(subscription)
    .transitionTo(SubState.ACTIVE, SubTransition.TRIAL_TO_ACTIVE, context);
```

Constants are a host convenience and nothing more — the framework sees strings either way. Use them where an id is referenced repeatedly; write the literal for one-off ids that have no other reference.

**Idiomatic enum-state resolver/applier pairing.** A host whose entity stores its state as an enum, and whose state ids are that enum's constant names, converts to and from `name()` directly:

```java
enum SubscriptionStatus { TRIAL, ACTIVE, SUSPENDED, CANCELLED, EXPIRED }

.withStateResolver(s -> s.getStatus().name())
.withStateApplier((s, newState) -> s.setStatus(SubscriptionStatus.valueOf(newState)))
```

Two lines, honest about where the bidirectional binding lives. The framework intentionally ships no sugar around this — the host owns how state is stored, and the explicit `.name()` / `.valueOf(...)` pair surfaces that decision at the SM definition rather than hiding it behind a typed shortcut.

#### 4.2.2 Advanced State Configuration

```java
StateMachine<Subscription> stateMachine = Transflux.defineStateMachine(Subscription.class)
    
    .state("active", s -> s
        .withName("Active")
        .withDescription("Active subscription state")

        // State entry/exit listeners. Every listener carries an id (§2.2.1); the body is
        // supplied as an instance, or through a configurer that also sets metadata.
        .onEntry("audit-activated", new SubscriptionActivatedListener())
        .onEntry("notify-activated", (subscription, context, change) ->
            notifier.send(subscription, "subscription-activated"))
        .onExit("audit-deactivated", l -> l
            .withDescription("Records departures from the active state")
            .using(new SubscriptionDeactivatedListener())))

    .build();
```

The lambda form shows the shape of the contract: a listener receives the entity, the firing context
as `Object`, and a `StateChange` carrying the phase, the state being entered or left, and a
read-only view of the responsible transition. See §2.2.10 for the observational-failure and
ordering rules.

### 4.3 Transition Configuration

A transition is declared on the state machine by its id, its source state and its target state,
and configured inside its own configurer. The context type is pre-bound in `transition(...)` as
shown; omitting it defaults the transition to `Object`, which accepts any firing context. Both
states must be declared, in either order, by the time the state machine is built.

```java
.transition("trial-to-active", "trial", "active", SubscriptionContext.class, t -> t
    .withName("trial-to-active")
    .withDescription("Activate trial subscription")

    // Operation
    .step("activate", new ActivateSubscriptionAction())

    // Pre/post conditions
    .preCondition("payment-method-valid", new PaymentMethodValidCondition())
    .preCondition("billing-ready", this::billingReady)
    .postCondition("features-activated", new SubscriptionFeaturesActivatedCondition())

    // Triggers
    .addManualTrigger("manual-activate")
    .addEventTrigger("payment-confirmed", "PAYMENT_CONFIRMED")
    .addEventTrigger("payment-filtered", et -> et
        .onEvent("PAYMENT_CONFIRMED")
        .filterExpression("#event.validation == 'CONFIRMED'"))
    .addDataTrigger("ready-for-activation", dt -> dt
        .condition("subscription-activated", new SubscriptionActivatedCondition()))

    // Listeners. Complete and error partition the outcomes — exactly one of the two
    // follows every start notification.
    .onStart("audit-start", new TransitionStartListener())
    .onComplete("audit-complete", new TransitionCompleteListener())
    .onError("audit-failure", el -> el
        .withDescription("Records activation failures and what was rolled back")
        .using(new TransitionErrorListener())))
```

Note that every inline condition form takes an id as well as the body — the id is the
condition's identity in diagnostics and for later reference.

### 4.4 Action Definitions

#### 4.4.1 Imperative Actions (Steps)

```java
public class ActivateSubscriptionAction
        implements Action<Subscription, SubscriptionContext> {
    
    @Inject private BillingService billingService;
    @Inject private SubscriptionFeaturesService featuresService;
    
    @Override
    public void execute(Subscription subscription, SubscriptionContext context,
                        ExecutingTransition<Subscription, SubscriptionContext> transition) {
        validateSubscription(subscription.getId());
        
        // Run other registered actions by id, through the transition view
        transition.run("prepare-billing-actor");
        transition.run("validate-payment-method");
        
        // Results flow back through the context (see §2.1.5)
        context.setActivatedAt(Instant.now());
        context.setSubscriptionStatus(SubscriptionStatus.ACTIVE);
    }
    
    // Optional: the rollback for this action's own effects (§2.2.11)
    @Override
    public Compensation<Subscription, SubscriptionContext> getCompensation(
            Subscription subscription, SubscriptionContext context) {
        return (s, ctx) -> billingService.reverse(ctx.getChargeId());
    }
}

// The action is declared inside the transition's configurer — there is no post-hoc
// "grab the transition and set its action" step.
.transition("trial-active", "trial", "active", SubscriptionContext.class, t -> t
    .step("activate-subscription", new ActivateSubscriptionAction()))
```

> Any action may be declared directly on a transition, in either form, so there is no wrapper to author when the unit of work is a single Java body — nor when it is several, since a transition's body is an ordered list. Asynchronous dispatch is a property of a *member position*, so `fork(...)` is available wherever a member is declared (§4.4.2), a transition's body included: the members after a forked one do not wait for it, and the transition commits without it.

#### 4.4.2 Declarative Actions (Operations)

```java
// t is the TransitionDef inside its transition(...) configurer (§4.3)
t.operation("complex-subscription-activation", c -> c
    .withDescription("Complex subscription activation with several members")

    // Reference an action registered elsewhere...
    .run("prepare-billing-actor")

    // ...or declare one inline, here
    .step("validate-payment-method", new ValidatePaymentMethodAction())

    // ...or declare a whole nested sequence in place, when a group belongs to one call
    // site. It is an operation like any other: it can carry its own compensation, and it
    // unwinds as a unit. Its inline ids are visible only inside its own subtree.
    .operation("provisioning", inner -> inner
        .run("allocate-quota")
        .step("open-tenant", new OpenTenantAction()))

    // Multi-branch choice. Every branch condition carries an id of its own, which is
    // what names it in diagnostics; only the expression form may have one derived for it.
    .choice("subscription-tier-routing", cs -> cs
        .branch("premium-tier", b -> b
            .condition("is-premium", new PremiumTierPredicate())
            .step("premium-tier-processing", new PremiumTierAction())
            .run("vip-notification")

            // A branch is a sequence, so it holds everything a container's member list
            // does — a choice included, which is how routing nests
            .choice("premium-region-routing", inner -> inner
                .branch("eu", ib -> ib
                    .condition("is-eu", r -> "EU".equals(r.getRegion()))
                    .run("eu-provisioning"))
                .defaultBranch(d -> d.run("global-provisioning"))))

        .branch("standard-tier", b -> b
            .condition("is-standard", s -> "STANDARD".equals(s.getTier()) && s.getPriority() >= 5)
            .step("standard-tier-processing", new StandardTierAction()))

        .branch("enterprise-customer", b -> b
            .condition("is-enterprise", new EnterpriseCustomerPredicate())
            .step("enterprise-processing", new EnterpriseProcessingAction())
            .run("account-manager-notification"))

        .defaultBranch(d -> d
            .step("basic-processing", new BasicProcessingAction())
            .run("standard-notification")))

    .step("finalize", new FinalizeSubscriptionActivationAction())

    // Error handling: a route for the one failure worth treating specially, and an
    // unconditional fallback for everything else. The fallback is a property of the
    // action, so where it sits in the chain does not matter.
    .forException(RecoverableException.class)
        .matching(e -> e.getCode() == RECOVERABLE_ERROR)
        .withCompensation(new RecoverableCompensation())
    .withCompensation(new GeneralCompensation())

    // Forked members: submitted where they are written, and not waited for. Everything
    // after them starts immediately.
    .fork("async-notifications")
    .fork("external-integrations", "notification-from-activation")

    // A forked member can be declared in place too, in any of the three authoring forms
    .forkStep("emit-activation-metric", (sub, ctx, view) -> metrics.record(sub.getId()))
    .forkOperation("reconciliation-sweep", sweep -> sweep
        .run("refresh-projections")
        .run("emit-audit-record")));
```

> **Fork semantics.** Forking puts the member at the position it is written: the work starts when execution reaches that point, and the members after it do not wait. That subsumes both anchors an earlier design proposed — "kick off at a join point" is a fork declared after the choice, and "kick off once the previous member succeeded" is a fork declared after it, since a member that throws never reaches the next one. What a forked member does with its context is §4.5.3; what happens to its outcome is §4.5.3.6.

> **One verb per authored form, forked and not.** A **reference** is `run(...)` or `fork(...)`, in three call shapes each — bare, through a registered mapper id, or through an inline `ContextMapper`. A **declaration** brings a new action into existence at that position and names the form it is being given: `step` / `operation` / `choice`, and `forkStep` / `forkOperation` / `forkChoice`. Each declaring verb comes in three context shapes (§4.5.2.3), the mapped one taking an inline `ContextMapper` or a registered mapper's id. The asynchronous half cannot be a flag or an overload of `fork`, because a `ContextMapper` and a `Consumer<...Def>` are both applicable to an implicitly-typed lambda — the same erasure wall that keeps the three declaring verbs from collapsing into one. The whole family is declared once, on `ActionSequence<T, C, SELF>` (§2.2.5.1), so every position that holds a member list carries all of it. A dispatch issued from *inside* an action's body is not a member declaration, and carries the reference half alone — both verbs, in all three call shapes, and `fork` additionally in a policy-bearing form at each (§4.5.2.1). The declaring verbs stay out of it: a body that wants a new action declares it in the sequence that encloses it, where every other declaration lives.

#### 4.4.3 Choices

Branches are evaluated in declaration order; the first branch whose condition matches is executed. If no branch matches and a `defaultBranch()` is defined, it runs; otherwise the choice's behavior is controlled by `.onNoMatch(NoMatchBehavior)` — `WARN` (default; log + complete without dispatching inner actions), `SILENT` (complete without logging, suiting the guard pattern), or `ERROR` (fail the transition). See §3.4.3 for full semantics — the Java API mirrors them exactly.

**A choice is an action, so it occupies every position an action can.** It is a member of any sequence — a container's list, a transition's body, and a branch or default branch of another choice — and it registers at state-machine level under `choice(id, Class<C>, cfg)`, or inside a `forContext(...)` block, sharing one id namespace with steps, operations and conditions. A registered choice is reached by `run(...)` like any other action, since a reference says nothing about the form of what it names.

**A choice owns the scope its branches bind against.** Anything a branch declares inline is visible from every branch of that choice — so a step several of them need is declared once — and from nowhere outside it. The choice's own bound action goes into the enclosing scope, so naming it by id works from either side.

### 4.5 Context Usage in Transitions

#### 4.5.1 Context Data

The host is responsible for populating context before execution and reading results after completion. There is no `.input(...)` builder method — all data flows through the context.

```java
// The transition declares its context type and operation inside its configurer
.transition("trial-active", "trial", "active", SubscriptionContext.class, t -> t
    .step("activate-subscription", new ActivateSubscriptionAction()))

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
        .transitionTo("active", context);

    // Read results from context after execution
    if (result.isSuccess()) {
        entity.setActivatedTimestamp(context.getActivatedAt());
        entity.setSubscriptionStatus(context.getSubscriptionResult().getStatus());
    }
}
```

#### 4.5.2 Nested Actions and Call-Site Context Mapping

A container's member is an action, in either authoring form; nesting is recursive and every position obeys the same call-site grammar. The same grammar is available from inside an action's body, so a Java body can dispatch other actions with the same forms a container declares them in.

Context mapping is a **call-site** property, not a callee property. A reusable child action declares only the context type it needs (e.g., `Action<T, PaymentCtx>`); each caller is responsible for projecting its own context onto that type. One child + N callers + N small mappers — not one child preconfigured with N caller-specific mappers. This keeps reusable components callee-agnostic and lets a single mapper bridge a parent / child pair across many call sites.

##### 4.5.2.1 Definition Surface

Every by-id reference takes the same `(actionId, [mapperSpec])` grammar, whether it appears as a member of a declarative container or inside an action's body, and it takes it for both verbs:

| Form | Container member | Inside an action body |
|---|---|---|
| Pass-through | `.run("id")` | `view.run("id")` |
| Mapper by registered id | `.run("id", "mapperId")` | `view.run("id", "mapperId")` |
| Inline `ContextMapper<C, ?>` | `.run("id", mapperInstance)` | `view.run("id", mapperInstance)` |
| Inline projection (a lambda, which *is* a `ContextMapper`) | `.run("id", parent -> child)` | `view.run("id", parent -> child)` |
| Forked, in each of the four shapes above | `.fork("id"[, mapperSpec])` | `view.fork("id"[, mapperSpec])` |
| Forked, with a policy declared at the position | `.fork("id"[, mapperSpec], policy)` | `view.fork("id"[, mapperSpec], policy)` |

The last two rows are one overload, not two. A read-only projection is a `ContextMapper` that leaves `mapFrom` alone, so a lambda supplies it directly; there is deliberately no separate `Function<C, ?>` overload, because the two carry the same descriptor and a lambda written at the call site would match both. The same reasoning already removed the `Function` form from mapper *registration*.

A body carries the reference half and nothing else — there is no `view.step(...)`, because a declaration belongs in the sequence that encloses the body. What it does carry, it carries in full, and two things about a forked dispatch cannot be settled by the build the way a declared member's are. The executor has to have been asked for: `withAsyncPool(...)` or `withAsyncExecutor(...)` on the definition, since no definition-time walk can see a fork written in Java (§3.8). And the callee's context boundary is checked at the call site rather than at build — a mapper that produces the wrong type, or none, fails the enclosing transition there, where the failure can still be named, rather than as a cast failure on a worker thread that nothing joins.

An inline *declaration* that names no context of its own (`.step("id", Action<T, C>)`, `.choice("id", configurer)`) defines a member typed against the container's `C` and runs pass-through, needing no boundary mapping. One that declares a context of its own takes the same mapper grammar the by-id forms do.

The `ContextMapper<P, N>` interface:

```java
public interface ContextMapper<P, N> {
    N mapTo(P parent);                                // parent → child (input)
    default void mapFrom(P parent, N child) {}        // child → parent (output); default no-op
}
```

`mapFrom` defaults to a no-op, so a "read-only" mapper is just a lambda supplying `mapTo` — the shape a `Function<P, N>` would have had, which is why no `Function` overload exists to compete with it.

##### 4.5.2.2 Mapper Registry

`MapperDef` is a first-class reusable component, registered on `StateMachineDef` alongside `step`, `condition`, and `operation`:

```java
sm.mapper("payment-from-order", OrderCtx.class, PaymentCtx.class, new OrderToPaymentMapper());
sm.mapper("payment-from-order", OrderCtx.class, PaymentCtx.class,
    order -> new PaymentCtx(order.total(), order.currency()));   // read-only: mapFrom stays no-op
sm.mapperDef("payment-from-order", OrderCtx.class, PaymentCtx.class, m ->   // + name / description
    m.withName("Payment from order").using(new OrderToPaymentMapper()));
```

There is one source form — an instance — plus a lambda-configurer registration for the cases that also want a name or a description. A registration must declare its source: a `mapperDef(...)` whose configurer never called `using(...)` fails the build, referenced or not, the same way an empty listener registration does. `ContextMapper` has a single abstract method, so a lambda *is* the instance form: it supplies `mapTo` and leaves `mapFrom` the default no-op, which is exactly the read-only case. There is no separate `Function<P, N>` registration overload; one would be indistinguishable from the instance form at the call site while meaning the same thing.

The mandatory `Class<P>` / `Class<N>` tokens let the build pipeline verify that the mapper's parent type is assignable from the caller's context and the mapper's child type matches the called member's required context. An inline `ContextMapper` at the call site cannot be reliably introspected at build time (generic erasure); its alignment is checked at each dispatch: a mapper producing `null` or the wrong type for a callee that declared a context fails the enclosing transition with `TransfluxContextException`, before the child starts. The same check runs for a registered mapper's result.

##### 4.5.2.3 Worked Example — One Child, N Callers

The idiomatic mapper id follows the pattern `<child>-from-<parent>` (e.g. `payment-from-order`, `billing-from-send`, `address-from-order`). The convention reads naturally at call sites — `.run("charge-card", "payment-from-order")` says "run charge-card, mapping from the order-side context" — and makes the boundary direction obvious to the reader. The worked example below uses this convention throughout.

A reusable `charge-card` action knows only `PaymentCtx`. Three different parent flows each have their own context shape and call into the same child via three different mappers, registered once on the state machine:

```java
sm.step("charge-card", PaymentCtx.class, new ChargeCardAction());

sm.mapper("payment-from-order",   OrderCtx.class,  PaymentCtx.class,
    o -> new PaymentCtx(o.total(), o.currency()));
sm.mapper("payment-from-refund",  RefundCtx.class, PaymentCtx.class,
    r -> new PaymentCtx(r.amount().negate(), r.currency()));
sm.mapper("payment-from-subscr",  SubscrCtx.class, PaymentCtx.class,
    s -> new PaymentCtx(s.nextCharge(), s.currency()));

// Three call sites in three different containers — the child has no knowledge of any of them:
orderOperation .run("charge-card", "payment-from-order");
refundOperation.run("charge-card", "payment-from-refund");
subscrOperation.run("charge-card", "payment-from-subscr");
```

One verb covers every reference, so the `(actionId, [mapperSpec])` grammar is the same whichever form the callee was authored in — the call sites above would be unchanged if `charge-card` were a declarative container instead of a step:

```java
sm.step("validate-address", AddressCtx.class, new ValidateAddressAction());
sm.mapper("address-from-order",  OrderCtx.class,  AddressCtx.class, OrderCtx::shippingAddress);

orderOperation.run("validate-address", "address-from-order");
```

##### 4.5.2.4 Build-Time Type Compatibility

For every by-id reference declared inside a container - including inside a choice's branches - the build pipeline checks:

- **Pass-through (no mapper):** the called member's required context type must be assignable from the caller's context type (`memberCtx.isAssignableFrom(callerCtx)`; `Object`-typed members always pass through). Otherwise a `TransfluxValidationException` is raised at `build()` time with a message pointing the user to supply a mapper.
- **Mapper by id:** the registered mapper's `parentType` must be assignable from the caller's context and its `childType` must be assignable to the called member's required context. Mismatches are rejected at build time.
- **Inline `ContextMapper<C, ?>` (a lambda included):** type-checked at the source where the lambda or class is declared (Java compile-time generics); runtime alignment is enforced at each dispatch, when the user-supplied value is invoked.

##### 4.5.2.5 Identity, Uniqueness, and Visibility

Component identifiers are unique **across the entire state machine** — uniqueness is a global property regardless of nesting depth. Two sibling containers cannot independently host an inline component with the same id under two different payloads; one must be renamed. The same instance or the same class registered under the same id in multiple places is treated idempotently and does not trigger a collision, provided each registration declares the same context type, none counting as `Object`: a second registration may not re-type the first.

**Visibility, however, is lexical.** A component inline-declared inside a container (`op.step("foo", new FooAction())` and friends) is reachable only from inside that container's lexical subtree — its own member references, its choice branches, and any `view.run("foo")` issued while that container is on the call stack. Sibling containers cannot resolve another's inline ids by reference: doing so raises a build-time error ("unknown action id in scope"). SM-level (root) registrations are reachable from every container via the parent-chain walk.

**A choice is a scope of its own**, nested inside whatever encloses it. A component declared inside one of its branches belongs to the choice rather than to the enclosing container, so *every* branch can reach it — a step several branches share is declared once, in whichever branch reads best — while nothing outside the choice can, including the enclosing container and its other members. Resolution from inside a branch still walks outwards, so a branch reaches the enclosing container's inline ids and the root's as before. The choice's *own* id is registered in the enclosing scope, not its own, so it can be named from either side: by a sibling of the choice, and by its own branches.

Two practical consequences:

- Promoting an inline nested registration to a top-level reusable component (or inlining a top-level one) is a payload-preserving refactor and produces no silent override. Global uniqueness blocks an accidental shadow at promotion time; lexical visibility blocks an accidental shadow at inlining time.
- "Inline" vs. "top-level" *is* a visibility scope — not merely a definition-site convenience. An inline component is intentionally private to its container. To expose a component to multiple callers, register it at SM level (or in the appropriate `forContext(...)` scope, which still lands at root).

External addressability — using a component id as the target of a YAML `ref:` descriptor, a registry lookup, or any other cross-state-machine handle — applies only to root-registered components. Inline container members have no externally-stable name; their id is meaningful only within their lexical scope.

At runtime, every declarative container owns a `Registry` whose parent is the enclosing scope's registry — the root for a container registered at state-machine level and for a transition's body, the enclosing container's or choice's scope for one declared in place. (A process-wide registry is planned to parent the root.) Resolution walks the chain local-first and is flattened at the end of state-machine construction so by-id lookups are a single map operation thereafter. The framework never relies on the parent-chain walk at runtime hot paths.

##### 4.5.2.6 Result Reporting

`TransitionResult.getExecutedPath()` and `getCompensatedPath()` (see §2.1.4) report nested invocations as **qualified paths** of the form `parent-id/child-id`, recursively for deeper nesting. Top-level entries appear under their bare id.

Every action records its own entry — its id, qualified by any enclosing action ids — **before** it runs, so the entry precedes any sub-entries it produces and an action that throws is still on the list. Every action also qualifies whatever it dispatches underneath itself, whichever form it was authored in, so the reported tree matches the tree that actually ran at every level rather than only at container boundaries. An action that dispatches nothing appears as a single entry, which keeps work driven entirely from a Java body observable in the result.

The qualified form preserves the structural distinction between an entry that ran at the top level and one that ran nested, even when the same id is reused across parents. The same encoding applies whether the action was dispatched by a container, referenced from inside another action's body via `view.run("id"[, mapperSpec])`, or attached to the transition itself. Because any action may declare a compensation, `getCompensatedPath()` carries the same kind of entries as `getExecutedPath()`.

##### 4.5.2.7 Failure and Compensation

A nested operation's failure surfaces as if it were a member failure of the enclosing parent at that position; the parent's error-handling and compensation rules apply. Mapper failures are attributed to the side of the boundary the parent owns:

- **`mapTo` failure** (parent → child) is a **parent failure**. The child never starts, no child member ids are recorded for this position, and no compensation is captured for it.
- **`mapFrom` failure** (child → parent) is also a **parent failure** — the boundary belongs to the parent — and the parent's error-handling kicks in as if the parent itself raised the writeback failure. The child's own completion stands: it ran, it is on the executed path, and any listener attached to it saw a completion rather than an error. Its **compensation still runs**, though, and for the ordinary reason: a compensation is captured before its action executes (§2.4 step 5) and the enclosing transition drains the whole stack on any failure, whatever had completed by then. "The child succeeded" and "the child is compensated" are not in tension — that is what rollback means.

The same attribution applies to a registered mapper and to an inline `ContextMapper` at the call site alike.

Compensations registered by a *synchronously-executed* nested operation are pushed onto the **enclosing parent's** LIFO compensation stack as the child runs. When the parent unwinds, child compensations interleave correctly with sibling steps — there is one stack per synchronous execution path, not one per nesting level.

A **forked** member is a different story: it owns its own LIFO compensation stack, independent of the enclosing transition's and of its siblings'. That stack accumulates compensations from the forked member and from anything nested below it; on failure, only it unwinds. This decouples the two rollbacks entirely — synchronous work failing while a branch is still running does not drain the branch's stack, and a branch's failure does not trigger the transition's compensation. Nothing about a branch reaches `TransitionResult`; see §4.5.3.6.

##### 4.5.2.8 Void-Context Caller

A transition with `Void` context (or a container typed `<T, Void>`) cannot pass-through to any member that declares a context of its own; a member registered under `Object.class` still passes through, and receives `null` (§2.2.7). A mapper from `Void` to a populated child context is expressible but rare — the mapper's `mapTo(null)` would have to fabricate the child shape from nothing. A registered mapper is admitted at such a call site only when its `parentType` is `Void` or `Object`, and anything else fails the build; an inline mapper is unchecked, as everywhere. The common case is to lift the caller's context type or to attach the child member to a sibling transition that carries a populated context.

#### 4.5.3 Forked Members and Their Context

A forked member introduces a concurrency boundary: it runs on a separate thread from the enclosing path and from its sibling forks. The host owns the context type, so Transflux does not impose a one-size-fits-all concurrency model on it. Two opt-in paths exist for hosts that want isolation; a documented shared-reference fallback covers the rest.

##### 4.5.3.1 ForkableContext (per-member isolation, same context type)

Hosts that want each forked member to run against an isolated copy of the existing context implement `ForkableContext`:

```java
public interface ForkableContext<C> {
    C fork();   // produce an isolated context for a forked member
}
```

Runtime rule: at the fork boundary, if the context implements `ForkableContext`, the branch receives `context.fork()`; otherwise it receives the same reference held by the enclosing path (see §4.5.3.3). The host owns the copy strategy — deep, shallow, copy-on-write, or anything else appropriate to the context shape. Transflux does not perform reflective deep-copy; the failure modes (lazy proxies, transient fields, singletons captured by reference, framework-managed handles) make implicit reflection a worse default than explicit host control. A `fork()` that returns `null` is rejected as a broken implementation.

A convenience adapter implementing `fork()` through a JSON round-trip is **not shipped**: it needs a JSON databinder that is not currently a dependency, and a host that wants one writes it in a few lines against the mapper it already configures.

##### 4.5.3.2 ContextMapper on a forked call site (full isolation, different context type)

When a forked member needs a distinctly-shaped context — e.g., a notification subflow that needs only an order id and a customer email — declare a mapper at the call site using the same call-site grammar as any other reference (§4.5.2.1). The mapper is supplied positionally, exactly as for synchronous members:

```java
.fork("send-receipt", "notification-from-order")    // by registered mapper id

// or with an inline projection at the call site:
.fork("send-receipt", parent ->
    new AsyncNotificationCtx(parent.getOrderId(), parent.getCustomerId()))
```

`mapTo` runs on the enclosing thread before the member is submitted; the constructed context is what the branch sees, and a mapper therefore takes precedence over `ForkableContext` — there is nothing left to fork.

`mapFrom` is **not applied** at a forked call site, because a forked outcome does not merge back into the parent context. This is structural rather than policed: the branch runs with the mapping already done and no mapper to write back with, so the write-back call is unreachable for it. Supplying a mapper that overrides `mapFrom` is therefore accepted and simply has no effect there. The framework deliberately does not reject it — it cannot distinguish a mapper that overrides `mapFrom` from a proxy that merely appears to (a JDK dynamic proxy declares every interface method, defaults included), so the check would fail builds over the host container's implementation details. Outcomes follow §4.5.3.6.

##### 4.5.3.3 Shared-reference fallback and definition-time warning

When neither `ForkableContext` nor a context mapper is declared, the branch receives the same context reference as the enclosing path. This is a legitimate design choice for members that only read from context — common cases include post-action notifications, audit logging, and any work fired off after the last synchronous member, where the context is effectively read-only by then.

To prevent silent sharing, the framework emits a definition-time **warning** (not an error), **per forked member** rather than per container — a container may mix a mapped member with an unmapped one, and only the unmapped one is sharing. It is not emitted when the member declares a mapper, when the declared context type is `Void`, or when that type implements `ForkableContext`. It is not emitted for a fork issued from inside an action's body either, for the reason every definition-time facility misses those: nothing in the definition records that the fork exists. The rule it warns about still applies there — that call site shares the enclosing reference unless it maps or the context forks itself — and the host is simply on its own about it.

The warning names the action, the position the member was written at, and — separately — **the position that declared the context being shared**, which is not always the same place. A member that declares no context of its own is handed the enclosing one, and so is every branch of a choice, so the position a host has to change may be several levels out: an action attached to a transition takes the transition's context, and the fix is `transition(id, source, target, Class<C>, ...)`. Naming the position that holds the member instead would point at somewhere with no context to declare.

Where that context type is `Object` — which is what a transition declared without one has — the framework cannot establish anything about the runtime object, and says so: the warning fires with a distinct message reporting that forkability could not be checked, and naming the three ways out (declare a context on the position that owns it, implement `ForkableContext`, or map at the call site). Hosts that intend to share — explicitly — suppress either message through standard logging configuration.

##### 4.5.3.4 Memory-Model Guarantees

Transflux guarantees, at the fork boundary:

- All writes the enclosing path performed *before* submission are visible to the branch. This rests on the executor submission's happens-before edge, and it is the only synchronisation in the design.
- Writes performed by the enclosing path *after* submission are **not** synchronized with the branch and may or may not be observed.
- Symmetrically, writes the branch performs are not synchronized back into the enclosing path.

The second and third points hold by construction rather than by promise: nothing mutable is shared across the boundary after submission. Everything the branch reads — its context, its own execution view, the bound records it runs — is either produced before the submission or immutable for the life of the state machine.

These guarantees apply to both shared-reference and `ForkableContext` modes. In `ForkableContext` mode the second and third points are moot for the branch's own writes, since each side mutates a distinct object — but the host's `fork()` implementation is responsible for the snapshot itself being self-consistent (e.g., not capturing references to mutable nested objects it expects to remain stable). The **entity** is shared in every mode; see §2.1.2.

##### 4.5.3.5 Sibling Forked Members

Several forked members in the same container follow the same rules pairwise: each independently obtains its context per §4.5.3.1 / §4.5.3.2 / §4.5.3.3. `ForkableContext.fork()` is invoked once per forked member, not once per container.

##### 4.5.3.6 Outcomes

A forked member is **fire-and-forget**, and every consequence below follows from that one word.

- `TransitionResult` is unchanged by forking. A forked member appears on neither `getExecutedPath()` nor `getCompensatedPath()`: those report what the transition itself ran and rolled back, and the branch is not that.
- A branch failure never reaches the caller and never triggers the transition's compensation. It drains that branch's own stack (§4.5.2.7) and is logged.
- The transition does not wait for a branch, at any point. It may complete, apply its state and return while branches are still running.
- **Submission is the commitment point.** Once a member has been handed to the executor it runs, whatever the enclosing path does next — there is no cancellation, and no timeout.
- Hosts observe branches through the action-listener SPI (§2.2.10): a listener attached to the action fires on a forked invocation exactly as on a synchronous one, with the member's qualified path and the branch's own context.

Two failures at the boundary are told apart deliberately. Host code that cannot *produce* the branch's context — a throwing `mapTo` or `fork()` — fails the enclosing transition, per §4.5.2.7's attribution rule; nothing has been submitted, and a definition that cannot build its context is broken rather than merely busy. A *refused submission* — a full queue, or a state machine that has been closed — is an operational condition, and what happens is the declared policy: lose that member with a warning (`DROP`, the default), fail the transition (`FAIL`), wait for a slot (`BLOCK`), or run it on the submitting thread (`CALLER_RUNS`).

The policy is a property of the submission, and three parties can speak for one: the position that forks it (`fork(id, policy)`, for a by-id reference at a member position or in an action's body alike), the action's own def, and the state machine's default, most specific first. Some work knows how it must be treated and declares it on itself; some does not — a notification step is droppable in one flow and not in another — and only the flow that forks it can say. An inline forked declaration is its own position and declares on its own def. So `fork("audit")`, `fork("metrics", DROP)` and `fork("cache", FAIL)` in one sequence answer three different ways. Two of the four need a word about what they do not change. `BLOCK` waits inside the executor's own rejection handler, and so is available only on a pool the framework built — against a host-supplied executor it fails the build rather than degrading, or, for the one declaration site the build cannot see, fails at the submission naming the action it was asked for. It cannot wait where no wait would help: on a thread already running a branch of this state machine, which would be a worker waiting for a slot only it could free, and against a closed executor — the first runs inline, the second fails. The branch case is decided first, so a branch still finishing after `close()` runs its work inline rather than failing. `CALLER_RUNS` changes the thread and nothing else: the member is still its own branch, with its own rollback stack, its failure still logged rather than returned, still on neither reported path, and the thread running it is still barred from driving the state machine. It is the only policy that still runs the work after `close()` from any thread; `BLOCK` does so only from a branch thread, as above.

### 4.6 Writing an Action

#### 4.6.1 The Contract

Actions are entity-aware and receive `(entity, context, transition)`. The optional `getCompensation` is captured before `execute` runs and receives the same references it will see:

```java
public class PrepareEventActorAction
        implements Action<Subscription, ActivationContext> {
    
    @Inject private EventActorService eventActorService;
    
    @Override
    public void execute(Subscription subscription, ActivationContext context,
                        ExecutingTransition<Subscription, ActivationContext> transition) {
        EventActor eventActor = eventActorService.createEventActor(
            subscription.getId(), "SYSTEM");
        context.setEventActor(eventActor);
    }
    
    @Override
    public Compensation<Subscription, ActivationContext> getCompensation(
            Subscription subscription, ActivationContext context) {
        return (entity, ctx) -> eventActorService.removeEventActor(
            ctx.getEventActor().getId());
    }
}

public class ValidatePrerequisitesAction
        implements Action<Subscription, ActivationContext> {
    
    @Override
    public void execute(Subscription subscription, ActivationContext context,
                        ExecutingTransition<Subscription, ActivationContext> transition) {
        boolean isValid = performValidation(subscription, context);
        context.setValidationResult(isValid);
        context.setValidatedAt(Instant.now());
    }
}
```

#### 4.6.2 Configuring an Action

```java
operation("complex-operation", c -> c
    // A compensation declared on the def; a lambda is a compensation too
    .step("validate-prerequisites", s -> s
        .using(new ValidatePrerequisitesAction())
        .withCompensation(new ValidationCompensation()))

    // The same, plus rollbacks for the failures worth telling apart
    .step("charge-card", s -> s
        .using(new ChargeCardAction())
        .withCompensation(new RefundCompensation())
        .forException(GatewayTimeoutException.class)
            .withCompensation(new ReconcileLaterCompensation())
        .forException(CardDeclinedException.class)
            .matching(CardDeclinedException::isPermanent)
            .withCompensation(new BlacklistCardCompensation())));
```

Which reads: normally refund, on a gateway timeout reconcile later instead, and on a permanent decline blacklist the card. Each `forException(...)` opens a route that has to be closed with `withCompensation(...)`; opening one and dropping the result fails the build rather than quietly declaring nothing. Routes are tried in declaration order and only one of them runs, per §2.2.11.

The declaration is available on any action's def, in either authoring form, and is what a declarative container uses — it has no Java body to override `getCompensation` on:

```java
operation("charge-and-ship", c -> c
    .withCompensation((order, ctx) -> auditService.recordRollback(order.getId()))
    .run("charge-card")
    .run("reserve-stock"));
```

### 4.7 Conditions and Validators

#### 4.7.1 Condition Definition

```java
@Component
public class CheckoutFulfilledCondition implements Condition<Subscription, Object> {
    
    @Inject private CheckoutService checkoutService;
    
    @Override
    public boolean test(Subscription subscription, Object context, Transition transition) {
        return checkoutService.isCheckoutFulfilled(subscription.getCheckoutUid());
    }
}

@Component
public class MilestonesActivatedCondition implements Condition<Subscription, Object> {
    
    @Inject private MilestonesService milestonesService;
    
    @Override
    public boolean test(Subscription subscription, Object context, Transition transition) {
        return milestonesService.getMilestones(subscription.getId())
            .stream()
            .allMatch(m -> m.getState() == MilestoneState.ACTIVE);
    }
}

// Everything below is written inside a transition's configurer (§4.3); t is the TransitionDef.

// Reference to a registered condition
t.preCondition("checkout-fulfilled");

// Instance — a full Condition<T, C>
t.postCondition("milestones-activated", new MilestonesActivatedCondition());

// Predicate — a lambda, or a class implementing Predicate<T> / BiPredicate<T, C>
t.preCondition("checkout-fulfilled-inline",
        s -> checkoutService.isCheckoutFulfilled(s.getCheckoutUid()))
    .preCondition("payment-method-valid", new PaymentMethodValidPredicate());

// Expression — SpEL, with the entity as the evaluation root. Under an explicit id,
// or with the id derived (§2.2.1)
t.preCondition("has-payment-method", "paymentMethodId != null")
    .preConditionExpression("paymentMethodId != null");
```

The authoring forms above (reference, full `Condition<T>` instance, `BiPredicate<T, C>` — or its convenience `Predicate<T>` overload — and SpEL expression) are the Java-reachable arms of the Condition Descriptor (§3.6.1). YAML reaches one the Java DSL does not: `class:`, which the factory resolves into the instance form before registration, so the two DSLs describe the same descriptor grammar with the class name serving as YAML's way of naming an object it cannot hold.

**One `condition(...)` name everywhere.** Every registration form — instance, `BiPredicate`, `Predicate`, expression — is spelled `condition(...)` on both `StateMachineDef` and `ContextScope`, typed and untyped alike. The forms are told apart by how many parameters they take, which is what lets an implicitly-typed lambda select one: `Condition` takes three, `BiPredicate` two, `Predicate` one, and an expression is a `String`. The typed family briefly carried the distinguishing names `conditionPredicate` / `conditionExpression`, on the stated grounds that erasure made them indistinguishable from a class-taking `condition(...)`; that reasoning was wrong — the two differed in arity and could never both be applicable — and the class form has since gone in any case. The single-argument `conditionExpression(String)` / `preConditionExpression(String)` on `TransitionDef`, `BranchDef` and the triggers do keep their own names, because there `condition(String)` is genuinely taken by the reference-by-id form.

### 4.8 Listeners and Hooks

#### 4.8.1 Listener Definition

A state listener is a pure functional contract over `(entity, context, change)`. The context is
typed `Object` — see §2.2.10 — and the `StateChange` says which state, which phase, and which
transition.

```java
@Component
public class SubscriptionActivatedListener
        implements StateListener<Subscription> {

    @Inject private AuditService auditService;

    @Override
    public void onState(Subscription subscription, Object context, StateChange change) {
        auditService.logStateChange(subscription,
                                    change.phase(),
                                    change.state().getId(),
                                    change.transition().getId());
    }
}
```

A transition listener is the same shape, but its context is typed — a transition declares exactly
one context type. The `TransitionExecution` carries the phase, the transition, the trigger that
fired it, and (at the two terminal hooks) the outcome.

```java
@Component
public class TransitionAuditListener
        implements TransitionListener<Subscription, ActivationContext> {

    @Inject private AuditService auditService;

    @Override
    public void onTransition(Subscription subscription, ActivationContext context,
                             TransitionExecution<Subscription> execution) {
        Trigger firedBy = execution.firedBy();
        auditService.log(subscription,
                         execution.phase(),
                         execution.transition().getId(),
                         firedBy == null ? "direct" : firedBy.getId(),
                         context.getActor());
    }
}
```

The same class registered at `onComplete` and at `onError` tells the two apart through
`execution.phase()`; a listener registered at only one hook does not need to, since complete and
error partition the outcomes.

An action listener is the same shape again, over the context the action itself declares. The
`ActionExecution` carries the phase, the qualified path of this invocation, the form the action was
authored in, the transition, the failure at the error hook, and — at both terminal hooks — how long
the body ran.

```java
@Component
public class ChargeAuditListener
        implements ActionListener<Subscription, BillingContext> {

    @Inject private AuditService auditService;

    @Override
    public void onAction(Subscription subscription, BillingContext context,
                         ActionExecution execution) {
        auditService.record(subscription,
                            execution.phase(),
                            execution.path(),          // e.g. activate/charge-card
                            execution.kind(),          // STEP or OPERATION
                            context.getPaymentMethodId(),
                            execution.error());        // null outside the error hook
    }
}
```

#### 4.8.2 Listener Registration

```java
// Transition listeners — attached inside the transition's configurer (see §4.3)
.transition("trial-to-active", "trial", "active", ActivationContext.class, t -> t
    .onStart("activation-start", new ActivationStartListener())
    .onComplete("activation-complete", new ActivationCompleteListener())
    .onError("activation-failure", new ActivationFailureListener()))

// State entry/exit listeners (attached to the state — see §4.2.2)

// Global transition listeners — registered on the definition. They fire for every transition,
// after that transition's own listeners, and take an Object context because they span
// transitions with differing context types.
stateMachineDef
    .onAnyTransitionStart("audit-any-start", new AnyTransitionAuditListener())
    .onAnyTransitionComplete("audit-any-complete", new AnyTransitionAuditListener())
    .onAnyTransitionError("audit-any-error", new AnyTransitionAuditListener());
// AnyTransitionAuditListener implements TransitionListener<Subscription, Object>; the
// TransitionAuditListener of §4.8.1 is typed to ActivationContext and would not compile here.

// Global state listeners — registered on the definition, alongside the states themselves.
// They fire for every state, after that state's own listeners.
stateMachineDef
    .onAnyStateEntry("audit-any-entry", new StateAuditListener())
    .onAnyStateExit("audit-any-exit", new StateAuditListener());

// Action listeners — attached to the action's own definition, wherever that definition is
// written: an SM-level registration, a member of a transition's body, or an inline member
// of a container. The listener then fires at every invocation of that action, from every call site.
stateMachineDef
    .step("charge-card", BillingContext.class, s -> s
        .using(new ChargeCardAction())
        .onError("charge-audit", new ChargeAuditListener()));

// Global action listeners — they fire for every action at every nesting depth, after that
// action's own listeners, and take an Object context because they span actions declared
// against differing context types.
stateMachineDef
    .onAnyActionStart("audit-any-action-start", new ActionAuditListener())
    .onAnyActionComplete("audit-any-action-complete", new ActionAuditListener())
    .onAnyActionError("audit-any-action-error", new ActionAuditListener());

// Turning globals off, per owner and per category (§2.2.10). The owner's own listeners still
// receive everything, and the disable is not inherited by anything the owner dispatches.
stateMachineDef
    .step("capture-payment", BillingContext.class, s -> s
        .using(new CapturePaymentAction())
        .disableGlobalListener("audit-any-action-start")
        .onStart("capture-redacted", new RedactedCaptureListener()))
    .state("active", st -> st
        .disableAllGlobalListeners())
    .transition("cancel", "active", "cancelled", CancelContext.class, t -> t
        .disableGlobalListener("audit-any-start"));
```

Each hook accepts a listener instance or a
configurer (`Consumer<StateListenerDef<T>>` /
`Consumer<TransitionListenerDef<T, C>>` / `Consumer<ActionListenerDef<T, C>>`) for the cases that
also want a name or description. Listener ids are unique across the state machine, and all three
categories share one namespace.

Note the asymmetry the action category forces on the shorthand registrations: an action declared
through `step(id, Action)` has no def behind it to hold an attachment, so a
listener needs the configurer form. That holds at every position — the state-machine registry, a
transition, a container member, and a choice's branch member — and is the same
shorthand-versus-configurer split the rest of the DSL already makes for names and descriptions.

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
    .transitionTo("active", context);

// Selecting a specific named transition (when multiple transitions
// share source/target — e.g., different triggers)
TransitionResult<Subscription> result = stateMachine
    .entity(subscription)
    .transitionTo("active", "trial-to-active");
```

#### 4.9.2 Event and Trigger Processing

```java
// Process an event — eventData is the payload exposed to event-trigger filters;
// the firing context (if any) is a separate, optional argument.
ProcessResult<Subscription> outcome = stateMachine
    .entity(subscription)
    .processEvent("CHECKOUT_FULFILLED", eventData);

if (outcome.fired()) {
    TransitionResult<Subscription> result = outcome.result().orElseThrow();
    log.info("Event fired trigger {} -> {}", outcome.firedTriggerId(), result.isSuccess());
} else {
    log.info("No event trigger matched");
}

// Process a host-driven data change — re-evaluates the eligible data triggers and fires
// the first whose gate holds. Also returns a ProcessResult.
ProcessResult<Subscription> dataOutcome = stateMachine
    .entity(subscription)
    .processDataChange();
```

#### 4.9.3 Replacing the Definition

```java
// One handle, held for the life of the process; what is behind it can change.
StateMachine<Subscription> stateMachine = subscriptionDefinition(rules).build();
stateMachine.generation();                       // 1

// Later - an admin endpoint, a config change, a reloaded YAML document (§2.6).
StateMachineDef<Subscription> updated = subscriptionDefinition(newRules);
long generation = stateMachine.replaceDefinition(updated);   // 2

// A transition already running finishes on the topology it started under; everything that
// starts from here runs the new one. A rejected replacement changes nothing at all:
try {
    stateMachine.replaceDefinition(brokenDefinition);
} catch (TransfluxValidationException e) {
    // generation() is still 2, and the state machine still runs `updated`
}
```

See §2.7 for what a replacement guarantees, what it refuses, and what it deliberately leaves alone.

### 4.10 Configuration and Integration

#### 4.10.1 Framework Configuration

```java
TransfluxConfiguration config = TransfluxConfiguration.builder()
    .metricsEnabled(true)
    .flowLabel("subscription-management")
    .build();

Transflux transflux = Transflux.create(config);
```

> **Async settings are not here.** Where forked members run belongs to the state machine, because the state machine is the thing that can own a pool's lifecycle: it is declared with `withAsyncExecutor(...)` or `withAsyncPool(threads, queueCapacity[, threadFactory])` on `StateMachineDef`, and released by `StateMachine.close()`, which shuts down a pool the framework built and leaves a host-supplied executor alone. `withAsyncRejectionPolicy(...)` sits beside them. A process-wide `TransfluxConfiguration` could carry defaults for these one day, but it cannot carry the pool itself without taking over a lifetime it does not own.

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
        return Transflux.defineStateMachine(Subscription.class)
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
            .transitionTo("active", context);
            
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
- Pluggable `MetricsCollector` SPI exposing success/failure counts, timing histograms, per-action timings.
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
- **Manual wiring** via the `ComponentRegistry` SPI (see §4.1.3) — for environments without a DI framework, or for embedding Transflux in non-Spring applications.

Additional DI frameworks (Guice, CDI / Weld, Dagger 2) are deferred to a Post-1.0 theme (see §7.2). The framework-agnostic abstraction proposed in earlier drafts is part of that same Post-1.0 theme; in 1.0, Spring and manual wiring share a minimal `ComponentFactory` SPI without a multi-framework abstraction layer.

### 6.2 Class Instance Factory System

A minimal component factory SPI, widening `ComponentFactory` (`org.transflux.core`) — the YAML loader's instantiation path (§3.1.5) — rather than adding a second one, that:
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

The 1.0 release is the **smallest useful core** of Transflux: a programmatic and YAML DSL for defining state machines with conditions, actions, triggers (manual, event, host-driven data), listeners, and compensations — running in a single JVM, against host-owned in-memory entities.

In-scope capabilities:

- **Core abstractions** — `StateMachine`, `State`, `Transition`, `Action` (imperative "step" and declarative "operation" forms), `Context`, `Condition` (Pre/Post), `Trigger` (Manual, Event, host-driven Data), `Listener` (state entry/exit, transition start/complete/error, action start/complete/error), `Compensation`.
- **State resolver + applier** — host-supplied instances; a lambda is the idiomatic form.
- **Both DSLs at parity** — programmatic builder and YAML DSL cover the same surface area, including listener types and condition descriptor forms.
- **Component library + registry** — reusable component definitions with imports (YAML) and a Java-side `ComponentRegistry`.
- **Condition descriptor grammar** — instance, predicate, expression, reference, plus YAML's `class:`.
- **Choices** — sequential branch evaluation with default fallback.
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
