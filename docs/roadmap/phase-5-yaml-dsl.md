> Part of the [Transflux roadmap](../../todo.md). Forward-looking; not yet started.

## Phase 5: YAML DSL & Component System (v0.5.0)
*Target: The declarative DSL at parity with the Java DSL.*

### 5.0 Java DSL Audit & Java/YAML Alignment (first work item)
*This pass runs **before** any YAML implementation work. The YAML DSL is only as good as the Java DSL it shadows; if the Java DSL has drifted from `requirements.md` (or from itself) during Phases 2–4, that drift must be resolved first or it propagates into YAML.*
- [ ] **`requirements.md` ↔ Java DSL audit.** Walk `requirements.md` end-to-end against the implemented Java DSL. For every code snippet in the spec, verify it compiles and runs against the live API. Update wording, examples, and types in `requirements.md` to match the implementation; conversely, flag implementation gaps where the spec was right and the code drifted. Sections most likely to need touch-ups (cumulative debt from Phases 2–4; §3.8 already reconciled the action-model sections, so expect the remainder): §2.1 (core abstractions), §2.1.4 (`TransitionResult`), §2.1.5 (action execution), §2.4 (execution flow), §3.6 (conditions), §4.3 (transition definitions), §4.4 (action definitions), §4.5 (nested actions + async).
- [ ] **Java DSL self-consistency review.** Cross-cutting pass through every Def's public API. Verify: shape consistency (lambda-configurer everywhere children exist, per Phase 2.6); naming consistency (`with*` for entity properties, `using*` for declarative property-setters, `for*` for scoping/grouping blocks); generic-parameter consistency across paired Def/runtime types; metadata accessor parity (id / name / description). Surface any inconsistencies as targeted fix tasks before Phase 5 work proceeds.
- [ ] **Java/YAML alignment proposal.** Produce a short alignment doc (transient, repo-root scratch file along the lines of the offer-state-machine example in §2.6.9) walking the YAML shape side-by-side with the Java shape for every top-level element (state, transition, action, condition, mapper, async, listener, trigger). Flag every place where the YAML would naturally read differently from the Java — those are the design questions to resolve before writing the parser. Propose changes, additions, or improvements to the Java DSL where the YAML walkthrough surfaces opportunities (e.g., the Java DSL gains a sugar form because the YAML wants it, or both DSLs gain a feature the spec hadn't anticipated).
- [ ] **Inline nested declarative containers in the Java DSL** - a known parity gap, surfaced by Phase 3.8 rather than by this audit, so it does not have to be rediscovered here. A declarative container can reference another container by id (`run("notify-flow")`) but cannot declare one inline at a member position (`operation("notify-flow", n -> ...)`); the same holds for a conditional branch. YAML has no such restriction (§3.1.2 makes inline definitions first-class at *every* position that accepts a component), so the DSLs cannot reach parity until the Java side grows this. The unified action model makes it a small addition: a member-position `operation(id, Consumer<OperationDef<T, C>>)` that registers into the enclosing container's lexical scope exactly as inline steps already do. Decide at the same time whether inline `conditional(...)` nesting inside a branch follows.
- [ ] **Decisions captured.** Resolve the alignment questions and capture decisions in `requirements.md` before moving to §5.1. This means `requirements.md` is the single source of truth for both DSLs entering YAML implementation work.

### 5.1 YAML Processing Infrastructure
- [ ] Dependencies: SnakeYAML 2.4, Jackson YAML module (2.20.x, matching the core Jackson version), JSON Schema Validator 1.x current.
- [ ] JSON Schema for Transflux YAML format.
- [ ] Schema-based validation with line-number / context error reporting.
- [ ] IDE-support schema files for autocomplete (the schema itself; IDE plugin work is out of scope).

### 5.2 Component Library System
- [ ] `ComponentLibrary` — reusable definitions of actions, conditions, triggers, listeners.
- [ ] Component identification rules per requirements §2.2.1 (mandatory `id`; expression-based conditions excepted).
- [ ] Component versioning / compatibility metadata.

### 5.3 Component Reference Grammar
- [ ] String-shorthand reference resolution (`action: my-action`).
- [ ] Inline block definitions (`action: { type: operation, ... }`) — first-class everywhere.
- [ ] Long-form reference (`{ ref: my-op }`) accepted in block contexts.
- [ ] Type discrimination rules for inline definitions.
- [ ] Circular reference detection.
- [ ] Component dependency graph.

### 5.4 Definition Sourcing SPI
*Lands before §5.5 (parsing) — the loader consumes a `DefinitionSource`, not a `Path` or classloader. Per `requirements.md` §2.6.*
- [ ] `DefinitionSource` interface: `Optional<DefinitionResource> open(String identifier)`.
- [ ] `DefinitionResource` AutoCloseable carrying `identifier()`, `bytes()`, optional `lastModified()`, optional `etag()`.
- [ ] Identifiers are **opaque, source-defined strings** — no path canonicalisation, no implicit `.yml` suffix, no relative-to-importer resolution by the framework. Hosts pick the scheme; the source decides what to make of it.
- [ ] Ships-with implementations: `ClasspathDefinitionSource` (default), `FileSystemDefinitionSource(Path root)` (with `..`-traversal rejection and symlink policy), `CompositeDefinitionSource` (route by scheme prefix or by ordered fallback).
- [ ] Error reporting threads the resource identifier into every validation error message, including the full import chain.
- [ ] The framework never caches parsed definitions across builds; sources may cache bytes themselves. A fresh `replaceDefinition` (§5.8) re-loads through the source on every call.
- [ ] **Imports flow through the source.** Cross-file `path:` references on `imports:` are handed verbatim to the source, not resolved to filesystem paths.
- [ ] Cross-file ID-uniqueness detection still happens after the source has assembled the byte stream — it's a property of the combined definition, not the source.
- [ ] Circular import detection.

### 5.5 YAML DSL Parsing
- [ ] State machine definition parser.
- [ ] State, transition, action, condition, trigger, listener parsers.
- [ ] Container member grammar, mirroring the Java split settled in §3.8: a **reference** to an action registered elsewhere, and an **inline declaration** that names its authoring form. Each reference exposes the call-site mapper grammar — an optional `mapper:` field accepting a string (registered mapper id), a `class:` block (mapper class), or an inline `mapTo:` SpEL expression. Full inline `ContextMapper` instances are Java-only and have no YAML surface. Settle the YAML spelling of reference-versus-declaration during §5.0; the Java side uses `run(...)` against `step(...)` / `operation(...)`.
- [ ] `mapper:` is a new top-level component kind in the YAML DSL (peer to the action and condition kinds), with `parent-type` / `child-type` / `class` (or `mapTo:` SpEL) fields. Mappers participate in cross-file imports and ID-uniqueness checks.
- [ ] Cross-file ID-uniqueness checks walk into container members so nested action ids participate in collision detection (SM-wide uniqueness is already enforced on the Java side; the YAML loader must mirror it across imports).
- [ ] Condition Descriptor parsing (the four YAML-expressible forms — reference, class, predicate, expression). The fifth `InstanceBased` form is Java-only and has no YAML surface.
- [ ] State resolver + state applier configuration (class or SpEL).
- [ ] Listener parity with the Java DSL (state entry/exit + transition start/complete).
- [ ] Validation against the JSON Schema.
- [ ] Conversion from YAML model to runtime `*Def` builders, then to runtime instances.

### 5.6 `StateMachine` as Handle + `replaceDefinition`
*Per `requirements.md` §2.7. The handle abstraction is API-shape work that lands in Phase 5 because the YAML loader is its first non-trivial caller and `DefinitionSource`-driven swap is the use case that justifies the contract. Watcher-driven automatic reload is Post-1.0 (§7.2).*
- [ ] **`StateMachine<T>` becomes the host-facing handle.** The immutable per-version data — states, transitions, registries, bound steps/operations — moves into an internal `StateMachineSnapshot<T>` (a renamed-and-internalised `StateMachineImpl`). The current `StateMachineImpl` symbol stays as the snapshot type or gets renamed to make the role explicit; external callers continue to depend on `StateMachine<T>` and see no source-incompatible change.
- [ ] **Every external entry point on `StateMachine<T>`** (`entity(...)`, `executeTransition(...)`, `processEvent(...)`, `processDataChange(...)`, `getTransition(...)`, `getState(...)`, `resolveCurrentState(...)`, future catalog accessors) captures the current snapshot at the top of the call and delegates against that snapshot. Snapshot capture happens exactly once per top-level call; mid-call swaps never split a transition between versions.
- [ ] **`TransitionView`** holds the snapshot reference it was constructed with — no change to the view's own internals beyond pointing at a snapshot instead of the SM. `view.run(...)` and scope-stack resolution all run against the snapshot.
- [ ] **`long generation()`** on `StateMachine<T>`. Starts at `1` after `build()`. Monotonic per-handle, incremented by exactly `1` per successful swap.
- [ ] **`long replaceDefinition(StateMachineDef<T> newDef)`** on `StateMachine<T>`:
  - Full validation runs first (state graph, condition resolution, composite refs, context compatibility, cycle detection, id uniqueness). Any `TransfluxValidationException` leaves the existing snapshot in place; nothing was swapped.
  - **Entity-type compatibility check** — the new def's `entityType()` must be `==` the current snapshot's `entityType()`. Replacing a `StateMachine<Foo>`'s definition with a `StateMachineDef<Bar>` (including subtypes/supertypes of `Foo`) is rejected with a `TransfluxValidationException` whose message names both types. The entity type is the handle's identity contract.
  - Builds a new `StateMachineSnapshot<T>` from the validated def.
  - CAS-swaps the snapshot reference (concurrent swaps are serialised; only one wins per generation).
  - Increments `generation()` and returns the new generation number.
  - In-flight executions hold their own snapshot reference and finish on the pre-swap topology — required by §2.7's atomicity guarantee. The reentrancy guard keys on `(snapshot, entity)`, which already gives the right semantics.
- [ ] `StateMachine.build()` (and the existing `Transflux.defineStateMachine()...build()` chain) returns the handle unchanged from today's signature; the handle starts at generation `1`.
- [ ] **No host-side synchronisation requirement** for ordinary reads. The snapshot reference is held in a `volatile` field (or equivalent atomic primitive). `generation()` and snapshot reads are coherent without external locking.
- [ ] **Specs:**
  - Atomic-or-nothing semantics: a validation failure inside `replaceDefinition` leaves `generation()` unchanged and the current snapshot's behaviour intact.
  - Entity-type compatibility rejection covers identical types, supertypes, subtypes, and unrelated types — only `==` passes.
  - In-flight isolation: a transition started against generation N completes against generation N's snapshot even when concurrent threads swap to generations N+1, N+2 during the call.
  - Generation monotonicity: failed swaps don't bump; successful swaps bump by exactly 1.
  - `StateMachine<T>` as handle: existing specs that construct `Transflux.defineStateMachine()...build()` and call `.entity(...).transitionTo(...)` continue to pass unchanged — confirms the source-compat contract.
- [ ] **Java DSL hot-swap demo spec** — a state machine is built, transitioned once against generation 1, has its definition replaced with a topologically different (but entity-type-compatible) one, transitioned again against generation 2. Covers the manual-swap use case end-to-end.
- [ ] **YAML hot-swap demo spec** — same exercise driven through the §5.4 source, demonstrating that YAML reload is just "build a new def via the source + call `replaceDefinition`."

### 5.7 Specifications
- [ ] Parser specs for each top-level element.
- [ ] Reference Grammar specs (ref vs. inline; bare string vs. block).
- [ ] Import resolution specs (through §5.4's `DefinitionSource`).
- [ ] Schema validation error message specs.
- [ ] DSL parity check: a single non-trivial state machine expressed in both DSLs produces equivalent runtime instances.

