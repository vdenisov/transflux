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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builder class for defining and constructing state machines.
 * <p>
 * {@code StateMachineDef} provides the main fluent API entry point for creating state machine
 * definitions in Transflux. It manages the configuration of entity types, metadata
 * (name, description, version), state resolvers, states, and transitions, providing
 * a declarative DSL for building complex state machines in a readable and maintainable way.
 * 
 * <p>The StateMachineDef supports method chaining throughout the definition process,
 * allowing for concise and expressive state machine configurations that can be easily
 * understood and maintained.
 * 
 * <p><b>Example usage:</b>
 * <pre>{@code
 * StateMachine<Order> orderStateMachine = new StateMachineDef<Order>()
 *     .forEntityType(Order.class)
 *     .withName("Order Processing State Machine")
 *     .withDescription("Manages the lifecycle of customer orders")
 *     .withVersion("1.0")
 *     .withStateResolver(order -> order.getStatus())
 *     .state("pending")
 *         .withName("Pending Order")
 *         .withDescription("Order received but not yet processed")
 *         .transitionsTo("processing", "start-processing")
 *         .transitionsTo("cancelled", "cancel-order")
 *     .state("processing")
 *         .withName("Processing Order")
 *         .transitionsTo("shipped", "ship-order")
 *         .transitionsTo("cancelled", "cancel-order")
 *     .state("shipped")
 *         .withName("Shipped Order")
 *         .transitionsTo("delivered", "mark-delivered")
 *     .state("delivered")
 *         .withName("Delivered Order")
 *     .state("cancelled")
 *         .withName("Cancelled Order")
 *     .build();
 * }</pre>
 * 
 * @param <T> the type of entity managed by the state machine being defined
 */
class StateMachineDefImpl<T> implements StateMachineDef<T> {
    private static final Logger log = LoggerFactory.getLogger(StateMachineDefImpl.class);

    private Class<T> entityType;
    private String name;
    private String description;
    private String version;

    private StateResolver<T> stateResolver;
    private StateApplier<T> stateApplier;

    private final Map<String, StateDefImpl<T>> states = new LinkedHashMap<>();

    // transitionId -> TransitionDefImpl
    private final Map<String, TransitionDefImpl<T>> transitionsById = new LinkedHashMap<>();
    // Source-target index: sourceStateId -> targetStateId -> list of TransitionDefImpl
    private final Map<String, Map<String, List<TransitionDefImpl<T>>>> transitionsBySourceTarget = new LinkedHashMap<>();

    StateMachineDefImpl() {
    }

    @Override
    public StateMachineDef<T> forEntityType(Class<T> entityType) {
        if (entityType == null) {
            throw new TransfluxValidationException("Entity type cannot be null");
        }
        this.entityType = entityType;
        return this;
    }

    /**
     * Sets the human-readable name for this state machine.
     * <p>
     * This method allows you to provide a descriptive name for the state machine
     * that can be used in documentation, user interfaces, and logging.
     * 
     * @param name the human-readable name for this state machine
     * @return this StateMachineDef instance for method chaining
     */
    public StateMachineDef<T> withName(String name) {
        if (this.name != null) {
            log.warn("Name is already defined: {}. Overriding previous value with {}",
                               this.name, name);
        }

        this.name = name;
        return this;
    }

    /**
     * Sets the description for this state machine.
     * <p>
     * This method allows you to provide additional details about the state machine's
     * purpose, behavior, or business domain within your application.
     * 
     * @param description the description for this state machine
     *
     * @return this StateMachineDef instance for method chaining
     */
    public StateMachineDef<T> withDescription(String description) {
        if (this.description != null) {
            log.warn("Description is already defined: {}. Overriding previous value with {}",
                               this.description, description);
        }

        this.description = description;
        return this;
    }

    /**
     * Sets the version for this state machine definition.
     * <p>
     * This method allows you to specify a version identifier for the state machine
     * definition, which can be useful for versioning, deployment tracking, and
     * compatibility management.
     * 
     * @param version the version identifier for this state machine definition
     *
     * @return this StateMachineDef instance for method chaining
     */
    public StateMachineDef<T> withVersion(String version) {
        if (this.version != null) {
            log.warn("Version is already defined: {}. Overriding previous value with {}",
                               this.version, version);
        }

        this.version = version;
        return this;
    }

    /**
     * Sets the state resolver used to determine the current state of entities.
     * <p>
     * The state resolver is a critical component that bridges your domain entities
     * with the Transflux framework, allowing the state machine to understand the
     * current state of entities without imposing specific storage requirements.
     * 
     * @param stateResolver the state resolver implementation
     *
     * @return this StateMachineDef instance for method chaining
     *
     * @throws TransfluxValidationException if the state resolver is null
     */
    public StateMachineDef<T> withStateResolver(StateResolver<T> stateResolver) {
        if (stateResolver == null) {
            throw new TransfluxValidationException("State resolver cannot be null");
        }

        if (this.stateResolver != null) {
            log.warn("State resolver is already defined: {}. Overriding previous value with {}",
                               this.stateResolver.getClass().getName(), stateResolver.getClass().getName());
        }

        this.stateResolver = stateResolver;
        return this;
    }

