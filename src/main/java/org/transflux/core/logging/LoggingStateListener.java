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

import org.transflux.core.state.StateChange;
import org.transflux.core.state.StateListener;
import org.transflux.core.state.StatePhase;

/**
 * Writes a state's entry and exit to {@code org.transflux.trace.state}.
 *
 * @param <T> the entity type
 */
final class LoggingStateListener<T> implements StateListener<T> {

    private final ExecutionLogging<? super T> options;

    LoggingStateListener(ExecutionLogging<? super T> options) {
        this.options = options;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onState(T entity, Object context, StateChange<T> change) {
        TraceLine.write(TraceLine.STATE, (ExecutionLogging<T>) options,
                        change.phase() == StatePhase.ENTRY ? "State entered" : "State exited",
                        entity, null, context,
                        "stateId", change.state().getId(),
                        "transitionId", change.transition().getId());
    }
}
