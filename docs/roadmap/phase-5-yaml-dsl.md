> Part of the [Transflux roadmap](../../todo.md). Forward-looking; not yet started.

## Phase 5: YAML DSL & Component System (v0.5.0)
*Target: The declarative DSL at parity with the Java DSL.*

**Shape of the phase.** Each numbered item is sized to one working session and has one kind of output. §5.1–§5.4 settle the Java DSL and the YAML grammar before any YAML code exists; §5.5 and §5.6 are core work with no YAML dependency; §5.7–§5.11 build the loader in slices; §5.12 and §5.13 close it.

| Item | Output | Depends on |
| --- | --- | --- |
| 5.1 `requirements.md` ↔ Java audit | spec edits, fixture additions, a drift list | — |
| 5.2 Java DSL self-consistency review | small Java fixes | — |
| 5.3 Java/YAML alignment and decisions | `requirements.md` §3 rewritten where needed | 5.1 |
| 5.4 Java DSL changes from the alignment | Java code | 5.3 |
| 5.5 `StateMachine` as handle + `replaceDefinition` | Java code | — |
| 5.6 Definition Sourcing SPI | Java code | — |
| 5.7 `transflux-yaml` module and loader infrastructure | module, entry point, node walk, error model | 5.3, 5.6 |
| 5.8 Parsing: component libraries | loader code | 5.4, 5.7 |
| 5.9 Parsing: the state machine | loader code | 5.8 |
| 5.10 Parsing: member grammar, compensation, fork | loader code | 5.8 |
| 5.11 Imports and global configuration | loader code | 5.9, 5.10 |
| 5.12 JSON Schema | schema file, agreement spec | 5.11 |
| 5.13 Parity and hot-swap | specs | 5.5, 5.11 |

**Three decisions that hold across the phase.**
- **The mapper only emits `Def` calls.** Everything the Java build already checks — reference existence, action cycles, state-machine-wide id uniqueness, context compatibility — is checked there and nowhere else. The loader adds only what a document can get wrong before a def exists (structure, unknown keys, unloadable classes, import cycles) and attributes a build failure to the file and line that declared the offending def.
- **Parity is capability, not spelling.** `InstanceBased` conditions are Java-only and `class:` is YAML-only already; SpEL forms of the state resolver, the state applier and a mapper's `mapTo` join the YAML-only side (§5.9, §5.8), since Java has a lambda for the same job. Where YAML needs something the Java DSL has no equivalent of *capability* for — a listener or trigger declared once and attached in several places — Java gains it (§5.4).
- **The loader validates; the schema is for editors.** A mapper walking the YAML node tree knows the line, the column and what is being declared, so it can say `subscription.yml:42: action entry declares both 'run' and 'step'` where a schema validator says `must be valid to exactly one schema`. The JSON Schema ships for IDE autocomplete and is kept honest by a spec rather than used at runtime (§5.12).

