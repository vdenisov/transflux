# Transflux Examples — Contracting Sample Application

## Plan & Design

This document is the canonical plan for the **contracting sample application**, a reference module shipped alongside the Transflux core library. It is a sibling to `requirements.md` and `todo.md`; phased delivery is tracked in `examples-app-todo.md`.

The sample is pedagogical, not productized. Its job is to demonstrate every component on the 1.0 contract surface in a single coherent domain, while teaching consuming developers *which form of each abstraction to reach for and why*.

---

## 1. Purpose

### 1.1 Why a Sample App Is a 1.0 Deliverable

The Spock specifications prove that each Transflux primitive works in isolation. They do not — and cannot — show how the primitives *compose* in realistic application code: how a `StateResolver` pairs with a `StateApplier`, when to elevate a step into a `SimpleOperation`, when an inline expression condition is appropriate versus a class-based one, how compensation chains read across a multi-step composite operation, and what the line looks like between domain code and framework code.

A cohesive sample is the only artifact that answers those questions. It also functions as the most honest API-surface review available before the 1.0 contract freezes — ergonomics issues that unit specs cannot surface (verbose builder chains, awkward generics, surprising default behavior) show up immediately when an entire domain is wired end-to-end.

### 1.2 Goals

1. **Showcase every 1.0 feature** in a single coherent application. Every item on the 1.0 contract summary (`todo.md` v1.0.0 Release section) is exercised somewhere in the sample.
2. **Establish blessed patterns.** For each major decision point — resolver/applier form, condition form, step vs. operation, trigger type, listener placement, compensation shape — the sample contains a canonical example *and* a contrasting example, with prose explaining when each is appropriate.
3. **Demonstrate failure handling.** Roughly 60% of the sample's surface area (by transition count and by line count) is dedicated to non-happy-path behavior: compensation, exception-specific routing, post-condition violations, conditional rollback, dispute paths, funding failures.
4. **Serve as a living regression suite.** The sample is built and tested in CI on every PR; an API change that breaks the sample is treated as a breaking change.
5. **Provide DSL parity proof.** Every state machine in the sample is expressed in both the Java DSL and the YAML DSL, with a parity spec proving the two produce equivalent runtime instances.

### 1.3 Non-Goals

- **No real product.** The domain is deliberately thin. No payment-gateway integration, no real authentication, no UI, no persistence beyond in-memory stores.
- **No platform attribution.** The vocabulary (proposal / offer / contract / milestone / dispute / release) is generic contracting language. No reference to any specific platform.
- **No published artifact.** The sample module is not deployed to Maven Central. It exists to be read, run locally, and depended on by Transflux's own CI.
- **No exhaustive domain coverage.** The sample contains exactly the contract types and states needed to exercise framework features. It is not an attempt to model real-world contracting comprehensively.

---

## 2. Domain Overview

The sample models a generic contracting workflow between two parties (referred to as **client** and **provider**) coordinating paid work.

### 2.1 Entity Vocabulary

| Term | Meaning |
|------|---------|
| **Proposal** | A provider's response to a posted job, expressing interest and terms. |
| **Offer** | A client's formal offer to engage a provider; precedes a contract. Carries proposed terms, contract type, and funding intent. |
| **Contract** | The active engagement. Three variants: **fixed-price**, **hourly**, **milestone-based**. |
| **Milestone** | A unit of work within a milestone-based contract: defined, funded, submitted, approved, released, or disputed. |
| **Escrow** | Funds held against a contract or milestone. Modeled as a field on the parent entity, not a separate state machine. |
| **Dispute** | A contested submission. Modeled as a substate within `Contract` and `Milestone`, not a separate state machine. |
| **Release** | The act of transferring escrowed funds to the provider upon approval. |

### 2.2 Why This Domain

Contracting exercises Transflux features in a non-contrived way:

- **Multiple contract types** map naturally onto distinct `StateMachine` instances that share a component library (Phase 5).
- **Escrow funding** is a side-effecting step with a clear compensation (refund) — the canonical Saga rollback example.
- **Milestone lifecycle** drives multi-branch conditional operations (submit → approved | revision-requested | disputed).
- **Funding confirmation** is a natural event-trigger target (host receives a webhook, calls `processEvent(...)`).
- **Auto-release after N days** is a host-driven data-trigger example (the host re-evaluates on a batch tick; the framework does not poll).
- **Dispute path** demonstrates exception-specific compensation and post-condition gating.

