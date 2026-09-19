# Contracting Sample Application

Work items and their ordering against Phase 6 live in [docs/roadmap/contracting-sample.md](../roadmap/contracting-sample.md). `requirements.md` is the spec the sample is written against; where this document and the spec disagree, the spec wins and this document is stale.

## 1. Summary

**Problem.** The Spock specs prove each primitive in isolation. Nothing shows how they compose in application code, and nothing exercises the two DSLs the way a host would — end to end, in one domain, from outside `core.impl`. `JavaDslSurface` proves every call shape compiles; it does not prove the shapes read well together.

**Decision.** Two unpublished Maven modules ship in this repository: `transflux-examples-contracting` (plain Java, both DSLs) and `transflux-examples-contracting-spring` (the Spring sibling). Four state machines over a generic contracting domain — `Proposal`, `Offer`, `Contract` in three variants, `Milestone` — each written in the Java DSL and in YAML, with a parity spec per machine. A `PATTERNS.md` cookbook at the module root is the primary reader-facing artifact; the code is its proof.

**Timing.** The sample starts once Phase 5 has shipped and runs interleaved with Phase 6. It has two jobs in that period: demonstrate the 1.0 surface, and be the last ergonomics review of both DSLs before the API sign-off in §6.9. A friction point found while writing the sample is a finding against the core, fixed there, not worked around here.

**Provisional surface.** Several 1.0 features are Phase 6 work whose shape is not settled (`ComponentRegistry`, `ComponentFactory`, Spring discovery, `MetricsCollector`, `@bean` resolution in SpEL). They are showcased too. Each is marked *provisional* in §6, isolated behind one package or one module per §7, and the sample's design for it is an assumption to be replaced, not a constraint on Phase 6.

## 2. Goals and Non-Goals

1. **Every item of the 1.0 contract** (`todo.md`, "1.0 contract summary") is exercised somewhere; §6 is the reverse index.
2. **Blessed patterns.** Each decision point a host faces has a canonical example and, where a real alternative exists, a contrasting one, explained in `PATTERNS.md`.
3. **Failure handling carries the weight.** The majority of transitions and specs are non-happy-path: compensation, routed compensation, post-condition rejection, no-match, funding failure, disputes, refused async submissions.
4. **Both DSLs, same behaviour.** Every machine exists in Java and in YAML over the same component classes, with a parity spec.
5. **A regression suite for the public API.** The sample builds in CI on every change; a core change that breaks it is a breaking change.
6. **DSL feedback before 1.0.** Findings are recorded in the roadmap file and resolved in the core.

| Non-goal | Reason |
| --- | --- |
| A real product | In-memory stores, faked escrow and notification services, no UI, no auth |
| Platform attribution | Vocabulary is generic contracting language |
| A published artifact | Not deployed to Maven Central; versions track the core exactly |
| Domain comprehensiveness | The domain models what a framework feature needs and nothing else |
| Persistence, distribution, real integrations, benchmarks | Post-1.0 themes, or not what a teaching sample is for |

## 3. Domain

Two parties, **client** and **provider**, coordinate paid work.

| Term | Meaning |
| --- | --- |
| **Proposal** | A provider's response to a posted job. |
| **Offer** | A client's formal offer to engage a provider: terms, contract type, funding intent. |
| **Contract** | The engagement. Three variants: fixed-price, hourly, milestone-based. |
| **Milestone** | A unit of work within a milestone-based contract. |
| **Escrow** | Funds held against an offer, contract or milestone. A field on the parent entity, not a machine. |
| **Dispute** | A contested submission. A state of `Contract` and of `Milestone`, not a machine. |
| **Release** | Transfer of escrowed funds to the provider. |

## 4. State Machines

### 4.1 `Proposal`

`draft` → `submitted` → (`accepted` | `declined`); `draft` | `submitted` → `withdrawn`.

The smallest machine, and the one a reader starts with.

