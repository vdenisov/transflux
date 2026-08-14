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

import org.transflux.core.trigger.ManualTrigger;

import java.util.List;

import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Runtime manual trigger paired with its resolved pre-conditions.
 * <p>
 * Implements the public {@link ManualTrigger} catalog view and additionally carries the bound
 * pre-conditions evaluated when the trigger fires. The pre-conditions are applied after the
 * enclosing transition's own pre-conditions; the accessor is package-private so only the dispatch
 * path reads them.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
final class ManualTriggerImpl<T, C> extends TriggerImpl implements ManualTrigger {

    private final List<BoundCondition<T, C>> preConditions;

    ManualTriggerImpl(String id, String name, String description, String transitionId,
                      List<BoundCondition<T, C>> preConditions) {
        super(id, name, description, transitionId);
        requireNotNull(preConditions, "Trigger pre-conditions");
        this.preConditions = List.copyOf(preConditions);
    }

    /**
     * Returns the resolved pre-conditions gating this trigger's invocation, in declaration order.
     *
     * @return an immutable list of bound pre-conditions
     */
    @Override
    List<BoundCondition<T, C>> preConditions() {
        return preConditions;
    }

    @Override
    void checkDirectlyFireable() {
        // Manual triggers exist to be fired directly; their gate is the pre-condition list above.
    }

    @Override
    String kindPhrase() {
        return "a manual trigger";
    }

    @Override
    String dispatchEntryPoint() {
        return "fire(triggerId[, context])";
    }
}
