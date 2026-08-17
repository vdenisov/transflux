> Part of the [Transflux roadmap](../../todo.md). In progress — §4.1 has landed; the rest is forward-looking.

## Phase 4: Async Operations & Error Handling (v0.4.0)
*Target: The compensation engine, async anchoring, and exception-specific recovery.*

### 4.1 Compensation Engine
*§3.8 already delivered the dynamic half: one `Compensation<T, C>` interface, captured before every action runs and pushed onto the single per-execution LIFO stack. What remains here is the static def-side declaration and the routing.*
- [x] A single `Compensation<T, C>` interface (entity + context), declarable by **any** action in either authoring form.
- [x] LIFO compensation stack management. **One stack per synchronous execution path.** Nested actions push onto the enclosing execution's stack so unwinding interleaves child and sibling compensations correctly. Async branches own their own stack — see §4.4.
- [x] Compensation registration before each action runs (so an action that throws partway through producing side effects still gets its compensation invoked).
- [x] **`withCompensation(...)` on the def side.** An imperative action can return its compensation dynamically from `getCompensation(entity, context)`; a declarative container has no Java object to hang that on and needs a static declaration on its def. §3.8 documented the intended semantics without shipping the DSL: a container's compensation is **additive** (its own and its members' all run), and because the container is pushed on entry its compensation runs even when the first member fails immediately. (Shipped on `ActionDef` in instance and class forms, return-type narrowed on `StepDef` / `OperationDef` / `ConditionalOperationDef`, and reachable from every declaration position through the configurer forms that already exist — no new declaration overloads were needed. The declaration rides on `BoundAction` alongside the id, kind and listeners, and `ExecutingTransitionImpl.runAction` is the single point that decides between the two channels. **A declaration wins over `getCompensation`**, which is then not consulted at all: the declaration site is the more specific statement, and one action to one compensation keeps `compensatedPath` from carrying the same qualified path twice. The bare instance and class member forms on a container still have no def, so — as with listeners — they carry no declaration.)
- [x] **Post-condition violation triggers full compensation; entity state is *not* applied.** `executeTransitionInternal` used to return from the post-condition branch without draining the stack, so that path reported an empty `compensatedPath` even when actions had registered compensations. (The violation now *throws* the same `TransfluxValidationException` it used to return, so it unwinds through the catch that already drains, reports `compensatedPath`, and notifies `onError` — the path a post-condition that *throws* always took. The state applier sits below the throw and is skipped.)
- [x] **Found during the work: observers could dispatch, and the pre-condition path proved it.** Review of the post-condition fix asked why the *pre*-condition rejections a few lines above still return without draining. They receive the live view, so a pre-condition calling `view.run(...)` followed by a second one returning `false` discarded that action's compensations silently — and an expression-based condition could reach the same dispatch through SpEL's `#transition`. Rather than making pre-conditions drain too, the capability was removed: `Transition` is now the read-only runtime view and the new `ExecutingTransition<T, C>` carries `run(...)`, handed only to an action's body. Conditions, branch conditions, data-trigger gates and all three listener kinds take the read-only type, which makes the pre-condition `return` correct by construction — nothing can have been pushed. Payload records dropped the type parameters only the old type needed (`ActionExecution` is non-generic, `TransitionExecution<T>` lost its `C`), and `TransitionImpl` copies the three topology strings instead of wrapping its `BoundTransition`, since SpEL resolves `#transition` reflectively against the runtime object and a wrapper would expose the resolved graph.
- [x] **Found during the work: a compensation was invoked against the wrong context.** `Compensation.compensate` documents a context "matching what was passed to the original `execute(...)` call", but the drain handed every compensation the *transition's* context. Dynamic compensations got away with it by closing over the child context and ignoring the parameter; a declared `Compensation<T, ChildCtx>` behind a `ContextMapper` would not have. `BoundCompensation` now carries the effective context from push time and the drain hands it back.

### 4.2 Exception-Specific Compensation
- [ ] `.onException(...)` / `.onAllExceptions()` builder DSL on declarative containers.
- [ ] Exception matching by class hierarchy + optional predicate.
- [ ] Compensation chaining and ordering.

### 4.3 Async Operation Support
- [ ] `async` block on declarative containers. The block accepts the same members a sync container does, referenced with `.run(...)` or declared inline with `.step(...)`. The async executor submits the branch root; the nested operation runs on the async thread with its own compensation stack (see §4.4).
- [ ] **Anchor forms**: exactly one of
  - [ ] `startBefore(actionId)` — kick off when execution reaches the named sync step (join-point pattern).
  - [ ] `startAfter(actionId)` — kick off when the named sync step completes successfully (post-action notifications pattern).
- [ ] Configurable thread pool and queue capacity.
- [ ] Async result handling and callbacks.
- [ ] Async operation cancellation semantics.