### 5.1 `requirements.md` ↔ Java DSL Audit
*Ran before anything else. The YAML DSL is only as good as the Java DSL it shadows; drift accumulated during Phases 2–4 propagates into YAML unless it is resolved first.*
- [x] Walked `requirements.md` §2 and §4 against the implemented Java DSL and corrected the spec where the code was deliberate: `TransitionResult`'s accessors and what its target means on a failure, the exception accessors, the id-namespace rule, a transition's body and its lack of a compensation of its own, first-match data triggers, the `Error` carve-out, §4.7.1's condition verbs, the context argument to `transitionTo`, an `Object`-typed global listener, and several statements in §4.5 that predated the dispatch-time mapper check. §2.4's execution order and §4.2–§4.4's snippets checked out as written, the latter under javac.
- [x] **Every Java snippet compiles, durably.** `JavaDslSurface` gained the documented shapes it lacked: every condition attachment form (a method reference included), the whole trigger DSL, every listener hook in both forms with listeners written as classes, `mapperDef` and the instance / method-reference mapper registrations, a `getCompensation` override, a method-reference route guard, `onNoMatch`, and the host-side entry points with the accessors on what they return — which no Java caller had exercised.
- [x] **Cut, as unowned and unneeded**: call-site and operation-level pre-/post-conditions (§4.5.2.8 as was — a conditional already gates a member) and batch execution (§4.9.3 — a host-side loop).
- [x] **Left as written, being ahead of the code rather than drifted**, each with an owner: `ComponentRegistry` and its annotations (§4.1, Phase 6 — and see §5.3), `TransfluxConfiguration` / `MetricsCollector` / `@EnableTransflux` (§4.10, Phase 6), the handle and `replaceDefinition` (§2.1.2, §2.1.3, §2.7 — §5.5). They carry no "not yet implemented" marker, which would go stale the day the owner ships.
- [x] **§3 for internal consistency**: every SpEL example now follows one convention (§3.9) — the entity is the root and written bare, `#context` / `#transition` / `#event` are variables, and `#entity` names the root for passing it whole. `#entity` is not bound yet (§5.4). The single `action:` key on transitions stays for §5.3.
- [x] Handed on: to §5.2, the entry point's type witness and a `transitionTo` overload trap; to §5.3, §4.1's details and §4.7.2.

### 5.2 Java DSL Self-Consistency Review
- [ ] Cross-cutting pass through every Def's public API. Verify: shape consistency (lambda-configurer everywhere children exist); naming consistency (`with*` for entity properties, `using*` for declarative property-setters, `for*` for scoping/grouping blocks); generic-parameter consistency across paired Def/runtime types; metadata accessor parity (id / name / description).
- [ ] **Two known metadata-accessor anomalies (surfaced in Phase 3.7).** `StateDef` and `TransitionDef` publish `withName` / `withDescription` but not the matching getters (which exist on `IdentifiedDefImpl`, so only the published contract is short); and the runtime `Transition` exposes no `getName()` while `State<T>` does.
- [ ] **`Transflux.defineStateMachine(Class<T>)`.** `Transflux.defineStateMachine().forEntityType(X.class)` — the spelling `requirements.md` uses throughout, and the JavaDoc of `Transflux` and `StateMachine` with it — does not compile: `T` is inferred as `Object` at the receiver, so every Java caller writes the `Transflux.<X>defineStateMachine()` witness. Add the overload that takes the class, so the natural spelling is the legal one, and move the spec and the JavaDoc examples onto it.
- [ ] **`transitionTo(String, String)` against `transitionTo(String, Object)`.** Both are applicable when the context is itself a `String`, and most-specific resolution silently picks the transition-id form — the trap the `Identifiable` family carried, in a smaller place. `fire` and `executeTransition` want the same look. Decide whether it is worth a rename or a JavaDoc warning.
- [ ] Fix what is small and uncontested here. Anything that changes the shape of the DSL goes to §5.3 as a question instead.

