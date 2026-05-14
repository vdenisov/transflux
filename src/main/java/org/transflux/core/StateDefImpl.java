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

import static org.transflux.core.ValidationUtils.requireNotBlank;
import static org.transflux.core.ValidationUtils.requireNotNull;
import static org.transflux.core.ValidationUtils.warnIfSet;

/**
 * Builder implementation class for defining states within a state machine definition.
 * <p>
 * {@link StateDef} provides a fluent API for configuring state properties such as name,
 * description, and outgoing transitions. It serves as part of the declarative
 * DSL for building state machines in a readable and maintainable way.
 *
 * <p>This class supports method chaining to allow for concise state machine
 * definitions, enabling you to define states, their metadata, and their
 * transitions in a single fluent expression.
 *
 * <p><b>Example usage:</b>
 * <pre>{@code
 * StateMachine<Order, OrderContext> orderSM = Transflux.defineStateMachine()
 *     .forEntityType(Order.class)
 *     .withStateResolver(order -> order.getStatus())
 *     .state("pending")
 *         .withName("Pending Order")
 *         .withDescription("Order has been placed but not yet processed")
 *         .transitionsTo("processing", "start-processing")
 *         .transitionsTo("cancelled", "cancel-order")
 *     .state("processing")
 *         .withName("Processing Order")
 *         .transitionsTo("shipped", "ship-order")
 *     .build();
 * }</pre>
 *
 * @param <T> the type of entity managed by the state machine
 * @param <C> the host-supplied context type carried through transition execution
 */
class StateDefImpl<T, C> implements StateDef<T, C> {
    private static final Logger log = LoggerFactory.getLogger(StateDefImpl.class);

    private final String id;
    private String name;
    private String description;

    private final StateMachineDefImpl<T, C> stateMachineDef;

    /**
     * Constructs a new {@code StateDefImpl} with the specified state machine definition and state ID.
     * <p>
     * This package-private constructor is used internally by the framework when
     * building state machine definitions through the fluent API.
     *
     * @param smd the parent state machine definition
     * @param id the unique identifier for this state
     *
     * @throws TransfluxValidationException if the state machine definition is null or the ID is null/blank
     */
    StateDefImpl(StateMachineDefImpl<T, C> smd, String id) {
        requireNotNull(smd, "State machine definition");
        requireNotBlank(id, "State ID");

        this.stateMachineDef = smd;
        this.id = id;
    }

    /**
     * Constructs a new {@code StateDefImpl} with the specified state machine definition and identifiable object.
     * <p>
     * This package-private constructor is used internally by the framework when
     * building state machine definitions using identifiable objects for state IDs.
     *
     * @param smd the parent state machine definition
     * @param identifiable an object that provides the unique identifier for this state
     *
     * @throws TransfluxValidationException if the state machine definition is null, identifiable is null, or its ID is null/blank
     */
    StateDefImpl(StateMachineDefImpl<T, C> smd, Identifiable identifiable) {
        requireNotNull(smd, "State machine definition");
        requireNotNull(identifiable, "Identifiable for state ID");
        requireNotBlank(identifiable.getId(), "State ID");

        this.stateMachineDef = smd;
        this.id = identifiable.getId();
    }

    /**
     * Sets the human-readable name for this state.
     * <p>
     * This method allows you to provide a descriptive name for the state that can be
     * used in documentation, user interfaces, and logging. If a name has already been
     * set, this method will override it and log a warning.
     *
     * @param name the human-readable name for this state
     *
     * @return this StateDefImpl instance for method chaining
     */
    @Override
    public StateDefImpl<T, C> withName(String name) {
        warnIfSet(this.name, name, "Name", log);
        this.name = name;
        return this;
    }

    /**
     * Sets the description for this state.
     * <p>
     * This method allows you to provide additional details about the state's purpose,
     * behavior, or business meaning within the entity's lifecycle. If a description
     * has already been set, this method will override it and log a warning.
     *
     * @param description the description for this state
     *
     * @return this StateDefImpl instance for method chaining
     */
    @Override
    public StateDefImpl<T, C> withDescription(String description) {
        warnIfSet(this.description, description, "Description", log);
        this.description = description;
        return this;
    }

    /**
     * Defines a transition from this state to the specified target state.
     * <p>
     * This method registers a transition that can be used to move entities from
     * the current state to the target state using the specified transition identifier.
     * Note that the target state may not be defined at the time the transition is created,
     * but it must be defined eventually, or the state machine building will fail.
     *
     * @param targetStateId the ID of the target state for this transition
     * @param transitionId the unique identifier for this transition
     *
     * @return this {@code StateDefImpl} instance for method chaining
     *
     * @throws TransfluxValidationException if either parameter is null or blank
     */
    @Override
    public StateDefImpl<T, C> transitionsTo(String targetStateId, String transitionId) {
        stateMachineDef.registerTransition(id, targetStateId, transitionId);
        return this;
    }

    /**
     * Defines a transition from this state to the specified target state using an identifiable object.
     * <p>
     * This method registers a transition that can be used to move entities from
     * the current state to the target state identified by the provided identifiable object.
     * Note that the target state may not be defined at the time the transition is created,
     * but it must be defined eventually, or the state machine building will fail.
     *
     * @param targetStateIdentifiable an identifiable object providing the target state ID
     * @param transitionId the unique identifier for this transition
     *
     * @return this {@code StateDefImpl} instance for method chaining
     *
     * @throws TransfluxValidationException if the target state identifiable is null or the transition ID is null/blank
     */
    @Override
    public StateDefImpl<T, C> transitionsTo(Identifiable targetStateIdentifiable, String transitionId) {
        requireNotNull(targetStateIdentifiable, "Target state identifiable");

        return transitionsTo(targetStateIdentifiable.getId(), transitionId);
    }

    /**
     * Switches the builder context to define another state in the same state machine.
     * <p>
     * This method allows you to continue building the state machine by defining
     * additional states without breaking the fluent API chain. Note that each state
     * (as identified by its id) can only be defined once.
     *
     * @param stateId the ID of the state to define next
     *
     * @return a {@code StateDef} instance for the specified state
     */
    @Override
    public StateDef<T, C> state(String stateId) {
        return stateMachineDef.state(stateId);
    }

    /**
     * Switches the builder context to define another state using an identifiable object.
     * <p>
     * This method allows you to continue building the state machine by defining
     * additional states without breaking the fluent API chain. Note that each state
     * (as identified by its id) can only be defined once.
     *
     * @param stateIdentifiable an identifiable object providing the state ID
     *
     * @return a {@code StateDef} instance for the specified state
     */
    @Override
    public StateDef<T, C> state(Identifiable stateIdentifiable) {
        return stateMachineDef.state(stateIdentifiable);
    }

    /**
     * Completes the state machine definition and creates the final {@code StateMachine} instance.
     * <p>
     * This method finalizes the state machine configuration and returns a concrete
     * implementation that can be used to manage entity state transitions. Note that
     * the state machine is immutable, and absolutely all configuration must be
     * completed before calling this method.
     *
     * @return the constructed {@code StateMachine} instance
     *
     * @throws IllegalStateException if the state machine definition is invalid or incomplete
     */
    @Override
    public StateMachine<T, C> build() {
        return stateMachineDef.build();
    }

    /**
     * Returns the unique identifier of this state.
     *
     * @return the state ID
     */
    public String getId() {
        return id;
    }

    /**
     * Returns the human-readable name of this state.
     *
     * @return the state name, may be {@code null} if not set
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the description of this state.
     *
     * @return the state description, may be {@code null} if not set
     */
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
