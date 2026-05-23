# Transflux Examples — Contracting Sample Application

## Development Plan

This document tracks phased delivery of the contracting sample application defined in `examples-app-plan.md`. It is the sibling of `todo.md`: each sample-app phase is gated on the matching core-library phase landing first.

The sample is **not** a separate product. It builds against `HEAD` of the core library and ships as a Maven module in this repository. No Maven Central publishing; no independent versioning.

---

## Phase Dependencies

| Sample phase | Depends on core phase | Why |
|---|---|---|
| 4.x — Scaffold & first machine | Core Phase 4 (compensation engine, async, error handling) | The sample's pedagogical value is in failure handling. Showing composite operations + compensation honestly requires the full compensation engine. |
| 5.x — DSL parity & multi-variant `Contract` | Core Phase 5 (YAML DSL, component library) | YAML twins and shared component-library reuse cannot land before the YAML DSL exists. |
| 6.x — Spring sibling, observability, polish | Core Phase 6 (Spring integration, `MetricsCollector` SPI) | Spring sibling depends on Spring auto-config. Observability stub depends on the SPI being defined. |

There is no Phase 1–3 sample work. Earlier phases of the core library do not yet produce enough surface to make a coherent sample worth maintaining; the features they introduce are exercised retroactively as Phase 4.x lands.

---

## Phase 4.x: Scaffold & First Machine (alongside core v0.4.0)

*Target: Plain-Java sample module exists, builds in CI, exercises the framework's failure-handling surface end-to-end with the `Proposal`, `Offer`, and fixed-price `Contract` machines plus the `Milestone` machine.*

### 4.1 Module Scaffold
- [ ] Create `transflux-examples-contracting/` Maven module.
  - [ ] `pom.xml` with parent reference, `transflux-core` dependency at current snapshot, Spock + Groovy test deps.
  - [ ] Module declared in parent `pom.xml` and excluded from any release-deployment configuration.
  - [ ] Java 11 target consistent with core; toolchain uses JDK 21 to build.
- [ ] Module added to the GitHub Actions build matrix; failing sample build fails the PR.
- [ ] Skeleton `README.md` (what the module is, how to run scenarios from `Main.java`).
- [ ] Skeleton `PATTERNS.md` with the section list from `examples-app-plan.md` §5.2; sections marked "TBD" until populated in the relevant phase.

### 4.2 Domain Model
- [ ] `Proposal`, `Offer`, `Contract`, `Milestone` POJOs under `domain/`.
- [ ] Supporting value types: `Money`, `Party` (client/provider IDs), `ContractType` enum, status enums per entity.
- [ ] `support/InMemoryStore<T>` for each entity type — no JPA, no Spring data, no actual database.
- [ ] Fake services under `support/`: `FakeEscrowService` (rejects on configurable inputs to demonstrate `InsufficientFundsException` / `EscrowProviderException` flows), `FakeNotificationService`.

### 4.3 `Proposal` State Machine
- [ ] `machines/ProposalStateMachine.java` — Java DSL definition.
- [ ] Lambda-based `StateResolver` (single enum field).
- [ ] Manual triggers: `submit`, `withdraw`, `accept`, `decline`.
- [ ] Pre-condition demonstrating predicate form and inline expression form.
- [ ] State entry listener emitting a domain event on `submitted`.
- [ ] `ProposalStateMachineSpec` — happy path + at least three failure scenarios.
- [ ] In-code pattern comments at each canonical decision point.

### 4.4 `Offer` State Machine
- [ ] `machines/OfferStateMachine.java` — Java DSL definition.
- [ ] Class-based `StateResolver` deriving state from multiple fields.
- [ ] Composite operation on `extended → funded`:
  - [ ] Step: validate terms (predicate condition).
  - [ ] Step: reserve escrow (side-effecting, with compensation).
  - [ ] Step: confirm funding (side-effecting, with compensation).
- [ ] Exception-specific compensation:
  - [ ] `InsufficientFundsException` → release reservation + route to `funding-failed`.
  - [ ] `EscrowProviderException` → retry-then-release.
- [ ] Post-condition: `funded` state requires escrow balance ≥ offer amount.
- [ ] Cross-machine handoff: `contract-created` transition instantiates the matching `Contract` machine.
- [ ] `OfferStateMachineSpec` — happy path, every exception branch, post-condition violation rollback, partial-funding rollback.

