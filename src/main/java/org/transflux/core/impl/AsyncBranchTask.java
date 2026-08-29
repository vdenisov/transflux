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

import org.transflux.core.transition.ActionPath;

/**
 * One forked member, on the executor's thread: run it, and if it fails, roll back what it did.
 * <p>
 * The mapper is always {@code null} here, and that is the whole reason a forked member never
 * writes back into the parent context. Any mapping was applied at submission, so the view this
 * task holds already carries the child context; with no mapper to hand
 * {@link ExecutingTransitionImpl#runAction}, the {@code mapFrom} call at the end of that method is
 * unreachable for a branch by construction rather than by a flag anyone has to remember.
 *
 * @param <T> the entity type the enclosing state machine manages
 */
final class AsyncBranchTask<T> implements Runnable {

    private final StateMachineImpl<T> stateMachine;
    private final ExecutingTransitionImpl<T, Object> view;
    private final BoundAction<T, Object> action;
    private final ActionPath path;

    AsyncBranchTask(StateMachineImpl<T> stateMachine, ExecutingTransitionImpl<T, Object> view,
                    BoundAction<T, Object> action, ActionPath path) {
        this.stateMachine = stateMachine;
        this.view = view;
        this.action = action;
        this.path = path;
    }

    @Override
    public void run() {
        stateMachine.enterAsyncBranch();
        try {
            view.runAction(action, null);
            Loggers.EXECUTION_ASYNC.trace("Async branch completed, path={}", path);
        } catch (Exception e) {
            // The one place a branch's failure is reported: nothing joins it, and the transition
            // that spawned it has long since returned its own result to the caller.
            Loggers.EXECUTION_ASYNC.warn("Async branch failed, path={}, errorType={}",
                                         path, e.getClass().getName());
            CompensationDrain.forBranch(view, view.getEntity(), e, path);
        } finally {
            stateMachine.exitAsyncBranch();
        }
    }
}
