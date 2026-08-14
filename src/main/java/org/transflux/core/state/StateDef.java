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
import org.transflux.core.exception.TransfluxValidationException;
import org.transflux.core.transition.TransitionDef;

import java.util.function.Consumer;

/**
 * Builder interface for defining states within a state machine definition.
 * <p>
 * {@code StateDef} is configured through a lambda-configurer passed to
 * {@link org.transflux.core.StateMachineDef#state(String, Consumer)}. Inside the configurer
 * body, the user may set metadata ({@link #withName} / {@link #withDescription}), attach entry
 * and exit listeners ({@link #onEntry(String, StateListener)} / {@link #onExit(String, StateListener)}
 * and overloads), and declare outgoing transitions via
 * {@link #transitionsTo(String, String, Consumer)} and overloads.
 *
 * <p>Once the configurer returns, the {@code StateDef} reference becomes inert: any subsequent
 * mutating call throws {@link TransfluxValidationException}. To declare another state or to
 * build the state machine, return from the lambda and continue on the enclosing
 * {@link org.transflux.core.StateMachineDef}.
 *
 * <p><b>Example usage:</b>
 * <pre>{@code
 * StateMachine<Order> orderSM = Transflux.defineStateMachine()
 *     .forEntityType(Order.class)
 *     .withStateResolver(order -> order.getStatus().name())
 *     .state("pending", s -> s
 *         .withName("Pending Order")
 *         .withDescription("Order has been placed but not yet processed")
 *         .transitionsTo("processing", "start-processing", OrderContext.class, t -> {})
 *         .transitionsTo("cancelled", "cancel-order", CancelReason.class, t -> {}))
 *     .state("processing", s -> s
 *         .withName("Processing Order")
 *         .transitionsTo("shipped", "ship-order", t -> {}))
 *     .state("shipped", s -> {})
 *     .state("cancelled", s -> {})
 *     .build();
 * }</pre>
 *
 * @param <T> the type of entity managed by the state machine
 */
public interface StateDef<T> extends Identifiable {

    /**
     * Returns the state's identifier.
     *
     * @return the state ID
     */
    @Override
    String getId();

    /**
     * Sets the human-readable name for this state.
     *
     * @param name the human-readable name for this state
     *
     * @return this StateDef instance for chaining inside the configurer body
     */
    StateDef<T> withName(String name);

    /**
     * Sets the description for this state.
     *
     * @param description the description for this state
     *
     * @return this StateDef instance for chaining inside the configurer body
     */
    StateDef<T> withDescription(String description);

    /**
     * Attaches a listener notified when an entity enters this state.
     *
     * <p>Entry listeners fire after the transition has been committed, in declaration order,
     * ahead of any listener registered through
     * {@link org.transflux.core.StateMachineDef#onAnyStateEntry(String, StateListener)}.
     *
     * @param listenerId the listener id, unique among all state listeners on this state machine
     * @param listener the listener instance; never {@code null}
     *
     * @return this StateDef instance for chaining inside the configurer body
     *
     * @throws TransfluxValidationException if either argument is {@code null}, the id is blank,
     *         or another state listener is already registered under the same id
     */
    StateDef<T> onEntry(String listenerId, StateListener<T> listener);

    /**
     * {@link Identifiable} overload of {@link #onEntry(String, StateListener)} — delegates via
     * {@link Identifiable#getId()}.
     *
     * @param listenerIdentifiable an identifiable supplying the listener id
     * @param listener the listener instance; never {@code null}
     *
     * @return this StateDef instance for chaining inside the configurer body
     *
     * @throws TransfluxValidationException if either argument is {@code null}, the id is blank,
     *         or another state listener is already registered under the same id
     */
    StateDef<T> onEntry(Identifiable listenerIdentifiable, StateListener<T> listener);

    /**
     * Attaches a listener class notified when an entity enters this state. The class is
     * instantiated through its public no-arg constructor when the state machine is built.
     *
     * @param listenerId the listener id, unique among all state listeners on this state machine
     * @param listenerClass the listener class; never {@code null}
     *
     * @return this StateDef instance for chaining inside the configurer body
     *
     * @throws TransfluxValidationException if either argument is {@code null}, the id is blank,
     *         or another state listener is already registered under the same id
     */
    StateDef<T> onEntry(String listenerId, Class<? extends StateListener<T>> listenerClass);

    /**
     * {@link Identifiable} overload of {@link #onEntry(String, Class)}.
     *
     * @param listenerIdentifiable an identifiable supplying the listener id
     * @param listenerClass the listener class; never {@code null}
     *
     * @return this StateDef instance for chaining inside the configurer body
     */
    StateDef<T> onEntry(Identifiable listenerIdentifiable, Class<? extends StateListener<T>> listenerClass);