### 5.3 Java/YAML Alignment and Decisions
*A design item: its output is `requirements.md`, not code. Expect it to be a conversation rather than a single pass.*
- [ ] **Alignment doc.** A transient repo-root scratch file walking the YAML shape side-by-side with the Java shape for every top-level element (state, transition, action, condition, mapper, fork, listener, trigger, compensation and its routes, global config). Flag every place where the YAML would naturally read differently from the Java — those are the questions to resolve before writing the loader. Propose Java DSL changes where the walkthrough surfaces them.
- [ ] **Shared listener and trigger pools.** §3.1.1 declares a listener or a trigger once in a library and references it from many owners; the Java DSL claims a listener id or a trigger id at the point of attachment, so two references are a duplicate id. Decided in principle: Java gains registrations (§5.4). `requirements.md` §4.1.2 already sketches the by-id half (`addTrigger("id")`, a one-argument `onStart("id")`), against a model that has since moved. To settle here: the registration and by-id attachment surface for each, what a by-id attachment's id is when the same listener sits on two owners, how `TransitionExecution.firedBy` and the trigger catalog report a trigger shared by two transitions, and whether any other component kind needs the same.
- [ ] **§4.1, the component registry.** The design stands and the registry itself is Phase 6; its details are outdated — `EventTrigger.builder()` and `DataTrigger.builder()` where the def side is `EventTriggerDef` / `DataTriggerDef`, a free-standing `operation("x")...build()` where a def exists only inside its configurer, a transition held in a variable. Rewrite the details against the shipped model once the listener and trigger registrations above are settled, since §4.1.2 is where they are documented. **The constraint on this phase**: nothing it adds may make a registry harder to introduce. The registrations §5.4 adds are the natural thing for a registry to populate, and the root scope is already planned to take a process-wide parent, so the loader registers through `StateMachineDef` like any other caller and keeps no component table of its own.
- [ ] **YAML spelling for action listeners (deferred from Phase 3.5).** Not the state and transition blocks with a third noun substituted: an action listener attaches to the *action* rather than to a call site, and YAML makes inline action definitions first-class at every member position, so where the attachment is written — and that a `run:` reference inherits the callee's listeners, which in the Java DSL it does — both need stating. Global action listeners and the three `disableGlobalListener*` forms need spellings on all three owner kinds.
- [ ] **`ConditionDef<T, C>` — decide (deferred from Phase 3.7).** Phase 3 closed it as a decision rather than code: its only payload beyond what exists is a name and a description nothing reads (no `Condition` runtime view, no catalog, no payload carrying one), and reaching runtime means widening every `ConditionDescriptor` record plus `BoundCondition` and mirroring the family across `StateMachineDef`, `ContextScope`, `TransitionDef`'s two slots, `BranchDef`, `ManualTriggerDef` and `DataTriggerDef`. YAML conditions do carry `name:` / `description:` (§3.1.1), so the question is now concrete: does the loader accept and drop them, or does something read them? `requirements.md` §4.7.2 documents a third payload, `withErrorMessage(...)` / `withErrorCode(...)` on a three-argument `preCondition`, which nothing implements; today a rejection is identified by the ids on `TransfluxConditionException`. If the answer is no, §4.7.2 goes. If it lands, naming is settled by precedent — `condition(String, Consumer<…>)` collides with `condition(String, Predicate<T>)`, a real collision of one-argument functional interfaces, so it needs a name of its own exactly as `mapperDef` did.
- [ ] **`Describable` super-interface — decide (deferred from Phase 3.7).** Gate (a), the listener payload shape, answered *per-kind*. Gate (b) is this phase's: does the loader walk Defs polymorphically to apply `name` / `description`, or per-kind? If per-kind, the item stays closed. If polymorphic, introduce `Describable extends Identifiable`; it is purely additive.
- [ ] **A transition's body becomes an `actions:` list**, replacing the single `action:` key §3's transition examples still show. Phase 4b made a transition a sequence on the Java side; this is the YAML half, and it leaves the loader one member-list grammar everywhere.
- [ ] **SpEL conventions.** The root and the variables are settled and stated in §3.9 (entity as root; `#context`, `#transition`, `#event`, `#entity`). Left to settle is what the three YAML-only expression forms bind: a resolver expression reads the state id off the entity; an applier expression is an assignable property path; a mapper's `mapTo:` sees the parent context and returns the child. `mapFrom` has no expression form — write-back is a class.
- [ ] **Loose ends in §3.** The `contexts:` section (§3.5.1) — nothing references a context by id, so define its use or remove it. `stateMachine.version` and component "versioning / compatibility metadata" — nothing reads either; define or cut. `config.metrics` belongs to Phase 6.3 — reserve the key or reject it until then.
- [ ] **Decisions captured** in `requirements.md`, which is the single source of truth for both DSLs entering the loader work. Delete the scratch file.
- [x] **Inline nested declarative containers in the Java DSL** - moved to [Phase 4b](../history/phase-4b-action-sequence-grammar.md), which closed the whole parity gap rather than this one cell: a member-position `operation(id, ...)`, a conditional nested inside a branch, mappers and forks on branch members, and one shared member grammar (`ActionSequence`) the loader is written against. **Shipped.**