- Lambda `StateResolver` / `StateApplier` over a single enum field — the canonical enum pairing.
- Manual triggers `submit`, `accept`, `decline`, fired with `entity(p).fire(id)`.
- **A shared trigger**: `withdraw` is registered once and attached to both `draft → withdrawn` and `submitted → withdrawn`, so the host fires it without knowing the state.
- `withdraw`'s transitions declare `Void.class` context; `submit` takes a `SubmissionContext`.
- Pre-conditions in the predicate form (`Predicate<T>`) and the inline expression form, the latter with an auto-derived id.
- A state entry listener on `submitted` publishing a domain event, declared `withAsync(...)`.
- `withExecutionLogging(...)` with an entity label.

Failure surface: pre-condition rejection (nothing notified, nothing drained), firing a trigger from the wrong state, a non-null context against `Void`.

### 4.2 `Offer`

`draft` → `extended` → (`withdrawn` | `declined` | `accepted`); `accepted` → (`funded` | `funding-failed`); `funding-failed` → `funded`; `funded` → `contract-created`.

The compensation machine.

- Resolver and applier written as **classes**: the state derives from `status` plus `fundingStatus`, and the applier writes both.
- `accepted → funded` (`fund`, context `FundingContext`) runs the registered operation `fund-escrow`; `funding-failed → funded` (`retry-funding`) runs the same operation by id.
- `fund-escrow` is written against `EscrowContext` and reached through the registered mapper `escrow-from-funding`, whose `mapFrom` writes the reservation id back. Members: `reserve-escrow`, `capture-funds`, `record-ledger-entry`, then a forked `notify-parties`.
- Post-condition `is-fully-funded` on `fund`; a rejection drains the stack like any other failure.
- Compensation per §5.1.
- `funded → contract-created` fires on the event trigger `escrow-settled` (`processEvent("ESCROW_SETTLED", payload)`), filtered on `#event.offerId == id`. Its body creates the `Contract` and places its initial state — the host owns initial-state placement.
- A state exit listener on `extended` clearing scratch fields.

Failure surface: insufficient funds, an escrow provider error with an unknown outcome, post-condition rejection after a successful capture, a filter that passes nothing (`ProcessResult.fired()` is `false`).

### 4.3 `Contract`

Common: `active` → (`completed` | `cancelled` | `disputed`); `disputed` → (`resolved-completed` | `resolved-cancelled`).

| Variant | Adds | Completion |
| --- | --- | --- |
| Fixed-price | `submitted`; `submitted → active` on a revision request | `approve` on `submitted → completed` releases the single escrow lump |
| Hourly | `period-in-review`; `active → period-in-review` on a work-log submission | `period-in-review → active` carries two triggers: manual `approve-period` and the data trigger `auto-release`, gated on the review window having elapsed |
| Milestone-based | nothing — milestones carry the work | event trigger `milestone-settled` on `active → completed`, filtered on every milestone being settled |

Three machines over one entity class; the host picks the machine by `ContractType`. What they share is one component set (§5.3), which is the showcase for component reuse in both DSLs.

- **Dispute resolution** is two transitions, since a transition has one target. Each runs a `choice` that selects the *work*: `resolve-for-provider` branches on full release / split payout, with `onNoMatch(ERROR)` and no default; `resolve-for-client` has a default branch.
- **Nested declarations**: the completion body declares an operation in place, with a step inside it, and a sibling reference that fails the build if uncommented — lexical visibility, shown as a commented negative.
- **Dispatch from a body**: the hourly `settle-period` step loops over the period's approved entries and calls `transition.run("release-escrow", mapper)` per entry — the pattern for a loop the declarative form cannot express.
- **Forked work** per §5.5; **listeners** per §5.6; **hot-swap** per §5.7.
- The reentrancy guard and the ban on driving the machine from a forked branch are negative-path specs here.

Failure surface: a dispute with no matching branch (`TransfluxNoMatchException`), cancellation with unreleased escrow, a release post-condition rejecting, a data-trigger tick that matches nothing.

### 4.4 `Milestone`

