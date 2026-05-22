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
- [x] `OperationDef` base interface plus `SimpleOperationDef` and `CompositeOperationDef` for fluent operation definition.
- [x] `Operation<T, C>` runtime interface — `execute(entity, context, transition)` returns `void`; pure functional contract, identity-free at runtime; results flow through the context (requirements §2.1.5).
- [x] `SimpleOperationDef` def-side anchor (no runtime `SimpleOperation` type — users implement `Operation<T, C>` directly).
- [x] Operation lifecycle and execution order within a transition.
- [x] Operation results documented as context-flowing (no `.input(...)` API; no domain return value).

### 2.2 Steps
- [x] `Step<T, C>` interface — entity-aware, receives `(entity, context, transition)`. Pure functional contract; reusable across operations under different ids.
- [x] `CompositeOperationDef` builds an internal framework-owned `Operation<T, C>` that iterates the declared step list. Single-step composites cover the "step as an operation" case without a special elevation mechanism.
- [x] Sequential step execution within composite operations.
- [x] Step-level error handling primitives (full compensation engine lands in Phase 4).
- [x] `Transition.step("id")` framework-executed dispatch from inside an operation, with the same step-id recording and compensation registration as composite-driven steps.

### 2.3 Multi-Branch Conditional Operations
- [x] Conditional step type within composite operations.
- [x] Sequential branch evaluation, first-match-wins semantics.
- [x] `default` fallback branch.
- [x] Configurable `NoMatchBehavior` (WARN — default; SILENT; ERROR) when no branch matches and no default is defined.

### 2.4 Condition System
- [x] **SpEL Integration**
  - [x] Spring Expression Language 6.2.x dependency. (Note: the project baseline was raised to Java 17 mid-Phase-2, so the SpEL Java 11 compatibility concern is moot.)
  - [x] SpEL expression evaluator with entity and context variable binding.
  - [x] Expression caching.

- [x] **Condition Framework**
  - [x] `Condition<T, C>` interface — pure functional contract; ids live on the def side (`StateMachineDef.condition(id, ...)` registry and `ConditionDescriptor`).
  - [x] `Predicate<T>`-style lightweight conditions.
  - [x] Pre/Post condition wiring on transitions.
  - [x] **Condition Descriptor** — the five-form grammar from requirements §3.6.1:
    - [x] Reference (by id).
    - [x] Instance-based (pre-built `Condition<T, C>` instance under an explicit id; Java DSL only).
    - [x] Class-based (`Condition<T, C>` implementation).
    - [x] Predicate-based.
    - [x] Expression-based (SpEL).
  - [x] Auto-id derivation for inline expression-based conditions only.

### 2.5 Reentrancy Guard
- [x] Runtime detection of reentrant transition attempts on the same `StateMachine<T>` instance for the same entity.
- [x] Throw `TransfluxReentrancyException` with diagnostic context.
- [x] Permit transitions on *different* entities from within an executing transition.
- [x] Spock coverage for the guard.

### 2.6 Specifications
- [x] Composite operation specs with step sequencing and parameterized data tables.
- [x] Multi-branch conditional specs (each branch matched, no-match-with-default, no-match-no-default).
- [x] Condition Descriptor specs for each form, including auto-id derivation.
- [x] Reentrancy guard specs.

---

## Phase 2.5: Nested Operations & Call-Site Context Mapping (v0.2.5)
*Target: First-class operation-as-composite-member with caller-side `ContextMapper`. See `requirements.md` §4.5.2.*

### 2.5.0 Per-transition context refactor (prerequisite, completed)
- [x] Drop the `<C>` generic from `StateMachine`, `StateMachineDef`, `StateMachineImpl`, `StateDef`, and the nested `EntityBinding`. Context now lives at the transition level: `TransitionDef<T, C>` declares its own `C`, defaulting to `Object.class` when neither `transitionsTo(target, id, Class<C>)` nor `usingContext(Class<C>)` is called. `Void.class` becomes a sentinel that rejects any non-null firing context. SM-level component registries take wildcard `Step<T, ?>` / `Condition<T, ?>` plus typed overloads `step(id, Class<C>, Step<T, C>)`, mirroring `useContext(...)` tagging. `StateMachineDef.forContextType(Class<C>)` deleted outright. Heterogeneous transitions on a single SM are now expressible.

### 2.5.1 Reusable Component Types (def-side anchors)
- [x] `OperationDef` gains `Class<C> contextType()` accessor (default `Object.class`; overridden by composite to return `declaredContextType`).
- [x] Introduce `StepDef<T, C>` def-side anchor with id, optional name/description, mandatory `Class<C> contextType()`, `using(Step|Class)` source forms, and `buildBoundStep()`. (Pulled forward from the original Phase 3.7 plan.)
- [x] Introduce `MapperDef<P, N>` def-side anchor with id, mandatory `parentType` / `childType`, three source forms (`ContextMapper` instance, `ContextMapper` class, `Function<P, N>` wrapped with default no-op `mapFrom`), and `buildMapper()`.
- [x] Promote `ContextMapper<P, N>` to a first-class reusable component: `default void mapFrom(P, N) {}` so "read-only" mappers are a one-method override.

### 2.5.2 SM-Level Registries
- [x] `StateMachineDef.mapper(id, parentType, childType, ContextMapper)` — instance form.
- [x] `StateMachineDef.mapper(id, parentType, childType, Class<? extends ContextMapper>)` — class form.
- [x] `StateMachineDef.mapper(id, parentType, childType, Function<P, N>)` — read-only sugar (wraps with default no-op `mapFrom`).
- [x] `StateMachineDef.operation(id, contextType, Operation<T, C>)` and `operation(id, contextType, Class)` — SM-level registration for callee-agnostic reusable operations (mirrors `step(...)`).
- [x] `ContextScope.operation(...)` — same registrations inside `useContext(...)` blocks.
- [x] `StateMachineDefImpl.getMapperDef(id)` framework-internal accessor used at dispatch time.