### 4.5 `Contract` State Machine — Fixed-Price Variant
- [ ] `machines/ContractStateMachine.java` — Java DSL, fixed-price wiring only at this stage.
- [ ] Multi-branch conditional operation on `disputed → resolved-*`.
- [ ] Transition listeners (start / complete / error) logging the contract lifecycle.
- [ ] Async operation anchored via `startAfter` on `completed` for notification fan-out.
- [ ] Async operation anchored via `startBefore` on `funded` for parallel risk-check.
- [ ] Async compensation scenario: in-flight notification when the contract is cancelled mid-flight.
- [ ] `ContractStateMachineSpec` — fixed-price flows, dispute branches, async cancellation, reentrancy negative path.

### 4.6 `Milestone` State Machine
- [ ] `machines/MilestoneStateMachine.java` — Java DSL definition.
- [ ] Conditional re-entry: `revision-requested → submitted` cycle.
- [ ] LIFO compensation across `defined → funded → submitted`.
- [ ] Event emission consumed by the milestone-based variant of `Contract` (wired in Phase 5.x once the milestone-based variant lands).
- [ ] `MilestoneStateMachineSpec` covering all paths including the revision cycle and refund-failure scenarios.

### 4.7 Scenarios and Pattern Documentation
- [ ] `Main.java` with runnable scenarios:
  - [ ] Happy-path fixed-price contract from proposal to release.
  - [ ] Funding failure with full compensation.
  - [ ] Dispute escalation and resolution.
  - [ ] Mid-flight cancellation with async work in progress.
- [ ] `PATTERNS.md` sections populated for everything exercised in Phase 4.x:
  - [ ] Resolver/applier forms.
  - [ ] Condition Descriptor forms (the four-grammar — without YAML variants yet).
  - [ ] Step vs. simple operation vs. composite operation.
  - [ ] Compensation: declaration by class vs. dynamic from `getCompensation`.
  - [ ] Exception-specific compensation routing.
  - [ ] State listeners vs. transition listeners.
  - [ ] Async anchors.
- [ ] Cross-link `PATTERNS.md` sections from in-code comments (file:line citations).

### 4.8 Quality Gates (Phase 4.x)
- [ ] Spock coverage ≥ 80% for the sample module.
- [ ] Failure-path content is ≥ 60% of the sample's transition definitions and Spock specs by count.
- [ ] Every Phase 1–4 core feature appears in the reverse-index table (`examples-app-plan.md` §4) and is exercised by at least one location in the sample.

---

## Phase 5.x: DSL Parity & Multi-Variant Contract (alongside core v0.5.0)

*Target: YAML DSL twins of every machine, the milestone-based and hourly contract variants via a shared component library, and DSL parity proven by spec.*

### 5.1 YAML Twins
- [ ] `src/main/resources/machines/proposal.yaml`.
- [ ] `src/main/resources/machines/offer.yaml`.
- [ ] `src/main/resources/machines/contract-fixed-price.yaml`.
- [ ] `src/main/resources/machines/milestone.yaml`.
- [ ] Each YAML file demonstrates inline definitions, references, and component-library use as appropriate.
- [ ] DSL parity spec per machine: build the Java-DSL version and the YAML-DSL version, assert structural equivalence and behavioral equivalence on a representative scenario.

### 5.2 Component Library
- [ ] `src/main/resources/components/` with shared definitions:
  - [ ] Conditions reused across `Contract` variants and `Milestone` (e.g., `is-fully-funded`, `is-overdue`, `is-dispute-eligible`).
  - [ ] Steps reused across variants (e.g., release-escrow, notify-parties).
  - [ ] Listeners reused across machines.
- [ ] Cross-file references with the Reference Grammar (bare string, inline block, long-form `ref`).
- [ ] Import-resolution spec covering all three reference forms.

### 5.3 Hourly Contract Variant
- [ ] `machines/contract-hourly.yaml` consuming shared components.
- [ ] Data trigger (host-driven): host batch tick re-evaluates eligible periods for auto-release.
- [ ] Spock spec covering auto-release happy path, blocked auto-release (review window not elapsed), and overlapping period boundaries.

### 5.4 Milestone-Based Contract Variant
- [ ] `machines/contract-milestone.yaml` consuming shared components.
- [ ] Event trigger wiring: `Milestone` machine emits events; `Contract` machine consumes them via `processEvent`.
- [ ] Cross-machine coordination spec: contract completes when all milestones reach `released`; contract cancellation triggers refund cascade across milestones.