`defined` → `funded` → `submitted` → (`approved` → `released` | `revision-requested` → `submitted` | `disputed` → (`approved` | `cancelled-refunded`)).

- `defined → funded` reuses `fund-escrow` through a second mapper, `escrow-from-milestone-funding` — the same operation, a different parent context, which is what call-site mapping is for.
- `revision-requested → submitted` is a legitimate re-entry of an earlier state.
- LIFO compensation *within* `fund` and within `release`. Compensation belongs to one transition's execution; a failure in `submit` does not unwind `fund`, which committed earlier. `PATTERNS.md` says so explicitly, since Saga vocabulary suggests otherwise.
- The dispute path reuses the conditions registered for `Contract`.
- Settling a milestone (`released` or `cancelled-refunded`) notifies the parent contract per §5.2.

Failure surface: a refund that itself fails mid-drain (WARN, the rest of the stack still runs), partial revision cycles, a disputed milestone under an active contract.

## 5. Cross-Cutting Designs

### 5.1 Funding Failure: Routes Pick the Rollback, the Host Picks the Next State

A failed `fund` leaves the offer in `accepted` with the failure on `TransitionResult`. Nothing inside the framework can move it to `funding-failed`: a transition has one target, and compensations and listeners run while the entity is still guarded against reentry. So the flow is two steps, and the second is the host's:

1. `fund` fails. The stack drains. `reserve-escrow` declares routes, tried in order against the failure that ended the transition — which `capture-funds` threw, not `reserve-escrow`:
   - `forException(InsufficientFundsException.class)` → release the reservation.
   - `forException(EscrowProviderException.class).matching(EscrowProviderException::isOutcomeUnknown)` → flag the reservation for reconciliation rather than release something that may not exist.
   - the fallback `withCompensation(...)` → release the reservation.
2. `OfferService` reads `result.getError()`, and for `InsufficientFundsException` fires the manual trigger `mark-funding-failed` on `accepted → funding-failed`.

`record-ledger-entry` uses the third channel, `getCompensation(entity, context)`, for a reversal entry that needs values captured at execution time.

There is no retry: resilience patterns are post-1.0, and a compensation cannot re-run its action. `retry-funding` is a second, host-initiated transition.

| Alternative | Verdict | Reason | Revisit when |
| --- | --- | --- | --- |
| The step swallows the failure, records the outcome on the entity, and data triggers route to `funded` / `funding-failed` | Contrast example in `PATTERNS.md`, not the primary | Shows data triggers well, but nothing throws, so it demonstrates no compensation at all | — |
| A listener on `onError` fires `mark-funding-failed` | Rejected | Reentrancy guard; and a listener's failure is swallowed, so a lost state change would be silent | — |

### 5.2 Cross-Machine Coordination

Driving a *different* state machine from inside an action is permitted, from a forked branch included.

| Hand-off | Mechanism | Why |
| --- | --- | --- |
| `Offer` → `Contract` creation | a step in `funded → contract-created` | The contract must exist or the transition must fail and roll back |
| `Milestone` settled → parent `Contract` | `MilestoneService`, after a successful result, calls `contractMachine.entity(c).processEvent("MILESTONE_SETTLED", milestone)` | The milestone's transition has already committed; a failure here is the contract's, and the host must see it |
| `Contract` cancelled → refund open milestones | a step looping over milestones, firing each one's `refund` | Part of the cancellation's own outcome |

The contrasting anti-pattern, shown and explained: doing the second hand-off from a state entry listener. It works until it fails, and then nobody is told.

### 5.3 Shared Components and Contract Variants

| DSL | Sharing mechanism |
| --- | --- |
| YAML | Libraries `components/escrow.yml`, `components/disputes.yml`, `components/notifications.yml`; three contract roots and the milestone root import them. Diamond imports occur naturally. |
| Java | `EscrowComponents.register(def)` and siblings — a method taking the definition (§4.1.2 of the spec). |
| Java, DI (*provisional*) | The same components contributed by a `ComponentRegistry`; see §7. |

