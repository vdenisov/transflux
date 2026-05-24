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

import org.transflux.core.Identifiable;
import org.transflux.core.state.StateDef;
import org.transflux.core.transition.TransitionDef;

import java.util.function.Consumer;

import static org.transflux.core.Preconditions.requireNotBlank;
import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Builder implementation class for defining states within a state machine definition.
 *
 * @param <T> the type of entity managed by the state machine
 */
class StateDefImpl<T> extends IdentifiedDefImpl<StateDefImpl<T>> implements StateDef<T> {

    private final StateMachineDefImpl<T> stateMachineDef;

    StateDefImpl(StateMachineDefImpl<T> smd, String id) {
        super(id, "state", "State ID");
        requireNotNull(smd, "State machine definition");

        this.stateMachineDef = smd;
    }

    StateDefImpl(StateMachineDefImpl<T> smd, Identifiable identifiable) {
        super(requireNotNullThenGetId(identifiable), "state", "State ID");
        requireNotNull(smd, "State machine definition");

        this.stateMachineDef = smd;
    }

    @Override
    public StateDefImpl<T> transitionsTo(String targetStateId, String transitionId,
                                         Consumer<TransitionDef<T, Object>> configurer) {
        requireConfigurerActive("transitionsTo");
        requireNotBlank(targetStateId, "Target state ID");
        requireNotBlank(transitionId, "Transition ID");
        requireNotNull(configurer, "Transition configurer");
        TransitionDefImpl<T, Object> td = stateMachineDef.<Object>registerTransition(
            getId(), targetStateId, transitionId, Object.class);
        ConfigurableDefImpl.runConfigurer(td, configurer);
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
            getId(), targetStateId, transitionId, contextType);
        ConfigurableDefImpl.runConfigurer(td, configurer);
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

    @Override
    public StateDefImpl<T> transitionsTo(String targetStateId, Identifiable transitionIdentifiable,
                                         Consumer<TransitionDef<T, Object>> configurer) {
        requireNotNull(transitionIdentifiable, "Transition identifiable");
        return transitionsTo(targetStateId, transitionIdentifiable.getId(), configurer);
    }

    @Override
    public <C> StateDefImpl<T> transitionsTo(String targetStateId, Identifiable transitionIdentifiable,
                                             Class<C> contextType,
                                             Consumer<TransitionDef<T, C>> configurer) {
        requireNotNull(transitionIdentifiable, "Transition identifiable");
        return transitionsTo(targetStateId, transitionIdentifiable.getId(), contextType, configurer);
    }

    @Override
    public StateDefImpl<T> transitionsTo(Identifiable targetStateIdentifiable, Identifiable transitionIdentifiable,
                                         Consumer<TransitionDef<T, Object>> configurer) {
        requireNotNull(targetStateIdentifiable, "Target state identifiable");
        requireNotNull(transitionIdentifiable, "Transition identifiable");
        return transitionsTo(targetStateIdentifiable.getId(), transitionIdentifiable.getId(), configurer);
    }

    @Override
    public <C> StateDefImpl<T> transitionsTo(Identifiable targetStateIdentifiable, Identifiable transitionIdentifiable,
                                             Class<C> contextType,
                                             Consumer<TransitionDef<T, C>> configurer) {
        requireNotNull(targetStateIdentifiable, "Target state identifiable");
        requireNotNull(transitionIdentifiable, "Transition identifiable");
        return transitionsTo(targetStateIdentifiable.getId(), transitionIdentifiable.getId(), contextType, configurer);
    }

    private static String requireNotNullThenGetId(Identifiable identifiable) {
        requireNotNull(identifiable, "Identifiable for state ID");
        return identifiable.getId();
    }

    @Override
    public String toString() {
        return "StateDef{" +
            "id='" + getId() + '\'' +
            ", name='" + getName() + '\'' +
            ", description='" + getDescription() + '\'' +
            ", stateMachineDef=" + stateMachineDef +
            '}';
    }
}
