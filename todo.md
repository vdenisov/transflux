# Transflux — Roadmap

This is the **roadmap index**: the phase map, versioning policy, the 1.0 contract,
post-1.0 themes, and the release-readiness gates. Detailed per-phase task breakdowns
live in linked files under `docs/`; completed phases are archived (verbatim) under
`docs/history/`.

The roadmap tracks high-level work items derived from `requirements.md` (the canonical
spec). Detailed design and per-feature breakdowns happen in feature-specific docs as we go.

## Phase Map

Status legend: ✅ done · 🚧 active · ⬜ next / planned · 🔮 post-1.0

| Phase | Version | Status | Detail |
| --- | --- | --- | --- |
| 1 — Core Foundation | v0.1.0 | ✅ done | [history](docs/history/phase-1-core-foundation.md) |
| 2 — Operations, Steps & Conditions | v0.2.0 | ✅ done | [history](docs/history/phase-2-operations-steps-conditions.md) |
| 2.5 — Nested Operations & Context Mapping | v0.2.5 | ✅ done | [history](docs/history/phase-2.5-nested-operations.md) |
| 2.6 — DSL Shape Consistency | v0.2.6 | ✅ done | [history](docs/history/phase-2.6-dsl-shape-consistency.md) |
| 3 — Triggers & Listeners | v0.3.0 | ✅ done | [history](docs/history/phase-3-triggers-listeners.md) |
| 4 — Async Operations & Error Handling | v0.4.0 | 🚧 active | [phase 4](docs/roadmap/phase-4-async-compensation.md) |
| 5 — YAML DSL & Component System | v0.5.0 | ⬜ planned | [phase 5](docs/roadmap/phase-5-yaml-dsl.md) |
| 6 — Integration, Polish & Release Prep | v0.6.0 → v1.0.0 | ⬜ planned | [phase 6](docs/roadmap/phase-6-integration-release.md) |
| Post-1.0 — Additive & Breaking themes | 1.x / 2.x | 🔮 future | [below](#post-10--additive-themes-1x-line) |

## Document Map

- **[docs/roadmap/](docs/roadmap/)** — one file per **remaining** phase (4, 5, 6). A phase moves to `docs/history/` when it ships.
- **[docs/history/](docs/history/)** — one file per **shipped** phase (1, 2, 2.5, 2.6, 3), verbatim, nothing compressed.
- **[docs/design/](docs/design/)** - design notes for individual changes large enough to want one before they become a plan. Written against the model at the time; reconciled into `requirements.md` when the change lands.
- **[docs/project-baseline.md](docs/project-baseline.md)** — Java baseline, dependency versions, release cadence.
- **`requirements.md`** — the canonical high-level spec.

---

## Versioning Strategy

- **0.x.y** — pre-1.0 phases. API is unstable; breaking changes are expected between phases.
- **1.0.0** — stable core. Semantic versioning applies from this point on.
- **1.x.0** — additive features only. Anything that would force a breaking change against the 1.0 contract is queued for 2.0.
- **2.0.0** — reserved for themes that cannot realistically remain additive: persistence, long-running/durable executions, and distributed execution. These touch the core operation/context contract (serializable contexts, resumable state, distributed identity/locking) in ways that 1.0 does not anticipate.

Patch releases (`x.y.z`) ship between minor releases for bug fixes and security updates.

### Release Policy

**No releases until 1.0.** All pre-1.0 work is internal — no Maven Central publishing, no GitHub releases, no public artifacts. The library is in active design and the surface is changing too fast for external consumption to be useful. This policy may be revisited mid-roadmap (likely around v0.4.0 or v0.5.0 when YAML DSL and triggers are in place) if a controlled preview release becomes valuable; the default remains "no release" until that decision is made explicitly.

---

## v1.0.0 Release

The first stable release. Semantic versioning applies from this point on.

**1.0 contract summary:**
- Programmatic and YAML DSLs at parity.
- Core abstractions: `StateMachine`, `State`, `Transition`, `Action` (imperative "step" and declarative "operation" forms, nestable, with type-safe context mapping via `ContextMapper<P, N>`), `Context`, `Condition`, `Trigger` (Manual / Event / host-driven Data), `Listener` (state entry/exit + transition start/complete/error + action start/complete/error), `Compensation`.
- Paired `StateResolver<T>` + `StateApplier<T>` (class / lambda / SpEL forms).
- Condition Descriptor grammar (reference, class, predicate, expression).
- Multi-branch conditional operations.
- LIFO compensation engine with exception-specific routing.
- Async operations anchored via `startBefore` / `startAfter`.
- Spring auto-configuration (optional) + manual wiring via `ComponentRegistry`.
- `MetricsCollector` SPI hook (no shipped backend integration).

---

## Post-1.0 — Additive Themes (1.x line)

Ordering between themes will depend on demand. None of these should require breaking changes against the 1.0 contract.

### Trigger Expansion
- [ ] **Timer / Cron Triggers**
  - [ ] `TimerTrigger` implementation.
  - [ ] Quartz Scheduler 2.3.x integration.
  - [ ] Timezone / DST handling.
  - [ ] Timer persistence considerations (will interact with the persistence theme on the 2.x line).
- [ ] **Signal Triggers**
  - [ ] `SignalTrigger` for framework-wide signals.
  - [ ] Signal broadcasting, subscription, predicate matching.
  - [ ] Cross-state-machine signal coordination.
- [ ] **Automatic Data-Change Detection**
  - [ ] Field-watcher infrastructure.
  - [ ] ORM-hook integration adapters (Hibernate listener, JPA, etc.).
  - [ ] Efficient change detection algorithms.

### Observability Integration
- [ ] Micrometer 1.12.x integration (first-party `MetricsCollector` implementation).
- [ ] OpenTelemetry 1.32.x tracing (span creation, context propagation, sampling).
- [ ] Structured logging with MDC and correlation IDs.
- [ ] Health-check framework (state machine, triggers, thread pools, dependencies).
- [ ] Grafana dashboard templates and example alerting rules.

### DI Framework Expansion
- [ ] **Google Guice 7.0.x** integration: `TransfluxGuiceModule`, `@TransfluxComponent`, scope management.
- [ ] **CDI / Weld SE 5.1.x** integration: `TransfluxExtension`, bean definitions, Jakarta EE compatibility.
- [ ] **Dagger 2.48.x** integration: compile-time components, annotation processing, multibinding.
- [ ] Framework-agnostic `DIContainer` abstraction with adapters.
- [ ] Specs for each integration; cross-framework benchmark / parity checks.

### Testing Framework (separate artifact)
- [ ] `transflux-test` (or similar) module.
- [ ] `TestStateMachine<T>` wrapper with transition-path recording.
- [ ] Context snapshot capture at transition points.
- [ ] Per-action execution tracking.
- [ ] AssertJ-inspired fluent assertion API (`TransfluxAssertions`):
  - [ ] State assertions.
  - [ ] Transition assertions.
  - [ ] Context assertions.
  - [ ] Action / compensation assertions.
- [ ] Test data builders for entities and contexts.
- [ ] Integration with Spock, JUnit, TestNG.

### Resilience Patterns
- [ ] Resilience4j 2.1.x integration.
- [ ] Configurable retry strategies (exponential backoff, jitter).
- [ ] Circuit breaker pattern.
- [ ] Rate limiting.
- [ ] Graceful degradation strategies.

### Advanced DSL Features
- [ ] YAML anchors / aliases support.
- [ ] Template-based component definitions and inheritance.
- [ ] Parameterized components.
- [ ] Hot reload in development mode.
- [ ] Dynamic runtime reconfiguration (blue/green with rollback).

### Plugin System
- [ ] Plugin interface and extension points.
- [ ] Plugin discovery and loading.
- [ ] Plugin lifecycle management.
- [ ] Plugin dependency resolution.
- [ ] Built-in plugins (subject to demand): message-queue integration, REST API for external triggers, alerting integrations.

---

## Post-1.0 — Breaking Themes (2.x line)

These themes alter the core operation/context contract enough that they cannot realistically remain additive. Bundling them into a single 2.0 release (vs. a series of 1.x with breaking sub-releases) preserves semver integrity.

### Persistence
- [ ] Pluggable persistence layer for state-machine definitions.
- [ ] Transition history auditing.
- [ ] Entity state persistence and recovery.
- [ ] Implications: serializable contexts may become a 1.x-soft requirement; full enforcement is 2.0.

### Long-Running / Durable Executions
- [ ] Checkpoint and resume capabilities.
- [ ] Progress tracking and monitoring.
- [ ] Suspend / resume semantics for the transition lifecycle.
- [ ] Distributed transaction support.
- [ ] BPMN interoperability considerations.

### Distributed Execution
- [ ] Cluster-wide locking primitives.
- [ ] Distributed state-machine coordination.
- [ ] Cluster-aware triggers (event de-duplication, leader election).
- [ ] Cross-node entity identity and dispatch.
- [ ] Failure handling and recovery in distributed environments.

### Jackson 3 Migration
- [ ] Migrate from Jackson 2.20.x to Jackson 3.x.
- [ ] Package rename: `com.fasterxml.jackson.*` → `tools.jackson.*` across all parsing code.
- [ ] Verify SnakeYAML / Jackson YAML 3.x interoperability.
- [ ] Audit all `ObjectMapper` and `YAMLMapper` usages for API changes.
- [ ] Document Jackson 3 as a breaking change for users who pin Jackson on their classpath. Bundle with the other 2.x breaking themes to avoid double migration disruption.

---

## Phase Completion Criteria

Each phase must meet the following before the next phase starts:
- [ ] All planned features implemented and tested.
- [ ] Spock specification coverage ≥ 80% for new code.
- [ ] No critical security findings against new code.
- [ ] `requirements.md` and `todo.md` updated to reflect any in-flight scope changes.
- [ ] Migration notes for breaking changes (pre-1.0 only; post-1.0 changes follow semver).

## 1.0 Release Readiness

- [ ] All Phase 1–6 tasks completed.
- [ ] 1.0 quality gates (§6.9) satisfied.
- [ ] Documentation complete.
- [ ] Release notes prepared.
- [ ] Migration guide template ready (for future 2.0).
- [ ] Code audit completed against the rewritten `requirements.md` (forced-state revert, `TransitionResult` shape verification, etc.).

---

*This plan supersedes earlier 15-phase drafts. Detailed feature design lives in feature-specific docs as we go; `requirements.md` remains the canonical high-level spec.*
