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

/**
 * Builder interface for defining and constructing state machines.
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
 * StateMachine<Order> orderStateMachine = Transflux.stateMachineFor(Order.class)
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
public interface StateMachineDef<T> {

    /**
     * Sets the human-readable name for this state machine.
     * <p>
     * This method allows you to provide a descriptive name for the state machine
     * that can be used in documentation, user interfaces, and logging.
     * 
     * @param name the human-readable name for this state machine
     * @return this StateMachineDef instance for method chaining
     */
    StateMachineDef<T> withName(String name);

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
    StateMachineDef<T> withDescription(String description);

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
    StateMachineDef<T> withVersion(String version);

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
    StateMachineDef<T> withStateResolver(StateResolver<T> stateResolver);

    /**
     * Begins defining a new state in the state machine.
     * <p>
     * This method creates a new state definition with the specified identifier
     * and returns a {@code StateDef} instance that allows you to configure
     * the state's properties and transitions using the fluent API.
     * 
     * @param stateId the unique identifier for the new state
     * @return a {@code StateDef} instance for configuring the new state
     *
     * @throws TransfluxValidationException if the state ID is null, blank, or already defined
     */
    StateDef<T> state(String stateId);

    /**
     * Begins defining a new state using an identifiable object.
     * <p>
     * This method creates a new state definition using the ID from the provided
     * identifiable object and returns a {@code StateDef} instance that allows you
     * to configure the state's properties and transitions using the fluent API.
     * 
     * @param stateIdentifiable an identifiable object providing the state ID
     * @return a {@code StateDef} instance for configuring the new state
     *
     * @throws TransfluxValidationException if the identifiable is null, its ID is null/blank, or already defined
     */
    StateDef<T> state(Identifiable stateIdentifiable);

    /**
     * Completes the state machine definition and creates the final {@code StateMachine} instance.
     * <p>
     * This method finalizes the state machine configuration, validates all
     * definitions, and returns a concrete implementation that can be used to
     * manage entity state transitions.
     * 
     * @return a configured {@code StateMachine} instance ready for use
     * @throws TransfluxValidationException if the state machine definition is incomplete or invalid
     */
    StateMachine<T> build();

    /**
     * Retrieves a transition definition between two specific states.
     * <p>
     * This method allows you to access a transition definition using the source
     * and target state identifiers, enabling further configuration of the transition's
     * properties such as operations, conditions, triggers, and listeners.
     * This is particularly useful for programmatic configuration after the initial
     * state machine definition.
     * 
     * @param sourceStateId the identifier of the source state
     * @param targetStateId the identifier of the target state
     *
     * @return the transition definition between the specified states
     *
     * @throws TransfluxValidationException if either state ID is null/blank or if no transition exists between the specified states
     */
    TransitionDef getTransition(String sourceStateId, String targetStateId);

    /**
     * Retrieves a transition definition by its unique identifier.
     * <p>
     * This method allows you to access a transition definition using its unique
     * transition identifier, enabling further configuration of the transition's
     * properties such as operations, conditions, triggers, and listeners.
     * This provides an alternative way to reference transitions when you know
     * the specific transition ID rather than the source and target states.
     * 
     * @param transitionId the unique identifier of the transition
     *
     * @return the transition definition with the specified identifier
     *
     * @throws TransfluxValidationException if the transition ID is null/blank or if no transition exists with the specified ID
     */
    TransitionDef getTransition(String transitionId);
}