---

## 3. State Machine Inventory

Four state machines. The first three are major lifecycle machines; the fourth is a reusable secondary machine consumed by milestone-based contracts.

### 3.1 `Proposal` State Machine

A provider's expression of interest, before any contract exists.

**States:** `draft` → `submitted` → (`withdrawn` | `accepted` | `declined`)

**Primary feature surface:**
- Lambda-based `StateResolver` (state is a single enum field).
- Manual triggers (provider submits; provider withdraws; client accepts/declines).
- Simple pre-conditions (cannot submit without required fields).
- State entry listener emitting domain events (submitted).

**Failure surface:** Submission validation failures; concurrent withdraw vs. accept race (resolved by reentrancy guard semantics in caller code).

### 3.2 `Offer` State Machine

A client's offer to engage a provider on specified terms.

**States:** `draft` → `extended` → (`withdrawn` | `declined` | `accepted` → `funded` → `contract-created` | `funding-failed`)

**Primary feature surface:**
- Class-based `StateResolver` (state derives from multiple fields: status + funding-status).
- Composite operation on `extended → funded`: validate terms → reserve escrow → confirm funding (with compensation on each step).
- Exception-specific compensation: `InsufficientFundsException` → release reservation + transition to `funding-failed`; `EscrowProviderException` → retry-then-release.
- Post-condition gating: `funded` requires escrow balance ≥ offer amount.
- Cross-machine handoff: `contract-created` transition instantiates the matching `Contract` machine.

**Failure surface:** Funding rejection, escrow service errors, partial-funding rollback, client-side withdrawal mid-funding.

### 3.3 `Contract` State Machine

The engagement itself. **One state machine definition, parameterized by contract type via the component library.** The three variants (fixed-price, hourly, milestone-based) share most states and transitions; differences are isolated to specific steps and conditions.

**Common states:** `active` → (`completed` | `cancelled` | `disputed` → (`resolved-completed` | `resolved-cancelled`))

**Variant-specific behavior:**
- **Fixed-price:** Single submission/approval cycle on the whole contract. Single escrow lump.
- **Hourly:** Periodic work-log submissions; auto-release after host-driven data-trigger fires past the review window. Escrow tops up per period.
- **Milestone-based:** Drives one or more `Milestone` sub-machines; contract completes when all milestones reach `released` or `cancelled-refunded`.

**Primary feature surface:**
- Component library sharing across the three variants (this is the showcase for Phase 5).
- Both DSLs (Java + YAML) for the same machine, with parity spec.
- Multi-branch conditional operations on `disputed → resolved-*` (decision tree based on dispute outcome).
- Event triggers (payment-provider webhook → confirm release).
- Data triggers (host batch tick → auto-release on eligible hourly periods).
- Async operation anchored via `startAfter` (notification fan-out after `completed`).

**Failure surface:** Dispute escalation, partial milestone completion with overall cancellation, mid-flight cancellation with unreleased escrow, post-condition violations on the release path.

### 3.4 `Milestone` State Machine (secondary, milestone-based contracts only)

Used by milestone-based `Contract` instances. Multiple `Milestone` machines may be in flight under one contract.

**States:** `defined` → `funded` → `submitted` → (`approved` → `released` | `revision-requested` → `submitted` | `disputed` → (`approved` | `cancelled-refunded`))

**Primary feature surface:**
- Cross-machine event flow: milestone state changes emit events that the parent `Contract` machine consumes via `processEvent`.
- Conditional re-entry: `revision-requested → submitted` demonstrates a state machine that re-enters an earlier state legitimately.
- LIFO compensation across `defined → funded → submitted`: failure during `submitted` compensates funding, which compensates the milestone reservation, in order.
- Dispute path uses the same Condition Descriptor grammar as `Contract` (showcase of component reuse).

**Failure surface:** Submission timeout, partial revision cycles, disputed milestone with parent-contract still active, refund failures.

---

## 4. Feature-to-Showcase Mapping

A reverse index for the 1.0 contract: every 1.0 feature is exercised by at least one location in the sample.

