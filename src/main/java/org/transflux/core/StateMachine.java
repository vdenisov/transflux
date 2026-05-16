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

import org.transflux.core.exception.TransfluxValidationException;
import org.transflux.core.state.State;
import org.transflux.core.transition.Transition;
import org.transflux.core.transition.TransitionResult;

/**
 * The central orchestrator that manages entity state transitions and coordinates all framework operations.
 * <p>
 * StateMachine is the core component of the Transflux framework that provides a standardized
 * approach to finite-state machine entities and associated transition workflows. It handles
 * the logic and execution of transitions themselves, including dependencies, sequencing,
 * error handling, and compensations during state changes.
 *
 * <p><b>Key Responsibilities:</b>
 * <ul>
 * <li>Maintain the state transition matrix definition</li>
 * <li>Validate transition requests against defined rules</li>
 * <li>Execute transition operations and manage their lifecycle</li>
 * <li>Handle trigger evaluation and activation</li>
 * <li>Coordinate pre/post-conditions and listeners</li>
 * </ul>
 *
 * <p><b>Example usage:</b>
 * <pre>{@code
 * // Create a state machine for subscription entities
 * StateMachine<Subscription, SubscriptionContext> subscriptionSM = Transflux
 *     .defineStateMachine()
 *     .forEntityType(Subscription.class)
 *     .withStateResolver(subscription -> subscription.getStatus())
 *     .state("trial")
 *         .withName("Trial Period")
 *         .transitionsTo("active", "upgrade-transition")
 *         .transitionsTo("expired", "expire-transition")
 *     .state("active")
 *         .withName("Active Subscription")
 *         .transitionsTo("cancelled", "cancel-transition")
 *     .state("expired")
 *         .withName("Expired Subscription")
 *     .state("cancelled")
 *         .withName("Cancelled Subscription")
 *     .build();
 * }</pre>
 *
 * @param <T> the type of entity managed by this state machine
 * @param <C> the host-supplied context type carried through transition execution
 */
public interface StateMachine<T, C> {

    /**
     * Begins a fluent execution scope for the given entity. Preferred usage is
     * {@code stateMachine.entity(e).withContext(c).transitionTo("target")}.
     *
     * @param entity the entity to operate on
     *
     * @return an entity binding for chaining
     *
     * @throws TransfluxValidationException if {@code entity} is {@code null}
     */
    EntityBinding<T, C> entity(T entity);

    /**
     * Executes a transition for the given entity from its current state to the specified target state.
     * <p>
     * This method resolves the entity's current state using the configured state resolver,
     * determines the appropriate transition based on the target state, and executes it.
     * If multiple transitions exist between the current and target states, this method
     * will throw an exception - use {@link #executeTransition(Object, String, String)} to
     * specify which transition to use.
     *
     * @param entity the entity to transition
     * @param targetStateId the ID of the target state
     *
     * @return the result of the transition execution
     *
     * @throws TransfluxValidationException if no transition exists, multiple transitions exist,
     *         or the current state cannot be resolved
     */
    TransitionResult<T, C> executeTransition(T entity, String targetStateId);

    /**
     * Executes a specific transition for the given entity.
     * <p>
     * This method executes the transition identified by both the target state and transition ID,
     * allowing for explicit selection when multiple transitions exist between two states.
     *
     * @param entity the entity to transition
     * @param targetStateId the ID of the target state
     * @param transitionId the ID of the specific transition to execute
     *
     * @return the result of the transition execution
     *
     * @throws TransfluxValidationException if the transition does not exist or the entity
     *         is not in the correct source state
     */
    TransitionResult<T, C> executeTransition(T entity, String targetStateId, String transitionId);

    /**
     * Resolves and returns the current state ID of the given entity.
     * <p>
     * This method uses the configured state resolver to determine the entity's
     * current state identifier.
     *
     * @param entity the entity whose state to resolve
     *
     * @return the current state ID of the entity
     *
     * @throws TransfluxValidationException if the state cannot be resolved or is invalid
     */
    String resolveCurrentState(T entity);

    /**
     * Retrieves a state by its identifier.
     *
     * @param stateId the state identifier
     *
     * @return the state with the given ID
     *
     * @throws TransfluxValidationException if no state exists with the given ID
     */
    State<T> getState(String stateId);

    /**
     * Retrieves a transition by its identifier.
     *
     * @param transitionId the transition identifier
     *
     * @return the transition with the given ID
     *
     * @throws TransfluxValidationException if no transition exists with the given ID
     */
    Transition<T, C> getTransition(String transitionId);

    /**
     * Fluent execution scope returned by {@link StateMachine#entity(Object)}.
     *
     * @param <T> the entity type
     * @param <C> the context type
     */
    interface EntityBinding<T, C> {
        /**
         * Attaches a host-supplied context object to this execution scope. May be called
         * with {@code null} to clear a previously set context.
         *
         * @param context the context object; may be {@code null}
         *
         * @return this binding for chaining
         */
        EntityBinding<T, C> withContext(C context);

        /**
         * Executes the unique transition from the entity's current state to {@code targetStateId},
         * passing the bound context through to the underlying operation.
         *
         * @param targetStateId the ID of the target state
         *
         * @return the result of the transition execution
         */
        TransitionResult<T, C> transitionTo(String targetStateId);

        /**
         * Executes the named transition from the entity's current state to {@code targetStateId},
         * passing the bound context through to the underlying operation.
         *
         * @param targetStateId the ID of the target state
         * @param transitionId the ID of the specific transition to execute
         *
         * @return the result of the transition execution
         */
        TransitionResult<T, C> transitionTo(String targetStateId, String transitionId);

        /**
         * Executes the unique transition from the entity's current state to {@code targetStateId},
         * passing {@code context} through to the underlying operation. The framework verifies
         * at the dispatch boundary that {@code context == null || transitionContextType.isInstance(context)}
         * and throws {@link TransfluxValidationException} on mismatch.
         *
         * @param targetStateId the ID of the target state
         * @param context the fire-time context; may be {@code null}
         *
         * @return the result of the transition execution
         */
        TransitionResult<T, ?> transitionTo(String targetStateId, Object context);

        /**
         * Executes the named transition from the entity's current state to {@code targetStateId},
         * passing {@code context} through to the underlying operation. The framework verifies
         * at the dispatch boundary that {@code context == null || transitionContextType.isInstance(context)}
         * and throws {@link TransfluxValidationException} on mismatch.
         *
         * @param targetStateId the ID of the target state
         * @param transitionId the ID of the specific transition to execute
         * @param context the fire-time context; may be {@code null}
         *
         * @return the result of the transition execution
         */
        TransitionResult<T, ?> transitionTo(String targetStateId, String transitionId, Object context);
    }
}
