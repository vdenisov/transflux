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
import org.transflux.core.transition.ProcessResult;
import org.transflux.core.transition.TransitionResult;
import org.transflux.core.trigger.Trigger;

import java.util.Collection;

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
 * <li>Evaluate pre- and post-conditions guarding a transition</li>
 * </ul>
 *
 * <p><b>Example usage:</b>
 * <pre>{@code
 * StateMachine<Subscription> subscriptionSM = Transflux
 *     .defineStateMachine()
 *     .forEntityType(Subscription.class)
 *     .withStateResolver(subscription -> subscription.getStatus().name())
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
public interface StateMachine<T> extends AutoCloseable {

    /**
     * Releases what this state machine owns - which is a thread pool, and only when it built one
     * for itself.
     * <p>
     * A state machine whose definition never forks holds no resources, so closing it does nothing.
     * One that forks against a host-supplied executor leaves that executor running: it was never
     * this state machine's to shut down. Only a pool the framework built is shut down here, and
     * then gracefully - already-submitted branches are given a bounded chance to finish rather
     * than being interrupted mid-flight.
     *
     * <p>Closing more than once is harmless. Forking after a close is refused like any other
     * refused submission, which means the state machine keeps running transitions.
     *
     * <p><b>JVM exit is a different matter, and this method does not govern it.</b> A pool the
     * framework builds uses daemon threads, so a host that never calls this and simply lets the
     * process end can have a branch killed where it stands - mid-compensation, if that is where it
     * was. The default favours the host who forgot: the alternative makes every exit wait on an
     * idle pool. A host that wants the process to wait for its branches supplies non-daemon threads
     * through {@link StateMachineDef#withAsyncPool(int, int, java.util.concurrent.ThreadFactory)},
     * and a host that wants this method called on the way out registers it as a shutdown hook.
     */
    @Override
    void close();

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
     * Returns all triggers declared across this state machine's transitions.
     * <p>
     * Only triggers explicitly declared on transitions appear here; a transition with no declared
     * trigger is still directly invocable through {@link EntityBinding#transitionTo(String)} but
     * contributes nothing to the catalog.
     *
     * @return an unmodifiable collection of triggers; empty when none are declared
     */
    Collection<Trigger> getTriggers();

    /**
     * Returns the triggers of the given kind declared across this state machine's transitions.
     * <p>
     * The kind is matched by assignability, so passing a trigger subtype
     * ({@link org.transflux.core.trigger.ManualTrigger}, {@link org.transflux.core.trigger.EventTrigger},
     * {@link org.transflux.core.trigger.DataTrigger}) selects exactly the triggers of that kind, and
     * passing {@link Trigger} returns the whole catalog. The return type is narrowed to the
     * requested kind for type-safe iteration.
     *
     * @param kind the trigger kind to select; never {@code null}
     * @param <X> the requested trigger kind
     *
     * @return an unmodifiable collection of the matching triggers; empty when none are declared
     *
     * @throws TransfluxValidationException if {@code kind} is {@code null}
     */
    <X extends Trigger> Collection<X> getTriggers(Class<X> kind);

    /**
     * Returns the trigger registered under the given id.
     *
     * @param triggerId the trigger id; never {@code null} or blank
     *
     * @return the trigger
     *
     * @throws TransfluxValidationException if {@code triggerId} is {@code null}/blank or no
     *         trigger is registered under it
     */
    Trigger getTrigger(String triggerId);

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
         * Fires the manual trigger registered under {@code triggerId} with no firing context.
         * <p>
         * The trigger determines its own transition, so no target state is supplied. The entity
         * must be in that transition's source state. The transition's own pre-conditions are
         * evaluated first, then the trigger's, in declaration order.
         *
         * @param triggerId the trigger id; never {@code null} or blank
         *
         * @return the result of the transition execution
         *
         * @throws TransfluxValidationException if {@code triggerId} is {@code null}/blank, no
         *         trigger is registered under it, or the entity is not in the trigger's source state
         */
        TransitionResult<T> fire(String triggerId);

        /**
         * Fires the manual trigger registered under {@code triggerId}, passing {@code context}
         * through to the underlying operation. The framework verifies at the dispatch boundary that
         * {@code context == null || transitionContextType.isInstance(context)} and throws
         * {@link TransfluxValidationException} on mismatch.
         *
         * @param triggerId the trigger id; never {@code null} or blank
         * @param context the fire-time context; may be {@code null}
         *
         * @return the result of the transition execution
         *
         * @throws TransfluxValidationException if {@code triggerId} is {@code null}/blank, no
         *         trigger is registered under it, the entity is not in the trigger's source state,
         *         or the context type does not match
         */
        TransitionResult<T> fire(String triggerId, Object context);

        /**
         * Processes a host-published event against the entity's eligible event triggers, with no
         * firing context.
         * <p>
         * Among the event triggers on transitions leaving the entity's current state, those whose
         * declared event id equals {@code eventId} are evaluated in declaration order; the first
         * whose filter holds fires its transition. {@code eventData} is the event payload — it is
         * exposed to filters (as {@code #event} in SpEL forms) but is <b>not</b> passed to the
         * transition as its context.
         *
         * @param eventId the published event id; never {@code null} or blank
         * @param eventData the event payload; may be {@code null}
         *
         * @return the outcome — a fired transition's result, or a not-fired marker when nothing matched
         *
         * @throws TransfluxValidationException if {@code eventId} is {@code null} or blank
         */
        ProcessResult<T> processEvent(String eventId, Object eventData);

        /**
         * Processes a host-published event against the entity's eligible event triggers, passing
         * {@code context} through to the fired transition's operation. The framework verifies the
         * context type against the fired transition at the dispatch boundary.
         *
         * @param eventId the published event id; never {@code null} or blank
         * @param eventData the event payload; may be {@code null}
         * @param context the fire-time context for the transition; may be {@code null}
         *
         * @return the outcome — a fired transition's result, or a not-fired marker when nothing matched
         *
         * @throws TransfluxValidationException if {@code eventId} is {@code null} or blank, or the
         *         matched transition's context type does not accept {@code context}
         */
        ProcessResult<T> processEvent(String eventId, Object eventData, Object context);

        /**
         * Re-evaluates the entity's eligible data triggers, with no firing context.
         * <p>
         * Among the data triggers on transitions leaving the entity's current state, each gate is
         * evaluated in declaration order; the first whose gate holds fires its transition. The
         * library does not watch fields or evaluate in the background — re-evaluation happens only
         * on this explicit call.
         *
         * @return the outcome — a fired transition's result, or a not-fired marker when nothing matched
         */
        ProcessResult<T> processDataChange();

        /**
         * Re-evaluates the entity's eligible data triggers, passing {@code context} through to both
         * gate evaluation (as {@code #context} in SpEL forms) and the fired transition's operation.
         * The framework verifies the context type against the fired transition at the dispatch
         * boundary.
         *
         * @param context the fire-time context; may be {@code null}
         *
         * @return the outcome — a fired transition's result, or a not-fired marker when nothing matched
         *
         * @throws TransfluxValidationException if the matched transition's context type does not
         *         accept {@code context}
         */
        ProcessResult<T> processDataChange(Object context);
    }
}
