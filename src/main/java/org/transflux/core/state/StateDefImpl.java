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

package org.transflux.core.state;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.transflux.core.Identifiable;
import org.transflux.core.StateMachine;
import org.transflux.core.StateMachineDefImpl;
import org.transflux.core.exception.TransfluxValidationException;

import static org.transflux.core.ValidationUtils.requireNotBlank;
import static org.transflux.core.ValidationUtils.requireNotNull;
import static org.transflux.core.ValidationUtils.warnIfSet;

/**
 * Builder implementation class for defining states within a state machine definition.
 *
 * @param <T> the type of entity managed by the state machine
 */
public class StateDefImpl<T> implements StateDef<T> {
    private static final Logger log = LoggerFactory.getLogger(StateDefImpl.class);

    private final String id;
    private String name;
    private String description;

    private final StateMachineDefImpl<T> stateMachineDef;

    public StateDefImpl(StateMachineDefImpl<T> smd, String id) {
        requireNotNull(smd, "State machine definition");
        requireNotBlank(id, "State ID");

        this.stateMachineDef = smd;
        this.id = id;
    }

    public StateDefImpl(StateMachineDefImpl<T> smd, Identifiable identifiable) {
        requireNotNull(smd, "State machine definition");
        requireNotNull(identifiable, "Identifiable for state ID");
        requireNotBlank(identifiable.getId(), "State ID");

        this.stateMachineDef = smd;
        this.id = identifiable.getId();
    }

    @Override
    public StateDefImpl<T> withName(String name) {
        warnIfSet(this.name, name, "Name", log);
        this.name = name;
        return this;
    }

    @Override
    public StateDefImpl<T> withDescription(String description) {
        warnIfSet(this.description, description, "Description", log);
        this.description = description;
        return this;
    }

    @Override
    public StateDefImpl<T> transitionsTo(String targetStateId, String transitionId) {
        stateMachineDef.registerTransition(id, targetStateId, transitionId, null);
        return this;
    }

    @Override
    public StateDefImpl<T> transitionsTo(String targetStateId, String transitionId, Class<?> contextType) {
        requireNotNull(contextType, "Context type");
        stateMachineDef.registerTransition(id, targetStateId, transitionId, contextType);
        return this;
    }

    @Override
    public StateDefImpl<T> transitionsTo(Identifiable targetStateIdentifiable, String transitionId) {
        requireNotNull(targetStateIdentifiable, "Target state identifiable");

        return transitionsTo(targetStateIdentifiable.getId(), transitionId);
    }

    @Override
    public StateDef<T> state(String stateId) {
        return stateMachineDef.state(stateId);
    }

    @Override
    public StateDef<T> state(Identifiable stateIdentifiable) {
        return stateMachineDef.state(stateIdentifiable);
    }

    @Override
    public StateMachine<T> build() {
        return stateMachineDef.build();
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
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
