/*
 *
 *  * Copyright 2025 Victor Denisov
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *     http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package org.transflux.core.impl;

import org.transflux.core.StateMachine;
import org.transflux.core.StateMachineDef;
import org.transflux.core.action.AsyncRejectionPolicy;
import org.transflux.core.exception.TransfluxReentrancyException;
import org.transflux.core.exception.TransfluxValidationException;
import org.transflux.core.transition.ActionPath;
import org.transflux.core.transition.TransitionResult;
import org.transflux.core.trigger.Trigger;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Implementation of the {@link StateMachine} interface: the host-facing handle over the
 * {@link StateMachineSnapshot} a definition built into.
 * <p>
 * The handle is what a host holds for the lifetime of the JVM; what is behind it can change. Every
 * entry point captures the current snapshot once and runs against it, so a replacement never splits
 * an execution between two versions. Three things are the handle's rather than a snapshot's, because
 * they outlive any one definition: the executor forked members and async listeners run on, the
 * reentrancy guard, and the ban on driving this state machine from a branch it spawned.
 *
 * @param <T> the type of entity managed by this state machine
 */
class StateMachineImpl<T> implements StateMachine<T> {
    // Thread-confined by construction, and deliberately not carried across a fork: capture and
    // restore would make a branch a continuation of the transition that spawned it, which it is
    // not - it outlives that transition, owns a different rollback stack, and reports to nobody.
    // Branches are kept out of the machine altogether instead; see ASYNC_BRANCH below.
    private static final ThreadLocal<Set<EntityKey>> IN_FLIGHT = ThreadLocal.withInitial(HashSet::new);

    /**
     * The state machines whose branches the calling thread is currently running. Consulted to
     * refuse a call back into one of them: a forked member is fire-and-forget, so a transition it
     * drove would report its outcome to nobody.
     */
    private static final ThreadLocal<Deque<StateMachineImpl<?>>> ASYNC_BRANCH =
        ThreadLocal.withInitial(ArrayDeque::new);

    // ponytail: fixed 10s drain window on close; make it configurable if a host ever needs longer.
    private static final long ASYNC_SHUTDOWN_TIMEOUT_MS = 10_000L;

    /**
     * The entity class this handle is bound to, and its identity contract: a definition whose
     * entity type differs cannot replace the one in force.
     */
    private final Class<T> entityType;

    private final Object swapLock = new Object();

    /**
     * Written under {@link #swapLock}, read everywhere. Volatile rather than final because that is
     * the whole point of the handle - and it also discharges the safe-publication contract for a
     * snapshot a swap installed.
     */
    private volatile StateMachineSnapshot<T> snapshot;

    private volatile long generation;

    /**
     * Where forked members run, and whether shutting it down is this state machine's business.
     * Both are {@code null} / {@code false} until a definition needs an executor, which may be the
     * one built at {@code build()} or a later one installed by a replacement.
     *
     * <p>The executor belongs to the handle rather than to a snapshot, and the first definition that
     * needs one configures it for the handle's lifetime. A snapshot is never explicitly retired, so
     * a per-snapshot pool would either be shut down while an in-flight pre-swap transition could
     * still fork into it - losing that branch - or be retained per generation forever.
     */
    private volatile ExecutorService asyncExecutor;
    private volatile boolean ownsAsyncExecutor;

    /** What configured {@link #asyncExecutor}, or {@code null} when the host supplied one. */
    private volatile AsyncPoolSpec asyncPoolSpec;

    /** Whether the pool in force was built with a fair queue, which only {@code BLOCK} needs. */
    private volatile boolean asyncPoolFair;

    private final AtomicBoolean closed = new AtomicBoolean();

    /** Guards the one WARN that says this machine started running async work on its callers. */
    private final AtomicBoolean inlineRunReported = new AtomicBoolean();

    StateMachineImpl(Class<T> entityType) {
        this.entityType = entityType;
    }

    /**
     * Installs the first snapshot. Called once, by the build that created this handle, before the
     * handle is returned to the host.
     *
     * @param initial the snapshot generation 1 runs against; never {@code null}
     */
    void install(StateMachineSnapshot<T> initial) {
        this.snapshot = initial;
        this.generation = 1L;
    }

