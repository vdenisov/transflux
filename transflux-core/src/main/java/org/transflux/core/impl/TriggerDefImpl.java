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

import org.transflux.core.condition.ConditionDescriptor;
import org.transflux.core.exception.TransfluxValidationException;

import java.util.List;
import java.util.Map;

import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Shared base for the trigger definition family.
 * <p>
 * A trigger is either declared in place on a transition, which then owns it, or registered on the
 * state machine and attached by id. The first reads its context back through its owner rather than
 * copying it at construction, so it reports whatever the transition carries rather than a stale
 * snapshot, and declaration order inside the configurer does not matter - the same freedom the rest
 * of the DSL gives. The second has no owner and carries a context of its own.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 * @param <SELF> the concrete def type, for the self-typed builder methods
 */
sealed abstract class TriggerDefImpl<T, C, SELF extends TriggerDefImpl<T, C, SELF>>
    extends IdentifiedDefImpl<SELF>
    permits ManualTriggerDefImpl, EventTriggerDefImpl, DataTriggerDefImpl {

    private final TransitionDefImpl<T, C> owner;
    private final Class<C> registeredContext;

    /** A trigger declared in place on a transition, which is the only one that may attach it. */
    TriggerDefImpl(String id, String kind, TransitionDefImpl<T, C> owner) {
        super(id, kind, "Trigger ID");
        requireNotNull(owner, "Trigger transition");
        this.owner = owner;
        this.registeredContext = null;
    }

    /** A trigger registered on the state machine, which any number of transitions may attach. */
    TriggerDefImpl(String id, String kind, Class<C> contextType) {
        super(id, kind, "Trigger ID");
        requireNotNull(contextType, "Trigger context type");
        this.owner = null;
        this.registeredContext = contextType;
    }

    /**
     * Returns the context class this trigger runs against - a registration's own, or the enclosing
     * transition's as currently declared.
     *
     * @return the context type; never {@code null}
     */
    public Class<C> getContextType() {
        return owner == null ? registeredContext : owner.getContextType();
    }

    /**
     * @return the transition declaring this trigger in place, or {@code null} for a registration
     */
    TransitionDefImpl<T, C> getOwner() {
        return owner;
    }

    /**
     * Returns the conditions this trigger carries, which the build claims and checks like any
     * other inline condition.
     *
     * @return the descriptors, in declaration order; possibly empty
     */
    abstract List<ConditionDescriptor> conditionDescriptors();

    /**
     * Names what {@link #conditionDescriptors()} are, as a rejection of one of them names it; a
     * kind that carries conditions names its own.
     *
     * @return such as {@code pre-condition}
     */
    String conditionRole() {
        return "condition";
    }

    /**
     * Resolves this definition into the runtime trigger.
     *
     * @param registry the state machine's resolved conditions
     * @param transitionIds the transitions it is attached to
     *
     * @return the runtime trigger
     *
     * @throws TransfluxValidationException if the definition is incomplete
     */
    abstract TriggerImpl buildBound(Map<String, BoundCondition<T, C>> registry, List<String> transitionIds);
}
