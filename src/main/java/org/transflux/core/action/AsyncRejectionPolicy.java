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

package org.transflux.core.action;

/**
 * What a state machine does when its executor cannot take async work right now - a full queue, or
 * a pool that has already been closed.
 * <p>
 * The refusal happens on the submitting thread, before the work has started, which is what leaves
 * every answer below open. Which one is right is the host's to choose rather than the framework's
 * to assume, and three places can say so for one submission - the most specific wins. The position
 * that forks a registered action ({@code fork(id, policy)}), for work that does not know how this
 * flow treats it; the action's own def ({@code ActionDef.withAsyncRejectionPolicy(...)}), for work
 * that does know; and the state machine ({@code StateMachineDef.withAsyncRejectionPolicy(...)}) for
 * everything unsaid. A listener declares it on its own def, since it has no position.
 *
 * <p>This covers only a refused submission. Host code that fails while producing the branch's
 * context - a throwing {@link ContextMapper#mapTo(Object) mapTo} or
 * {@link ForkableContext#fork() fork} - always fails the transition, whatever this says: that is
 * a broken definition rather than a saturated system.
 */
public enum AsyncRejectionPolicy {

    /**
     * Lose the work. A warning is logged naming it, and the enclosing transition continues as
     * though the submission had succeeded.
     * <p>
     * The default. Forked work is fire-and-forget by construction, and failing business
     * transitions because a queue filled up turns back-pressure into an outage.
     */
    DROP,

    /**
     * Fail the enclosing transition. The rejection is raised on the submitting thread, so the
     * transition unwinds and its compensations run like any other failure.
     * <p>
     * Choose this when losing the work silently is worse than losing the transition.
     */
    FAIL,

    /**
     * Wait for capacity, then submit. The submitting thread parks until a queue slot frees up,
     * which makes the enclosing transition as slow as the executor is busy - back-pressure
     * applied to the caller instead of to the work.
     * <p>
     * There is no timeout, deliberately: the wait is bounded by the work already queued ahead.
     * Two cases cannot wait and do something else instead. A closed executor never frees a slot,
     * so the submission is refused and the rejection is raised like {@link #FAIL}; and a thread
     * already running a branch of this state machine runs the work inline like
     * {@link #CALLER_RUNS} rather than parking, because a pool worker waiting on its own pool can
     * be waiting for a slot only it could free.
     *
     * <p>Requires the pool the framework builds for itself. The wait happens inside that pool's
     * rejection handler, which is the only place a refusal can still be turned into an enqueue; a
     * host-supplied executor is not the framework's to reconfigure, so declaring this alongside
     * {@code StateMachineDef.withAsyncExecutor(...)} fails the build. A host that wants this
     * back-pressure installs a blocking rejection handler on its own pool.
     */
    BLOCK,

    /**
     * Run the work on the submitting thread instead of an executor thread.
     * <p>
     * Only the thread changes. A forked member run this way is still its own branch - its own
     * compensation stack, its own drain, its failure logged and swallowed rather than returned,
     * and on neither reported path - and the thread running it is under the same ban on driving
     * the state machine that a pool thread is. It is the one policy that still executes the work
     * after {@link org.transflux.core.StateMachine#close()}, which is why it suits work that must
     * happen even as an application shuts down.
     *
     * <p>The single exception to "only the thread changes": an {@link Error} raised by inline work
     * propagates into the enclosing transition rather than dying on a pool worker. Everything the
     * framework catches around async work is an {@link Exception}, deliberately.
     */
    CALLER_RUNS
}
