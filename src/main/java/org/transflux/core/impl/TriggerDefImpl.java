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

import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Shared base for the trigger definition family, holding the enclosing transition every trigger
 * belongs to.
 * <p>
 * Both the transition id and the context type are read back through that transition rather than
 * copied at construction, so a trigger declared before its transition calls {@code usingContext}
 * still reports the type the transition ends up with. Declaration order inside the configurer
 * therefore does not matter, which is the same freedom the rest of the DSL gives.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 * @param <SELF> the concrete def type, for the self-typed builder methods
 */
sealed abstract class TriggerDefImpl<T, C, SELF extends TriggerDefImpl<T, C, SELF>>
    extends IdentifiedDefImpl<SELF>
    permits ManualTriggerDefImpl, EventTriggerDefImpl, DataTriggerDefImpl {

    private final TransitionDefImpl<T, C> owner;

    TriggerDefImpl(String id, String kind, TransitionDefImpl<T, C> owner) {
        super(id, kind, "Trigger ID");
        requireNotNull(owner, "Trigger transition");
        this.owner = owner;
    }

    /**
     * Returns the id of the transition this trigger fires.
     *
     * @return the enclosing transition's id
     */
    String transitionId() {
        return owner.getId();
    }

    /**
     * Returns the context class carried by the enclosing transition, as currently declared.
     *
     * @return the enclosing transition's context type
     */
    public Class<C> contextType() {
        return owner.getContextType();
    }
}
