> Part of the [Transflux roadmap](../../todo.md). Forward-looking; not yet started.

## Phase 4: Async Operations & Error Handling (v0.4.0)
*Target: The compensation engine, async anchoring, and exception-specific recovery.*

### 4.1 Compensation Engine
- [ ] Unified `Compensation<T, C>` interface (entity + context) for both operations and steps.
- [ ] LIFO compensation stack management. **One stack per synchronous execution path.** Sync nested operations (Phase 2.5) push onto the enclosing parent's stack so unwinding interleaves child and sibling-step compensations correctly. Async branches own their own stack — see §4.4.
- [ ] Compensation registration before each step runs (so a step that throws partway through producing side effects still gets its compensation invoked).
- [ ] Post-condition violation triggers full compensation; entity state is *not* applied.
- [ ] Compensation declared by class or returned dynamically from `getCompensation(entity, context)`.

### 4.2 Exception-Specific Compensation
- [ ] `.onException(...)` / `.onAllExceptions()` builder DSL on composite operations.
- [ ] Exception matching by class hierarchy + optional predicate.
- [ ] Compensation chaining and ordering.

### 4.3 Async Operation Support
- [ ] `async` block on composite operations. The block accepts both `.step(...)` and `.operation(...)` members — an async branch may host a nested operation just like a sync composite can (Phase 2.5). The async executor submits the branch root; the nested operation runs on the async thread with its own compensation stack (see §4.4).
- [ ] **Anchor forms**: exactly one of
  - [ ] `startBefore(stepId)` — kick off when execution reaches the named sync step (join-point pattern).
  - [ ] `startAfter(stepId)` — kick off when the named sync step completes successfully (post-action notifications pattern).
- [ ] Configurable thread pool and queue capacity.
- [ ] Async result handling and callbacks.
- [ ] Async operation cancellation semantics.

#### 4.3.1 Async Context Handling (requirements §4.5.3)
- [ ] `ForkableContext<C>` interface with single `C fork()` method.
- [ ] Runtime fork-at-boundary: at async-branch submission, if the context implements `ForkableContext`, the branch receives `context.fork()`; otherwise it receives the shared reference. Invoked once per branch (not once per `async` block) so sibling branches each get an independent fork.
- [ ] `ContextMapper`-on-`async` path: the async block accepts the same five-form call-site grammar as composite members (`.async().step("id", "mapperId")` / `.async().step("id", parent -> child)` / `.async().operation("id", mapperInstance)` etc., per Phase 2.5 §2.5.3). Supplying a `ContextMapper` whose `mapFrom` is overridden is rejected at definition time — async outcomes do not merge back synchronously.
- [ ] Definition-time **warning** (logged, not thrown) when an `async` block is declared on a context type that neither implements `ForkableContext` nor declares a mapper. Warning identifies the operation id and points to §4.5.3.
- [ ] Documented memory-model guarantees at the submission boundary (writes-before-submission visible to branch; writes-after not synchronized; symmetric for branch → enclosing path).
- [ ] Optional `JacksonForkableContext` adapter shipped as a convenience implementation (Jackson round-trip). Lives in the same module — it's a few dozen lines and Jackson is already a core dependency.
- [ ] Specs covering: `ForkableContext` is invoked per branch, shared-reference fallback works and warns, `ContextMapper` on async produces the right type, `mapFrom` on async fails definition-time, `JacksonForkableContext` round-trips a representative POJO context.

### 4.4 Async Compensation
- [ ] **Per-branch LIFO stack.** Each async branch owns its own compensation stack — independent from the enclosing transition's sync stack and from sibling async branches. Compensations registered by an async branch (including any nested operations it hosts) unwind only that branch's stack.
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
- [ ] Compensation engine specs (LIFO order, exception routing, partial rollback).
- [ ] Async anchor specs for both `startBefore` and `startAfter`.
- [ ] Async-compensation specs.

