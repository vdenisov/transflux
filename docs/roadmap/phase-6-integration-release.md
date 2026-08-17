> Part of the [Transflux roadmap](../../todo.md). Forward-looking; not yet started.

## Phase 6: Integration, Polish & Release Prep (v0.6.0 → v1.0.0)
*Target: 1.0-grade integration, infrastructure, and documentation.*

### 6.1 Spring Integration (Optional)
- [ ] Target **Spring Boot 3.4.x** (Spring Framework 6.2.x). **Documented Java floor: Java 17+ across the board** — the core library targets Java 17, and the optional Spring integration also requires Java 17 (Spring 6 mandates it). Document the Java 17 floor in the README and in the Spring-integration section of the user guide.
- [ ] Spring Boot auto-configuration class.
- [ ] `@EnableTransflux` annotation.
- [ ] `TransfluxConfiguration` Spring binding with configuration properties.
- [ ] Automatic Spring-bean discovery for Transflux components (`Step`, `Condition`, `Trigger`, `Listener`, `Operation`).
- [ ] Profile-aware configuration support.

### 6.2 Component Factory SPI
- [ ] `ComponentFactory` interface with generic type support.
- [ ] Reflection-based fallback when no DI framework is available.
- [ ] Named component registration and retrieval.
- [ ] Custom factory function registration.
- [ ] Circular dependency detection within component graphs.
- [ ] YAML DSL integration: instantiation from `class:` references.
- [ ] Nested-operation instantiation goes through the same factory path — no separate code path for operations used as composite members.

### 6.3 Observability Hooks
- [ ] `MetricsCollector` SPI (no shipped Micrometer integration in 1.0).
- [ ] Hook points: transition start/complete/error, step start/complete, compensation execution, trigger evaluation.
- [ ] ~~Consistent SLF4J logging with predictable logger names.~~ **Moved to §4.7.** One line could not settle the shape, and Phases 4 and 5 both add code that should be born compliant rather than retrofitted. The policy now lives in CLAUDE.md §Logging; what remains here is the `MetricsCollector` SPI, which answers "how often / how long" rather than "what did the framework decide".
- [ ] Configurable flow labels for metric separation.
- [ ] Re-check §4.7's logger names and level discipline against the shipped release, since Phase 5's YAML code lands between the two.

### 6.4 1.0 Dependency Baseline Refresh

Phase 1.1 captured the dependency versions present in the repo when bootstrapping. Before 1.0, bump to the target 1.0 baseline:

- [ ] **Jackson Core** 2.18.0 → **2.20.x** (staying on the 2.x line; Jackson 3 migration is queued as a Post-1.0 / 2.x theme).
- [ ] **Spock** 2.3-groovy-4.0 → **2.4-groovy-4.0**.
- [ ] **Groovy** 4.0.28 → latest 4.0.x.
- [ ] **SLF4J** 2.0.17 → latest 2.0.x.
- [ ] **Logback** (test scope) 1.5.18 → latest 1.5.x.
- [ ] Maven plugin versions audited and aligned with current Maven 3.9.x recommendations.
- [ ] Pin the exact SpEL 6.2.x patch version (the earlier Java 11 compatibility concern is moot now that the baseline is Java 17).
- [ ] Update `pom.xml` and re-run the full Spock specification suite after each bump to catch behavioral regressions.

### 6.5 CI/CD and Quality Infrastructure
- [x] Basic GitHub Actions workflow (build + test).
- [ ] Code-quality gates: SpotBugs, Checkstyle, PMD.
- [ ] Security vulnerability scanning.
- [ ] Code-coverage reporting (JaCoCo + Codecov).
- [ ] Required status checks for PRs; branch protection on `main`.
- [ ] Dependabot for dependency updates.
- [ ] Issue / PR / bug-report templates.
- [ ] `CONTRIBUTING.md`.
- [ ] Pre-commit hook configuration.

### 6.6 Maven Central Publishing
- [ ] Complete POM metadata (name, description, URL, licenses, developers, SCM).
- [ ] Distribution management configuration.
- [ ] Source jar and Javadoc jar plugins.
- [ ] GPG signing configuration.
- [ ] Sonatype OSSRH account and group-ID verification.
- [ ] Release automation (version bumping, tagging, changelog, deployment).
- [ ] GitHub releases with artifacts.

### 6.7 Documentation
- [ ] Complete README (badges, install snippets, hello-world example).
- [ ] Getting-started guide.
- [ ] Architecture overview (mirrors `requirements.md` §2 but reader-oriented).
- [ ] Configuration reference.
- [ ] Best-practices / patterns guide (when to use simple vs. composite operations, manual vs. event vs. data triggers, etc.).
- [ ] Migration guide template for breaking changes (will be reused at 2.0).
- [ ] Complete API Javadoc.
- [ ] Example applications: simple state machine, complex workflow, Spring Boot integration.

### 6.8 Release Engineering
- [ ] Semantic versioning policy document.
- [ ] Backward-compatibility policy.
- [ ] Release notes template.
- [ ] Community infrastructure: GitHub Discussions, SECURITY.md, code of conduct.

### 6.9 1.0 Quality Gates
- [ ] Spock specification coverage ≥ 80% for core packages.
- [ ] No critical or high-severity security findings.
- [ ] Performance baseline established (basic benchmarks; not a 1.0 feature, but a baseline to detect regressions).
- [ ] API surface review and sign-off.
- [ ] Load test of representative workflow.
- [ ] Documentation completeness verification.