    /**
     * Attaches an entry listener declared through a configurer, for the cases where the listener
     * carries a name or description as well as a body.
     *
     * @param listenerId the listener id, unique among all state listeners on this state machine
     * @param configurer callback that configures the listener; never {@code null}
     *
     * @return this StateDef instance for chaining inside the configurer body
     *
     * @throws TransfluxValidationException if either argument is {@code null}, the id is blank,
     *         another state listener is already registered under the same id, or the configurer
     *         declares no listener
     */
    StateDef<T> onEntry(String listenerId, Consumer<StateListenerDef<T>> configurer);

    /**
     * {@link Identifiable} overload of {@link #onEntry(String, Consumer)}.
     *
     * @param listenerIdentifiable an identifiable supplying the listener id
     * @param configurer callback that configures the listener; never {@code null}
     *
     * @return this StateDef instance for chaining inside the configurer body
     */
    StateDef<T> onEntry(Identifiable listenerIdentifiable, Consumer<StateListenerDef<T>> configurer);

    /**
     * Attaches a listener notified when an entity leaves this state.
     *
     * <p>Exit listeners fire once the transition's pre-conditions have passed and before its
     * operation runs, so a notification does <b>not</b> imply the transition went on to succeed.
     * They run in declaration order, ahead of any listener registered through
     * {@link org.transflux.core.StateMachineDef#onAnyStateExit(String, StateListener)}.
     *
     * @param listenerId the listener id, unique among all state listeners on this state machine
     * @param listener the listener instance; never {@code null}
     *
     * @return this StateDef instance for chaining inside the configurer body
     *
     * @throws TransfluxValidationException if either argument is {@code null}, the id is blank,
     *         or another state listener is already registered under the same id
     */
    StateDef<T> onExit(String listenerId, StateListener<T> listener);

    /**
     * {@link Identifiable} overload of {@link #onExit(String, StateListener)} — delegates via
     * {@link Identifiable#getId()}.
     *
     * @param listenerIdentifiable an identifiable supplying the listener id
     * @param listener the listener instance; never {@code null}
     *
     * @return this StateDef instance for chaining inside the configurer body
     *
     * @throws TransfluxValidationException if either argument is {@code null}, the id is blank,
     *         or another state listener is already registered under the same id
     */
    StateDef<T> onExit(Identifiable listenerIdentifiable, StateListener<T> listener);

    /**
     * Attaches a listener class notified when an entity leaves this state. The class is
     * instantiated through its public no-arg constructor when the state machine is built.
     *
     * @param listenerId the listener id, unique among all state listeners on this state machine
     * @param listenerClass the listener class; never {@code null}
     *
     * @return this StateDef instance for chaining inside the configurer body
     *
     * @throws TransfluxValidationException if either argument is {@code null}, the id is blank,
     *         or another state listener is already registered under the same id
     */
    StateDef<T> onExit(String listenerId, Class<? extends StateListener<T>> listenerClass);

    /**
     * {@link Identifiable} overload of {@link #onExit(String, Class)}.
     *
     * @param listenerIdentifiable an identifiable supplying the listener id
     * @param listenerClass the listener class; never {@code null}
     *
     * @return this StateDef instance for chaining inside the configurer body
     */
    StateDef<T> onExit(Identifiable listenerIdentifiable, Class<? extends StateListener<T>> listenerClass);

    /**
     * Attaches an exit listener declared through a configurer, for the cases where the listener
     * carries a name or description as well as a body.
     *
     * @param listenerId the listener id, unique among all state listeners on this state machine
     * @param configurer callback that configures the listener; never {@code null}
     *
     * @return this StateDef instance for chaining inside the configurer body
     *
     * @throws TransfluxValidationException if either argument is {@code null}, the id is blank,
     *         another state listener is already registered under the same id, or the configurer
     *         declares no listener
     */
    StateDef<T> onExit(String listenerId, Consumer<StateListenerDef<T>> configurer);

    /**
     * {@link Identifiable} overload of {@link #onExit(String, Consumer)}.
     *
     * @param listenerIdentifiable an identifiable supplying the listener id
     * @param configurer callback that configures the listener; never {@code null}
     *
     * @return this StateDef instance for chaining inside the configurer body
     */
    StateDef<T> onExit(Identifiable listenerIdentifiable, Consumer<StateListenerDef<T>> configurer);

    /**
     * Declares an outgoing transition from this state with pass-through ({@link Object}) context.
     * The configurer is invoked synchronously against a freshly-constructed
     * {@link TransitionDef}; the def is not exposed to the caller after the lambda returns.
     *
     * @param targetStateId the ID of the target state
     * @param transitionId the unique identifier for this transition
     * @param configurer callback that configures the transition; never {@code null}
     *
     * @return this StateDef instance for chaining inside the configurer body
     *
     * @throws TransfluxValidationException if any argument is {@code null} or blank
     */
    StateDef<T> transitionsTo(String targetStateId, String transitionId,
                              Consumer<TransitionDef<T, Object>> configurer);

