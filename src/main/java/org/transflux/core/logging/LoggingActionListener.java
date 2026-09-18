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

import org.transflux.core.action.ActionExecution;
import org.transflux.core.action.ActionListener;

/**
 * Writes an action's start and outcome to {@code org.transflux.trace.action}.
 *
 * @param <T> the entity type
 * @param <C> the context type
 */
final class LoggingActionListener<T, C> implements ActionListener<T, C> {

    private final ExecutionLogging<? super T> options;

    LoggingActionListener(ExecutionLogging<? super T> options) {
        this.options = options;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onAction(T entity, C context, ActionExecution execution) {
        ExecutionLogging<T> typed = (ExecutionLogging<T>) options;
        switch (execution.phase()) {
            case START -> TraceLine.write(TraceLine.ACTION, typed, "Action started", entity, null,
                                          context,
                                          "path", execution.path(),
                                          "kind", execution.kind());
            case COMPLETE -> TraceLine.write(TraceLine.ACTION, typed, "Action completed", entity,
                                             execution.duration(), context,
                                             "path", execution.path());
            case ERROR -> TraceLine.write(TraceLine.ACTION, typed, "Action failed", entity,
                                          execution.duration(), context,
                                          "path", execution.path(),
                                          "errorType", execution.error().getClass().getName());
        }
    }
}
