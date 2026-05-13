# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Transflux is a lightweight, embeddable microflow orchestration library (Java). It coordinates state changes for business entities — transitions, sequencing, pre-/post-conditions, triggers, listeners, and Saga-like compensations — without providing its own persistence. Project status: Phase 1 (Core Foundation), in progress; the public API is unstable.

`requirements.md` is the canonical spec for the vision and component model; consult it before designing new core abstractions. `todo.md` tracks phased implementation work.

## Build & Test

- Toolchain: Maven builds with **JDK 21+** (enforced via `maven-toolchains-plugin`) and compiles to **Java 11 bytecode** (`<release>11</release>`). A JDK 21+ must be discoverable through `~/.m2/toolchains.xml`.
- Run all tests: `mvn -q clean test`
- Run a single Spock spec: `mvn -q test -Dtest=StateMachineImplSpec`
- Run a single feature method: `mvn -q test -Dtest=StateMachineImplSpec#"feature method name"`
- Coverage report (JaCoCo, generated during `test` phase): `target/site/jacoco/index.html`
- Surefire only picks up classes matching `**/*Spec` — Groovy/Spock specs in `src/test/groovy`. Plain JUnit tests would not be discovered without changing the include pattern.

## Architecture

The core domain lives entirely in `org.transflux.core` (flat package; subpackages from the README are aspirational, not yet realized).

**Definition vs. runtime split** — every concept has paired types:
- `*Def` interfaces + `*DefImpl` classes — fluent builders that capture configuration (states, transitions, operations).
- Runtime interfaces (`StateMachine`, `State`, `Transition`, `Operation`) + `*Impl` — immutable instances produced by calling `build()` on the definition.

Read a `*Def` to understand the DSL surface; read the matching runtime interface to understand execution semantics.

**Entry point.** `Transflux.defineStateMachine()` returns a `StateMachineDef<T>`. The builder chain (`forEntityType` → `withStateResolver` → `withStateApplier` → `state(...).transitionsTo(...)` → `build()`) produces a `StateMachine<T>`.

**State accessor pair.** `StateResolver<T>` extracts the current state ID from a domain entity (read side); `StateApplier<T>` writes the new state ID after a successful transition (write side). The applier is optional — purely transient transitions, or hosts that mutate state inside steps, can omit it. Transflux never owns storage; both interfaces are host-supplied bridges.

**Identifiable.** Most components implement `Identifiable` and carry a stable `id` (used for lookup) plus an optional human-readable `name`. Per `requirements.md` §2.2.1, IDs are required and must be unique within their scope; only **inline expression-based conditions** may omit `id` and have one auto-derived from expression + path.

**Exception hierarchy.** All Transflux-thrown exceptions derive from `TransfluxException` (unchecked). `TransfluxValidationException` covers definition, builder, and lookup errors — raised synchronously. `TransfluxReentrancyException` is declared (Phase 1) and will be raised by the runtime guard in Phase 2.

**TransitionResult.** Transition execution returns a `TransitionResult<T>` rather than throwing on business outcomes — failures from invalid configuration still throw `TransfluxValidationException`. `TransitionResult` carries: success flag, source/target state, transition ID, error, executed-step IDs, compensated-step IDs, started/completed timestamps. There is no forced-state API (host owns initial-state placement).

## Conventions Specific to This Repo

- **Line endings: LF.** Enforced by recent refactor; do not let editors reintroduce CRLF on Windows.
- **Generics:** prefer `<T>` consistently for the entity type across paired `Def`/runtime interfaces — recent commits explicitly refactored for this consistency.
- **Commit messages:** Conventional Commits (`feat:`, `fix:`, `chore:`, `refactor:`, ...). See README §Contributing.
- **Tests:** Spock (Groovy 4.0 / Spock 2.3 — bump to 2.4 is scheduled for Phase 6.4). Co-locate a spec with each new core class; name it `<ClassName>Spec.groovy` so Surefire picks it up.
- **No new top-level packages** without updating `requirements.md` and README's "Package Structure" section — the planned subpackages (`state`, `transition`, `operation`, `context`, `exception`) are not yet introduced; flattening into `core` is intentional for now.
- **Source comments and JavaDoc must be time-neutral.** Treat every comment and JavaDoc block in source files as if it has to survive unchanged until 1.0. Do not write phrases like "introduced in Phase 2 Step 1", "wired in Phase X Step Y", "per requirements §2.1.5", "preserves the Phase 1 no-op behavior", "in Phase 2 specs" — anything that ties the prose to a transient point in the build plan. Phase numbers, plan steps, and `requirements.md`/`todo.md` section pointers are scaffolding that decays the moment the next phase lands; the diff is the right place to capture that context (commit message, PR description, the plan file), not the source. **One narrow exception**: `TODO` markers that flag a known placeholder are fine, because their whole purpose is to be removed when the placeholder is filled in — but even then, the body of the TODO should describe what the placeholder needs to do, not the plan step that will do it (write `// TODO: pre-condition evaluation against view`, not `// TODO Phase 2 Step 5: pre-condition evaluation`). Exception messages and log strings follow the same rule, since they surface to users at runtime.

## GitHub Access

This repo is **not** under `C:\Dev\Upwork`, so GitHub MCP tools are permitted (per global guidelines). Remote is `https://github.com/vdenisov/transflux` (see `pom.xml`'s `<url>`).