    @Override
    public StateMachineDef<T> withStateApplier(StateApplier<T> stateApplier) {
        if (stateApplier == null) {
            throw new TransfluxValidationException("State applier cannot be null");
        }

        if (this.stateApplier != null) {
            log.warn("State applier is already defined: {}. Overriding previous value with {}",
                               this.stateApplier.getClass().getName(), stateApplier.getClass().getName());
        }

        this.stateApplier = stateApplier;
        return this;
    }

    /**
     * Begins defining a new state in the state machine.
     * <p>
     * This method creates a new state definition with the specified ID and returns
     * a StateDef instance for further configuration through the fluent API.
     * 
     * @param stateId the unique identifier for the new state
     *
     * @return a StateDef instance for configuring the new state
     *
     * @throws TransfluxValidationException if the state ID is already defined
     */
    public StateDef<T> state(String stateId) {
        if (states.containsKey(stateId)) {
            throw new TransfluxValidationException("State ID " + stateId + " already defined");
        }

        var stateDef = new StateDefImpl<>(this, stateId);
        states.put(stateDef.getId(), stateDef);
        return stateDef;
    }

    /**
     * Begins defining a new state using an identifiable object for the state ID.
     * 
     * @param stateIdentifiable an identifiable object providing the state ID
     *
     * @return a StateDef instance for configuring the new state
     *
     * @throws TransfluxValidationException if the identifiable is null or state ID is already defined
     */
    public StateDef<T> state(Identifiable stateIdentifiable) {
        if (stateIdentifiable == null) {
            throw new TransfluxValidationException("State identifiable cannot be null");
        }

        return state(stateIdentifiable.getId());
    }

    /**
     * Registers a transition between two states.
     * <p>
     * This package-private method is used internally by StateDef to register
     * transitions when they are defined through the fluent API.
     * 
     * @param sourceStateId the ID of the source state
     * @param targetStateId the ID of the target state  
     * @param transitionId the unique identifier for the transition
     *
     * @throws TransfluxValidationException if any parameter is null/blank or transition ID already exists
     */
    void registerTransition(String sourceStateId, String targetStateId, String transitionId) {
        if (sourceStateId == null || sourceStateId.isBlank()) {
            throw new TransfluxValidationException("Source state ID cannot be null or blank");
        }
        if (targetStateId == null || targetStateId.isBlank()) {
            throw new TransfluxValidationException("Target state ID cannot be null or blank");
        }
        if (transitionId == null || transitionId.isBlank()) {
            throw new TransfluxValidationException("Transition ID cannot be null or blank");
        }

        if (transitionsById.containsKey(transitionId)) {
            throw new TransfluxValidationException("Transition ID " + transitionId + " already defined");
        }

        var byTarget = transitionsBySourceTarget.computeIfAbsent(sourceStateId, k -> new LinkedHashMap<>());
        var list = byTarget.computeIfAbsent(targetStateId, k -> new ArrayList<>(1)); // More than one transition between states is uncommon

        var def = new TransitionDefImpl<T>(transitionId, sourceStateId, targetStateId);
        list.add(def);
        transitionsById.put(transitionId, def);
    }

    /**
     * Completes the state machine definition and creates the final StateMachine instance.
     * <p>
     * This method finalizes the state machine configuration and returns a concrete
     * StateMachine implementation that can be used to manage entity state transitions.
     * 
     * @return the constructed StateMachine instance
     *
     * @throws IllegalStateException if the state machine definition is invalid or incomplete
     */
    public StateMachine<T> build() {
        return new StateMachineImpl<>(this);
    }

    public Class<T> getEntityType() {
        return entityType;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getVersion() {
        return version;
    }

    public StateResolver<T> getStateResolver() {
        return stateResolver;
    }

    public StateApplier<T> getStateApplier() {
        return stateApplier;
    }

    Map<String, StateDefImpl<T>> getStates() {
        return states;
    }

    Map<String, TransitionDefImpl<T>> getTransitionsById() {
        return transitionsById;
    }

    @Override
    public TransitionDef<T> getTransition(String sourceStateId, String targetStateId) {
        var byTarget = transitionsBySourceTarget.get(sourceStateId);
        if (byTarget == null) {
            throw new TransfluxValidationException("Source state '" + sourceStateId + "' not found");
        }

        var list = byTarget.get(targetStateId);
        if (list == null || list.isEmpty()) {
            throw new TransfluxValidationException("No transitions found for source state '" + sourceStateId
                                                   + "' and target state '" + targetStateId + "'");
        }

        if (list.size() > 1) {
            throw new TransfluxValidationException("Multiple transitions found for source state '" + sourceStateId
                                                   + "' and target state '" + targetStateId + "', use transition ID instead");
        }

        return list.get(0);
    }

    @Override
    public TransitionDef<T> getTransition(String transitionId) {
        var td = transitionsById.get(transitionId);
        if (td == null) {
            throw new TransfluxValidationException("Transition '" + transitionId + "' not found");
        }
        return td;
    }
}