Components shared across entity types — the escrow set serves `Offer` and `Milestone` — are written against an interface the entities share (`Escrowed`) and registered as they are: registrations accept `Action<? super T, C>` and its counterparts (Phase 5 §5.4).

Java variants are one method, `ContractMachines.define(ContractType)`, which registers the shared set and adds the variant's states and transitions.

### 5.4 Contexts and Mappers

| Shape | Where |
| --- | --- |
| Transition-declared context | `FundingContext` on `fund`, `DisputeResolutionContext` on the two resolution transitions |
| `Void` context | `Proposal.withdraw` |
| `Object`-typed component tolerating `null` | `notify-parties` |
| Registered mapper with write-back | `escrow-from-funding` |
| Second mapper into the same callee | `escrow-from-milestone-funding` |
| Inline `ContextMapper` lambda, read-only | `notification-from-funding` at a forked call site |
| Inline declaration with its own context, pass-through and mapped | the contract completion body |
| YAML `mapTo:` / `mapFrom:` expressions | the YAML twins of the first three |

### 5.5 Forked Work and Rejection

Forked members are notifications, ledger mirroring and fraud-signal emission — work whose loss or lateness does not change the transition's outcome. Nothing forked gates anything, and nothing is cancelled.

| Rung | Example |
| --- | --- |
| Machine default | `DROP`, left unstated |
| The def | `mirror-ledger-entry` declares `CALLER_RUNS`: an audit write is never droppable |
| The position | `fork("notify-parties", FAIL)` on the dispute-resolution body, where a lost notification is a compliance problem; plain `fork("notify-parties")` elsewhere |

Context precedence is shown once each: a mapper at the fork, a `ForkableContext` (`NotificationContext`), and a deliberately shared immutable context. `withAsyncPool(...)` is declared explicitly on the hourly machine, whose only fork is issued from `settle-period`'s body and is invisible to the build.

### 5.6 Listeners and Logging

| Feature | Where |
| --- | --- |
| State entry / exit | `Proposal.submitted`, `Offer.extended` |
| Transition start / complete / error, branching on `firedBy` | `Contract` lifecycle audit |
| Action listeners on a def, firing at every call site | `capture-funds`' `onError` audit, reached from `Offer` and `Milestone` |
| Registered listener attached to several owners and globally | `audit-trail` |
| One inline listener serving an owner's three hooks | `fund` on `Offer` |
| `onAny*` global listeners | `Contract` |
| Async listener, `CALLER_RUNS` | domain-event publisher |
| `disableAllGlobalListeners()` | `verify-payout-details`, whose context carries bank details; its own redacting listener stays |
| `disableGlobalListeners(ids)` against `ExecutionLogging.*_LISTENER_ID` | a high-volume step inside `settle-period` |
| `withExecutionLogging(...)` | every machine; `includeContext` off |

### 5.7 Definition Sourcing and Hot-Swap

The hourly contract's review window and auto-release gate live in its YAML root. A scenario loads it through a `FileSystemDefinitionSource`, runs a period under generation 1, rewrites the file, calls `replaceDefinition`, and runs the next period under generation 2, with a transition in flight across the swap finishing on the old topology. The other machines load through `ClasspathDefinitionSource`.

## 6. Feature → Showcase Index

Status: **settled** — shape fixed by `requirements.md` and Phase 5; **provisional** — Phase 6, see §7.

