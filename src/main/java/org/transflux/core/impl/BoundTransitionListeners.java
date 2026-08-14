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

import org.transflux.core.transition.TransitionPhase;

import java.util.List;

/**
 * The three hook lists a transition notifies, each already in notification order: the
 * transition's own listeners first, then the ones registered against every transition.
 * <p>
 * Merging at build time keeps notification a plain field read, and grouping the three lists here
 * keeps {@link BoundTransition} from growing three parallel components.
 *
 * @param onStart listeners notified once the pre-conditions have passed
 * @param onComplete listeners notified after a successful transition is committed
 * @param onError listeners notified after a failed transition has compensated
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
record BoundTransitionListeners<T, C>(List<BoundTransitionListener<T, C>> onStart,
                                      List<BoundTransitionListener<T, C>> onComplete,
                                      List<BoundTransitionListener<T, C>> onError) {

    BoundTransitionListeners {
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
    static <T, C> BoundTransitionListeners<T, C> none() {
        return new BoundTransitionListeners<>(List.of(), List.of(), List.of());
    }

    /**
     * Returns the list for the supplied phase.
     *
     * @param phase the phase being notified
     *
     * @return the listeners for that hook, in notification order
     */
    List<BoundTransitionListener<T, C>> forPhase(TransitionPhase phase) {
        return switch (phase) {
            case START -> onStart;
            case COMPLETE -> onComplete;
            case ERROR -> onError;
        };
    }
}