### 2.5.3 Call-Site Grammar (uniform across composite members and `TransitionView`)
Every by-id member accepts the same five forms:
- [x] Pass-through: `.step("id")` / `.operation("id")` (requires component context assignable from caller context).
- [x] By registered mapper id: `.step("id", "mapperId")` / `.operation("id", "mapperId")`.
- [x] Inline `Function<C, ?>`: `.step("id", parent -> child)` / `.operation("id", parent -> child)`.
- [x] Inline `ContextMapper<C, ?>`: `.step("id", mapperInstance)` / `.operation("id", mapperInstance)`.
- [x] (Class form for mappers is registry-only — Java erasure collides with inline-class step/operation registration; users go through `smd.mapper(id, P, N, Class)` + by-id ref.)
- [x] Same five forms on `TransitionView.step(...)` and `TransitionView.operation(...)`.
- [x] Inline-registered composite members (`step(id, Step<T, C>)`, `operation(id, Operation<T, C>)`, and class variants) are typed against the composite's own `C` and always run pass-through; no mapper slot.

### 2.5.4 Runtime Execution
- [x] `MapperRef` sealed type captures the unresolved call-site choice (`PassThrough`, `ById`, `InlineFunction`, `InlineMapper`).
- [x] `CompositeOperationDefImpl.build(stateMachine)` resolves each `MapperRef` to a runtime `ResolvedContextMapping` via the SM's mapper registry; inline `Function` wrapped with default no-op `mapFrom`.
- [x] Unified `dispatchMember` path on the composite executor: pass-through routes through `StateMachineImpl.runBoundStep(...)` or `operation.execute(...)` directly; mapped routes through `TransitionView.runChildStep(...)` / `runChildOperation(...)`.
- [x] Per-execution context-override stack on `TransitionView`: `getContext()` returns the active child context inside a mapped section so `runBoundStep` sees the correct shape.
- [x] Qualified-path tracking — child member ids emitted as `parent-id/child-id` (recursively) into `executedStepIds` / `compensatedStepIds`. Same encoding whether the call originated from a composite member or imperatively via `view.operation(...)`.
- [x] Mapper failure attribution: `mapTo` failure → parent failure; `mapFrom` failure → **parent failure** (boundary belongs to the parent; child completed and its compensations are not invoked).
- [ ] Pre-/post-conditions declared at a nested-op **call site** evaluate against the parent's context; conditions declared **inside** the nested op's own def evaluate against the child's context. (Condition wiring on call-site members lands once Phase 2's condition-binding code grows a call-site hook.)

### 2.5.5 Build-Time Type Compatibility
- [x] Walk every composite's `ActionRef` list; for each by-id member:
  - Pass-through: require `componentCtx.isAssignableFrom(callerCtx)` (`Object`-typed always passes).
  - Mapper by id: require `mapperParent.isAssignableFrom(callerCtx)` and `componentCtx.isAssignableFrom(mapperChild)`.
  - Inline `Function` / `ContextMapper`: deferred to first dispatch (generic erasure prevents reliable build-time introspection).
- [x] Error messages name the offending member id, the caller's context class, and the component's required context class; mapper errors name the mapper id.

### 2.5.6 Deletions
- [x] `NestedOperationDef` and `NestedOperationDefImpl` removed.
- [x] `CompositeOperationDef.operation(id, op, Consumer<NestedOperationDef>)` overloads removed (callee-side configurer).
- [x] `ActionRef.OperationInline{Instance,Class}Configured` variants removed.

### 2.5.7 Specifications
- [x] `StateMachineDefImplMapperRegistrationSpec` — registry CRUD, id collisions, instance/class/Function forms, null rejection.
- [x] `CompositeOperationDefStepMappingSpec` — step-level mapping at call sites (the new capability), with registered mapper / inline `Function` / inline `ContextMapper` forms; pass-through type-mismatch rejection; mapper P/N alignment rejection.
- [x] `TransitionViewOperationDispatchSpec` — `view.operation(...)` pass-through and mapped; `view.step(...)` mapper-aware; unknown-id rejection.
- [x] `NestedOperationMappingSpec` rewritten for caller-side API (by-id mapper, class mapper, inline `ContextMapper`, inline `Function`, registered `Function` form).
- [x] `NestedOperationMapperFailureSpec` rewritten — `mapTo` → parent failure; `mapFrom` → parent failure (child completed).
- [x] `NestedOperationPassThroughSpec` and `NestedOperationIdUniquenessSpec` — preserved (use only the pass-through inline form, unchanged).
- [x] Deleted: `NestedOperationBuilderValidationSpec`, `NestedOperationDefHierarchySpec` (tested the deleted callee-side surface; no semantic equivalent under the new model).

### 2.5.8 Documentation Alignment
- [x] `requirements.md` §4.5.2 rewritten end-to-end for the caller-side model: 5-form grammar, mapper registry, worked "5 parents, 1 child" example, build-time type-compatibility rules, failure attribution including `mapFrom`-as-parent-failure, condition scope (call-site vs. callee-side), Void-context edge case.
- [x] `requirements.md` §4.5.3.2 (`ContextMapper` on `async`) realigned with the call-site grammar.
- [x] `CLAUDE.md` updated for the new component shape and call-site grammar.

