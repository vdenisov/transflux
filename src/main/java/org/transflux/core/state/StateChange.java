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

package org.transflux.core.state;

import org.transflux.core.transition.Transition;

import static org.transflux.core.Preconditions.requireNotNull;

/**
 * The payload handed to a {@link StateListener} when a state is entered or left.
 * <p>
 * The same carrier serves both hooks: {@link #phase()} says which one fired, {@link #state()} is
 * the state being entered or left, and {@link #transition()} is the transition responsible for
 * the change. Together they let a globally-registered listener — which is attached to no
 * particular state — tell one notification from another.
 *
 * <p>The transition is a read-only topology view. Its id and source/target accessors answer
 * normally, but every {@code step} and {@code operation} dispatch method throws: a listener runs
 * either before the operation has started or after the transition has already been committed, so
 * work it dispatched would produce side effects whose compensations could never be executed.
 *
 * @param phase whether the state is being entered or left
 * @param state the state being entered or left
 * @param transition the transition causing the change, as a read-only topology view
 * @param <T> the entity type the surrounding state machine manages
 */
public record StateChange<T>(StatePhase phase, State<T> state, Transition<T, ?> transition) {

    public StateChange {
        requireNotNull(phase, "State change phase");
        requireNotNull(state, "State change state");
        requireNotNull(transition, "State change transition");
    }
}