#### 4.3.1 Async Context Handling (requirements §4.5.3)
- [ ] `ForkableContext<C>` interface with single `C fork()` method.
- [ ] Runtime fork-at-boundary: at async-branch submission, if the context implements `ForkableContext`, the branch receives `context.fork()`; otherwise it receives the shared reference. Invoked once per branch (not once per `async` block) so sibling branches each get an independent fork.
- [ ] `ContextMapper`-on-`async` path: the async block accepts the same call-site grammar as sync members (`.async().run("id", "mapperId")` / `.async().run("id", parent -> child)` / `.async().run("id", mapperInstance)`). Supplying a `ContextMapper` whose `mapFrom` is overridden is rejected at definition time — async outcomes do not merge back synchronously.
- [ ] Definition-time **warning** (logged, not thrown) when an `async` block is declared on a context type that neither implements `ForkableContext` nor declares a mapper. Warning identifies the operation id and points to §4.5.3.
- [ ] Documented memory-model guarantees at the submission boundary (writes-before-submission visible to branch; writes-after not synchronized; symmetric for branch → enclosing path).
- [ ] Optional `JacksonForkableContext` adapter shipped as a convenience implementation (Jackson round-trip). Lives in the same module — it's a few dozen lines and Jackson is already a core dependency.
- [ ] Specs covering: `ForkableContext` is invoked per branch, shared-reference fallback works and warns, `ContextMapper` on async produces the right type, `mapFrom` on async fails definition-time, `JacksonForkableContext` round-trips a representative POJO context.

### 4.4 Async Compensation
- [ ] **Per-branch LIFO stack.** Each async branch owns its own compensation stack — independent from the enclosing transition's sync stack and from sibling async branches. Compensations registered by an async branch (including any actions nested below it) unwind only that branch's stack.
- [ ] Sync-failure-while-async-running semantics: sync unwinds its own stack; each in-flight async branch unwinds (or completes and then unwinds) its own stack independently. No cross-stack interleaving.
- [ ] Async-branch failure does not trigger sync compensation; surfacing of async failures into `TransitionResult` follows the existing async result-handling design (§4.3).
- [ ] Timeout handling for async operations — on timeout, the affected branch unwinds its own stack.

### 4.5 Async Listener Execution
*Moved here from Phase 3.5, which shipped state and transition listeners synchronously. Phase 3 asked for "basic" async listener support, but the executor, pool sizing, and queueing are exactly what this phase owns — building a listener-specific dispatch ahead of it would mean designing the same thing twice.*

- [ ] Per-listener opt-in on the def side (`StateListenerDef` / `TransitionListenerDef`), matching the YAML `config: async: true` shape in requirements §3.1.1.
- [ ] Dispatch through the same configurable pool as async operations (§4.3), not a listener-private one.
- [ ] Semantics to pin down and document, none of which the sync form has to answer:
  - [ ] Ordering — sync listeners run in declaration order; an async listener leaves that order only partially defined. Decide whether async listeners are ordered among themselves and whether a sync listener declared after one may observe its effects.
  - [ ] Exception handling — the sync rule is "logged and swallowed, never reaching `TransitionResult`". Confirm it still holds when the throw happens after the transition has returned to the host.
  - [ ] Context sharing — an async listener reading the context after the transition completes hits the shared-reference problem described in §4.3.1. Decide whether `ForkableContext` applies to listeners.
  - [ ] Whether a listener may still be notified after the state machine's definition has been replaced (requirements §2.7.5 says listener delivery is per-execution).
- [ ] Specs covering opt-in dispatch, the ordering rule chosen, and exception isolation across the thread boundary.

### 4.6 Specifications
- [ ] Compensation engine specs (LIFO order, exception routing, partial rollback, container compensation additivity). *(Partly done with §4.1: `StateMachineImplCompensationSpec` covers LIFO order, partial rollback, the post-condition paths, container additivity and first-member-fails, the conditional, the declaration-wins rule, the declared compensation at each declaration position, and the captured-context rule; `StepDefImplSpec` / `OperationDefImplSpec` / `ConditionalOperationDefImplSpec` / `ConfigurableDefImplSpec` / `ExecutingTransitionImplSpec` cover the def surface. Exception routing waits on §4.2.)*
- [ ] Async anchor specs for both `startBefore` and `startAfter`.
- [ ] Async-compensation specs.

### 4.7 Logging & Diagnostics Baseline
*Pulled forward from §6.3, which carried this as a single line — "Consistent SLF4J logging with predictable logger names" — which is not enough to settle a shape. The library emits six statements today, and both this phase and Phase 5 add code that should be born compliant rather than retrofitted. §6.3 now points here and keeps only the `MetricsCollector` SPI, which is a different concern (counters and timings, not "what did the framework decide").*

