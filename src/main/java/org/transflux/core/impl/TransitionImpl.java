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

import org.transflux.core.transition.Transition;

import static org.transflux.core.Preconditions.requireNotNull;

/**
 * The {@link Transition} view handed to every observer of an execution — conditions, state
 * listeners, transition listeners, action listeners, and a data trigger's gate.
 * <p>
 * It carries copies of the three topology strings rather than a reference to the
 * {@link BoundTransition} they came from, and that is deliberate rather than incidental. An
 * expression-based condition binds this object into SpEL as {@code #transition}, and SpEL resolves
 * properties reflectively against the runtime object — including private fields. A view that held
 * its {@code BoundTransition} would therefore expose the whole resolved graph (bound actions,
 * conditions, listeners) to expression authors, handing back by reflection exactly the dispatch
 * capability that keeping observers off {@link org.transflux.core.transition.ExecutingTransition}
 * takes away. Only what this record declares is reachable.
 *
 * @param id the transition id
 * @param sourceStateId the source state id
 * @param targetStateId the target state id
 */
record TransitionImpl(String id, String sourceStateId, String targetStateId)
    implements Transition {

    /**
     * Snapshots the topology of a bound transition.
     *
     * @param bound the bound transition to snapshot; never {@code null}
     *
     * @return the read-only view
     */
    static Transition of(BoundTransition<?, ?> bound) {
        requireNotNull(bound, "Bound transition");
        return new TransitionImpl(bound.id(), bound.sourceStateId(), bound.targetStateId());
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getSourceStateId() {
        return sourceStateId;
    }

    @Override
    public String getTargetStateId() {
        return targetStateId;
    }
}
