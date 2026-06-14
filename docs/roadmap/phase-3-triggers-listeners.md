> Part of the [Transflux roadmap](../../todo.md). Forward-looking; not yet started.

## Phase 3: Triggers & Listeners (v0.3.0)
*Target: The 1.0 trigger set (Manual, Event, host-driven Data) and full listener parity between DSLs.*

### 3.1 Trigger Framework
- [x] `Trigger` interface and base implementations. (`core.trigger.Trigger` runtime view + `TriggerType`; triggers attach to transitions like pre/post-conditions rather than going through the `Component`/`Registry` machinery.)
- [x] Trigger registration and lookup on transitions. (`TransitionDef.addManualTrigger(...)`; ids are a per-state-machine namespace, uniqueness enforced at build.)
- [x] Trigger catalog API on the state machine — enumeration by id (`StateMachine.getTriggers()` / `getTrigger(id)`). **Kind-based discrimination ("by type") is deferred to 3.3**: with only manual triggers, a type filter is vacuous. When event/data triggers land it will be added class-based (`getTriggers(Class<? extends Trigger>)` with return-type narrowing) rather than via a parallel enum.

### 3.2 Manual Triggers
- [x] `ManualTrigger` implementation. (`ManualTriggerDef` builder + internal `ManualTriggerImpl` runtime.)
- [x] Per-trigger metadata: description, trigger-specific pre-conditions distinct from the transition's defaults. Trigger pre-conditions **augment** the transition's (transition's run first, then the trigger's). **Listener bindings are deferred to 3.5**, where listeners are introduced.
- [x] Invocation API — shipped as a dedicated `stateMachine.entity(e).fire(triggerId[, ctx])` (no target-state arg; the trigger names its transition). `transitionTo(...)` stays trigger-free, so triggerless transitions remain directly invocable and absent from the catalog.

### 3.3 Event Triggers
- [ ] `EventTrigger` implementation.
- [ ] `processEvent(event, eventData)` API on the entity binding.
- [ ] Event filtering via expressions / predicate classes.
- [ ] Entity correlation (matching events to entities) for the in-process case.
- [ ] **Trigger kind discrimination (deferred from 3.1).** With a second trigger kind now present, add kind-based catalog filtering to the state machine. Prefer a class-based `<X extends Trigger> Collection<X> getTriggers(Class<X> kind)` (type-safe filter + return-type narrowing) over a parallel `TriggerType` enum; introduce the public `EventTrigger` / `ManualTrigger` subtypes of `Trigger` that this filter selects on. The YAML `type: manual|event|data` vocabulary maps to concrete builders in the parser, so no runtime enum is required.

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

### 3.7 Component Metadata Model (remainder after 2.6.13)
*Phase 2.6.13 pulled forward the `step` / `simpleOperation` lambda-configurer overloads — they only depended on the `IdentifiedDefImpl` base that landed in 2.6.11a and unblocked themselves. The items below remain: the `Describable` super-interface is parked pending a real consumer (see note); `ConditionDef` requires new design that intersects the sealed `ConditionDescriptor` grammar; the remaining lambda-configurer overloads either depend on `ConditionDef` or wait on consumer demand; listener-payload shape pins down alongside `*Listener` interfaces.*

#### Parked: `Describable` super-interface (needs additional consideration)
- [ ] **Decision pending.** The original Phase 3.7 plan proposed introducing `Describable extends Identifiable` (default-`null` `getName()` / `getDescription()`) and retrofitting six public Def interfaces. The analysis done alongside 2.6.13 found:
  - Each Def interface already declares `getName()` / `getDescription()` independently. Collapsing them into a super-interface is cosmetic — no behavior change.
  - The presumed consumers (`StateListener`, `TransitionListener`, diagnostic logging) all know the *concrete* Def type they hold; none of them need polymorphic metadata access. The polymorphic case that would justify a common super-type does not yet exist.
  - Per CLAUDE.md's "Don't design for hypothetical future requirements", introducing a new public-API type now means exporting a symbol whose removal would be a breaking change, for speculative payoff.
- [ ] **To resolve before this task is closed**: (a) confirm Phase 3.5's listener payload shape — does any single listener method want to receive metadata for *more than one* Def kind without committing to a specific type? (b) confirm Phase 5's YAML serialization path — does it walk Defs polymorphically or per-kind? If both answers are "per-kind", drop this item entirely (the per-interface declarations stay). If either answer is "polymorphic", introduce `Describable` then.

#### Outstanding items
- [ ] Add `ConditionDef<T, C>` (mandatory id, optional name/description) covering the existing four authoring flavours (instance, class, predicate, expression). Design pass needs to reconcile the new def with the sealed `ConditionDescriptor` grammar in `core.condition` — `ConditionDef` likely becomes a builder that produces a `ConditionDescriptor`, with name/description as fields on the def that survive into the bound side.
- [ ] Add lambda-configurer overloads where step / condition / operation / mapper registrations exist:
  - [x] *(Done in 2.6.13)* `StateMachineDef.step(String id, Class<C> contextType, Consumer<StepDef<T, C>> configurer)`
  - [x] *(Done in 2.6.13)* `StateMachineDef.simpleOperation(String id, Class<C> contextType, Consumer<SimpleOperationDef<T, C>> configurer)`
  - [ ] `StateMachineDef.condition(String id, Consumer<ConditionDef<T, C>> configurer)` *(depends on `ConditionDef` above)*
  - [ ] `StateMachineDef.mapper(String id, Class<P> parentType, Class<N> childType, Consumer<MapperDef<P, N>> configurer)`
  - [ ] `TransitionDef.preCondition(String id, Consumer<ConditionDef<T, C>> configurer)` and `postCondition(...)` mirror *(depends on `ConditionDef`)*
- [x] *(Done in 2.6.13)* Existing flat overloads stay as sugar for the no-metadata case.
- [ ] Listener payloads (§3.5) surface `id` + `name` + `description` from the relevant def — concrete shape pinned down alongside the `*Listener` interfaces. **Resolution of the `Describable` parked item depends on what shape this lands at.**

