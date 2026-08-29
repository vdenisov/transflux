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

import org.transflux.core.action.Compensation;
import org.transflux.core.transition.ActionPath;

import java.util.ArrayList;
import java.util.List;

/**
 * Unwinds a rollback stack in LIFO order, asking each entry's routing table what answers for the
 * failure at hand.
 * <p>
 * There are two stacks that unwind this way and they behave identically: a transition's own, and a
 * forked member's. They differ only in what the opening line calls the thing being rolled back, so
 * the two entry points carry a message apiece and share everything below them.
 */
final class CompensationDrain {

    private CompensationDrain() {
        // utility class — no instances
    }

    /**
     * Unwinds a transition's stack after it failed.
     *
     * @param view the failed transition's view, whose stack is drained
     * @param entity the entity under transition
     * @param failure the throwable that ended the transition; every entry routes against this one
     * @param transitionId the transition's id, for diagnostics
     * @param <T> the entity type
     * @param <C> the transition's context type
     *
     * @return the paths that actually rolled back, in the order they did
     */
    static <T, C> List<ActionPath> forTransition(ExecutingTransitionImpl<T, C> view, T entity,
                                                 Exception failure, String transitionId) {
        List<BoundCompensation<T, C>> drained = view.drainCompensationsLifo();
        if (!drained.isEmpty()) {
            // Candidates rather than a count: an action whose routes all miss the failure is on
            // the stack but rolls back nothing. The class name, never the message: an exception
            // raised by the framework itself can carry the entity, and a host's own exception
            // can carry anything at all.
            Loggers.EXECUTION_COMPENSATION.info(
                "Draining compensations, transitionId={}, candidates={}, errorType={}",
                transitionId, drained.size(), failure.getClass().getName());
        }
        return run(drained, entity, failure);
    }

    /**
     * Unwinds one forked member's stack after that branch failed. Nothing outside the branch is
     * touched: the transition that spawned it has its own stack, and may well have completed.
     *
     * @param view the branch's view, whose stack is drained
     * @param entity the entity the branch ran against
     * @param failure the throwable that ended the branch
     * @param branchPath the qualified path of the forked member, for diagnostics
     * @param <T> the entity type
     * @param <C> the branch's context type
     *
     * @return the paths that actually rolled back, in the order they did
     */
    static <T, C> List<ActionPath> forBranch(ExecutingTransitionImpl<T, C> view, T entity,
                                             Exception failure, ActionPath branchPath) {
        List<BoundCompensation<T, C>> drained = view.drainCompensationsLifo();
        if (!drained.isEmpty()) {
            Loggers.EXECUTION_COMPENSATION.info(
                "Draining branch compensations, branchPath={}, candidates={}, errorType={}",
                branchPath, drained.size(), failure.getClass().getName());
        }
        return run(drained, entity, failure);
    }

    private static <T, C> List<ActionPath> run(List<BoundCompensation<T, C>> drained, T entity,
                                               Exception failure) {
        List<ActionPath> compensatedPath = new ArrayList<>(drained.size());

        for (BoundCompensation<T, C> bc : drained) {
            Compensation<T, C> selected = bc.router().select(failure, bc.path());
            if (selected == null) {
                continue;
            }
            // Recorded only once the table has answered, so the compensated path reports what
            // actually rolled back rather than what was merely eligible to.
            compensatedPath.add(bc.path());
            try {
                selected.compensate(entity, bc.context());
                // Exception and not Throwable, throughout the drain: an Error says the JVM is
                // in an unstable state, and driving the remaining handlers through network
                // calls and remote deletes there is worse than abandoning the rollback.
            } catch (Exception ce) {
                Loggers.EXECUTION_COMPENSATION.warn(
                    "Compensation threw, actionPath={}, errorType={}",
                    bc.path(), ce.getClass().getName());
            }
        }

        return compensatedPath;
    }
}
