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

package org.transflux.core.logging;

import org.transflux.core.transition.TransitionExecution;
import org.transflux.core.transition.TransitionListener;
import org.transflux.core.transition.TransitionResult;
import org.transflux.core.trigger.Trigger;

/**
 * Writes a transition's start and outcome to {@code org.transflux.trace.transition}.
 *
 * @param <T> the entity type
 * @param <C> the context type
 */
final class LoggingTransitionListener<T, C> implements TransitionListener<T, C> {

    private final ExecutionLogging<? super T> options;

    LoggingTransitionListener(ExecutionLogging<? super T> options) {
        this.options = options;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onTransition(T entity, C context, TransitionExecution<T> execution) {
        ExecutionLogging<T> typed = (ExecutionLogging<T>) options;
        String transitionId = execution.transition().getId();
        TransitionResult<T> result = execution.result();
        switch (execution.phase()) {
            case START -> {
                Trigger firedBy = execution.firedBy();
                TraceLine.write(TraceLine.TRANSITION, typed, "Transition started", entity, null,
                                context,
                                "transitionId", transitionId,
                                "source", execution.transition().getSourceStateId(),
                                "target", execution.transition().getTargetStateId(),
                                "firedBy", firedBy == null ? null : firedBy.getId());
            }
            case COMPLETE -> TraceLine.write(TraceLine.TRANSITION, typed, "Transition completed",
                                             entity, result.getDuration(), context,
                                             "transitionId", transitionId,
                                             "executedPath", result.getExecutedPath());
            case ERROR -> TraceLine.write(TraceLine.TRANSITION, typed, "Transition failed", entity,
                                          result.getDuration(), context,
                                          "transitionId", transitionId,
                                          "errorType", result.getError().getClass().getName(),
                                          "compensatedPath", result.getCompensatedPath());
        }
    }
}
