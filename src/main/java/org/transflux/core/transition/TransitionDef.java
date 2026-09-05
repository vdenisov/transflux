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

package org.transflux.core.transition;

import org.transflux.core.Identifiable;
import org.transflux.core.condition.Condition;
import org.transflux.core.exception.TransfluxValidationException;
import org.transflux.core.action.ActionSequence;
import org.transflux.core.trigger.DataTriggerDef;
import org.transflux.core.trigger.EventTriggerDef;
import org.transflux.core.trigger.ManualTriggerDef;

import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Definition interface for transitions between states in a state machine.
 * <p>
 * TransitionDef represents the configuration and metadata for a transition, including the
 * unique identifier, source state, target state, and the operation that runs while the
 * transition is in flight.
 *
 * <p>TransitionDef instances are created internally by the framework when transitions are
 * registered through the fluent API and should not be instantiated directly by client code.
 *
 * <p><b>The body.</b> A transition holds an ordered list of actions and runs them in order - the
 * same member grammar a declarative container and a conditional's branch carry, declared once on
 * {@link ActionSequence} and inherited here. Declaration order is execution order, and a member
 * may be a reference, a forked reference, or an action declared in place:
 *
 * <pre>{@code
 * .state("pending", s -> s.transitionsTo("active", "activate", ActivationCtx.class, t -> t
 *     .run("validate-payment-method")
 *     .step("activate", new ActivateAction())
 *     .operation("bill", op -> op
 *         .run("compute-total")
 *         .step("charge", new ChargeAction()))
 *     .fork("send-receipt")))
 * }</pre>
 *
 * <p>{@code fork(...)} is legal here for the same reason it is legal in any sequence: the members
 * after it do not wait for it. What a transition carries beyond the list is everything
 * <em>around</em> it - source and target states, pre- and post-conditions, triggers, the state
 * commit, and its own listeners.
 *
 * <p>The transition is not itself an action. It has no id in the action namespace, no compensation
 * of its own and no action listeners; a member's qualified path starts at the member. Rolling back
 * the body as a unit is a matter of declaring it as one - wrap the members in
 * {@code operation(...)} and put the compensation there.
 *
 * <p>Each method returns {@code TransitionDef<T, C>} so chained calls stay scoped to the
 * transition. The configurer forms grant temporary write access to the underlying def; it is not
 * exposed to the caller after the lambda returns, which keeps the member immutable from the moment
 * it is declared.
 *
 * <p><b>Attaching conditions.</b> Pre- and post-conditions are attached through the
 * {@code preCondition(...)} / {@code postCondition(...)} overloads. The single-argument
 * {@code preCondition(String registeredConditionId)} / {@code postCondition(...)} forms reference
 * a condition registered on the enclosing state machine through
 * {@link org.transflux.core.StateMachineDef#condition StateMachineDef.condition(...)}; the
 * remaining overloads inline a {@link Condition} instance, {@link Predicate}, or SpEL expression
 * under an explicit id. {@code preConditionExpression(String)} /
 * {@code postConditionExpression(String)} accept an inline expression with an auto-derived id.
 * Multiple calls accumulate; conditions are evaluated in declaration order, and the first
 * failure aborts the remainder of the corresponding list.
 *
 * <p><b>Attaching listeners.</b> {@code onStart(...)} / {@code onComplete(...)} /
 * {@code onError(...)} attach observers of this transition's execution, each accepting a
 * {@link TransitionListener} instance or a configurer that also sets metadata.
 * Complete and error partition the outcomes: exactly one of them follows every start
 * notification, and neither occurs without one. Listeners observe only — an exception thrown by
 * one is logged and swallowed, and never affects the {@link TransitionResult}.
 *
 * @param <T> the entity type managed by the enclosing state machine
 * @param <C> the host-supplied context type carried through transition execution
 */
@SuppressWarnings("GrazieInspection")
public interface TransitionDef<T, C>
    extends Identifiable, ActionSequence<T, C, TransitionDef<T, C>> {

    /**
     * Returns the unique identifier of this transition.
     *
     * @return the transition ID
     */
    @Override
    String getId();

    /**
     * Returns the ID of the source state for this transition.
     *
     * @return the source state ID
     */
    String getSourceStateId();

    /**
     * Returns the ID of the target state for this transition.
     *
     * @return the target state ID
     */
    String getTargetStateId();

    /**
     * Returns the context class declared for this transition at
     * {@code transitionsTo(target, id, Class, configurer)}. Defaults to {@code Object.class}
     * (accepts any non-{@code null} firing context, and also accepts {@code null}).
     *
     * @return the declared context class; never {@code null}
     */
    Class<C> getContextType();

    /**
     * Sets the human-readable name of this transition.
     *
     * @param name the human-readable name; may be {@code null}
     *
     * @return this transition def for chaining
     */
    TransitionDef<T, C> withName(String name);

    /**
     * Sets the description of this transition.
     *
     * @param description the description; may be {@code null}
     *
     * @return this transition def for chaining
     */
    TransitionDef<T, C> withDescription(String description);

    /**
     * Appends a pre-condition that references a condition already registered on the enclosing
     * state machine through {@link org.transflux.core.StateMachineDef#condition StateMachineDef.condition(...)}.
     *
     * @param registeredConditionId the registered condition id; never {@code null} or blank
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if {@code registeredConditionId} is {@code null} or
     *         blank
     */
    TransitionDef<T, C> preCondition(String registeredConditionId);

    /**
     * Appends an inline SpEL pre-condition with an auto-derived id. The id is computed
     * deterministically from the expression text and the descriptor's position within the
     * enclosing state machine. Use {@link #preCondition(String, String)} when an explicit id is
     * preferred.
     *
     * @param expression the SpEL expression text; never {@code null} or blank
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if {@code expression} is {@code null} or blank
     */
    TransitionDef<T, C> preConditionExpression(String expression);

    /**
     * Appends a pre-condition built from a {@link Condition} instance under the given id.
     *
     * @param id the condition id; never {@code null} or blank
     * @param condition the condition instance; never {@code null}
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank or
     *         {@code condition} is {@code null}
     */
    TransitionDef<T, C> preCondition(String id, Condition<T, C> condition);

    /**
     * Appends a pre-condition built from a {@link BiPredicate} over {@code (entity, context)}
     * under the given id. The predicate is adapted into a {@link Condition} that ignores the
     * transition view.
     *
     * @param id the condition id; never {@code null} or blank
     * @param predicate the predicate; never {@code null}
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank or
     *         {@code predicate} is {@code null}
     */
    TransitionDef<T, C> preCondition(String id, BiPredicate<T, C> predicate);

    /**
     * Convenience overload of {@link #preCondition(String, BiPredicate)} accepting an
     * entity-only {@link Predicate}; the context is ignored at evaluation time.
     *
     * @param id the condition id; never {@code null} or blank
     * @param predicate the entity predicate; never {@code null}
     *
     * @return this transition def for chaining
     */
    TransitionDef<T, C> preCondition(String id, Predicate<T> predicate);

    /**
     * Appends a pre-condition built from a SpEL expression under the given id.
     *
     * @param id the condition id; never {@code null} or blank
     * @param expression the SpEL expression text; never {@code null} or blank
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if {@code id} or {@code expression} is
     *         {@code null} or blank
     */
    TransitionDef<T, C> preCondition(String id, String expression);

    /**
     * Appends a post-condition that references a condition already registered on the enclosing
     * state machine through {@link org.transflux.core.StateMachineDef#condition StateMachineDef.condition(...)}.
     *
     * @param registeredConditionId the registered condition id; never {@code null} or blank
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if {@code registeredConditionId} is {@code null} or
     *         blank
     */
    TransitionDef<T, C> postCondition(String registeredConditionId);

    /**
     * Appends an inline SpEL post-condition with an auto-derived id. The id is computed
     * deterministically from the expression text and the descriptor's position within the
     * enclosing state machine. Use {@link #postCondition(String, String)} when an explicit id is
     * preferred.
     *
     * @param expression the SpEL expression text; never {@code null} or blank
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if {@code expression} is {@code null} or blank
     */
    TransitionDef<T, C> postConditionExpression(String expression);

    /**
     * Appends a post-condition built from a {@link Condition} instance under the given id.
     *
     * @param id the condition id; never {@code null} or blank
     * @param condition the condition instance; never {@code null}
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank or
     *         {@code condition} is {@code null}
     */
    TransitionDef<T, C> postCondition(String id, Condition<T, C> condition);

    /**
     * Appends a post-condition built from a {@link BiPredicate} over {@code (entity, context)}
     * under the given id. The predicate is adapted into a {@link Condition} that ignores the
     * transition view.
     *
     * @param id the condition id; never {@code null} or blank
     * @param predicate the predicate; never {@code null}
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank or
     *         {@code predicate} is {@code null}
     */
    TransitionDef<T, C> postCondition(String id, BiPredicate<T, C> predicate);

    /**
     * Convenience overload of {@link #postCondition(String, BiPredicate)} accepting an
     * entity-only {@link Predicate}; the context is ignored at evaluation time.
     *
     * @param id the condition id; never {@code null} or blank
     * @param predicate the entity predicate; never {@code null}
     *
     * @return this transition def for chaining
     */
    TransitionDef<T, C> postCondition(String id, Predicate<T> predicate);

    /**
     * Appends a post-condition built from a SpEL expression under the given id.
     *
     * @param id the condition id; never {@code null} or blank
     * @param expression the SpEL expression text; never {@code null} or blank
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if {@code id} or {@code expression} is
     *         {@code null} or blank
     */
    TransitionDef<T, C> postCondition(String id, String expression);

    /**
     * Attaches a manual trigger to this transition under the given id, with no extra metadata or
     * pre-conditions. The trigger is invokable through {@code entity(e).fire(id)}.
     *
     * @param id the trigger id; never {@code null} or blank
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null} or blank
     */
    TransitionDef<T, C> addManualTrigger(String id);

    /**
     * Attaches a manual trigger built through a fluent configurer. Use this form to set the
     * trigger's name, description, and trigger-specific pre-conditions.
     * <p>
     * The configurer is invoked synchronously against a freshly-constructed
     * {@link ManualTriggerDef} carrying the supplied {@code id}. The def is not exposed to the
     * caller after the lambda returns. When the trigger is invoked through
     * {@code entity(e).fire(id)}, the transition's own pre-conditions are evaluated first, then the
     * trigger's, in declaration order.
     *
     * @param id the trigger id; never {@code null} or blank
     * @param configurer the fluent configurer; never {@code null}
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank or
     *         {@code configurer} is {@code null}
     */
    TransitionDef<T, C> addManualTrigger(String id, Consumer<ManualTriggerDef<T, C>> configurer);

    /**
     * Attaches an event trigger to this transition, listening for {@code eventId} with no filter.
     * The trigger fires on every event of that id published through
     * {@code entity(e).processEvent(eventId, eventData)} while the entity is in this transition's
     * source state.
     *
     * @param id the trigger id; never {@code null} or blank
     * @param eventId the event id this trigger listens for; never {@code null} or blank
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if {@code id} or {@code eventId} is {@code null} or blank
     */
    TransitionDef<T, C> addEventTrigger(String id, String eventId);

    /**
     * Attaches an event trigger whose id is the event id, listening for {@code eventId} with no
     * filter. Use it when one event maps to exactly one trigger and a separate trigger id would
     * only restate the event's.
     *
     * @param eventId the event id, used as this trigger's id too; never {@code null} or blank
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if {@code eventId} is {@code null} or blank
     */
    TransitionDef<T, C> addEventTrigger(String eventId);

    /**
     * Attaches an event trigger built through a fluent configurer. Use this form to declare the
     * event id, an optional payload filter, and the trigger's name / description.
     * <p>
     * The configurer is invoked synchronously against a freshly-constructed {@link EventTriggerDef}
     * carrying the supplied {@code id}; the def is not exposed to the caller after the lambda
     * returns. The event id is mandatory — declare it with {@code onEvent(...)} inside the
     * configurer.
     *
     * @param id the trigger id; never {@code null} or blank
     * @param configurer the fluent configurer; never {@code null}
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank or
     *         {@code configurer} is {@code null}
     */
    TransitionDef<T, C> addEventTrigger(String id, Consumer<EventTriggerDef<T, C>> configurer);

    /**
     * Attaches a data trigger built through a fluent configurer. The configurer must declare the
     * trigger's gate condition through {@code condition(...)}; it may also set the trigger's name
     * and description.
     * <p>
     * The configurer is invoked synchronously against a freshly-constructed {@link DataTriggerDef}
     * carrying the supplied {@code id}; the def is not exposed to the caller after the lambda
     * returns. When the host calls {@code entity(e).processDataChange()}, the framework evaluates
     * each eligible data trigger's gate and fires the first whose condition holds.
     *
     * @param id the trigger id; never {@code null} or blank
     * @param configurer the fluent configurer; never {@code null}
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank or
     *         {@code configurer} is {@code null}
     */
    TransitionDef<T, C> addDataTrigger(String id, Consumer<DataTriggerDef<T, C>> configurer);

    /**
     * Attaches a listener notified once this transition's pre-conditions have passed, before its
     * operation runs.
     *
     * <p>Listeners run in declaration order, ahead of any registered through
     * {@link org.transflux.core.StateMachineDef#onAnyTransitionStart(String, TransitionListener)}.
     * Every start notification is followed by exactly one of {@link #onComplete} or
     * {@link #onError}.
     *
     * @param listenerId the listener id, unique among all listeners on this state machine
     * @param listener the listener instance; never {@code null}
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if either argument is {@code null}, the id is blank,
     *         or another listener is already registered under the same id
     */
    TransitionDef<T, C> onStart(String listenerId, TransitionListener<T, C> listener);

    /**
     * Attaches a start listener declared through a configurer, for the cases where the listener
     * carries a name or description as well as a body.
     *
     * @param listenerId the listener id, unique among all listeners on this state machine
     * @param configurer callback that configures the listener; never {@code null}
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if either argument is {@code null}, the id is blank,
     *         another listener is already registered under the same id, or the configurer declares
     *         no listener
     */
    TransitionDef<T, C> onStart(String listenerId, Consumer<TransitionListenerDef<T, C>> configurer);

    /**
     * Attaches a listener notified after this transition succeeds and its new state has been
     * committed.
     *
     * <p>Complete and error partition the outcomes, so a completion listener never has to check
     * whether the transition worked — a failed transition reaches {@link #onError} instead.
     *
     * @param listenerId the listener id, unique among all listeners on this state machine
     * @param listener the listener instance; never {@code null}
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if either argument is {@code null}, the id is blank,
     *         or another listener is already registered under the same id
     */
    TransitionDef<T, C> onComplete(String listenerId, TransitionListener<T, C> listener);

    /**
     * Attaches a completion listener declared through a configurer, for the cases where the
     * listener carries a name or description as well as a body.
     *
     * @param listenerId the listener id, unique among all listeners on this state machine
     * @param configurer callback that configures the listener; never {@code null}
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if either argument is {@code null}, the id is blank,
     *         another listener is already registered under the same id, or the configurer declares
     *         no listener
     */
    TransitionDef<T, C> onComplete(String listenerId, Consumer<TransitionListenerDef<T, C>> configurer);

    /**
     * Attaches a listener notified after this transition fails, once any compensations it
     * registered have run.
     *
     * <p>The hook fires only for transitions that started: a rejection by a pre-condition notifies
     * nothing, because the transition never reached {@link #onStart}.
     *
     * @param listenerId the listener id, unique among all listeners on this state machine
     * @param listener the listener instance; never {@code null}
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if either argument is {@code null}, the id is blank,
     *         or another listener is already registered under the same id
     */
    TransitionDef<T, C> onError(String listenerId, TransitionListener<T, C> listener);

    /**
     * Attaches an error listener declared through a configurer, for the cases where the listener
     * carries a name or description as well as a body.
     *
     * @param listenerId the listener id, unique among all listeners on this state machine
     * @param configurer callback that configures the listener; never {@code null}
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if either argument is {@code null}, the id is blank,
     *         another listener is already registered under the same id, or the configurer declares
     *         no listener
     */
    TransitionDef<T, C> onError(String listenerId, Consumer<TransitionListenerDef<T, C>> configurer);

}