### 5.4 Java DSL Changes from the Alignment
- [ ] Listener registrations and by-id attachment, per §5.3, for all three listener categories.
- [ ] Trigger registrations and by-id attachment, per §5.3.
- [ ] `ConditionDef` and `Describable`, only if §5.3 decided yes.
- [ ] **Bind `#entity`** in every SpEL evaluation context, beside the root it names (§3.9): passing the whole entity to a method has no other spelling short of `#root`.
- [ ] Every new call shape goes into `JavaDslSurface`; `requirements.md` §4 documents it.

### 5.5 `StateMachine` as Handle + `replaceDefinition`
*Per `requirements.md` §2.7. Independent of every YAML item except §5.13's demo, so it can run at any point. The handle is API-shape work that lands in this phase because the YAML loader is its first non-trivial caller. Watcher-driven automatic reload is Post-1.0 (§7.2).*
- [ ] **Decide first: who owns the async executor across a swap.** Today a framework-built pool belongs to the `StateMachineImpl` that becomes the snapshot, and `close()` shuts it down. A new definition may size its pool differently, declare none, or bring a host executor; branches forked under the old snapshot outlive the transition that forked them, and an in-flight transition may still fork after the swap. Settle: handle-owned or snapshot-owned, when a replaced snapshot's pool is released, what `close()` on the handle closes, and what a swap between a framework pool and a host executor does. Capture the answer in §2.7.
- [ ] **`StateMachine<T>` becomes the host-facing handle.** The immutable per-version data — states, transitions, registries, bound actions — moves into an internal `StateMachineSnapshot<T>` (a renamed-and-internalised `StateMachineImpl`). External callers continue to depend on `StateMachine<T>` and see no source-incompatible change.
- [ ] **Every external entry point on `StateMachine<T>`** (`entity(...)`, `executeTransition(...)`, `processEvent(...)`, `processDataChange(...)`, `getTransition(...)`, `getState(...)`, `resolveCurrentState(...)`, the catalog accessors) captures the current snapshot at the top of the call and delegates against it. Capture happens exactly once per top-level call; mid-call swaps never split a transition between versions. An entity binding returned by `entity(...)` is such a call: it runs on the snapshot it captured, however late its terminal method is invoked.
- [ ] **`ExecutingTransitionImpl`** holds the snapshot reference it was constructed with. `run(...)`, `fork(...)` and scope-stack resolution all run against the snapshot.
- [ ] **The reentrancy guard and the async-branch ban key on the handle, not the snapshot.** §2.7.4 requires that an in-flight execution reentering through the handle is rejected; keyed on `(snapshot, entity)`, a reentrant call made after a swap captures a different snapshot and passes. The same holds for the deque naming the machines whose branches the current thread is running: a branch forked under generation N must not drive generation N+1 either.
- [ ] **`long generation()`** on `StateMachine<T>`. Starts at `1` after `build()`. Monotonic per-handle, incremented by exactly `1` per successful swap.
- [ ] **`long replaceDefinition(StateMachineDef<T> newDef)`** on `StateMachine<T>`:
  - Full validation runs first (state graph, condition resolution, member refs, context compatibility, cycle detection, id uniqueness). Any `TransfluxValidationException` leaves the existing snapshot in place; nothing was swapped.
  - **Entity-type compatibility check** — the new def's `entityType()` must be `==` the current snapshot's `entityType()`. Replacing a `StateMachine<Foo>`'s definition with a `StateMachineDef<Bar>` (including subtypes/supertypes of `Foo`) is rejected with a `TransfluxValidationException` whose message names both types. The entity type is the handle's identity contract.
  - Builds a new `StateMachineSnapshot<T>` from the validated def.
  - CAS-swaps the snapshot reference (concurrent swaps are serialised; only one wins per generation).
  - Increments `generation()` and returns the new generation number.
  - In-flight executions hold their own snapshot reference and finish on the pre-swap topology — required by §2.7's atomicity guarantee.
