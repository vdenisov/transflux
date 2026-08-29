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
 * What a state machine does when its executor refuses a forked member - a full queue, or a pool
 * that has already been closed.
 * <p>
 * A rejection happens on the submitting thread, before the branch exists, so both answers are
 * still open: lose the work, or fail the transition that was about to spawn it. Which one is
 * right depends entirely on what the forked work does, which is why it is the host's to choose
 * rather than the framework's to assume.
 *
 * <p>This covers only a refused submission. Host code that fails while producing the branch's
 * context - a throwing {@link ContextMapper#mapTo(Object) mapTo} or
 * {@link ForkableContext#fork() fork} - always fails the transition, whatever this says: that is
 * a broken definition rather than a saturated system.
 */
public enum ForkRejectionPolicy {

    /**
     * Lose the forked member. A warning is logged naming the action, and the enclosing transition
     * continues as though the submission had succeeded.
     * <p>
     * The default. Forked work is fire-and-forget by construction, and failing business
     * transitions because a queue filled up turns back-pressure into an outage.
     */
    DROP,

    /**
     * Fail the enclosing transition. The rejection is raised on the submitting thread, so the
     * transition unwinds and its compensations run like any other failure.
     * <p>
     * Choose this when losing the forked work silently is worse than losing the transition.
     */
    FAIL
}
