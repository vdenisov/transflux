> Part of the [Transflux roadmap](../../todo.md). ✅ **Shipped** — verbatim record, nothing compressed.

## Phase 2.5: Nested Operations & Call-Site Context Mapping (v0.2.5)
*Target: First-class operation-as-composite-member with caller-side `ContextMapper`. See `requirements.md` §4.5.2.*

### 2.5.0 Per-transition context refactor (prerequisite, completed)
- [x] Drop the `<C>` generic from `StateMachine`, `StateMachineDef`, `StateMachineImpl`, `StateDef`, and the nested `EntityBinding`. Context now lives at the transition level: `TransitionDef<T, C>` declares its own `C`, defaulting to `Object.class` when neither `transitionsTo(target, id, Class<C>)` nor `usingContext(Class<C>)` is called. `Void.class` becomes a sentinel that rejects any non-null firing context. SM-level component registries take wildcard `Step<T, ?>` / `Condition<T, ?>` plus typed overloads `step(id, Class<C>, Step<T, C>)`, mirroring `useContext(...)` tagging. `StateMachineDef.forContextType(Class<C>)` deleted outright. Heterogeneous transitions on a single SM are now expressible.

### 2.5.1 Reusable Component Types (def-side anchors)
- [x] `OperationDef` gains `Class<C> contextType()` accessor (default `Object.class`; overridden by composite to return `declaredContextType`).
- [x] Introduce `StepDef<T, C>` def-side anchor with id, optional name/description, mandatory `Class<C> contextType()`, `using(Step|Class)` source forms, and `buildBoundStep()`. (Pulled forward from the original Phase 3.7 plan as a public-API down-payment; the lambda-configurer entry point that actually instantiates `StepDefImpl` lands in 2.6.13.)
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