- [ ] `build()` returns the handle unchanged from today's signature; the handle starts at generation `1`.
- [ ] **No host-side synchronisation requirement** for ordinary reads. The snapshot reference is held in a `volatile` field (or equivalent atomic primitive), which also discharges the safe-publication contract for a snapshot installed by a swap.
- [ ] Logging: a swap is an INFO line on `build.lifecycle` (rare, consequential, invisible from a transition's return value).
- [ ] **Specs:**
  - Atomic-or-nothing: a validation failure inside `replaceDefinition` leaves `generation()` unchanged and the current snapshot's behaviour intact.
  - Entity-type compatibility rejection covers supertypes, subtypes and unrelated types — only `==` passes.
  - In-flight isolation: a transition started against generation N completes against generation N's snapshot even when concurrent threads swap to N+1, N+2 during the call.
  - Reentrancy through the handle across a swap is rejected; so is driving the handle from a branch forked under an earlier generation.
  - Executor lifecycle across a swap, per the decision above.
  - Generation monotonicity: failed swaps don't bump; successful swaps bump by exactly 1.
  - Source compatibility: existing specs that build a machine and call `.entity(...).transitionTo(...)` pass unchanged.
- [ ] **Java DSL hot-swap demo spec** — a state machine is built, transitioned once against generation 1, has its definition replaced with a topologically different (but entity-type-compatible) one, transitioned again against generation 2.

### 5.6 Definition Sourcing SPI
*Per `requirements.md` §2.6. The SPI and its implementations only — no parser needed. What the loader does with a source (imports, import-chain errors, import cycles) is §5.11.*
- [ ] `DefinitionSource` interface: `Optional<DefinitionResource> open(String identifier)`.
- [ ] `DefinitionResource` AutoCloseable carrying `identifier()`, `bytes()`, optional `lastModified()`, optional `etag()`.
- [ ] Identifiers are **opaque, source-defined strings** — no path canonicalisation, no implicit `.yml` suffix, no relative-to-importer resolution by the framework. Hosts pick the scheme; the source decides what to make of it.
- [ ] Ships-with implementations: `ClasspathDefinitionSource` (default), `FileSystemDefinitionSource(Path root)` (with `..`-traversal rejection and symlink policy), `CompositeDefinitionSource` (route by scheme prefix or by ordered fallback).
- [ ] Home: the SPI has no YAML dependency, but nothing outside the loader consumes it. Place it in `transflux-yaml` unless §5.3 found a core consumer.

### 5.7 `transflux-yaml` Module and Loader Infrastructure
- [ ] **Multi-module build.** The repository becomes a parent POM with the existing library as one module and `transflux-yaml` as a second, depending on it. The core module gains no dependency. Toolchain, Surefire's `**/*Spec` include and JaCoCo move to the parent. Update CLAUDE.md and the README's "Package Structure" for the new top-level package.
- [ ] **Dependencies: SnakeYAML 2.x only**, used through its node API so every node keeps its line and column. Add a second library only when something needs it.
- [ ] **The loader uses the public API alone.** It lives outside `core.impl` and is written against `ActionSequence<T, C, SELF>` and the `*Def` interfaces, which makes it the second out-of-package caller of the DSL after `JavaDslSurface`. If it needs something that is not public, that is a finding about the Java DSL, not a reason to widen visibility.
- [ ] **Host entry point.** A loader taking a `DefinitionSource`, a root identifier and the entity `Class<T>`, returning a `StateMachineDef<T>` — a def rather than a built machine, since `replaceDefinition` (§5.5) takes one. The document's `entityType:` must name exactly the class passed; a mismatch is a validation error naming both.
- [ ] **Instantiation seam.** One small interface turning a `Class<X>` into an `X`, with a reflective no-arg-constructor default; the loader takes an optional override. Class loading goes through a configurable `ClassLoader`, defaulting to the context one. Every `class:` key in the grammar goes through this seam and nothing else instantiates. Phase 6.2's `ComponentFactory` widens this seam rather than introducing a second one. Failures — class not found, wrong type for the position, no usable constructor — are validation errors at the declaring line.
- [ ] **Error model.** Every loader error carries the resource identifier, line and column, and the declaration path (`transition 't' > operation 'op'`, the build's own label convention). A `TransfluxValidationException` raised by the Java build for a def the loader declared is rethrown with the source position of that def's declaration prepended. Unknown keys are errors, not ignored.
- [ ] **Raw-type safety.** The loader calls typed verbs with classes loaded by name, so what javac proves for a Java host is unproven here: an action's, compensation's, listener's or mapper's type arguments against the declared `context:`, and a mapped inline declaration's `ContextMapper<C, N>`, which the Java build deliberately does not check. Check what reflection can see (declared generic supertypes) at load time; where it cannot, the existing runtime context check is the backstop. State which is which in `requirements.md`.
- [ ] **Loggers**: `org.transflux.yaml.parse` / `.binding`, in a holder of the module's own; extend `LoggersSpec`'s rules (or mirror the spec) to cover it.

### 5.8 Parsing: Component Libraries
*A library document (§3.1.1) loaded into registrations on a `StateMachineDef`. No state machine, no imports yet — one file in, registrations out.*
- [ ] Document envelope: `apiVersion`, `metadata`, `spec`.
- [ ] `steps:` — `class`, `context`, `name` / `description`, `compensation` and `errorHandling` (routes share §5.10's parser; whichever item lands first writes it).
- [ ] `conditions:` and the Condition Descriptor grammar — reference, `class:`, `predicate:`, `expression:`, long-form `ref:`; "exactly one of" enforced. `InstanceBased` is Java-only and has no YAML surface. One descriptor parser, reused at every position that takes a condition.
- [ ] `mappers:` — a top-level component kind, peer to actions and conditions: `parent-type` / `child-type` and either `class:` or a `mapTo:` SpEL expression. The SpEL adapter is a `ContextMapper` built in this module and registered through the existing instance overload; `mapFrom` stays the inherited no-op.
- [ ] `listeners:` and `triggers:` — onto the registrations §5.4 added. Listener `config: { async, onRejection }` maps to `withAsync(...)`; `FAIL` is refused as it is in Java. Triggers: `type: manual | event | data`, `event:`, `filter:`, `condition:`, `preConditions:`.
- [ ] Component identification per `requirements.md` §2.2.1: `id` mandatory, inline expression conditions excepted.
- [ ] `operations:` registered at library level use §5.10's member grammar; until that lands, this item covers the other sections.

### 5.9 Parsing: the State Machine
- [ ] `stateMachine:` — `id`, `name`, `description`, `entityType`.
- [ ] **State resolver and state applier**: `class:` through the instantiation seam, or `expression:`. The expression forms are YAML-only adapters built in this module and passed to `withStateResolver(...)` / `withStateApplier(...)`: the resolver evaluates the expression against the entity and returns the state id; the applier assigns the new state id through the expression as a property path. Verify the assignment converts a `String` to an enum-typed property, the common case, and say so in §3.2.1 either way.
- [ ] `states:` — metadata, `listeners: { onEntry, onExit }`, global-listener disables.
- [ ] `transitions:` — `from` / `to`, `context:`, `preConditions` / `postConditions` (descriptor grammar from §5.8), `triggers:` (by reference or inline, all three kinds), `listeners: { onStart, onComplete, onError }`, global-listener disables. The body's `actions:` list is §5.10's.
- [ ] Global listeners — the top-level `listeners:` block of §3.7, all three categories.
- [ ] Every listener position accepts a reference or an inline definition, as §3.1.2 promises for every component.

### 5.10 Parsing: Member Grammar, Compensation, Fork
*One walk, generic in the concrete def type, fills all four positions that hold an ordered list — a container, a branch, a default branch, a transition's body. That is what `ActionSequence<T, C, SELF>` is self-typed for.*
- [ ] Verb-keyed entries: `run:` (reference) against `step:` / `operation:` / `conditional:` (declarations); exactly one per entry. Declarations nest to any depth.
- [ ] **Context shapes**: a declaration's own `context:` key, alone (pass-through) or with a `mapper:` (mapped). A reference's `mapper:` accepts a registered mapper id, a `class:` block, or an inline `mapTo:` expression. Full inline `ContextMapper` instances are Java-only.
- [ ] **`fork: true`** beside any verb, mapping to the verb's forked twin; `onRejection:` beside it — the call-site rung on a `run:`, the def's own declaration on a declaring entry.
- [ ] `conditional:` — `branches:` (id, condition descriptor, `actions:`), `default:`, and the `NoMatchBehavior` key.
- [ ] `compensation:` and `errorHandling:` routes (`exception`, optional guard `condition`, `compensation`) on any action, in declaration order.
- [ ] Action listeners at the position §5.3 settled, plus global-listener disables on an action.
- [ ] Library-level `operations:` (left open by §5.8) and transition bodies (left open by §5.9) both close here.

### 5.11 Imports and Global Configuration
- [ ] **Imports flow through the source.** Each `imports:` `path:` is handed verbatim to the `DefinitionSource`, never resolved as a filesystem path. Each resource is parsed exactly once per load; the loader caches nothing across loads, so a fresh `replaceDefinition` re-reads through the source every time. Sources may cache bytes themselves.
- [ ] A missing import and a **circular import** are validation errors naming the chain.
- [ ] **Import chain in every error** (§2.6.4): `root.yml -> imports/shared.yml -> condition 'foo': ...`, composed with §5.7's error model.
- [ ] **Cross-file id uniqueness** needs no check of its own: every imported registration lands on one `StateMachineDef`, whose build already enforces state-machine-wide uniqueness, nested members included. What this item owns is attribution — the message names both declaring files and lines.
- [ ] `config.async` → `withAsyncPool(...)` / `withAsyncRejectionPolicy(...)`, including the "block present but sizes nothing" case; `config.logging` → `withExecutionLogging(...)`. `config.metrics` per §5.3's decision.

### 5.12 JSON Schema
*Written last, against a grammar that has stopped moving.*
- [ ] JSON Schema for the Transflux YAML format — library documents and state-machine documents — shipped in the `transflux-yaml` jar for editor autocomplete and inline validation. IDE plugin work is out of scope.
- [ ] **Agreement spec.** A corpus of valid and invalid documents, each run through both the loader and a schema validator (test scope only); the two must agree on accept/reject. This is what keeps the schema honest without putting it on the runtime path, and the corpus doubles as the loader's error-message fixtures.
- [ ] Every YAML example in `requirements.md` §3 is in the valid corpus.

### 5.13 Parity and Hot-Swap
*Element-level specs are co-located with each class as it lands, per the repo convention; this item is only what cuts across.*
- [ ] **DSL parity check**: one non-trivial state machine — nested operations, a conditional, a forked member, a mapped call site, compensation routes, all three trigger kinds, all three listener categories — expressed in both DSLs, driven through the same scenarios, producing equal `TransitionResult` paths, catalogs and listener traces.
- [ ] **YAML hot-swap demo spec** — §5.5's exercise driven through a `DefinitionSource`, demonstrating that YAML reload is "load a new def through the source, call `replaceDefinition`".
- [ ] Error-message review: read the invalid corpus's messages as a host would. Each names the file, the line, the declaration, what was wrong and what was expected.
- [ ] README: the YAML module, its dependency coordinates, the `org.transflux.yaml.*` logger leaves.