| 1.0 feature | Primary showcase | Contrast | Status |
| --- | --- | --- | --- |
| Lambda resolver / applier | `Proposal` | `Offer` | settled |
| Class resolver / applier | `Offer` | `Proposal` | settled |
| SpEL resolver / applier (YAML-only) | `proposal.yml` | the Java twin's lambdas | settled |
| Step, registered and inline | `escrow` components; contract completion body | — | settled |
| Operation, registered, reused by id | `fund-escrow` on three transitions | — | settled |
| Nested inline operation, lexical visibility | contract completion body | — | settled |
| Choice, `onNoMatch`, default branch | the two dispute resolutions | `Milestone.submitted` routing | settled |
| Dispatch from a step body (`run`, `fork`) | `settle-period` | the declarative bodies | settled |
| Condition: reference | `is-fully-funded` across `Offer`, `Contract`, `Milestone` | — | settled |
| Condition: instance (Java) / `class:` (YAML) | `has-valid-terms` | — | settled |
| Condition: predicate, both arities | `is-overdue`, `Proposal` pre-conditions | — | settled |
| Condition: expression, auto-id | `Proposal.submit` | — | settled |
| Pre- / post-conditions | `Offer.extend`, `Offer.fund` | `Milestone.release` | settled |
| LIFO compensation, container's additive compensation | `fund-escrow` | `Milestone.release` | settled |
| Compensation channels: def, routes with guard, `getCompensation` | §5.1 | — | settled |
| Context mapping, all shapes | §5.4 | — | settled |
| Manual trigger; shared trigger | `Proposal` | `Contract.cancel` | settled |
| Event trigger with filter; `ProcessResult` | `Offer.escrow-settled` | `Contract.milestone-settled` | settled |
| Data trigger, host-driven tick | hourly `auto-release` | §5.1's alternative | settled |
| Listeners, all three categories, global, async, disabled | §5.6 | — | settled |
| Forked members, three rejection rungs, `ForkableContext` | §5.5 | — | settled |
| Reentrancy guard; async-branch ban | `Contract` negative specs | — | settled |
| Exception hierarchy (`TransfluxConditionException`, `TransfluxNoMatchException`, `TransfluxContextException`) | `OfferService`, `ContractService` error handling | — | settled |
| `TransitionResult` paths and timings | every spec | — | settled |
| YAML DSL, libraries, imports, parity | all machines | — | settled |
| `DefinitionSource`, `replaceDefinition` | §5.7 | — | settled |
| Shipped execution logging | every machine | — | settled |
| `ComponentRegistry`, manual wiring | `registry/` package | §5.3's plain-method form | provisional |
| `ComponentFactory` behind YAML `class:` | components with constructor dependencies | the no-arg components | provisional |
| Spring auto-configuration, bean discovery | the Spring module | the plain module's `Main` | provisional |
| `@bean` references in SpEL | one condition in the Spring module's YAML | — | provisional |
| `TransfluxConfiguration`, flow labels | the Spring module | — | provisional |
| `MetricsCollector` SPI | console-logging stub in `support/` | — | provisional |

## 7. Provisional Surface

Each row is a Phase 6 item. The sample's assumption is written down so that the difference is visible when the real shape lands; the isolation column is what keeps the rewrite small.

| Feature | The sample assumes | Isolated in | Revisit when |
| --- | --- | --- | --- |
| `ComponentRegistry` | The sketch in spec §4.1.3: factory methods for object-shaped kinds. Def-shaped kinds (operations, choices, triggers) stay in `*Components.register(def)` until the registry says how it contributes them | `registry/` package; machines depend on ids only | Phase 6.2 |
| `ComponentFactory` | It widens Phase 5.7's instantiation seam; until then components reached from YAML keep no-arg constructors and take collaborators from a static `SampleServices` holder | `support/SampleServices`, deleted when the factory lands | Phase 6.2 |
| Spring discovery | Beans implementing `Action`, `Condition`, `ContextMapper` and the listener interfaces are discoverable by bean name as id. Which kinds, and how a context type is declared on a bean, are open | the Spring module only; the plain module has no Spring dependency | Phase 6.1 |
| `@bean` in SpEL | Resolves through the Spring integration; fails at first evaluation without it | one condition, Spring module's YAML only | Phase 6.1 |
| `TransfluxConfiguration` | Carries metrics and the flow label, not async settings | Spring module configuration class | Phase 6.1 / 6.3 |
| `MetricsCollector` | Spec §4.10.3's two methods plus whatever hook points 6.3 settles; the stub implements every method by logging | one class in `support/` | Phase 6.3 |

