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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.transflux.core.Identifiable;
import org.transflux.core.exception.TransfluxValidationException;
import org.transflux.core.state.StateDef;
import org.transflux.core.transition.TransitionDef;

import java.util.function.Consumer;

import static org.transflux.core.Preconditions.requireNotBlank;
import static org.transflux.core.Preconditions.requireNotNull;
import static org.transflux.core.impl.ValidationUtils.warnIfSet;

/**
 * Builder implementation class for defining states within a state machine definition.
 *
 * @param <T> the type of entity managed by the state machine
 */
class StateDefImpl<T> implements StateDef<T> {
    private static final Logger log = LoggerFactory.getLogger(StateDefImpl.class);

    private final String id;
    private String name;
    private String description;

    private final StateMachineDefImpl<T> stateMachineDef;

    private boolean configurerActive;

    StateDefImpl(StateMachineDefImpl<T> smd, String id) {
        requireNotNull(smd, "State machine definition");
        requireNotBlank(id, "State ID");

        this.stateMachineDef = smd;
        this.id = id;
    }

    StateDefImpl(StateMachineDefImpl<T> smd, Identifiable identifiable) {
        requireNotNull(smd, "State machine definition");
        requireNotNull(identifiable, "Identifiable for state ID");
        requireNotBlank(identifiable.getId(), "State ID");

        this.stateMachineDef = smd;
        this.id = identifiable.getId();
    }

    @Override
    public StateDefImpl<T> withName(String name) {
        requireConfigurerActive("withName");
        warnIfSet(this.name, name, "Name", log);
        this.name = name;
        return this;
    }

    @Override
    public StateDefImpl<T> withDescription(String description) {
        requireConfigurerActive("withDescription");
        warnIfSet(this.description, description, "Description", log);
        this.description = description;
        return this;
    }

    @Override
    public StateDefImpl<T> transitionsTo(String targetStateId, String transitionId,
                                         Consumer<TransitionDef<T, Object>> configurer) {
        requireConfigurerActive("transitionsTo");
        requireNotBlank(targetStateId, "Target state ID");
        requireNotBlank(transitionId, "Transition ID");
        requireNotNull(configurer, "Transition configurer");
        TransitionDefImpl<T, Object> td = stateMachineDef.<Object>registerTransition(
            id, targetStateId, transitionId, Object.class);
        runTransitionConfigurer(td, configurer);
        return this;
    }

    @Override
    public <C> StateDefImpl<T> transitionsTo(String targetStateId, String transitionId,
                                             Class<C> contextType,
                                             Consumer<TransitionDef<T, C>> configurer) {
        requireConfigurerActive("transitionsTo");
        requireNotBlank(targetStateId, "Target state ID");
        requireNotBlank(transitionId, "Transition ID");
        requireNotNull(contextType, "Context type");
        requireNotNull(configurer, "Transition configurer");
        TransitionDefImpl<T, C> td = stateMachineDef.registerTransition(
            id, targetStateId, transitionId, contextType);
        runTransitionConfigurer(td, configurer);
        return this;
    }

    @Override
    public StateDefImpl<T> transitionsTo(Identifiable targetStateIdentifiable, String transitionId,
                                         Consumer<TransitionDef<T, Object>> configurer) {
        requireNotNull(targetStateIdentifiable, "Target state identifiable");
        return transitionsTo(targetStateIdentifiable.getId(), transitionId, configurer);
    }

    @Override
    public <C> StateDefImpl<T> transitionsTo(Identifiable targetStateIdentifiable, String transitionId,
                                             Class<C> contextType,
                                             Consumer<TransitionDef<T, C>> configurer) {
        requireNotNull(targetStateIdentifiable, "Target state identifiable");
        return transitionsTo(targetStateIdentifiable.getId(), transitionId, contextType, configurer);
    }

    private static <T, C> void runTransitionConfigurer(TransitionDefImpl<T, C> td,
                                                       Consumer<TransitionDef<T, C>> configurer) {
        td.beginConfigurer();
        try {
            configurer.accept(td);
        } finally {
            td.endConfigurer();
        }
    }

    /**
     * Marks this def as actively under construction by its configurer lambda. Package-private
     * — the enclosing {@code StateMachineDefImpl} flips this around the configurer invocation.
     */
    void beginConfigurer() {
        this.configurerActive = true;
    }

    /**
     * Clears the configurer-active flag once the lambda returns.
     */
    void endConfigurer() {
        this.configurerActive = false;
    }

    private void requireConfigurerActive(String operation) {
        if (!configurerActive) {
            throw new TransfluxValidationException(
                "Cannot call '" + operation + "' on state '" + id + "' after its configurer has returned. "
                    + "The StateDef reference is inert; declare children inside the configurer lambda.");
        }
    }

    @Override
    public String getId() {
        return id;
    }

    String getName() {
        return name;
    }

    String getDescription() {
        return description;
    }


    @Override
    public String toString() {
        return "StateDef{" +
            "id='" + id + '\'' +
            ", name='" + name + '\'' +
            ", description='" + description + '\'' +
            ", stateMachineDef=" + stateMachineDef +
            '}';
    }
}
