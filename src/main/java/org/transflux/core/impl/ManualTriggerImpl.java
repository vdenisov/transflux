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

import org.transflux.core.trigger.Trigger;

import java.util.List;

import static org.transflux.core.Preconditions.requireNotBlank;
import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Runtime manual trigger paired with its resolved pre-conditions.
 * <p>
 * Implements the public {@link Trigger} catalog view and additionally carries the bound
 * pre-conditions evaluated when the trigger fires. The pre-conditions are applied after the
 * enclosing transition's own pre-conditions; the accessor is package-private so only the dispatch
 * path reads them.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
final class ManualTriggerImpl<T, C> implements Trigger {

    private final String id;
    private final String name;
    private final String description;
    private final String transitionId;
    private final List<BoundCondition<T, C>> preConditions;

    ManualTriggerImpl(String id, String name, String description, String transitionId,
                      List<BoundCondition<T, C>> preConditions) {
        requireNotBlank(id, "Trigger ID");
        requireNotBlank(transitionId, "Trigger transition ID");
        requireNotNull(preConditions, "Trigger pre-conditions");
        this.id = id;
        this.name = name;
        this.description = description;
        this.transitionId = transitionId;
        this.preConditions = List.copyOf(preConditions);
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public String getTransitionId() {
        return transitionId;
    }

    /**
     * Returns the resolved pre-conditions gating this trigger's invocation, in declaration order.
     *
     * @return an immutable list of bound pre-conditions
     */
    List<BoundCondition<T, C>> preConditions() {
        return preConditions;
    }
}
