> Part of the [Transflux roadmap](../../todo.md). Forward-looking; not yet started. Design: [docs/design/contracting-sample.md](../design/contracting-sample.md).

## Contracting Sample Application (alongside Phase 6, v0.6.0 → v1.0.0)
*Target: every item of the 1.0 contract demonstrated in one domain, in both DSLs, and both DSLs fine-tuned against a real caller before the API sign-off.*

**Shape of the track.** It starts once Phase 5 has shipped and interleaves with Phase 6: S1–S6 need nothing from Phase 6 and can run before or between its items; S7–S9 each wait for one Phase 6 item and are the first out-of-repo-style caller of what that item ships. Each item is sized to one working session. A machine item delivers the Java definition, its YAML twin, the parity spec and the `PATTERNS.md` sections it is the primary showcase for — the two DSLs are written side by side, since comparing them is half the point.

| Item | Output | Depends on |
| --- | --- | --- |
| S1 Scaffold, domain, fakes | module, entities, stores, fake services | Phase 5 |
| S2 `Proposal` | machine ×2 DSLs, specs | S1 |
| S3 Escrow components and `Offer` | shared components, machine ×2, specs | S1; Phase 5 §5.4 (entity-type contravariance) |
| S4 `Milestone` | machine ×2, specs | S3 |
| S5 `Contract`: shared set and fixed-price | libraries, machine ×2, specs | S3 |
| S6 `Contract`: hourly and milestone-based; cross-machine; hot-swap | two machines ×2, scenario specs | S4, S5 |
| S7 `ComponentRegistry` and `ComponentFactory` adoption | `registry/` package, `SampleServices` removed | S5; Phase 6.2 |
| S8 Spring sibling | module, REST surface, integration specs | S6, S7; Phase 6.1 |
| S9 `MetricsCollector` stub | one class, wired in both modules | S1; Phase 6.3 |
| S10 Close | `PATTERNS.md` and README complete, reverse index verified | all |

**Provisional items.** S7–S9 are written against the assumptions in the design's §7. When the Phase 6 item lands differently, the item here is re-cut rather than the Phase 6 item bent to fit.

### Findings
*Standing item. A DSL friction point found while writing the sample is recorded here with the item that found it, then fixed in the core (and in `requirements.md`, `JavaDslSurface`, the YAML corpus) — not worked around in the sample. The list must be empty or explicitly deferred before Phase 6 §6.9's API sign-off.*

- [x] Registrations were invariant in the entity type, so a component shared across entity types had to be a generic class. Decided: accept `? super T`; scheduled as Phase 5 §5.4. (Found by the design, before S1.)

### S1 Scaffold, Domain, Fakes
- [ ] `transflux-examples-contracting` module in the multi-module build; depends on the core and `transflux-yaml`; `maven.deploy.skip`; Spock + Groovy test deps; Surefire's `**/*Spec` include inherited.
- [ ] Builds in the GitHub Actions workflow; a failing sample fails the build.
- [ ] `domain/`: `Proposal`, `Offer`, `Contract`, `Milestone`, `Money`, `Party`, `ContractType`, status enums.
- [ ] `support/`: `InMemoryStore<T>`; `FakeEscrowService`, scriptable to throw `InsufficientFundsException` and `EscrowProviderException` (outcome known / unknown); `FakeNotificationService`; a domain-event sink specs can read.
- [ ] Skeleton `README.md` and `PATTERNS.md` with the section list from the design's §8.

### S2 `Proposal`
- [ ] `machines/ProposalMachine` and `machines/proposal.yml` per design §4.1 — YAML uses the `expression:` resolver and applier.
- [ ] Shared `withdraw` trigger on two transitions; `Void` context; predicate and auto-id expression pre-conditions; async state entry listener; `withExecutionLogging` with an entity label.
- [ ] `ProposalService`: fire, read `TransitionResult`.
- [ ] Specs: happy path; pre-condition rejection notifies nothing; wrong-state fire; non-null context against `Void`; parity.
- [ ] `PATTERNS.md`: resolver and applier; condition forms; manual and shared triggers.

### S3 Escrow Components and `Offer`
- [ ] `components/escrow/` with `EscrowComponents.register(def)` and `components/escrow.yml`: `reserve-escrow`, `capture-funds`, `record-ledger-entry`, `release-escrow`, the `fund-escrow` operation, `is-fully-funded`, `capture-funds`' `onError` audit listener.
- [ ] Compensation per design §5.1: fallback, two routes (one guarded), `getCompensation` on `record-ledger-entry`.
- [ ] `machines/OfferMachine` and `offer.yml` per design §4.2: class resolver and applier; `fund` and `retry-funding` sharing `fund-escrow` through `escrow-from-funding` with write-back; post-condition; `escrow-settled` event trigger with filter; forked `notify-parties`; one inline listener serving `fund`'s three hooks.
- [ ] `OfferService`: the host-fired `mark-funding-failed` follow-up; `Contract` creation and initial-state placement.
- [ ] Specs: insufficient funds → routed release → `funding-failed` → `retry-funding`; unknown-outcome provider error → reconciliation route; post-condition rejection drains after a successful capture; routes see the failure that ended the transition; `mapFrom` write-back; an event whose filter passes nothing; parity.
- [ ] `PATTERNS.md`: compensation; failure as a state (with the data-trigger alternative as a contrast snippet); one target per transition; contexts and mapping.