### 5.5 Pattern Documentation Updates
- [ ] `PATTERNS.md` sections added/populated:
  - [ ] Inline conditions in YAML vs. component-library references.
  - [ ] Cross-machine coordination (events vs. direct invocation).
  - [ ] DSL choice: Java vs. YAML — what each is best at.
  - [ ] Component-library packaging conventions.

### 5.6 Quality Gates (Phase 5.x)
- [ ] DSL parity holds for every machine (parity spec passes).
- [ ] Every shared component is referenced from at least two machines (proves reuse, not just packaging).
- [ ] Phase 5 features in the reverse-index table are all exercised.

---

## Phase 6.x: Spring Sibling, Observability, Polish (alongside core v0.6.0 → v1.0.0)

*Target: Spring Boot variant module, observability hooks wired, full cookbook, ready for 1.0.*

### 6.1 Spring Sibling Module
- [ ] Create `transflux-examples-contracting-spring/` Maven module.
- [ ] Depend on `transflux-core` Spring integration and on the plain-Java sample for the domain model (avoids duplication).
- [ ] `@EnableTransflux` configuration.
- [ ] Spring-bean discovery for `Step`, `Condition`, `Trigger`, `Listener`, `Operation` types.
- [ ] REST endpoints (Spring MVC) fronting:
  - [ ] Manual triggers (e.g., POST `/proposals/{id}/submit`).
  - [ ] Event triggers (e.g., POST `/webhooks/escrow` → `processEvent`).
  - [ ] Data triggers (e.g., POST `/admin/tick` → `processDataChange`).
- [ ] Configuration properties for the sample (escrow simulation behavior, batch-tick cadence).
- [ ] Spock integration specs hitting the REST surface.
- [ ] `README.md` for the Spring module: how to run, what each endpoint demonstrates.

### 6.2 Observability Stub
- [ ] `MetricsCollector` implementation under `support/` logging hook invocations.
- [ ] Wired in both the plain-Java and Spring modules.
- [ ] Section in `PATTERNS.md` covering the `MetricsCollector` SPI: when to write one, what to record, and pointers to the post-1.0 Micrometer integration.

### 6.3 Documentation Polish
- [ ] `PATTERNS.md` complete:
  - [ ] Every section populated.
  - [ ] Each section references at least one canonical sample location (file:line).
  - [ ] Anti-pattern callouts for each section.
- [ ] Module `README.md` walkthrough:
  - [ ] Getting started: clone, build, run `Main.java`.
  - [ ] Tour by feature: a numbered tour matching the reverse-index table.
  - [ ] Spring variant: how to run, endpoint catalog.
- [ ] Add `CONTRIBUTING.md` reviewer-checklist item: PATTERNS.md is reviewed when a core API change affects a documented decision point.

### 6.4 Reverse-Index Verification
- [ ] Every item in the v1.0.0 contract summary (`todo.md`) is exercised by at least one location in the sample. Verified by checklist in this section.
- [ ] Reverse-index table in `examples-app-plan.md` §4 reviewed and updated to reflect the final shape of the sample.

### 6.5 Quality Gates (Phase 6.x — 1.0 readiness)
- [ ] Both sample modules build green in CI.
- [ ] Spock coverage ≥ 80% for both modules.
- [ ] No critical or high-severity security findings against sample code.
- [ ] PATTERNS.md complete and reviewed.
- [ ] Reverse-index table verified.

---

## 1.0 Readiness Contribution

The sample module is part of the 1.0 quality gate (proposed addition to `todo.md` §6.9). At 1.0 release time:

- [ ] Both sample modules build and test cleanly.
- [ ] Every 1.0 contract feature is demonstrated by the sample.
- [ ] PATTERNS.md is complete and reviewed alongside the core API documentation.
- [ ] The sample is referenced from the core `README.md` as the canonical learning resource.

---

## Out of Scope (Sample Module)

The following are explicitly **not** sample-module work, even after 1.0:

- **Persistence demonstration.** The sample uses in-memory stores. A persistence-themed sample lives on the 2.x roadmap once persistence itself lands.
- **Distributed scenarios.** Single JVM, single process. Always.
- **Real third-party integrations.** All external services are faked. No real payment gateway, no real notification provider.
- **Performance benchmarks.** Sample is for teaching, not for measuring. Benchmarks live elsewhere.
- **Domain comprehensiveness.** The contracting domain is a vehicle. Features the framework does not exercise are not modeled.

---

*Scope changes during delivery are recorded in `examples-app-plan.md` first, then mirrored here. Phase boundaries in this doc must remain aligned with the corresponding phase boundaries in `todo.md`.*
