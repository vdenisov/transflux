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

package org.transflux.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Implementation of the {@link StateMachine} interface.
 * <p>
 * This implementation serves as a concrete state machine that manages entity state
 * transitions and coordinates framework operations. It is constructed from a
 * {@link StateMachineDef} definition and maintains collections of states and transitions
 * along with metadata such as name, description, and version.
 *
 * <p><b>Note:</b> This is currently a placeholder implementation that will be enhanced
 * with full state machine functionality in future versions.
 * 
 * @param <T> the type of entity managed by this state machine
 */
public class StateMachineImpl<T> implements StateMachine<T> {
    private final Class<T> entityType;
    private final String name;
    private final String description;
    private final String version;

    private final StateResolver<T> stateResolver;

    private final Map<String, State> states = new LinkedHashMap<>();
    private final Map<String, Transition> transitions = new LinkedHashMap<>();

    /**
     * Constructs a new StateMachineImpl from the provided state machine definition.
     * <p>
     * This constructor initializes the state machine with all necessary components
     * including entity type, metadata, state resolver, and collections of states
     * and transitions created from their respective definitions.
     * 
     * @param def the state machine definition to construct this state machine from
     *
     * @throws TransfluxValidationException if the definition is null or invalid
     */
    StateMachineImpl(StateMachineDefImpl<T> def) {
        this.entityType = def.getEntityType();
        this.name = def.getName();
        this.description = def.getDescription();
        this.version = def.getVersion();
        this.stateResolver = def.getStateResolver();

        this.states.putAll(def.getStates().values().stream()
                              .collect(Collectors.toMap(StateDef::getId, StateImpl::new)));

        this.transitions.putAll(def.getTransitionsById().values().stream()
                                   .collect(Collectors.toMap(TransitionDef::getId, TransitionImpl::new)));
    }

    /**
     * Returns the entity type managed by this state machine.
     * 
     * @return the entity class type
     */
    public Class<T> getEntityType() {
        return entityType;
    }

    /**
     * Returns the human-readable name of this state machine.
     * 
     * @return the name of the state machine, may be {@code null}
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the description of this state machine.
     * 
     * @return the description of the state machine, may be {@code null}
     */
    public String getDescription() {
        return description;
    }

    /**
     * Returns the version of this state machine definition.
     * 
     * @return the version string, may be {@code null}
     */
    public String getVersion() {
        return version;
    }

    /**
     * Returns the state resolver used to determine entity current states.
     * 
     * @return the state resolver for this state machine
     */
    public StateResolver<T> getStateResolver() {
        return stateResolver;
    }

    /**
     * Returns an immutable map of all states defined in this state machine.
     * 
     * @return a map of state IDs to state instances
     */
    public Map<String, State> getStates() {
        return states;
    }

    /**
     * Returns an immutable map of all transitions defined in this state machine.
     * 
     * @return a map of transition IDs to transition instances
     */
    public Map<String, Transition> getTransitions() {
        return transitions;
    }
}
