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
 * StateMachine<Order> orderSM = Transflux.defineStateMachine()
 *     .forEntityType(Order.class)
 *     .withStateResolver(order -> order.getStatus())
 *     .state("pending")
 *         .withName("Pending Order")
 *         .withDescription("Order has been placed but not yet processed")
 *         .transitionsTo("processing", "start-processing", OrderContext.class)
 *         .transitionsTo("cancelled", "cancel-order", CancelReason.class)
 *     .state("processing")
 *         .withName("Processing Order")
 *         .transitionsTo("shipped", "ship-order")
 *     .build();
 * }</pre>
 *
 * @param <T> the type of entity managed by the state machine
 */
public interface StateDef<T> {

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
    StateDef<T> withName(String name);

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
    StateDef<T> withDescription(String description);

    /**
     * Defines a transition from this state to the specified target state. The new transition
     * has no declared context type and accepts any {@link Object} as firing context.
     * <p>
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
    StateDef<T> transitionsTo(String targetStateId, String transitionId);

    /**
     * Defines a transition from this state to the specified target state, pre-binding the
     * transition's context type. Equivalent to declaring the transition and then calling
     * {@link org.transflux.core.transition.TransitionDef#usingContext(Class)} on it.
     *
     * <p>{@code Void.class} declares that the transition takes no context — fire calls with
     * a non-null context are rejected at the dispatch boundary.
     *
     * @param targetStateId the ID of the target state for this transition
     * @param transitionId the unique identifier for this transition
     * @param contextType the transition's context type; use {@code Void.class} for a
     *                    context-free transition
     *
     * @return this {@code StateDef} instance for method chaining
     *
     * @throws TransfluxValidationException if any parameter is null or blank
     */
    StateDef<T> transitionsTo(String targetStateId, String transitionId, Class<?> contextType);

    /**
     * Defines a transition from this state to the specified target state using an identifiable object.
     *
     * @param targetStateIdentifiable an identifiable object providing the target state ID
     * @param transitionId the unique identifier for this transition
     *
     * @return this {@code StateDef} instance for method chaining
     *
     * @throws TransfluxValidationException if the target state identifiable is null, its ID is null/blank,
     *                                      or the transition ID is null/blank
     */
    StateDef<T> transitionsTo(Identifiable targetStateIdentifiable, String transitionId);

    /**
     * Switches the builder context to define another state in the same state machine.
     *
     * @param stateId the ID of the state to define next
     *
     * @return a {@code StateDef} instance for the specified state
     */
    StateDef<T> state(String stateId);

    /**
     * Switches the builder context to define another state using an identifiable object.
     *
     * @param stateIdentifiable an identifiable object providing the state ID
     *
     * @return a {@code StateDef} instance for the specified state
     */
    StateDef<T> state(Identifiable stateIdentifiable);

    /**
     * Completes the state machine definition and creates the final {@code StateMachine} instance.
     *
     * @return a configured {@code StateMachine} instance ready for use
     *
     * @throws TransfluxValidationException if the state machine definition is incomplete or invalid
     */
    StateMachine<T> build();
}
