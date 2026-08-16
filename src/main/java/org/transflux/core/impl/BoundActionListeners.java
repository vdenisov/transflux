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

import org.transflux.core.action.ActionPhase;

import java.util.List;

/**
 * The three hook lists an action notifies, in declaration order.
 * <p>
 * Unlike the transition equivalent these carry the action's <b>own</b> listeners only. The
 * state-machine-wide registrations are held once on the state machine and appended at notification
 * time, because an action's bound record is shared by every call site that reaches it and merging
 * the globals into each one would bind the same global listener as many times as there are actions.
 *
 * @param onStart listeners notified before the action's body runs
 * @param onComplete listeners notified after the body returns normally
 * @param onError listeners notified when the body, or an action it dispatched, throws
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the context type the observed action runs against
 */
record BoundActionListeners<T, C>(List<BoundActionListener<T, C>> onStart,
                                  List<BoundActionListener<T, C>> onComplete,
                                  List<BoundActionListener<T, C>> onError) {

    BoundActionListeners {
        onStart = List.copyOf(onStart);
        onComplete = List.copyOf(onComplete);
        onError = List.copyOf(onError);
    }

    /**
     * Returns an instance with no listeners on any hook.
     *
     * @param <T> the entity type
     * @param <C> the context type
     *
     * @return the empty set of hook lists
     */
    static <T, C> BoundActionListeners<T, C> none() {
        return new BoundActionListeners<>(List.of(), List.of(), List.of());
    }

    /**
     * Returns {@code true} when no hook carries a listener.
     *
     * @return whether every hook list is empty
     */
    boolean isEmpty() {
        return onStart.isEmpty() && onComplete.isEmpty() && onError.isEmpty();
    }

    /**
     * Returns the list for the supplied phase.
     *
     * @param phase the phase being notified
     *
     * @return the listeners for that hook, in notification order
     */
    List<BoundActionListener<T, C>> forPhase(ActionPhase phase) {
        return switch (phase) {
            case START -> onStart;
            case COMPLETE -> onComplete;
            case ERROR -> onError;
        };
    }
}
