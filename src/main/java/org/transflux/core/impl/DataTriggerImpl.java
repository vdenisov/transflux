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

import org.transflux.core.trigger.DataTrigger;

import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Runtime data trigger paired with its resolved gate condition.
 * <p>
 * Implements the public {@link DataTrigger} catalog view. The gate is evaluated during
 * {@code processDataChange} dispatch to decide whether the trigger fires; the accessor is
 * package-private so only the dispatch path reads it.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
final class DataTriggerImpl<T, C> extends TriggerImpl implements DataTrigger {

    private final BoundCondition<T, C> gate;

    DataTriggerImpl(String id, String name, String description, String transitionId,
                    BoundCondition<T, C> gate) {
        super(id, name, description, transitionId);
        requireNotNull(gate, "Trigger gate condition");
        this.gate = gate;
    }

    /**
     * Returns the resolved gate condition deciding whether this trigger fires.
     *
     * @return the bound gate condition
     */
    BoundCondition<T, C> gate() {
        return gate;
    }

    @Override
    String kindPhrase() {
        return "a data trigger";
    }

    @Override
    String dispatchEntryPoint() {
        return "processDataChange([context])";
    }
}
