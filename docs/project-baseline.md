# Project Baseline — Toolchain, Dependencies & Cadence

> Part of the [Transflux roadmap](../todo.md). Stable reference material: the Java
> baseline, dependency versions, and release cadence. Versioning strategy and release
> policy live in the [roadmap index](../todo.md).

---

## Technical Implementation Notes

### Java Baseline

- **Core library**: Java 17+ to build (toolchain enforced); **Java 17+** target. Compiles to Java 17 bytecode via `<release>17</release>`.
- **Optional Spring integration**: Java 17+ runtime (Spring 6 mandates Java 17), matching the core library's Java 17 floor.

### Core Dependencies (1.0 Target Baseline)
- **SLF4J 2.0.x** (latest) — logging.
- **Jackson 2.20.x** — JSON / YAML data binding (staying on the 2.x line for 1.0; Jackson 3 migration is a 2.x post-1.0 item).
- **SnakeYAML 2.4** — YAML parsing (Phase 5).
- **Spring Expression Language 6.2.x** — SpEL for conditions, applier paths, expression-based conditions. Pin the exact SpEL JAR patch version during the Phase 6.4 dependency refresh.

### Optional Integrations (1.0)
- **Spring Boot 3.4.x** + **Spring Framework 6.2.x** — auto-configuration; Java 17+ runtime required for this integration.

### Testing (1.0)
- **Spock Framework 2.4-groovy-4.0** + **Groovy 4.0.x** — BDD-style specifications.
- **Logback 1.5.x** (test scope).

### Build & Quality
- **Maven 3.9.x**.
- **JaCoCo** — coverage.
- **SpotBugs**, **Checkstyle**, **PMD** — static analysis (Phase 6.5).

### Deferred Dependencies (Post-1.0)
- Micrometer 1.15.x+ / 2.x (observability theme).
- OpenTelemetry 1.45.x+ (observability theme).
- Quartz Scheduler 2.5.x (timer-trigger theme).
- Resilience4j 2.3.x (resilience theme).
- Spring JMS 6.2.x (event-transport extensions).
- Google Guice 7.x, Weld SE 6.x, Dagger 2.51+ (DI expansion theme).
- Testcontainers 1.21.x (testing framework or persistence theme).
- Jackson 3.x (breaking — bundled with the 2.x release; see below).

---

## Release Cadence

- **Pre-1.0 phases**: ~6–8 weeks per phase.
- **1.x minor releases**: as themes complete.
- **Patch releases**: as needed for critical bugs.
- **LTS**: revisit policy after 1.0 ships.