*Scope bound: the goal is being able to understand how the framework behaved — why a trigger fired nothing, why a YAML definition resolved the way it did. MDC keys, correlation ids, and a formally parseable record format stay post-1.0 themes. The policy itself is written up in CLAUDE.md §Logging; the items below are what implementing it requires.*

#### 4.7.1 Framework-emitted logging
- [ ] **Virtual-package logger names**, per the tree in CLAUDE.md §Logging — `build.*`, `execution.*`, `trigger`, and `yaml.*` when Phase 5 lands. Per-class names are the wrong unit: implementation classes are deliberately concentrated in `core.impl` so they can see each other package-privately without widening the public surface, which leaves a host choosing between "all of Transflux" and "one class whose name we may refactor next phase". A logical leaf is finer-grained than a class name anyway, since a single class routinely spans several concerns.
- [ ] **`Loggers` holder** in `core.impl` declaring the tree as constants, so the names are typo-proof and reviewable in one place.
- [ ] **Document the tree in the README**, not only in CLAUDE.md — it is the surface a host configures, and it is the one part of this work that is public contract rather than internal convention.
- [ ] **Trigger dispatch diagnostics** — the widest gap today. `processEvent` / `processDataChange` can return `fired() == false` with no explanation at all; the host is left guessing between "wrong source state", "context incompatible with the transition", "filter returned false", and "gate returned false". Log the scan at DEBUG, one line per candidate with its skip reason, and fold in the single existing ineligibility DEBUG.
- [ ] **Condition evaluation** — pre/post conditions, branch selection, event filters, data gates, each with its outcome. DEBUG for the transition-level ones, TRACE for branch selectors.
- [ ] **Build pipeline** — DEBUG at the phase boundaries (registries populated, conditions resolved, refs validated, cycles checked, scopes flattened) and per bound component with what it resolved to; INFO once on completion with counts and the generation. This is the half that answers "why did my definition build into *that*".
- [ ] **Registry resolution and call-site mapping** at TRACE — which scope an id resolved in, and whether a call site mapped the context and to what type.
- [ ] **Normalise the existing statements** to the house format (message first, then `key={}` pairs matching field names). The current six are inconsistent with it.
- [ ] **PII sweep.** `TransfluxReentrancyException` concatenates the entity itself into its message, so a host's PII lands in any log that prints that stack trace. Audit every message and log statement for the same pattern; the rule is ids, class names, states, and paths only.

#### 4.7.2 Shipped logging listeners
*The execution trace is not framework logging's job — the listener SPI already carries it, and a host that registers a listener controls format, level, and cost. What is missing is the sugar, so that "just show me what ran" is not a half-hour of boilerplate.*
- [ ] `LoggingStateListener` / `LoggingTransitionListener` / `LoggingActionListener` implementing the existing SPIs. No new framework hooks are needed — this is the first real consumer of the Phase 3 listener surface, and doubles as its reference implementation.
- [ ] Java DSL sugar: one registration on `StateMachineDef` that attaches all three globally, taking the level to emit at (default DEBUG) and the options below. Shape to agree before writing it — it is the piece a host touches first, so it should read well before it is convenient.
- [ ] **`includeContext` defaults to `false`.** These listeners are the one place in the library allowed to log host payloads, which is exactly why the host has to ask: a default-on switch would turn "I enabled logging" into "I logged my customers' data". The expected pattern for a host that wants payloads on most of its flow is the inverse of per-owner opt-outs — leave the global listeners context-free and attach context-logging listeners explicitly where they are wanted, through the same DSL.
- [ ] YAML parity, which **reconciles `requirements.md` §3.8's `logging: { level, includeContext, includeTimings }` block**. That block currently reads as configuration for framework-internal logging, which contradicts the never-log-payloads rule. Re-frame it as the configuration for these listeners: `includeContext: true` then means the host asking for its own context in its own logs, which is the host's call to make and nobody else's. Update §3.8 when the shape is settled.
- [ ] Package placement decision — a new subpackage is needed (they are public API, not `core.impl`), which per CLAUDE.md means updating both CLAUDE.md and the README's package list.
- [ ] **Generic listener configuration is a YAML-side concern, not a competing design.** In Java the host constructs the instance (`new LoggingActionListener(level, includeContext)`), so no API is required; in YAML there is no constructor to call, which is what `requirements.md` §3.1.1's `config: { async: true }` block already anticipates. The shipped logging listeners' options are then just keys in that block, and this belongs with the Phase 5 listener library rather than here — noted so the two are not designed twice.

#### 4.7.3 Disabling global listeners
*Motivating case: an action whose context carries sensitive data, running in a state machine whose host registered global listeners the action knows nothing about. The author wants that action observed on its own terms — typically by attaching a listener of its own that records a redacted line — without the generic globals seeing the payload.*

