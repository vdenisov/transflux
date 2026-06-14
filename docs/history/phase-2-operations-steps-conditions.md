> Part of the [Transflux roadmap](../../todo.md). ✅ **Shipped** — verbatim record, nothing compressed.

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
  - [x] `BiPredicate<T, C>`-style lightweight conditions, with a `Predicate<T>` convenience overload for entity-only tests.
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