| 1.0 feature | Primary showcase | Contrasting showcase |
|---|---|---|
| Lambda `StateResolver` | `Proposal` | (n/a — single-field case is the lambda case) |
| Class-based `StateResolver` | `Offer` (multi-field derivation) | `Proposal` (lambda) for comparison in PATTERNS.md |
| SpEL `StateResolver` | `Contract` (YAML variant) | Java DSL uses class-based for the same machine |
| `StateApplier` (lambda / class / SpEL) | One per major machine | All three forms cross-referenced in PATTERNS.md |
| Composite operation with steps | `Offer.extended → funded` | `Milestone.submitted → approved` |
| Step elevated as `SimpleOperation` | `Proposal.draft → submitted` (validate-and-stamp step reused as op) | — |
| Multi-branch conditional operation | `Contract.disputed → resolved-*` | `Milestone.submitted → *` |
| Condition Descriptor: reference | `Contract` (shared `is-fully-funded` condition) | — |
| Condition Descriptor: class-based | `Offer.has-valid-terms` | — |
| Condition Descriptor: predicate | `Milestone.is-overdue` | — |
| Condition Descriptor: expression | `Contract` inline `entity.balance >= entity.amount` | — |
| Pre-conditions | `Offer.extend` (terms must be valid) | — |
| Post-conditions | `Offer.funded` (escrow balance check) | `Milestone.released` |
| LIFO compensation | `Offer` funding chain | `Milestone` defined→funded→submitted chain |
| Exception-specific compensation | `Offer.InsufficientFundsException` vs. `EscrowProviderException` | — |
| Manual trigger | `Proposal.submit`, `Offer.extend`, `Contract.cancel` | — |
| Event trigger | `Contract` payment-webhook → release | `Offer` funding-confirmed event |
| Data trigger (host-driven) | `Contract` hourly auto-release on batch tick | — |
| State entry listener | `Proposal.submitted` (emit domain event) | `Contract.completed` |
| State exit listener | `Offer.extended` (clear scratch fields) | — |
| Transition listeners (start/complete/error) | `Contract` lifecycle logging | — |
| Async operation `startAfter` | `Contract.completed` notification fan-out | — |
| Async operation `startBefore` | `Offer.funded` parallel risk-check | — |
| Async compensation | `Contract.cancelled` with in-flight async notification | — |
| Reentrancy guard | Negative-path spec in `Contract` test suite | — |
| Component library | Cross-variant reuse in `Contract` | — |
| YAML DSL parity | All four machines | — |
| Spring auto-config | `transflux-examples-contracting-spring` sibling | — |
| `MetricsCollector` SPI | Stub implementation logging to console | — |

Phase 5 adds the YAML variants; Phase 6 adds the Spring sibling module.

---

## 5. Blessed Patterns

The sample carries two layers of guidance:

### 5.1 In-Code Comments

Short, focused comments at each canonical decision point. Format: one to three lines explaining *why this form here*, never explaining *what the code does*. Examples (illustrative):

```java
// Lambda resolver: state is a single enum field on Proposal.
// For multi-field derivation (see Offer.java) prefer a class-based resolver.
.withStateResolver(Proposal::getStatus)
```

```java
// Class-based condition: dispute-eligibility depends on three fields
// and is unit-tested in isolation. Inline expressions are reserved for
// one-line predicates against a single field (see Contract.yaml: is-fully-funded).
.precondition(DisputeEligibleCondition.class)
```

Comments never duplicate `PATTERNS.md`; they point to it where deeper rationale lives.

### 5.2 `PATTERNS.md` (Cookbook)

A standalone document at the module root with one section per decision point. Each section follows the structure:

1. **Situation.** What the developer is trying to express.
2. **Forms available.** The 1.0 options.
3. **Which to pick.** A short decision matrix with line-of-code thresholds where relevant ("more than ~5 lines → class-based").
4. **Examples.** Cross-references into the sample source (file:line).
5. **Anti-patterns.** What people reach for that doesn't work as well, and why.

Initial section list:

- Resolver and applier forms.
- Condition Descriptor forms (the four-grammar).
- Step vs. simple operation vs. composite operation.
- Compensation declaration: by class vs. dynamic from `getCompensation(entity, context)`.
- Exception-specific compensation routing.
- Inline conditions in YAML vs. component-library references.
- Manual vs. event vs. data triggers — when each is appropriate.
- State listeners vs. transition listeners.
- Async anchors: `startBefore` vs. `startAfter`.
- Cross-machine coordination (when to use events vs. direct invocation).
- DSL choice: Java vs. YAML — what each is best at.