### 2.5.9 Known Follow-Ups (carried to later phases)
- Call-site pre-/post-conditions on composite members (sequenced with Phase 3's listener/condition wiring).
- `mapFrom`-on-`async` definition-time rejection check (sequenced with Phase 4.3.1 async work).

---

## Phase 2.6: DSL Shape Consistency (v0.2.6)
*Target: Lambda-configurer becomes the single declaration shape for every Def that owns children. The chained-return form on `StateDef` / `TransitionDef` is removed outright — it is inconsistent with every other Def and is the source of scope-leak bugs where a caller accidentally attaches a child to the wrong parent. The API is still pre-1.0; this is a breaking change to early users.*

### 2.6.1 `StateDef` lambda-configurer (replaces the chained form)
- [x] Add `StateMachineDef.state(String id, Consumer<StateDef<T>> configurer)` and the `Identifiable` overload, mirroring `compositeOperation(id, configurer)` / `conditional(id, configurer)` / `simpleOperation(id, configurer)` / `branch(id, configurer)`.
- [x] **Remove the chained `StateMachineDef.state(String id)` form** that returns a free-floating `StateDef<T>`. The state-id-only registration is replaced by `state(id, s -> {})` for the truly empty case; the no-arg form does not pull its weight once the configurer exists.
- [x] `StateDef`'s public surface no longer exposes anything callable *after* the configurer returns: the impl tracks "configurer in flight" and rejects post-return mutation calls with a clear error (see §2.6.3). The closed-over reference becomes inert.

### 2.6.2 `TransitionDef` lambda-configurer (replaces the chained form)
- [x] `StateDef.transitionsTo(String target, String id, Consumer<TransitionDef<T, ?>> configurer)` — pass-through context.
- [x] `<C> StateDef.transitionsTo(String target, String id, Class<C> contextType, Consumer<TransitionDef<T, C>> configurer)` — typed-context form.
- [x] **Remove all existing `StateDef.transitionsTo(...)` overloads that return a chainable `TransitionDef`.** Every transition declaration goes through a configurer. The bare-target `transitionsTo(target, id)` registration is replaced by `transitionsTo(target, id, t -> {})` for the empty case.
- [x] Configurer surface mirrors what the chained `TransitionDef` API exposed (operations, conditions, future triggers/listeners). Post-configurer mutation is rejected by the same scope guard as §2.6.1.

### 2.6.3 Scope guard (mandatory, not optional)
- [x] `StateDefImpl` and `TransitionDefImpl` track a `configurerActive` flag set on entry to the configurer and cleared on return. Every public mutating method (`transitionsTo`, `simpleOperation`, `compositeOperation`, `preCondition`, `postCondition`, `withName`, `withDescription`, future trigger/listener attachments) asserts the flag is set, throwing `TransfluxValidationException` with a message naming the offending def id when it isn't.
- [x] The reentrant case (a configurer that calls `transitionsTo(target, id, t -> ...)`, which sets `configurerActive` on the new `TransitionDef`) is fine — guards are per-def, not global.

### 2.6.4 Rename `useContext` → `forContext`
- [x] Rename `StateMachineDef.useContext(Class<C>, Consumer<ContextScope<T, C>>)` to `forContext(...)`. Rationale: the `for/using` split is the semantic split — `forContext(C, scope -> ...)` is a **grouping** block ("for context C, register these"), whereas `usingContext(C)` on `TransitionDef` / `CompositeOperationDef` is a **property setter** ("this transition uses context C"). `useContext` blurred the boundary; `forContext` aligns the verb to the action.
- [x] Receiver type stays `ContextScope<T, C>` — the method *opens* a scope; the value handed to the configurer *is* a scope. Asymmetric naming is the same shape `compositeOperation(id, c -> ...)` already uses.
- [x] Internal helper names: `ContextScopeImpl` unchanged. `StateMachineDefImpl.useContext(...)` becomes `forContext(...)`.
- [x] Migrate every spec and every doc snippet from `useContext` → `forContext`. Spec rename: `UseContextScopingSpec` → `ForContextScopingSpec`.

### 2.6.5 Flat-shorthand policy (explicit decision, not a task)
- The flat `smd.step(...)` / `smd.condition(...)` / `smd.operation(...)` / `smd.mapper(...)` overloads **stay** alongside `forContext(...)` blocks. Rationale: when a state machine registers a single one-off component, the flat form is meaningfully more compact than wrapping it in a one-line block. The typed flat overloads (`smd.step(id, Class<C>, Step<T, C>)`) already carry the context tag inline. `forContext(...)` is the right shape for *groups* of components sharing a context; the flat form is the right shape for *one-offs*. Both forms populate the same registry; they are stylistic, not semantic, alternatives.
- [x] Document the decision in `CLAUDE.md` ("DSL Shape" note) and in the user guide so the choice between forms is unambiguous to readers.

### 2.6.6 Multi-level `Registry` (composite-local scoping + Phase 6.2 seam)
*Originally Step 3b of the Phase 2.5 plan. Pulled into 2.6 because it lands a final piece of DSL-shape consistency (component resolution semantics) and prepares the Phase 6.2 process-wide registry seam.*
- [ ] `Registry<T>` interface gains `parent()` returning the optional enclosing registry; `resolve(id)` walks `parent()` on miss; `get(id)` stays local-only. `RegistryImpl<T>` accepts an optional parent at construction.
- [ ] `StateMachineImpl<T>` owns one root `Registry<T>` (no parent in 2.6; Phase 6.2 supplies one).
- [ ] Each `CompositeOperationDefImpl` exposes a per-composite `Registry<T>` whose `parent()` is the enclosing scope's registry (root SM or enclosing composite). Inline-registered composite members go into the composite's local registry; SM-level registrations stay on the root.
- [ ] By-id resolution at runtime (`view.step("id")` / `view.operation("id")` / composite `ActionRef.resolve`) walks the active scope's registry chain: local first, then ancestors. Same id at multiple levels resolves to the innermost (shadowing).
- [ ] Id-namespace uniqueness rule: still SM-wide for the canonical case, but **a composite-local inline registration with the same id as an ancestor's is permitted and shadows the ancestor for that composite's subtree**. (Confirm this matches §4.5.2.5 in `requirements.md`; if not, decide which wins — the section may need an addendum.)
- [ ] Existing `StateMachineImpl.getBoundStep(String)` / `getBoundOperation(String)` accessors stay as thin wrappers over the root registry; non-breaking for callers outside the resolution path.
- [ ] **Build-time flattening (rolled in here, not split out).** After all registrations settle but before runtime, each registry's chain is walked once and reachable components are copied into the registry's local map. At runtime, `resolve(id)` is a single map lookup with no `parent()` traversal. Shadowing is preserved because the bottom-up flattening writes parent entries first and lets local entries overwrite them. `parent()` stays as a public introspection accessor for tooling / diagnostics; `resolve(...)` no longer needs it.
- [ ] New `RegistryResolutionSpec` — local hit, parent-walk hit, shadowing (innermost wins), not-found returns `Optional.empty()`, flattened-mode parity (resolution result before and after build-time flattening is identical).

### 2.6.7 Migration & Spec Updates
- [ ] Walk every Spock spec under `src/test/groovy`; rewrite chained `smd.state("a").transitionsTo("b", "t1").state("b")...` patterns into the lambda form. No backward-compatibility shims — the chained API is gone.
- [ ] `StateDefImplSpec`, `StateDefImplTransitionsToContextSpec`, `StateMachineDefImplSpec`, `TransitionDefImplSpec`, `TransitionDefImplConditionsSpec`, `UseContextScopingSpec` → `ForContextScopingSpec`, etc. — all need their fixture setup updated. Audit `StateMachineDefImpl#baseDef`-style helpers across the test tree.
- [ ] New `StateDefImplLambdaConfigurerSpec` — configurer wires transitions correctly; post-return mutation throws; nested `transitionsTo(target, id, t -> ...)` configurer guards work; reentrant guard fires on attempted misuse.
- [ ] New `TransitionDefImplLambdaConfigurerSpec` — typed and untyped context overloads; configurer-surface parity with what the chained form exposed; post-return mutation rejected.

### 2.6.8 Documentation Alignment
- [ ] `requirements.md` — replace every chained-form example with the lambda-configurer form; rename every `useContext` reference to `forContext`. The chained form is not mentioned (it never existed in the 1.0 contract).
- [ ] `CLAUDE.md` — "DSL Shape" note: lambda-configurer is the single declaration shape for any Def that owns children. Post-configurer mutation is rejected; the configurer is the *only* place to declare children. Also document the flat-vs-`forContext` choice per §2.6.5 and the multi-level `Registry` resolution semantics per §2.6.6.
- [ ] README hello-world snippet updated.
- [ ] User-guide migration note (one-time, lives in the README or a `MIGRATION.md` until 1.0): chained → configurer transformation pattern, plus the `useContext` → `forContext` rename. Both are breaking changes against any user code already written against the pre-2.6 API.

### 2.6.9 `Identifiable` overload parity (all three tiers)
*Currently only states and event triggers expose `Identifiable` overloads. The audit found ~65 String-only sites that benefit from a symmetric `Identifiable` overload. Each overload is a one-line delegate calling `.getId()` on the supplied `Identifiable`. Bonus: every `*Def` already extends `Identifiable`, so reference-site overloads automatically accept a held-onto `StateDef` / `TransitionDef` / `StepDef` / etc., enabling the "register-then-pass-the-def" pattern alongside enum-constant tagging.*

#### Tier 1 — Reference sites (highest value)
*Sites that look up something declared elsewhere. Enum-tagged ids give compile-checked refactoring safety here — the primary use case for `Identifiable`.*
- [ ] `StateDef.transitionsTo(...)` — already accepts `Identifiable` for target; no Tier-1 work needed beyond what exists (the `transitionId` side is a definition, see Tier 3).
- [ ] `TransitionDef.step(Identifiable registeredStep)` (by-id step ref).
- [ ] `TransitionDef.preCondition(Identifiable registeredCondition)` and `postCondition(Identifiable registeredCondition)`.
- [ ] `CompositeOperationDef.step(Identifiable registeredStep)`, `step(Identifiable registeredStep, Identifiable mapper)`, `step(Identifiable registeredStep, String mapperId)`, `step(String registeredStepId, Identifiable mapper)` — every combination of step ref + mapper ref.
- [ ] `CompositeOperationDef.operation(Identifiable registeredOperation)` and the same matrix of step-ref-times-mapper-ref combinations as above.
- [ ] `TransitionView.step(Identifiable)` and `step(Identifiable, Identifiable mapper)` plus mixed-form overloads — same matrix.
- [ ] `TransitionView.operation(Identifiable)` and the matching matrix.
- [ ] `ConditionDescriptor.ref(Identifiable)` and the four named factory forms (`instanceBased`, `classBased`, `predicate`, `expression`) — wherever an id parameter identifies a registered component or auto-derived reference.

#### Tier 2 — Runtime entry points (high value)
*Host-facing API. Where workflow code wires transitions and looks up runtime types — heavy enum usage pays off most here.*
- [ ] `EntityBinding.transitionTo(Identifiable targetState)`.
- [ ] `EntityBinding.transitionTo(Identifiable targetState, Object context)`.
- [ ] `EntityBinding.transitionTo(Identifiable targetState, Identifiable transition)`.
- [ ] `EntityBinding.transitionTo(Identifiable targetState, Identifiable transition, Object context)`.
- [ ] `StateMachine.executeTransition(T entity, Identifiable targetState)` and `executeTransition(T entity, Identifiable targetState, Identifiable transition)`.
- [ ] `StateMachine.getState(Identifiable)`.
- [ ] `StateMachine.getTransition(Identifiable transition)`.
- [ ] `StateMachine.getTransition(Identifiable sourceState, Identifiable targetState)`.
- [ ] `StateMachineDef.getTransition(Identifiable transition)`.
- [ ] `StateMachineDef.getTransition(Identifiable sourceState, Identifiable targetState)`.

#### Tier 3 — Definition sites (symmetry / consistency)
*Pure consistency win. The user is creating the name, so the immediate ergonomic gain is smaller — but a `*Def`-as-`Identifiable` parameter lets callers reuse an already-registered def as the source of an id when defining a derived component. Adds uniformity: every DSL method that takes an id accepts both forms.*
- [ ] `StateMachineDef.step(Identifiable, ...)` — all four overloads (with/without `Class<C>`, instance/class).
- [ ] `StateMachineDef.condition(Identifiable, ...)` — all six overloads (instance/class/predicate/expression × untyped/typed).
- [ ] `StateMachineDef.operation(Identifiable, Class<C>, ...)` — instance and class forms.
- [ ] `StateMachineDef.compositeOperation(Identifiable, Class<C>, Consumer<...>)`.
- [ ] `StateMachineDef.mapper(Identifiable, ...)` — instance / class / Function forms.
- [ ] `StateDef.transitionsTo(String|Identifiable target, Identifiable transition, ...)` — adds the `Identifiable transitionId` side alongside the existing `Identifiable target` side.
- [ ] `TransitionDef.simpleOperation(Identifiable, ...)` and `compositeOperation(Identifiable, ...)`.
- [ ] `TransitionDef.preCondition(Identifiable, Condition)` / `(Identifiable, Class)` / `(Identifiable, Predicate)` / `(Identifiable, String expression)` inline forms; matching `postCondition(...)`.
- [ ] `TransitionDef.addManualTrigger(Identifiable)`, `addEventTrigger(Identifiable, ...)` (where the *first* arg is the trigger id, not the event), `addDataTrigger(Identifiable, ...)`.
- [ ] `CompositeOperationDef.step(Identifiable, Step<T, C>)` / `step(Identifiable, Class<? extends Step<T, C>>)` inline registration.
- [ ] `CompositeOperationDef.operation(Identifiable, Operation<T, C>)` / `operation(Identifiable, Class<? extends Operation<T, C>>)`.
- [ ] `CompositeOperationDef.conditional(Identifiable, Consumer<...>)`.
- [ ] `ConditionalStepDef.branch(Identifiable, Consumer<BranchDef>)`.
- [ ] `ContextScope.step / condition / operation / compositeOperation` — every `Identifiable` overload mirrors the matching `StateMachineDef` method inside `forContext` blocks.

#### Spec coverage
- [ ] One parameterized spec per affected interface (`StateMachineDefImplIdentifiableOverloadsSpec`, `TransitionDefImplIdentifiableOverloadsSpec`, `CompositeOperationDefImplIdentifiableOverloadsSpec`, `TransitionViewIdentifiableOverloadsSpec`, etc.). Each verifies: (a) the `Identifiable` overload registers / resolves identically to the matching `String` overload; (b) passing a registered `*Def` instance works as the `Identifiable` (the "register-then-reference" pattern); (c) `null` `Identifiable` is rejected with a clear message.

#### Documentation
- [ ] `CLAUDE.md` — short paragraph documenting the parity rule: every DSL method that takes a `String id` exposes an `Identifiable` overload that delegates via `.getId()`. New methods added in later phases are expected to follow the rule.
- [ ] `requirements.md` — the canonical examples lean on enum-constant `Identifiable` ids where idiomatic (states, transition ids, trigger / event ids).

### 2.6.10 Offer-state-machine worked example (DSL polish gate)
*Originally Step 5 of the Phase 2.5 plan. Last task in 2.6 — after all DSL-shape changes have landed, walk a realistic workflow end-to-end against the final API to catch awkward grammar before Phase 3 multiplies the surface.*
- [ ] New scratch file `offer-state-machine.md` at the **repository root**. **Not committed** — temporary doc for joint review during this step (add to `.gitignore` if needed).
- [ ] Contents:
  - `JobOffer` entity sketch.
  - States: `draft`, `submitted`, `sent`, `withdrawn`.
  - Per-transition contexts: `DraftCtx` (opening + application), `SubmitCtx` (review metadata), `SendCtx` (contact details), `WithdrawCtx` (reason, code).
  - SM definition end-to-end: state graph with transitions, each transition's `.usingContext(...)` + inline composite, SM-level `forContext` blocks for components reused across transitions, at least one nested operation with context narrowing via `ContextMapper` (e.g. a `BillingFlow` nested inside the `sent` transition).
  - Caller sketch — fire calls for each transition with the right context object.
  - Edge cases: `Void`-context transition, mid-flow context narrowing, multi-level registry resolution order (composite-local → enclosing → SM root).
- [ ] Walk the example with the user, identify awkward DSL spots, fix the implementation, re-walk until clean.
- [ ] Roll any polish fixes (spec coverage gaps, JavaDoc for new public types like `Registry`, `ContextScope`) into the 2.6 commit. The scratch doc itself is not committed.

### 2.6.11 Def-hierarchy consolidation (dedupe + polymorphism)
*Lands after 2.6.1–2.6.10. The current shape has the lambda-configurer DSL settled but the impl classes carry repeated `id` + `name` + `description` + `withName`/`withDescription` boilerplate across ~7 classes, the scope-guard triad is duplicated verbatim across `StateDefImpl` and `TransitionDefImpl`, and several places dispatch over sealed types with `instanceof` chains instead of polymorphic methods. Forward-looking benefit: Phase 3 trigger/listener defs inherit the metadata + scope-guard machinery for free.*

*Records stay records.* The three sealed-record families (`MapperRef`, `ConditionDescriptor`, `ActionRef`) do not share state across variants and lose nothing by keeping their `equals` / `hashCode` / `toString` for free. Polymorphism for those is added via abstract methods on the sealed interface, not by switching to classes.

*Guard unification uses class inheritance, not composition.* `ConfigurableDefImpl` (see 2.6.11b) is an abstract base; subclasses extend it. Composition with a `ConfigurerGuard` field was considered and rejected because the existing sealed `OperationDefImpl` already imposes an abstract layer, and the other six targets (`StateDefImpl`, `TransitionDefImpl`, `ConditionalStepDefImpl`, `BranchDefImpl`, `DefaultBranchDefImpl`, `ContextScopeImpl`) extend nothing today — inheritance gives the cleanest call sites without forcing per-def delegating boilerplate.

#### 2.6.11a — `IdentifiedDefImpl` base for the `*DefImpl` family
- [ ] Introduce `org.transflux.core.IdentifiedDefImpl<SELF>` (or similar) holding `id` + `name` + `description`, validating `id` in the constructor, and exposing `withName(String)` / `withDescription(String)` with `warnIfSet` logging. Subclass exposes covariant return via the `SELF` generic or a `protected SELF self()` hook.
- [ ] Migrate to extend the new base: `StateDefImpl`, `TransitionDefImpl`, `MapperDefImpl`, `StepDefImpl`, `BranchDefImpl`, `ConditionalStepDefImpl`. `OperationDefImpl` (already sealed abstract over `SimpleOperationDefImpl` / `CompositeOperationDefImpl`) folds into the same shape.
- [ ] The protected hook for the log message ("state '$id'", "transition '$id'", etc.) lives on the base via an abstract `defKind()` returning `"state"` / `"transition"` / etc.

#### 2.6.11b — `ConfigurableDefImpl extends IdentifiedDefImpl` for scope-guarded defs
- [ ] Introduce `ConfigurableDefImpl extends IdentifiedDefImpl` with a `private boolean configurerActive` field.
- [ ] `public void beginConfigurer()` and `public void endConfigurer()` on the base — kept `public` rather than package-private because cross-package callers exist (`StateMachineDefImpl` in `core` calls `StateDefImpl` in `core.state`; `StateDefImpl` calls `TransitionDefImpl` in `core.transition`). Mark both as framework-internal in JavaDoc per the existing cross-package-visibility convention in `CLAUDE.md` ("user code should not invoke it directly").
- [ ] `protected final void requireConfigurerActive(String operation)` reads the flag, uses the inherited `defKind()` from `IdentifiedDefImpl` to build the error message, and throws `TransfluxValidationException`. Subclasses call it directly from each mutator (no override needed).
- [ ] Migrate `StateDefImpl` and `TransitionDefImpl` to extend it. Both keep their per-method `requireConfigurerActive("methodName")` calls — only the field and the helper move.
- [ ] Add a static helper on `ConfigurableDefImpl`:

  ```java
  public static <D extends ConfigurableDefImpl<?>> void runConfigurer(D child, Consumer<? super D> configurer) {
      child.beginConfigurer();
      try { configurer.accept(child); } finally { child.endConfigurer(); }
  }
  ```

  The `Consumer<? super D>` bound matches existing call-site conventions and avoids a wildcard-capture wrinkle at the typed-context overloads.
- [ ] Migrate every existing open-coded begin/try/finally site to `runConfigurer(...)`. Today the sites are:
  - `StateMachineDefImpl.state(String, Consumer<StateDef<T>>)` and the `Identifiable` overload
  - `StateDefImpl.transitionsTo(...)` — all four configurer overloads

  §2.6.12 introduces additional call sites (composite, conditional, branch, forContext); those use the same helper.
- [ ] Document the expected shape in the "DSL Shape" note in `CLAUDE.md` so Phase 3 `TriggerDefImpl` / `ListenerDefImpl` follow it.

#### 2.6.11c — Polymorphic dispatch over sealed types (kill instanceof chains)
- [ ] `OperationDefImpl.buildBound(StateMachineImpl<T>)` — abstract method on the sealed base, with `SimpleOperationDefImpl` and `CompositeOperationDefImpl` overrides. Removes the 2-arm `instanceof` chain at `transition/TransitionDefImpl.java:194-202`.
- [ ] `ConditionDescriptor.resolve(registry, path)` — abstract method on the sealed `ConditionDescriptor`, each of the five record variants implements it. Flattens the 5-arm chain at `condition/ConditionResolver.java:74-92`. `ConditionResolver.resolve` becomes a thin dispatch to `descriptor.resolve(...)`.
- [ ] `MapperRef.validateAgainst(scopeContext, scopeLabel, kind, memberId, componentContext, mapperRegistry)` — abstract method on `MapperRef`. Each variant (`PassThrough`, `ById`, `InlineFunction`, `InlineMapper`) knows how to validate itself. Flattens `StateMachineDefImpl.checkMemberRef` (lines ~825-855).
- [ ] `MapperRef.resolve(stateMachine)` returning `ResolvedContextMapping` — same pattern. Flattens the 4-arm chain at `operation/CompositeOperationDefImpl.java:388-410`.

#### 2.6.11d — `ActionRef.collectInlineRegistrations(SmRegistry sink)` (optional, bigger)
- [ ] Define a small `SmRegistry` (or reuse `StateMachineDefImpl`'s existing scoped-registration methods) as the visitor sink.
- [ ] Add polymorphic `collectInlineRegistrations(...)` to each `ActionRef` variant: by-id variants no-op, inline-instance/class variants push themselves to the sink, `Conditional` recurses into its `BranchDef`s.
- [ ] Replace `CompositeOperationDefImpl.getInlineStepInstances()` / `getInlineStepClasses()` / `getInlineOperationInstances()` / `getInlineOperationClasses()` accessors and `StateMachineDefImpl.walkCompositeForInlineMembers` (lines ~280-320) with a single `composite.collectInlineRegistrations(sink)` walk. Eliminates the parallel-maps reconstruction.
- [ ] Defer this sub-item if 2.6.11a-c run long — the win is real but the surface is larger than the other three combined.

#### 2.6.11e — Spec coverage
- [ ] `IdentifiedDefImplSpec` — parameterized over each concrete subclass: validates id-in-constructor, `withName` / `withDescription` round-trip, override-warning behavior.
- [ ] `ConfigurableDefImplSpec` — guard semantics tested against a minimal concrete subclass, plus regression covering both real users (`StateDefImpl` and `TransitionDefImpl`) via the migrated existing specs.
- [ ] Existing specs (`ConditionResolverSpec`, `CompositeOperationDefImplSpec`, etc.) keep their black-box assertions — the refactor is internal.

### 2.6.12 Broaden scope guard to every configurer-exposed def
*Lands after 2.6.11b (the `ConfigurableDefImpl` base). Behavioral change: every Def passed to a user lambda rejects post-return mutation, not just `StateDef` / `TransitionDef`. Same bug shape — capture the def, mutate it after the configurer returns, silent scope leak — extended consistently. Kept separate from 2.6.11 because 2.6.11 is internal refactor with no behavior change; this step adds new runtime checks that user code can hit.*

#### Defs to extend
- [ ] `CompositeOperationDefImpl` — extend `ConfigurableDefImpl`, guard all mutators (`step(...)`, `operation(...)`, `conditional(...)`, `usingContext(...)`, `withName`/`withDescription`). Hosts: `TransitionDef.compositeOperation(id, c -> ...)` and `StateMachineDef.compositeOperation(id, Class<C>, c -> ...)`.
- [ ] `SimpleOperationDefImpl` — guard `using(...)`, `withName`/`withDescription`. Host: `TransitionDef.simpleOperation(id, op -> ...)`.
- [ ] `ConditionalStepDefImpl` — guard `branch(...)`, `defaultBranch(...)`, `onNoMatch(...)`, `withName`/`withDescription`. Host: `CompositeOperationDef.conditional(id, cs -> ...)`.
- [ ] `BranchDefImpl` — guard `condition(...)` (all overloads), `conditionExpression(...)`, `step(...)` (all overloads). Host: `ConditionalStepDef.branch(id, b -> ...)`.
- [ ] `DefaultBranchDefImpl` — guard `step(...)`. Host: `ConditionalStepDef.defaultBranch(d -> ...)`.
- [ ] `ContextScopeImpl` — guard `step(...)`, `condition(...)`, `compositeOperation(...)`, `operation(...)`, `mapper(...)`. Host: `StateMachineDef.forContext(Class<C>, scope -> ...)`. The scope is a tagged pass-through to the SM, but capturing it and registering post-return would silently land registrations under a stale context tag — exactly the leak the guard is meant to catch.

#### Configurer-runner call sites
- [ ] Every parent that currently invokes one of the above's configurer (e.g. `TransitionDefImpl.compositeOperation`, `CompositeOperationDefImpl.conditional`, `ConditionalStepDefImpl.branch`, `StateMachineDefImpl.forContext`) must wrap the lambda invocation with the `runConfigurer(child, configurer)` helper introduced in 2.6.11b. Existing open-coded `configurer.accept(def)` calls migrate to `runConfigurer(def, configurer)`.

#### Defs that stay un-guarded
- `MapperDefImpl` and `StepDefImpl` — built via fluent SM-level registration calls, not exposed to a user lambda. No leak vector.

#### Spec coverage
- [ ] Per-def "post-return mutation throws" regression spec — one parameterized spec covering every newly guarded def, mirroring the shape used for `StateDef` / `TransitionDef` (capture the def, return from the configurer, attempt a mutator call, assert `TransfluxValidationException` with a message naming the def id).
- [ ] Audit existing specs that construct these defs directly (e.g. `CompositeOperationDefImplSpec`, `ConditionalStepDefImplSpec`, `SimpleOperationDefImplSpec`) — those tests will need to call `beginConfigurer()` after construction, same pattern as the `StateDefImplSpec` / `TransitionDefImplSpec` migration in 2.6.7.

#### Documentation
- [ ] Update the "DSL Shape" note in `CLAUDE.md`: the post-configurer-rejection rule applies to *every* Def passed to a user lambda, not just `StateDef` / `TransitionDef`. List the guarded set explicitly so Phase 3 contributors know the expected default for new defs (triggers, listeners) is also "guarded".

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

- [ ] **Component `validate()` hook** (deferred from the Phase 2.5 plan, Step 3c)
  - [ ] Add a `validate()` method to each `Component<T>` sealed variant (`Component.Step`, `Component.Operation`, `Component.Condition`, plus any new variants Phase 3 introduces such as `Component.Trigger` and the listener-related variants if listeners get componentized).
  - [ ] Invoke `validate()` once per component at the end of `StateMachineDefImpl.build(...)` — after the registry chain is settled and after the existing context-compatibility + cycle-detection passes. Failure throws `TransfluxValidationException` with the component id and a clear diagnostic.
  - [ ] Original use case driving the hook: a `Component.Step` may reject listener attachments that are illegal for its position (e.g. a transition-level listener attached to a sub-step). The Phase 2.5 plan's framing: "Step's validate (e.g.) might reject attached listeners once Phase 3 adds them. For Phase 2.5 the validate methods are mostly empty — the hook is in place so Phase 3 doesn't need to retouch the registry pipeline."
  - [ ] In practice, the hook lands here in Phase 3 (with listeners as the first real consumer) rather than as empty stubs in 2.6. Each variant's default `validate()` is a no-op; only variants with structural rules override it.

### 3.6 Specifications
- [ ] Trigger specs for each type, including catalog enumeration.
- [ ] Manual-trigger metadata override specs.
- [ ] Data trigger specs covering all four Condition Descriptor forms.
- [ ] Listener-ordering specs covering the execution flow.

### 3.7 Component Metadata Model
*Prerequisite for §3.5 Listeners — listener payloads, diagnostic logging, and (later) the YAML DSL all need a uniform way to read `name` and `description` off any framework def. The Phase 2 design deliberately deferred this until there was a real consumer.* `StepDef<T, C>` already ships in Phase 2.5 (call-site context mapping needed `contextType()` on every component); the metadata work below builds on that anchor.
- [ ] Introduce a `Describable extends Identifiable` interface declaring `getName()` and `getDescription()` as default-`null` methods.
- [ ] Make `StateMachineDef`, `StateDef`, `TransitionDef`, `OperationDef`, `StepDef`, `MapperDef` implement `Describable`; each `*DefImpl` overrides one or both methods to return its stored value.
- [ ] Add `ConditionDef<T, C>` (mandatory id, optional name/description) covering the existing four authoring flavours (instance, class, predicate, expression).
- [ ] Add lambda-configurer overloads where step / condition / operation / mapper registrations exist:
  - [ ] `StateMachineDef.step(String id, Class<C> contextType, Consumer<StepDef<T, C>> configurer)`
  - [ ] `StateMachineDef.condition(String id, Consumer<ConditionDef<T, C>> configurer)`
  - [ ] `StateMachineDef.operation(String id, Class<C> contextType, Consumer<SimpleOperationDef<T, C>> configurer)`
  - [ ] `StateMachineDef.mapper(String id, Class<P> parentType, Class<N> childType, Consumer<MapperDef<P, N>> configurer)`
  - [ ] `TransitionDef.preCondition(String id, Consumer<ConditionDef<T, C>> configurer)` and `postCondition(...)` mirror
- [ ] Existing flat overloads stay as sugar for the no-metadata case.
- [ ] Listener payloads (§3.5) surface `id` + `name` + `description` from the relevant def — concrete shape pinned down alongside the `*Listener` interfaces.

---

## Phase 4: Async Operations & Error Handling (v0.4.0)
*Target: The compensation engine, async anchoring, and exception-specific recovery.*

### 4.1 Compensation Engine
- [ ] Unified `Compensation<T, C>` interface (entity + context) for both operations and steps.
- [ ] LIFO compensation stack management. **One stack per synchronous execution path.** Sync nested operations (Phase 2.5) push onto the enclosing parent's stack so unwinding interleaves child and sibling-step compensations correctly. Async branches own their own stack — see §4.4.
- [ ] Compensation registration before each step runs (so a step that throws partway through producing side effects still gets its compensation invoked).
- [ ] Post-condition violation triggers full compensation; entity state is *not* applied.
- [ ] Compensation declared by class or returned dynamically from `getCompensation(entity, context)`.

### 4.2 Exception-Specific Compensation
- [ ] `.onException(...)` / `.onAllExceptions()` builder DSL on composite operations.
- [ ] Exception matching by class hierarchy + optional predicate.
- [ ] Compensation chaining and ordering.

### 4.3 Async Operation Support
- [ ] `async` block on composite operations. The block accepts both `.step(...)` and `.operation(...)` members — an async branch may host a nested operation just like a sync composite can (Phase 2.5). The async executor submits the branch root; the nested operation runs on the async thread with its own compensation stack (see §4.4).
- [ ] **Anchor forms**: exactly one of
  - [ ] `startBefore(stepId)` — kick off when execution reaches the named sync step (join-point pattern).
  - [ ] `startAfter(stepId)` — kick off when the named sync step completes successfully (post-action notifications pattern).
- [ ] Configurable thread pool and queue capacity.
- [ ] Async result handling and callbacks.
- [ ] Async operation cancellation semantics.

#### 4.3.1 Async Context Handling (requirements §4.5.3)
- [ ] `ForkableContext<C>` interface with single `C fork()` method.
- [ ] Runtime fork-at-boundary: at async-branch submission, if the context implements `ForkableContext`, the branch receives `context.fork()`; otherwise it receives the shared reference. Invoked once per branch (not once per `async` block) so sibling branches each get an independent fork.
- [ ] `ContextMapper`-on-`async` path: the async block accepts the same five-form call-site grammar as composite members (`.async().step("id", "mapperId")` / `.async().step("id", parent -> child)` / `.async().operation("id", mapperInstance)` etc., per Phase 2.5 §2.5.3). Supplying a `ContextMapper` whose `mapFrom` is overridden is rejected at definition time — async outcomes do not merge back synchronously.
- [ ] Definition-time **warning** (logged, not thrown) when an `async` block is declared on a context type that neither implements `ForkableContext` nor declares a mapper. Warning identifies the operation id and points to §4.5.3.
- [ ] Documented memory-model guarantees at the submission boundary (writes-before-submission visible to branch; writes-after not synchronized; symmetric for branch → enclosing path).
- [ ] Optional `JacksonForkableContext` adapter shipped as a convenience implementation (Jackson round-trip). Lives in the same module — it's a few dozen lines and Jackson is already a core dependency.
- [ ] Specs covering: `ForkableContext` is invoked per branch, shared-reference fallback works and warns, `ContextMapper` on async produces the right type, `mapFrom` on async fails definition-time, `JacksonForkableContext` round-trips a representative POJO context.

### 4.4 Async Compensation
- [ ] **Per-branch LIFO stack.** Each async branch owns its own compensation stack — independent from the enclosing transition's sync stack and from sibling async branches. Compensations registered by an async branch (including any nested operations it hosts) unwind only that branch's stack.
- [ ] Sync-failure-while-async-running semantics: sync unwinds its own stack; each in-flight async branch unwinds (or completes and then unwinds) its own stack independently. No cross-stack interleaving.
- [ ] Async-branch failure does not trigger sync compensation; surfacing of async failures into `TransitionResult` follows the existing async result-handling design (§4.3).
- [ ] Timeout handling for async operations — on timeout, the affected branch unwinds its own stack.

### 4.5 Specifications
- [ ] Compensation engine specs (LIFO order, exception routing, partial rollback).
- [ ] Async anchor specs for both `startBefore` and `startAfter`.
- [ ] Async-compensation specs.

---

## Phase 5: YAML DSL & Component System (v0.5.0)
*Target: The declarative DSL at parity with the Java DSL.*

### 5.0 Java DSL Audit & Java/YAML Alignment (first work item)
*This pass runs **before** any YAML implementation work. The YAML DSL is only as good as the Java DSL it shadows; if the Java DSL has drifted from `requirements.md` (or from itself) during Phases 2–4, that drift must be resolved first or it propagates into YAML.*
- [ ] **`requirements.md` ↔ Java DSL audit.** Walk `requirements.md` end-to-end against the implemented Java DSL. For every code snippet in the spec, verify it compiles and runs against the live API. Update wording, examples, and types in `requirements.md` to match the implementation; conversely, flag implementation gaps where the spec was right and the code drifted. Sections most likely to need touch-ups (cumulative debt from Phases 2–4): §2.1 (core abstractions), §2.1.4 (`TransitionResult`), §2.1.5 (operation execution), §2.4 (execution flow), §3.6 (conditions), §4.3 (transition definitions), §4.4 (operation definitions), §4.5 (nested operations + async).
- [ ] **Java DSL self-consistency review.** Cross-cutting pass through every Def's public API. Verify: shape consistency (lambda-configurer everywhere children exist, per Phase 2.6); naming consistency (`with*` for entity properties, `using*` for declarative property-setters, `for*` for scoping/grouping blocks); generic-parameter consistency across paired Def/runtime types; metadata accessor parity (id / name / description). Surface any inconsistencies as targeted fix tasks before Phase 5 work proceeds.
- [ ] **Java/YAML alignment proposal.** Produce a short alignment doc (transient, repo-root scratch file along the lines of the offer-state-machine example in §2.6.9) walking the YAML shape side-by-side with the Java shape for every top-level element (state, transition, operation, step, condition, mapper, async, listener, trigger). Flag every place where the YAML would naturally read differently from the Java — those are the design questions to resolve before writing the parser. Propose changes, additions, or improvements to the Java DSL where the YAML walkthrough surfaces opportunities (e.g., the Java DSL gains a sugar form because the YAML wants it, or both DSLs gain a feature the spec hadn't anticipated).
- [ ] **Decisions captured.** Resolve the alignment questions and capture decisions in `requirements.md` before moving to §5.1. This means `requirements.md` is the single source of truth for both DSLs entering YAML implementation work.

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
- [ ] Composite-operation member grammar accepts `operation:` alongside `step:` (YAML counterpart of the Java `.operation(...)` builder added in Phase 2.5). Each member exposes the same five-form call-site grammar: an optional `mapper:` field that accepts a string (registered mapper id), a `class:` block (mapper class), or an inline `mapTo:` SpEL expression. Full inline `ContextMapper` instances are Java-only and have no YAML surface.
- [ ] `mapper:` is a new top-level component kind in the YAML DSL (peer to `step:` / `condition:` / `operation:`), with `parent-type` / `child-type` / `class` (or `mapTo:` SpEL) fields. Mappers participate in cross-file imports and ID-uniqueness checks.
- [ ] Cross-file ID-uniqueness checks walk into composite members so nested-operation ids participate in collision detection (Phase 2.5 enforces SM-wide uniqueness; the YAML loader must mirror that across imports).
- [ ] Condition Descriptor parsing (the four YAML-expressible forms — reference, class, predicate, expression). The fifth `InstanceBased` form is Java-only and has no YAML surface.
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
- [ ] Nested-operation instantiation goes through the same factory path — no separate code path for operations used as composite members.

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
- Core abstractions: `StateMachine`, `State`, `Transition`, `Operation` (top-level and nested, with type-safe context mapping via `ContextMapper<P, N>`), `Step`, `Context`, `Condition`, `Trigger` (Manual / Event / host-driven Data), `Listener` (state entry/exit + transition start/complete), `Compensation`.
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