If a Phase 6 item is cut or reshaped beyond these assumptions, §6 loses or changes the row; the sample does not keep a feature alive.

## 8. `PATTERNS.md`

One section per decision point, each with: the situation, the forms available, which to pick, cross-references into the source, and the anti-pattern. In-code comments at each canonical decision point are one to three lines of *why this form here* and point to the section rather than repeat it.

- Resolver and applier: lambda, class, YAML expression.
- Condition forms; registered vs. inline.
- Step, operation or choice; registered vs. declared in place; when a loop forces a step body.
- A transition has one target: outcome-dependent states are separate transitions (§5.1, §4.3's disputes).
- Compensation: the three channels, route ordering, what the routed failure is, what LIFO does and does not span.
- Failure as a state: host-fired follow-up vs. outcome-on-entity with data triggers.
- Contexts: transition context, `Void`, `Object`; when to map; write-back.
- Manual, event and data triggers; shared triggers; reading `ProcessResult`.
- State, transition and action listeners; observe-don't-gate; global listeners and disabling them; async listeners.
- Forking: what is safe to fork, the three rejection rungs, context precedence.
- Cross-machine coordination: action, host service, and why not a listener.
- Sharing components: YAML libraries, a Java method, a registry *(provisional)*.
- Java or YAML; hot-swapping a definition.
- Wiring: plain Java, `ComponentRegistry`, Spring *(provisional)*.
- Observability: execution logging, framework loggers, `MetricsCollector` *(provisional)*.

## 9. Module Structure

Both modules join the multi-module build Phase 5.7 creates.

| Path | Contents |
| --- | --- |
| `transflux-examples-contracting/PATTERNS.md`, `README.md` | the cookbook; how to run |
| `.../contracting/domain/` | entities, `Money`, `Party`, `ContractType`, status enums |
| `.../contracting/components/` | actions, compensations, conditions, mappers, listeners, grouped by concern (`escrow`, `disputes`, `notifications`), each with its `*Components.register(def)` |
| `.../contracting/machines/` | Java DSL definitions, one class per machine |
| `.../contracting/service/` | host services: fire, read the result, coordinate across machines |
| `.../contracting/registry/` | *provisional* — `ComponentRegistry` wiring |
| `.../contracting/support/` | in-memory stores, fake escrow and notification services, `MetricsCollector` stub |
| `.../contracting/Main.java` | runnable scenarios |
| `src/main/resources/machines/` | `proposal.yml`, `offer.yml`, `contract-fixed-price.yml`, `contract-hourly.yml`, `contract-milestone.yml`, `milestone.yml` |
| `src/main/resources/components/` | YAML libraries |
| `src/test/groovy/` | Spock specs per machine, parity specs, scenario specs |
| `transflux-examples-contracting-spring/` | *provisional* — Spring Boot app depending on the plain module for domain and components; REST endpoints fronting `fire`, `processEvent`, `processDataChange` |

GroupId `org.transflux`; package root `org.transflux.examples.contracting`; `maven.deploy.skip` on both. The plain module depends on the core and `transflux-yaml` and on nothing Spring.

## 10. Maintenance Contract

- Both modules build and test in CI on every change. A core change that breaks them is a breaking change to the public API, whatever the core's own specs say.
- Versions track the core. Nothing is published.
- `PATTERNS.md` is reviewed with any core API change touching a documented decision point; the reviewer-checklist item goes into `CONTRIBUTING.md` with Phase 6.5.
- §6 is kept current; it is a 1.0 quality gate (Phase 6 §6.9).

## 11. Open Questions

| Question | Who answers | What changes |
| --- | --- | --- |
| Does `transflux-examples-contracting-spring` depend on a separate Spring integration artifact, or is the integration an optional dependency of the core? | Phase 6.1 | The Spring module's POM only |
| How does a `ComponentRegistry` contribute def-shaped kinds? | Phase 6.2 | Whether `*Components.register(def)` survives beside the registry or is replaced by it |
