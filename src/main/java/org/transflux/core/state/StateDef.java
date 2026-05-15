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

import org.transflux.core.Identifiable;
import org.transflux.core.StateMachine;
import org.transflux.core.exception.TransfluxValidationException;

/**
 * Builder interface for defining states within a state machine definition.
 * <p>
 * {@code StateDef} provides a fluent API for configuring state properties such as name,
 * description, and outgoing transitions. It serves as part of the declarative
 * DSL for building state machines in a readable and maintainable way.
 *
 * <p>This interface supports method chaining to allow for concise state machine
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
public interface StateDef<T, C> {

    /**
     * Sets the human-readable name for this state.
     * <p>
     * This method allows you to provide a descriptive name for the state that can be
     * used in documentation, user interfaces, and logging. If a name has already been
     * set, this method will override it and log a warning.
     *
     * @param name the human-readable name for this state
     *
     * @return this StateDef instance for method chaining
     */
    StateDef<T, C> withName(String name);

    /**
     * Sets the description for this state.
     * <p>
     * This method allows you to provide additional details about the state's purpose,
     * behavior, or business meaning within the entity's lifecycle. If a description
     * has already been set, this method will override it and log a warning.
     *
     * @param description the description for this state
     *
     * @return this StateDef instance for method chaining
     */
    StateDef<T, C> withDescription(String description);

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
     * @return this {@code StateDef} instance for method chaining
     *
     * @throws TransfluxValidationException if either parameter is null or blank
     */
    StateDef<T, C> transitionsTo(String targetStateId, String transitionId);

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
     * @return this {@code StateDef} instance for method chaining
     *
     * @throws TransfluxValidationException if the target state identifiable is null, its ID is null/blank,
     *                                      or the transition ID is null/blank
     */
    StateDef<T, C> transitionsTo(Identifiable targetStateIdentifiable, String transitionId);

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
    StateDef<T, C> state(String stateId);

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
    StateDef<T, C> state(Identifiable stateIdentifiable);

    /**
     * Completes the state machine definition and creates the final {@code StateMachine} instance.
     * <p>
     * This method finalizes the state machine configuration and returns a concrete
     * implementation that can be used to manage entity state transitions. Note that
     * all states referenced in transitions must have been defined before calling this method.
     *
     * @return a configured {@code StateMachine} instance ready for use
     *
     * @throws TransfluxValidationException if the state machine definition is incomplete or invalid
     */
    StateMachine<T, C> build();
}