***Sizing this deliberately.** This is a convenience, not a security control. An application serious about PII or secret handling writes its own global listener that sanitises exactly what it needs to, and nothing we could ship competes with that. So the bar is "good enough and predictable", not "airtight" — which is what makes the deny-list below an acceptable shape rather than a defect.*

**Settled.**
- [ ] **Two forms on the def side**: disable named global listeners, and disable all of them. Nothing finer.
- [ ] **Scope is global listeners only.** An owner's own listeners always receive everything; attaching a listener to a specific owner *is* the consent, and it is also how the owner replaces what it just turned off — with whatever configuration it wants, through the same DSL the globals use.
- [ ] **Full suppression, not payload withholding.** The pattern is "replace the generic observation with a bespoke one", so notifying the global anyway — even context-free — produces a second record of the same execution. Consequence worth stating: **the public payload records do not change.** `ActionExecution` and `TransitionExecution` stay exactly as they are, and there is no `WITHOUT_CONTEXT` mode to decide about before 1.0.
- [ ] **Enforced at the `notify*` seams**, by filtering the global list on the way out. A flag that listeners are asked to honour would not be enforcement, and the listener that ignores it is the one the feature exists for.
- [ ] **The named form is a deny-list and fails open** — register a new global listener next year and owners that carefully disabled the old ones will not disable it. Accepted, given the sizing above, and it must be said plainly in the JavaDoc rather than left for a reader to infer a guarantee that was never offered.
- [ ] **A disable covers exactly the def it is declared on, never its children.** An operation that disables its global action listeners does not disable its members'; a transition that disables its global transition listeners does not touch the action listeners of the actions it runs, nor the state listeners of the states it moves between. PII-handling defs are few, so specificity beats inheritance — and inheriting would make an action's observers depend on its call site, which contradicts §2.2.10.
- [ ] **The category follows the owner.** "All globals" means all global *action* listeners on an action, all global *transition* listeners on a transition, all global *state* listeners on a state. No cross-category reach, and therefore no implicit downgrades: an earlier proposal had a marked transition silently hand global state listeners a `null` context, which was rejected — sensible in the moment, guaranteed WTF later. The consequence of the uniform rule (a transition's context still reaches global state listeners unless the state disables them too) gets documented, not patched over.
- [ ] **An unknown or non-global id fails the build.** A typo in a deny-list silently protects nothing, and that is the one failure mode this design cannot absorb. The id must name a registered global listener of the owner's own category — checked at build, for the same reason listener-id uniqueness is: a def holds no reference back to the SM def.

**DSL shape — settled.**
- [ ] **`disableGlobalListener(String id)`, repeatable**, with the `Identifiable` sibling the overload-parity rule requires. Chained one-per-call matches the rest of the DSL, which has no varargs anywhere today — pre-conditions, triggers and listeners are all declared this way. Repeats of the same id collapse: the disable list is a set, so declaring one twice is a no-op rather than an error.
- [ ] **`disableAllGlobalListeners()` wins.** When both forms appear on the same def, the blanket one takes effect and the named list becomes irrelevant — no error, and no sensitivity to the order the two were called in.
- [ ] **Carried by `ActionDef` (hence step / operation / conditional), `TransitionDef`, and `StateDef`** — one per listener category, each owner disabling globals of its own kind.
- [ ] **`disable` prefix, not `with`.** It is not a property setter. If a *configurable* form is ever wanted — binding the decision to a host config flag rather than declaring it — that one takes the setter convention: `withDisableAllGlobalListeners(config.isDisableAllGlobalListeners())`. Recorded so the naming is not re-litigated; no current need.

**DSL shape — open.**
- [ ] **Whether a plural bulk form joins the singular one.** It is less boilerplate for a consumer disabling three listeners at once, and it is safely deferrable: adding `disableGlobalListeners(...)` alongside the singular later is purely additive, breaks nothing, and can be decided on evidence once the singular is in use. Two notes for whoever decides. (a) The YAML argument is weaker than it looks — the mapper is code, not a DSL user, so it can loop over a YAML list into repeated singular calls; it needs no bulk entry point of its own. (b) Both bulk spellings collide with the `Identifiable` parity rule and need care: `(String...)` plus `(Identifiable...)` makes the no-argument call ambiguous, so it would have to be spelled `(String first, String... rest)`; and `Collection<String>` plus `Collection<Identifiable>` share an erasure and will not compile as overloads at all, so a collection form could only ever carry one of the two types.

#### 4.7.4 Specifications
- [ ] Specs asserting level discipline where it is a contract rather than taste: no INFO on the per-transition path, and the trigger scan explaining every skip reason.
- [ ] Specs for the shipped listeners (attachment through the sugar, level routing, the `includeContext` switch).
- [ ] Whatever the §4.7.3 decision requires.