    /**
     * Declares an outgoing transition from this state with the supplied context class.
     *
     * <p>{@code Void.class} declares that the transition takes no context — fire calls with a
     * non-null context are rejected at the dispatch boundary.
     *
     * @param targetStateId the ID of the target state
     * @param transitionId the unique identifier for this transition
     * @param contextType the transition's context class; use {@code Void.class} for a
     *                    context-free transition
     * @param configurer callback that configures the transition; never {@code null}
     * @param <C> the transition context type
     *
     * @return this StateDef instance for chaining inside the configurer body
     *
     * @throws TransfluxValidationException if any argument is {@code null} or blank
     */
    <C> StateDef<T> transitionsTo(String targetStateId, String transitionId, Class<C> contextType,
                                  Consumer<TransitionDef<T, C>> configurer);

    /**
     * Declares an outgoing transition with pass-through context to a target state identified by
     * an {@link Identifiable}.
     *
     * @param targetStateIdentifiable an identifiable providing the target state ID
     * @param transitionId the unique identifier for this transition
     * @param configurer callback that configures the transition; never {@code null}
     *
     * @return this StateDef instance for chaining inside the configurer body
     *
     * @throws TransfluxValidationException if any argument is {@code null}, the target id is
     *         blank, or the transition id is blank
     */
    StateDef<T> transitionsTo(Identifiable targetStateIdentifiable, String transitionId,
                              Consumer<TransitionDef<T, Object>> configurer);

    /**
     * Declares an outgoing transition with the supplied context class to a target state
     * identified by an {@link Identifiable}.
     *
     * @param targetStateIdentifiable an identifiable providing the target state ID
     * @param transitionId the unique identifier for this transition
     * @param contextType the transition's context class
     * @param configurer callback that configures the transition; never {@code null}
     * @param <C> the transition context type
     *
     * @return this StateDef instance for chaining inside the configurer body
     *
     * @throws TransfluxValidationException if any argument is {@code null}, the target id is
     *         blank, or the transition id is blank
     */
    <C> StateDef<T> transitionsTo(Identifiable targetStateIdentifiable, String transitionId,
                                  Class<C> contextType, Consumer<TransitionDef<T, C>> configurer);

    /**
     * Declares an outgoing transition with pass-through context, with the transition id supplied
     * by an {@link Identifiable}.
     *
     * @param targetStateId the ID of the target state
     * @param transitionIdentifiable an identifiable providing the transition id
     * @param configurer callback that configures the transition; never {@code null}
     *
     * @return this StateDef instance for chaining inside the configurer body
     *
     * @throws TransfluxValidationException if any argument is {@code null}, the target id is
     *         blank, or the transition id is blank
     */
    StateDef<T> transitionsTo(String targetStateId, Identifiable transitionIdentifiable,
                              Consumer<TransitionDef<T, Object>> configurer);

    /**
     * Declares an outgoing typed-context transition, with the transition id supplied by an
     * {@link Identifiable}.
     *
     * @param targetStateId the ID of the target state
     * @param transitionIdentifiable an identifiable providing the transition id
     * @param contextType the transition's context class
     * @param configurer callback that configures the transition; never {@code null}
     * @param <C> the transition context type
     *
     * @return this StateDef instance for chaining inside the configurer body
     *
     * @throws TransfluxValidationException if any argument is {@code null}, the target id is
     *         blank, or the transition id is blank
     */
    <C> StateDef<T> transitionsTo(String targetStateId, Identifiable transitionIdentifiable,
                                  Class<C> contextType, Consumer<TransitionDef<T, C>> configurer);

    /**
     * Declares an outgoing pass-through-context transition where both target state and transition
     * id are supplied as {@link Identifiable}s.
     *
     * @param targetStateIdentifiable an identifiable providing the target state ID
     * @param transitionIdentifiable an identifiable providing the transition id
     * @param configurer callback that configures the transition; never {@code null}
     *
     * @return this StateDef instance for chaining inside the configurer body
     *
     * @throws TransfluxValidationException if any argument is {@code null}, the target id is
     *         blank, or the transition id is blank
     */
    StateDef<T> transitionsTo(Identifiable targetStateIdentifiable, Identifiable transitionIdentifiable,
                              Consumer<TransitionDef<T, Object>> configurer);

    /**
     * Declares an outgoing typed-context transition where both target state and transition id
     * are supplied as {@link Identifiable}s.
     *
     * @param targetStateIdentifiable an identifiable providing the target state ID
     * @param transitionIdentifiable an identifiable providing the transition id
     * @param contextType the transition's context class
     * @param configurer callback that configures the transition; never {@code null}
     * @param <C> the transition context type
     *
     * @return this StateDef instance for chaining inside the configurer body
     *
     * @throws TransfluxValidationException if any argument is {@code null}, the target id is
     *         blank, or the transition id is blank
     */
    <C> StateDef<T> transitionsTo(Identifiable targetStateIdentifiable, Identifiable transitionIdentifiable,
                                  Class<C> contextType, Consumer<TransitionDef<T, C>> configurer);
}