    /**
     * @return the snapshot in force; every entry point captures it exactly once per call
     */
    StateMachineSnapshot<T> snapshot() {
        return snapshot;
    }

    @Override
    public long generation() {
        return generation;
    }

    @Override
    public long replaceDefinition(StateMachineDef<T> newDef) {
        requireNotNull(newDef, "Definition");
        if (!(newDef instanceof StateMachineDefImpl<T> def)) {
            throw new TransfluxValidationException(
                "Definition replacement expects a definition built through Transflux, but got "
                    + newDef.getClass().getName());
        }

        synchronized (swapLock) {
            requireNotClosed();
            requireSameEntityType(def);
            requireBlockingPossible(def);

            long next = generation + 1;
            // Full validation happens here: a throw leaves the snapshot and the generation exactly
            // as they were.
            StateMachineSnapshot<T> built = def.buildSnapshot(this, next);

            // ponytail: two volatile writes, so a concurrent reader can see the new snapshot's
            // metadata beside the old generation; hold both in one immutable record if a host ever
            // reads them together and cares.
            this.snapshot = built;
            this.generation = next;

            Loggers.BUILD_LIFECYCLE.info(
                "State machine definition replaced, id={}, version={}, generation={}",
                def.getId(), def.getVersion(), next);
            return next;
        }
    }