### S4 `Milestone`
- [ ] `machines/MilestoneMachine` and `milestone.yml` per design §4.4, reusing `fund-escrow` through `escrow-from-milestone-funding`.
- [ ] Revision cycle; dispute path on the shared dispute conditions (stubbed here if S5 has not landed, moved into the library there).
- [ ] Specs: revision re-entry; LIFO within `fund` and within `release`, and that a failed `submit` unwinds nothing of `fund`; a refund compensation that throws mid-drain; the action listener on `capture-funds` firing from this machine too; parity.

### S5 `Contract`: Shared Set and Fixed-Price
- [ ] `components/disputes/`, `components/notifications/` with their `register(def)` methods and YAML libraries; `ContractMachines.define(ContractType)` with the common states and transitions.
- [ ] Fixed-price variant and `contract-fixed-price.yml`: submission / revision / approval; the two dispute-resolution choices (`onNoMatch(ERROR)` without a default; a default branch); completion body with a nested inline operation, inline contexts pass-through and mapped.
- [ ] Listeners per design §5.6: transition audit branching on `firedBy`; registered `audit-trail` attached per owner and globally; `onAny*`; `disableAllGlobalListeners()` on `verify-payout-details`.
- [ ] Forks per design §5.5: the three rejection rungs; mapper, `ForkableContext` and shared-context branches.
- [ ] Specs: dispute branches and `TransfluxNoMatchException`; lexical-visibility build failure; disable semantics (global suppressed, own listener kept); a refused submission under each rung; reentrancy and the async-branch ban; parity.
- [ ] `PATTERNS.md`: step / operation / choice; listeners; forking; sharing components.

### S6 `Contract`: Hourly and Milestone-Based; Cross-Machine; Hot-Swap
- [ ] Hourly variant and `contract-hourly.yml`: `period-in-review`; `approve-period` and the `auto-release` data trigger on one transition; `settle-period` dispatching and forking from its body; explicit `withAsyncPool(...)`; `disableGlobalListeners(ids)` against the logging ids.
- [ ] Milestone-based variant and `contract-milestone.yml`: `milestone-settled` event trigger filtered on all milestones settled.
- [ ] `MilestoneService` → `ContractService` hand-off; cancellation refund cascade; the listener-based anti-pattern as a commented contrast.
- [ ] Hot-swap scenario per design §5.7 through a `FileSystemDefinitionSource`.
- [ ] `Main.java` scenarios: fixed-price happy path from proposal to release; funding failure and retry; dispute resolution; hourly tick with nothing eligible, then auto-release; milestone-based completion; hot-swap.
- [ ] Specs: tick matching nothing (`ProcessResult.fired()` false); auto-release blocked then allowed; contract completes only when every milestone is settled; cascade; generations across the swap; parity ×2.
- [ ] `PATTERNS.md`: event and data triggers; cross-machine coordination; Java or YAML; hot-swap.

### S7 `ComponentRegistry` and `ComponentFactory` Adoption *(provisional)*
- [ ] `registry/`: the shared components contributed through a `ComponentRegistry`; one machine built from it, asserted equivalent to its hand-registered twin.
- [ ] Components take collaborators through constructors; `support/SampleServices` deleted; YAML `class:` instantiation goes through the factory.
- [ ] `PATTERNS.md`: wiring without DI; the registry form of sharing.

### S8 Spring Sibling *(provisional)*
- [ ] `transflux-examples-contracting-spring`: Spring Boot app on the plain module's domain and components; `@EnableTransflux`; components as beans; machines as beans, Java and YAML one each.
- [ ] REST: manual triggers (`POST /proposals/{id}/submit`), events (`POST /webhooks/escrow`), data tick (`POST /admin/tick`).
- [ ] One `@bean` SpEL condition; `TransfluxConfiguration` with a flow label; configuration properties for the fake escrow's behaviour.
- [ ] Integration specs over the REST surface; module `README.md` with the endpoint catalog.
- [ ] `PATTERNS.md`: Spring wiring.

### S9 `MetricsCollector` Stub *(provisional)*
- [ ] `support/LoggingMetricsCollector`, wired in both modules.
- [ ] `PATTERNS.md`: what the SPI is for beside execution logging and the framework loggers; pointer to the post-1.0 Micrometer integration.

### S10 Close
- [ ] `PATTERNS.md`: every section populated, each citing a sample location, each with its anti-pattern; provisional markers removed or the section cut with its feature.
- [ ] Module `README.md`: build and run; a tour following the design's reverse index.
- [ ] Reverse index (design §6) verified row by row against the 1.0 contract summary in `todo.md`.
- [ ] Findings list empty or explicitly deferred.
- [ ] Core `README.md` references the sample as the learning resource.
- [ ] `CONTRIBUTING.md` reviewer-checklist item (with Phase 6.5).
