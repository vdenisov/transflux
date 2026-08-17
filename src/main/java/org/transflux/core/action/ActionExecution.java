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

import org.transflux.core.exception.TransfluxValidationException;
import org.transflux.core.transition.ActionPath;
import org.transflux.core.transition.Transition;

import static org.transflux.core.Preconditions.requireNotNull;

/**
 * The payload handed to an {@link ActionListener} at each point of an action's execution.
 * <p>
 * {@link #phase()} says which hook fired, {@link #path()} is the invocation's qualified position in
 * the execution tree, {@link #kind()} is the form the action was authored in, and
 * {@link #transition()} is the transition the execution belongs to. Together they let a listener
 * registered against every action - which is attached to no particular one - tell one notification
 * from another:
 *
 * <pre>{@code
 * .onAnyActionError("capture", (order, ctx, execution) -> {
 *     if (execution.kind() == ActionKind.STEP) {
 *         auditService.record(order, execution.path(), ctx, execution.error());
 *     }
 * })
 * }</pre>
 *
 * <p>{@link #path()} is the same value the invocation contributes to
 * {@link org.transflux.core.transition.TransitionResult#getExecutedPath()}, so a listener can line
 * its own record up against the reported path without reconstructing the nesting itself.
 * {@link #actionId()} is the leaf of that path - the action's own id.
 *
 * <p>{@link #error()} carries the failure at {@link ActionPhase#ERROR} and is {@code null} at the
 * other two hooks. Because {@link ActionPhase#COMPLETE} and {@link ActionPhase#ERROR} partition the
 * outcomes, a completion listener never has to check whether the action actually worked.
 *
 * <p>The transition is the read-only {@link Transition} view rather than the dispatching handle the
 * observed action itself received: an action listener runs inside a live execution, and work it
 * dispatched would interleave into the executed path and the compensation stack as though the
 * observed action had dispatched it.
 *
 * @param phase which point of the execution this notification describes
 * @param path the qualified path of this invocation, outermost enclosing action first
 * @param kind the form the action was authored in
 * @param transition the transition this execution belongs to
 * @param error the failure, or {@code null} at any phase other than {@link ActionPhase#ERROR}
 */
public record ActionExecution(ActionPhase phase,
                              ActionPath path,
                              ActionKind kind,
                              Transition transition,
                              Throwable error) {

    public ActionExecution {
        requireNotNull(phase, "Action execution phase");
        requireNotNull(path, "Action execution path");
        requireNotNull(kind, "Action execution kind");
        requireNotNull(transition, "Action execution transition");
        requireErrorMatchesPhase(phase, error);
    }

    /**
     * Returns the observed action's own id - the leaf segment of {@link #path()}.
     *
     * @return the action id; never {@code null} or blank
     */
    public String actionId() {
        return path.leaf();
    }

    private static void requireErrorMatchesPhase(ActionPhase phase, Throwable error) {
        if (phase == ActionPhase.ERROR) {
            if (error == null) {
                throw new TransfluxValidationException(
                    "Action execution error cannot be null at phase ERROR");
            }
            return;
        }

        if (error != null) {
            throw new TransfluxValidationException(
                "Action execution error must be null at phase " + phase);
        }
    }
}
