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
import org.transflux.core.transition.TransitionResult;

/**
 * The central orchestrator that manages entity state transitions and coordinates all framework operations.
 * <p>
 * StateMachine is the core component of the Transflux framework that provides a standardized
 * approach to finite-state machine entities and associated transition workflows. It handles
 * the logic and execution of transitions themselves, including dependencies, sequencing,
 * error handling, and compensations during state changes.
 *
 * <p>The state machine itself is not parameterized by a context type. Each transition declares
 * its own context type (via {@code transitionsTo(target, id, Class<C>)} or
 * {@code TransitionDef.usingContext(Class<C>)}); the host supplies the firing-time context
 * to {@link EntityBinding#transitionTo(String, Object)} and the framework verifies the type
 * at the dispatch boundary.
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
 * StateMachine<Subscription> subscriptionSM = Transflux
 *     .defineStateMachine()
 *     .forEntityType(Subscription.class)
 *     .withStateResolver(subscription -> subscription.getStatus())
 *     .state("trial", s -> s
 *         .withName("Trial Period")
 *         .transitionsTo("active", "upgrade-transition", t -> {})
 *         .transitionsTo("expired", "expire-transition", t -> {}))
 *     .state("active", s -> s
 *         .withName("Active Subscription")
 *         .transitionsTo("cancelled", "cancel-transition", t -> {}))
 *     .state("expired", s -> s.withName("Expired Subscription"))
 *     .state("cancelled", s -> s.withName("Cancelled Subscription"))
 *     .build();
 * }</pre>
 *
 * @param <T> the type of entity managed by this state machine
 */
public interface StateMachine<T> {

    /**
     * Begins a fluent execution scope for the given entity. Preferred usage is
     * {@code stateMachine.entity(e).transitionTo("target", ctx)}.
     *
     * @param entity the entity to operate on
     *
     * @return an entity binding for chaining
     *
     * @throws TransfluxValidationException if {@code entity} is {@code null}
     */
    EntityBinding<T> entity(T entity);

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
    TransitionResult<T> executeTransition(T entity, String targetStateId);

    /**
     * {@link Identifiable} overload of {@link #executeTransition(Object, String)} — delegates
     * via {@link Identifiable#getId()}.
     *
     * @param entity the entity to transition
     * @param targetState an identifiable supplying the target state id
     *
     * @return the result of the transition execution
     *
     * @throws TransfluxValidationException if {@code targetState} is {@code null}
     */
    TransitionResult<T> executeTransition(T entity, Identifiable targetState);

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
    TransitionResult<T> executeTransition(T entity, String targetStateId, String transitionId);

    /**
     * {@link Identifiable} overload of {@link #executeTransition(Object, String, String)} —
     * both target state and transition supplied as identifiables.
     *
     * @param entity the entity to transition
     * @param targetState an identifiable supplying the target state id
     * @param transition an identifiable supplying the transition id
     *
     * @return the result of the transition execution
     *
     * @throws TransfluxValidationException if either identifiable is {@code null}
     */
    TransitionResult<T> executeTransition(T entity, Identifiable targetState, Identifiable transition);

    /**
     * Mixed-form overload of {@link #executeTransition(Object, String, String)} — target
     * identifiable, transition by id.
     *
     * @param entity the entity to transition
     * @param targetState an identifiable supplying the target state id
     * @param transitionId the transition id
     *
     * @return the result of the transition execution
     *
     * @throws TransfluxValidationException if {@code targetState} is {@code null} or
     *         {@code transitionId} is {@code null}/blank
     */
    TransitionResult<T> executeTransition(T entity, Identifiable targetState, String transitionId);