    @Override
    public void close() {
        ExecutorService executor;
        boolean owned;
        // Under the swap lock, so a replacement in progress finishes first: otherwise it could adopt
        // an executor after this read, or be seen between recording the executor and its owner.
        // The lock is released before the drain, so a swap never waits out the shutdown timeout.
        synchronized (swapLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            executor = asyncExecutor;
            owned = ownsAsyncExecutor;
        }

        if (executor == null) {
            return;
        }
        if (!owned) {
            Loggers.EXECUTION_ASYNC.debug("Async close ignored, executor is host-supplied");
            return;
        }

        executor.shutdown();
        boolean terminated;
        try {
            terminated = executor.awaitTermination(ASYNC_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            terminated = false;
        }

        if (terminated) {
            Loggers.EXECUTION_ASYNC.info("Async pool shut down, terminated={}", true);
        } else {
            // Never shutdownNow(): a branch interrupted mid-compensation leaves exactly the
            // half-rolled-back state the compensation existed to prevent. That promise is this
            // method's alone - daemon workers still die where they stand at JVM exit, which is why
            // a host that cares registers this as a shutdown hook or supplies its own factory.
            Loggers.EXECUTION_ASYNC.warn(
                "Async pool did not terminate within the shutdown timeout, timeoutMs={}",
                ASYNC_SHUTDOWN_TIMEOUT_MS);
        }
    }

    @Override
    public String getId() {
        return snapshot.getId();
    }

    @Override
    public String getName() {
        return snapshot.getName();
    }

    @Override
    public String getDescription() {
        return snapshot.getDescription();
    }

    @Override
    public String getVersion() {
        return snapshot.getVersion();
    }

    @Override
    public EntityBinding<T> entity(T entity) {
        requireNotNull(entity, "Entity");
        rejectIfInsideAsyncBranch();
        return snapshot.entity(entity);
    }

    @Override
    public TransitionResult<T> executeTransition(T entity, String targetStateId) {
        return entity(entity).transitionTo(targetStateId);
    }

    @Override
    public TransitionResult<T> executeTransition(T entity, String targetStateId, String transitionId) {
        return entity(entity).transitionTo(targetStateId, transitionId);
    }

    @Override
    public String resolveCurrentState(T entity) {
        return snapshot.resolveCurrentState(entity);
    }

    @Override
    public Collection<Trigger> getTriggers() {
        return snapshot.getTriggers();
    }

    @Override
    public <X extends Trigger> Collection<X> getTriggers(Class<X> kind) {
        return snapshot.getTriggers(kind);
    }

    @Override
    public Trigger getTrigger(String triggerId) {
        return snapshot.getTrigger(triggerId);
    }

    /**
     * Gives this handle the executor a definition asks for, if it does not have one already.
     * <p>
     * The first definition that needs an executor configures it; a later one that declares a
     * different configuration is told, once, that what it declared is not in force. Replacing the
     * executor instead would leave an in-flight transition of the previous generation forking into
     * a pool nobody drains.
     *
     * @param def the definition being built or installed; never {@code null}
     */
    void adoptExecutorIfNeeded(StateMachineDefImpl<T> def) {
        if (asyncExecutor != null) {
            warnIfAsyncConfigDiffers(def);
            return;
        }

        ExecutorService supplied = def.getAsyncExecutor();
        if (supplied != null) {
            this.asyncExecutor = supplied;
            this.ownsAsyncExecutor = false;
            this.asyncPoolSpec = null;
            this.asyncPoolFair = false;
            Loggers.EXECUTION_ASYNC.info("Async executor supplied by host, executorType={}",
                                         supplied.getClass().getName());
        } else if (def.definitionForks() || def.declaresAsyncPool() || def.declaresAsyncListener()) {
            AsyncPoolSpec spec = def.getAsyncPoolSpec();
            // A fair queue only earns its lock where something actually waits on it.
            boolean blocks = def.declaresBlockingRejection();
            this.asyncExecutor = spec.newPool(new AsyncRejectionHandler(this::insideOwnAsyncBranch),
                                              blocks);
            this.ownsAsyncExecutor = true;
            this.asyncPoolSpec = spec;
            this.asyncPoolFair = blocks;
            Loggers.EXECUTION_ASYNC.info("Async pool created, threads={}, queueCapacity={}",
                                         spec.threads(), spec.queueCapacity());
        }
    }

    /**
     * Hands one branch to the executor, and answers for a refusal.
     *
     * @param task the branch to run
     * @param path the forked member's qualified path, for diagnostics
     * @param policy the resolved rejection policy; never {@code null}
     *
     * @throws RejectedExecutionException under {@link AsyncRejectionPolicy#FAIL}, and under
     *         {@link AsyncRejectionPolicy#BLOCK} when waiting cannot help, failing the transition
     *         that was forking
     */
    void submitBranch(Runnable task, ActionPath path, AsyncRejectionPolicy policy) {
        String lost = submitAsync(task, policy, path);
        if (lost != null) {
            // A branch that never starts is invisible everywhere else: no listener fires, and the
            // transition's result is unchanged by design, so this line is the only trace of it.
            Loggers.EXECUTION_ASYNC.warn("Async branch not started, path={}, errorType={}",
                                         path, lost);
        }
    }

    /**
     * Gets one piece of async work running, and answers for an executor that cannot take it.
     * <p>
     * A refusal is an operational condition rather than a broken definition - the queue filled up,
     * or the state machine has been closed - so what happens next is the declared
     * {@link AsyncRejectionPolicy} rather than a rule of the framework's. Losing the work is the
     * only outcome this method reports rather than acts on, because what to say about it differs
     * by caller.
     *
     * @param task the work to run
     * @param policy what to do when the executor cannot take it; never {@code null}
     * @param subject what the work is, for diagnostics - an action path, or a listener id
     *
     * @return {@code null} when the work was handed over or run inline; otherwise the type of the
     *         refusal, for the caller to report as it sees fit
     *
     * @throws TransfluxValidationException when this state machine has no executor, or when
     *         {@link AsyncRejectionPolicy#BLOCK} is asked for against a host-supplied one
     * @throws RejectedExecutionException under {@link AsyncRejectionPolicy#FAIL}, and under
     *         {@link AsyncRejectionPolicy#BLOCK} when no wait could free a slot
     */
    String submitAsync(Runnable task, AsyncRejectionPolicy policy, Object subject) {
        requireAsyncAccepted(policy, subject);

        try {
            asyncExecutor.execute(new AsyncWork(task, policy, subject));
        } catch (RejectedExecutionException e) {
            return refuse(task, policy, subject, e);
        }
        return null;
    }

    /**
     * Refuses async work this state machine could never run as asked, before anything is spent
     * preparing it.
     *
     * @param policy the resolved rejection policy; never {@code null}
     * @param subject what the work is, for the message
     *
     * @throws TransfluxValidationException when this state machine has no executor, or when
     *         {@link AsyncRejectionPolicy#BLOCK} is asked for against a host-supplied one
     */
    void requireAsyncAccepted(AsyncRejectionPolicy policy, Object subject) {
        if (asyncExecutor == null) {
            throw new TransfluxValidationException(
                "No async executor is configured, yet async work was reached at '" + subject
                    + "'; a fork written inside an action body is invisible to the build, so a"
                    + " definition whose only forks are imperative has to ask for an executor with"
                    + " withAsyncPool() or withAsyncExecutor(...)");
        }

        // The build rejects BLOCK against a host executor wherever it can see the declaration, but
        // it cannot see one chosen inside a Java body. Refusing at every submission rather than
        // only at a rejection keeps the two answers the same: a policy that could never be honoured
        // fails the first time it is used, not the first time the queue happens to be full.
        if (policy == AsyncRejectionPolicy.BLOCK && !ownsAsyncExecutor) {
            throw new TransfluxValidationException(
                StateMachineDefImpl.blockUnavailable("the fork of '" + subject + "'"));
        }
    }

    /**
     * Marks the calling thread as running a branch this state machine spawned. Paired with
     * {@link #exitAsyncBranch()} in a {@code finally}, and nested rather than boolean so a branch
     * that drives another state machine which forks onto the same thread stays correct.
     */
    void enterAsyncBranch() {
        ASYNC_BRANCH.get().push(this);
    }

    /**
     * Clears the mark, and drops the thread-local entirely once the last branch on this thread has
     * returned - a pooled thread must not go on holding a reference to a state machine, which is a
     * reference to its whole definition graph and classloader.
     */
    void exitAsyncBranch() {
        Deque<StateMachineImpl<?>> stack = ASYNC_BRANCH.get();
        stack.pop();
        if (stack.isEmpty()) {
            ASYNC_BRANCH.remove();
        }
    }

    /**
     * Refuses a call into this state machine from inside a branch it spawned, for any entity.
     * <p>
     * A forked member is fire-and-forget: nothing joins it and its outcome reaches no caller, so a
     * transition driven from there would report success or failure to nobody. Work that has to
     * drive a machine belongs on the synchronous path, or on an executor the host owns and watches.
     * Other state machines are unaffected - only the one that spawned the branch is closed to it.
     *
     * @throws TransfluxReentrancyException if the calling thread is running a branch of this
     *         state machine
     */
    void rejectIfInsideAsyncBranch() {
        // No entity in the message, because the ban is not entity-scoped and there is nothing to
        // name - which also keeps the host's data out of the stack trace.
        if (insideOwnAsyncBranch()) {
            throw new TransfluxReentrancyException(
                "Dispatch into this state machine rejected: the calling thread is running an async"
                    + " branch it spawned, and a forked member may not drive the machine that"
                    + " forked it");
        }
    }

    /**
     * Claims the entity for one execution on this thread. Keyed on the handle rather than on the
     * snapshot, so an execution in flight against one generation still rejects a reentrant call
     * that arrives after a replacement.
     *
     * @param entity the entity under transition
     * @param transitionId the transition being attempted, for the message
     * @param targetStateId the state it leads to, for the message
     *
     * @throws TransfluxReentrancyException if this thread already has a transition in flight for
     *         this state machine and this entity
     */
    void enterTransition(Object entity, String transitionId, String targetStateId) {
        EntityKey key = new EntityKey(this, entity);
        Set<EntityKey> inFlight = IN_FLIGHT.get();
        if (inFlight.contains(key)) {
            // The entity's type, never the entity: it belongs to the host and may be full of PII,
            // and this message surfaces wherever the resulting stack trace is printed.
            throw new TransfluxReentrancyException(
                "Reentrant transition '" + transitionId + "' to state '" + targetStateId
                    + "' rejected, entityType=" + entity.getClass().getName()
                    + " (a transition is already in flight for the same state machine and entity)");
        }
        inFlight.add(key);
    }

    /**
     * Releases the claim {@link #enterTransition} took, and drops the thread-local once this thread
     * has no execution left in flight.
     *
     * @param entity the entity whose execution has finished
     */
    void exitTransition(Object entity) {
        Set<EntityKey> inFlight = IN_FLIGHT.get();
        inFlight.remove(new EntityKey(this, entity));
        if (inFlight.isEmpty()) {
            IN_FLIGHT.remove();
        }
    }

    /**
     * Refuses a replacement on a closed state machine.
     * <p>
     * Installing a definition here would either leave it without an executor it declares it needs -
     * and async work that cannot even be refused has nowhere to apply its
     * {@link AsyncRejectionPolicy} - or build a pool that nothing would ever shut down. Closing is
     * the end of a handle's life; a host that needs another definition builds another handle.
     */
    private void requireNotClosed() {
        if (closed.get()) {
            throw new TransfluxValidationException(
                "Definition replacement rejected: this state machine has been closed");
        }
    }

    /**
     * Refuses a replacement whose entity type is not the one this handle was built for - a
     * supertype and a subtype included, since every call site already holding the handle was
     * compiled against exactly one entity class.
     */
    private void requireSameEntityType(StateMachineDefImpl<T> def) {
        if (def.getEntityType() != entityType) {
            throw new TransfluxValidationException(
                "Definition replacement rejected: this state machine manages "
                    + entityType.getName() + ", but the new definition declares "
                    + (def.getEntityType() == null ? "none" : def.getEntityType().getName())
                    + "; the entity type is the handle's identity contract");
        }
    }

    /**
     * Keeps "BLOCK against a host-supplied executor fails the build" true across a replacement,
     * advising what a replacement can do. A definition's own build check only sees the executor
     * that definition declares, and under handle ownership the executor in force may have come from
     * an earlier one; a handle without one yet leaves the definition to its own build check.
     *
     * @param def the replacement
     *
     * @throws TransfluxValidationException if the replacement declares {@code BLOCK} where it
     *         cannot be honoured
     */
    private void requireBlockingPossible(StateMachineDefImpl<T> def) {
        if (asyncExecutor == null) {
            return;
        }
        String declaredOn = def.firstBlockingDeclaration();
        if (declaredOn == null) {
            return;
        }
        if (!ownsAsyncExecutor) {
            throw new TransfluxValidationException(StateMachineDefImpl.blockUnavailable(declaredOn,
                "the executor in force is host-supplied and a replacement keeps it", "choose another policy"));
        }
        if (def.getAsyncExecutor() != null) {
            throw new TransfluxValidationException(StateMachineDefImpl.blockUnavailable(declaredOn,
                StateMachineDefImpl.DECLARES_HOST_EXECUTOR + ", which a replacement ignores",
                "either drop withAsyncExecutor(...), or choose another policy"));
        }
    }

    /**
     * Says once, where a host will see it, that a definition's async configuration is not the one
     * running. Sizes rather than record equality: {@link AsyncPoolSpec} carries a
     * {@link java.util.concurrent.ThreadFactory}, so two default specs are never equal.
     */
    private void warnIfAsyncConfigDiffers(StateMachineDefImpl<T> def) {
        // The fair queue is chosen when the pool is built, from the definition that built it, so a
        // definition that introduces BLOCK later gets the waiting it asked for on a queue that does
        // not order the waiters - which is the starvation the flag exists to prevent.
        if (ownsAsyncExecutor && !asyncPoolFair && def.declaresBlockingRejection()) {
            Loggers.EXECUTION_ASYNC.warn(
                "Async rejection policy BLOCK declared by the new definition waits on a pool whose"
                    + " queue was not built fair, because the definition that created it declared"
                    + " no BLOCK, threads={}, queueCapacity={}",
                asyncPoolSpec.threads(), asyncPoolSpec.queueCapacity());
        }

        ExecutorService supplied = def.getAsyncExecutor();
        if (supplied != null) {
            if (supplied != asyncExecutor) {
                Loggers.EXECUTION_ASYNC.warn(
                    "Async executor declared by the new definition is ignored, the one in force was"
                        + " configured by an earlier definition, executorType={}",
                    supplied.getClass().getName());
            }
            return;
        }

        if (!def.declaresAsyncPool()) {
            return;
        }

        AsyncPoolSpec declared = def.getAsyncPoolSpec();
        if (asyncPoolSpec == null) {
            Loggers.EXECUTION_ASYNC.warn(
                "Async pool declared by the new definition is ignored, the executor in force is"
                    + " host-supplied, declaredThreads={}, declaredQueueCapacity={}",
                declared.threads(), declared.queueCapacity());
            return;
        }

        if (declared.threads() != asyncPoolSpec.threads()
            || declared.queueCapacity() != asyncPoolSpec.queueCapacity()) {
            Loggers.EXECUTION_ASYNC.warn(
                "Async pool declared by the new definition is ignored, declaredThreads={},"
                    + " declaredQueueCapacity={}, threads={}, queueCapacity={}",
                new Object[] {declared.threads(), declared.queueCapacity(),
                              asyncPoolSpec.threads(), asyncPoolSpec.queueCapacity()});
        }
    }

    /**
     * Applies the policy to work the executor would not take.
     * <p>
     * {@link AsyncRejectionPolicy#BLOCK} reaches this only when waiting was impossible rather than
     * merely slow: the executor is the host's and has no handler of ours (rejected at build time),
     * it is shutting down, or the caller is a worker of the very pool it is submitting to. The last
     * of those is answered by running the work inline, which always completes; the others fail,
     * because the whole point of the policy was that this work is not to be lost.
     *
     * @param rejection the refusal, for its type in the failure the caller may see
     *
     * @return the refusal's type when the work is lost; {@code null} when it ran inline
     */
    private String refuse(Runnable task, AsyncRejectionPolicy policy, Object subject,
                          RejectedExecutionException rejection) {
        // Exhaustive over the enum with no default, so a policy added later fails the build here
        // rather than silently falling through to losing the work.
        return switch (policy) {
            case DROP -> rejection.getClass().getName();
            case CALLER_RUNS -> runInline(task, subject, "executor refused the submission");
            case BLOCK -> {
                if (insideOwnAsyncBranch()) {
                    yield runInline(task, subject, "waiting would deadlock the pool");
                }
                throw rejection;
            }
            case FAIL -> throw rejection;
        };
    }

    /**
     * Runs the work on the calling thread. Only the thread changes: the task is the one the
     * executor would have run, so a branch keeps its own stack, its own drain and its own failure
     * handling, and the caller is held under the ban on driving this state machine while it runs.
     */
    private String runInline(Runnable task, Object subject, String reason) {
        // Running inline is the policy working, not an anomaly, so it is DEBUG per occurrence and
        // WARN once - a saturated pool would otherwise emit a line per submission for as long as it
        // stayed saturated, which is exactly when a host least wants its log budget spent.
        if (inlineRunReported.compareAndSet(false, true)) {
            Loggers.EXECUTION_ASYNC.warn(
                "Async work running inline rather than on the executor, subject={}, reason={}",
                subject, reason);
        } else if (Loggers.EXECUTION_ASYNC.isDebugEnabled()) {
            Loggers.EXECUTION_ASYNC.debug("Async work running inline, subject={}, reason={}",
                                          subject, reason);
        }

        task.run();
        return null;
    }

    /**
     * Reports whether the calling thread is already running a branch this state machine spawned.
     */
    private boolean insideOwnAsyncBranch() {
        return ASYNC_BRANCH.get().contains(this);
    }

    private record EntityKey(StateMachineImpl<?> sm, Object entity) {

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }

            if (!(o instanceof EntityKey other)) {
                return false;
            }

            return this.sm == other.sm && this.entity == other.entity;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(sm) * 31 + System.identityHashCode(entity);
        }
    }
}
