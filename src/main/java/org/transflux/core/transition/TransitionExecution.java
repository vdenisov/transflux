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

package org.transflux.core.transition;

import org.transflux.core.exception.TransfluxValidationException;
import org.transflux.core.trigger.Trigger;

import static org.transflux.core.Preconditions.requireNotNull;

/**
 * The payload handed to a {@link TransitionListener} at each point of a transition's execution.
 * <p>
 * {@link #phase()} says which hook fired, {@link #transition()} is the transition itself, and
 * {@link #firedBy()} is the trigger that caused it. Together they let a listener registered
 * against every transition — which is attached to no particular one — tell one notification from
 * another, and let a listener react only to a specific invocation path:
 *
 * <pre>{@code
 * .onComplete("audit", (order, ctx, execution) -> {
 *     Trigger by = execution.firedBy();
 *     auditService.record(order, by == null ? "direct" : by.getId());
 * })
 * }</pre>
 *
 * <p>{@link #result()} carries the full outcome — executed path, compensated path, error, and
 * timings — at the two terminal hooks. It is {@code null} at {@link TransitionPhase#START},
 * where nothing has run yet. Because {@link TransitionPhase#COMPLETE} and
 * {@link TransitionPhase#ERROR} partition success from failure, a completion listener never has
 * to check whether the transition actually worked.
 *
 * <p>The transition is the read-only {@link Transition} view rather than the dispatching handle an
 * action receives: a listener runs either before the operation has started or after the outcome is
 * settled, so work it dispatched would produce side effects whose compensations could never be
 * executed.
 *
 * @param phase which point of the execution this notification describes
 * @param transition the transition being executed
 * @param firedBy the trigger that caused this execution, or {@code null} when the host invoked
 *                the transition directly
 * @param result the outcome, or {@code null} at {@link TransitionPhase#START}
 * @param <T> the entity type the surrounding state machine manages
 */
public record TransitionExecution<T>(TransitionPhase phase,
                                     Transition transition,
                                     Trigger firedBy,
                                     TransitionResult<T> result) {

    public TransitionExecution {
        requireNotNull(phase, "Transition execution phase");
        requireNotNull(transition, "Transition execution transition");
        requireResultMatchesPhase(phase, result);
    }

    private static void requireResultMatchesPhase(TransitionPhase phase, TransitionResult<?> result) {
        if (phase == TransitionPhase.START) {
            if (result != null) {
                throw new TransfluxValidationException(
                    "Transition execution result must be null at phase START");
            }
            return;
        }

        if (result == null) {
            throw new TransfluxValidationException(
                "Transition execution result cannot be null at phase " + phase);
        }
        if (phase == TransitionPhase.COMPLETE && !result.isSuccess()) {
            throw new TransfluxValidationException(
                "Transition execution at phase COMPLETE requires a successful result");
        }
        if (phase == TransitionPhase.ERROR && result.isSuccess()) {
            throw new TransfluxValidationException(
                "Transition execution at phase ERROR requires a failed result");
        }
    }
}