    /**
     * Mixed-form overload of {@link #executeTransition(Object, String, String)} — target
     * by id, transition identifiable.
     *
     * @param entity the entity to transition
     * @param targetStateId the target state id
     * @param transition an identifiable supplying the transition id
     *
     * @return the result of the transition execution
     *
     * @throws TransfluxValidationException if {@code targetStateId} is {@code null}/blank
     *         or {@code transition} is {@code null}
     */
    TransitionResult<T> executeTransition(T entity, String targetStateId, Identifiable transition);

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
     * Fluent execution scope returned by {@link StateMachine#entity(Object)}.
     *
     * @param <T> the entity type
     */
    interface EntityBinding<T> {
        /**
         * Executes the unique transition from the entity's current state to {@code targetStateId}
         * with no firing context.
         *
         * @param targetStateId the ID of the target state
         *
         * @return the result of the transition execution
         */
        TransitionResult<T> transitionTo(String targetStateId);

        /**
         * Executes the named transition from the entity's current state to {@code targetStateId}
         * with no firing context.
         *
         * @param targetStateId the ID of the target state
         * @param transitionId the ID of the specific transition to execute
         *
         * @return the result of the transition execution
         */
        TransitionResult<T> transitionTo(String targetStateId, String transitionId);

        /**
         * Executes the unique transition from the entity's current state to {@code targetStateId},
         * passing {@code context} through to the underlying operation. The framework verifies
         * at the dispatch boundary that {@code context == null || transitionContextType.isInstance(context)}
         * and throws {@link TransfluxValidationException} on mismatch. A transition declared with
         * {@code Void.class} context rejects any non-null firing value.
         *
         * @param targetStateId the ID of the target state
         * @param context the fire-time context; may be {@code null}
         *
         * @return the result of the transition execution
         */
        TransitionResult<T> transitionTo(String targetStateId, Object context);

        /**
         * Executes the named transition from the entity's current state to {@code targetStateId},
         * passing {@code context} through to the underlying operation. The framework verifies
         * at the dispatch boundary that {@code context == null || transitionContextType.isInstance(context)}
         * and throws {@link TransfluxValidationException} on mismatch. A transition declared with
         * {@code Void.class} context rejects any non-null firing value.
         *
         * @param targetStateId the ID of the target state
         * @param transitionId the ID of the specific transition to execute
         * @param context the fire-time context; may be {@code null}
         *
         * @return the result of the transition execution
         */
        TransitionResult<T> transitionTo(String targetStateId, String transitionId, Object context);

        /**
         * {@link Identifiable} overload of {@link #transitionTo(String)} — delegates via
         * {@link Identifiable#getId()}.
         *
         * @param targetState an identifiable supplying the target state id
         *
         * @return the result of the transition execution
         *
         * @throws TransfluxValidationException if {@code targetState} is {@code null}
         */
        TransitionResult<T> transitionTo(Identifiable targetState);

        /**
         * {@link Identifiable} overload of {@link #transitionTo(String, String)} — both
         * target state and transition supplied as identifiables.
         *
         * @param targetState an identifiable supplying the target state id
         * @param transition an identifiable supplying the transition id
         *
         * @return the result of the transition execution
         *
         * @throws TransfluxValidationException if either identifiable is {@code null}
         */
        TransitionResult<T> transitionTo(Identifiable targetState, Identifiable transition);

        /**
         * Mixed-form overload of {@link #transitionTo(String, String)} — target identifiable,
         * transition by id.
         *
         * @param targetState an identifiable supplying the target state id
         * @param transitionId the transition id
         *
         * @return the result of the transition execution
         *
         * @throws TransfluxValidationException if {@code targetState} is {@code null}
         *         or {@code transitionId} is {@code null}/blank
         */
        TransitionResult<T> transitionTo(Identifiable targetState, String transitionId);

        /**
         * Mixed-form overload of {@link #transitionTo(String, String)} — target by id,
         * transition identifiable.
         *
         * @param targetStateId the target state id
         * @param transition an identifiable supplying the transition id
         *
         * @return the result of the transition execution
         *
         * @throws TransfluxValidationException if {@code targetStateId} is {@code null}/blank
         *         or {@code transition} is {@code null}
         */
        TransitionResult<T> transitionTo(String targetStateId, Identifiable transition);

        /**
         * {@link Identifiable} overload of {@link #transitionTo(String, Object)} — target
         * identifiable with firing context.
         *
         * @param targetState an identifiable supplying the target state id
         * @param context the fire-time context; may be {@code null}
         *
         * @return the result of the transition execution
         *
         * @throws TransfluxValidationException if {@code targetState} is {@code null}
         */
        TransitionResult<T> transitionTo(Identifiable targetState, Object context);

        /**
         * {@link Identifiable} overload of {@link #transitionTo(String, String, Object)} —
         * both target and transition as identifiables, with firing context.
         *
         * @param targetState an identifiable supplying the target state id
         * @param transition an identifiable supplying the transition id
         * @param context the fire-time context; may be {@code null}
         *
         * @return the result of the transition execution
         *
         * @throws TransfluxValidationException if either identifiable is {@code null}
         */
        TransitionResult<T> transitionTo(Identifiable targetState, Identifiable transition, Object context);

        /**
         * Mixed-form overload of {@link #transitionTo(String, String, Object)} — target
         * identifiable, transition by id, with firing context.
         *
         * @param targetState an identifiable supplying the target state id
         * @param transitionId the transition id
         * @param context the fire-time context; may be {@code null}
         *
         * @return the result of the transition execution
         *
         * @throws TransfluxValidationException if {@code targetState} is {@code null}
         *         or {@code transitionId} is {@code null}/blank
         */
        TransitionResult<T> transitionTo(Identifiable targetState, String transitionId, Object context);

        /**
         * Mixed-form overload of {@link #transitionTo(String, String, Object)} — target by
         * id, transition identifiable, with firing context.
         *
         * @param targetStateId the target state id
         * @param transition an identifiable supplying the transition id
         * @param context the fire-time context; may be {@code null}
         *
         * @return the result of the transition execution
         *
         * @throws TransfluxValidationException if {@code targetStateId} is {@code null}/blank
         *         or {@code transition} is {@code null}
         */
        TransitionResult<T> transitionTo(String targetStateId, Identifiable transition, Object context);
    }
}