`PATTERNS.md` is the primary user-facing artifact of this module. Code is the proof; PATTERNS is the explanation.

---

## 6. Module Structure

### 6.1 Layout

```
transflux-examples-contracting/                — plain Java sample
  pom.xml
  PATTERNS.md
  README.md                                    — getting started, how to run
  src/main/java/org/transflux/examples/contracting/
    domain/                                    — Proposal, Offer, Contract, Milestone, value types
    machines/                                  — Java-DSL StateMachineDef builders, one per machine
    operations/                                — composite operations and steps
    conditions/                                — class-based and predicate conditions
    listeners/                                 — state and transition listeners
    triggers/                                  — manual / event / data trigger wiring
    support/                                   — in-memory stores, fake services, MetricsCollector stub
    Main.java                                  — runnable scenarios
  src/main/resources/                          — YAML DSL twins (Phase 5)
    machines/proposal.yaml
    machines/offer.yaml
    machines/contract-fixed-price.yaml
    machines/contract-hourly.yaml
    machines/contract-milestone.yaml
    machines/milestone.yaml
    components/                                — shared component library YAML
  src/test/groovy/org/transflux/examples/contracting/
    *Spec.groovy                               — Spock specs for each machine + parity specs

transflux-examples-contracting-spring/         — Spring Boot sibling (Phase 6)
  pom.xml
  src/main/java/...
  src/main/resources/application.yml
  README.md
```

### 6.2 Java Package Root

`org.transflux.examples.contracting`. Subpackages follow the source-tree above and are introduced freely — the flat-package rule for `org.transflux.core` does not apply to the sample.

### 6.3 Maven Coordinates

- GroupId: `org.transflux` (same as core).
- ArtifactId: `transflux-examples-contracting`, `transflux-examples-contracting-spring`.
- Not deployed to Central. `pom.xml` carries `<distributionManagement>` blocks excluding these modules from release deployment.

### 6.4 Dependencies

- Depends on `transflux-core` at the current snapshot version (always built against `HEAD`).
- Test scope: Spock 2.x + Groovy 4.x (same versions as core).
- Spring sibling adds Spring Boot 3.4.x. The plain-Java module pulls no Spring dependency.

---

## 7. Maintenance Contract

### 7.1 CI Integration

- The sample module is built and tested in CI on every PR against `main`.
- A core-library change that breaks the sample build or specs blocks the PR. This is the explicit signal that the change is a breaking change to the public API — even if the core's own specs pass.
- The sample is *not* exempted from CI when the change is "obviously" non-breaking. The whole point is to catch the cases where that judgment is wrong.

### 7.2 Versioning

- The sample module's `pom.xml` version tracks the core library's version exactly. No independent versioning.
- The sample is not published — no Maven Central artifacts, no GitHub release attachments.

### 7.3 Documentation Drift

`PATTERNS.md` is reviewed as part of every core-library API change that affects a documented decision point. The reviewer checklist (added to `CONTRIBUTING.md` during Phase 6) includes "Does this PR change a pattern documented in PATTERNS.md? If so, update it."

---

## 8. 1.0 Quality Gate Addition

Proposed addition to `todo.md` §6.9 (1.0 Quality Gates):

> - [ ] Contracting sample module (`transflux-examples-contracting`) and its Spring sibling build successfully, all specs pass, and every item in the 1.0 contract summary is exercised by at least one location in the sample. The reverse-index table in `examples-app-plan.md` §4 is up to date.

---

## 9. Cross-References

- **`requirements.md`** — canonical specification for the framework. The sample exercises features defined there; it does not extend or amend the specification.
- **`todo.md`** — phased delivery of the framework itself. Sample-module work is tracked separately in `examples-app-todo.md` but is gated on the same phase boundaries.
- **`examples-app-todo.md`** — phased delivery of the sample module.
- **`CLAUDE.md`** — repository-level conventions. The sample follows them except where it deliberately demonstrates an alternative (e.g., subpackages under `org.transflux.examples.contracting` despite the flat-package rule for `core`).

---

*This document supersedes any ad-hoc discussion of the sample application. Scope changes during delivery are recorded here first, then reflected in `examples-app-todo.md`.*