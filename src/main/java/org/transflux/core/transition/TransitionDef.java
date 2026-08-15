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
import org.transflux.core.action.OperationDef;
import org.transflux.core.action.Action;
import org.transflux.core.action.StepDef;
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
 * <p><b>Attaching an action.</b> A transition carries at most one action, in either authoring
 * form: a <i>step</i> (a Java body doing the work itself) or an <i>operation</i> (an ordered
 * list of members). Both attach the same way, and either may also be named by id if it is
 * already registered on the state machine:
 *
 * <pre>{@code
 * // Imperative, class form:
 * .step("activate", ActivateAction.class)
 *
 * // Imperative, instance form:
 * .step("activate", new ActivateAction())
 *
 * // Imperative, with name/description set inside the configurer:
 * .step("activate", a -> a
 *     .withName("Activate Subscription")
 *     .withDescription("Marks the subscription active and bills the first period")
 *     .using(ActivateAction.class))
 *
 * // Declarative, an ordered list of members:
 * .operation("validate-and-pay", op -> op
 *     .withName("Validate and Charge")
 *     .run("validate-cart")
 *     .run("compute-total")
 *     .step("charge", ChargeAction.class))
 *
 * // By id, referencing something registered on the state machine:
 * .run("activate-subscription")
 * }</pre>
 *
 * Each method returns {@code TransitionDef<T, C>} so chained calls stay scoped to the
 * transition. The configurer forms grant temporary write access to the underlying operation
 * def; the def is not exposed to the caller after the lambda returns, which keeps the
 * operation immutable from the moment it is attached.
 *
 * <p><b>Attaching conditions.</b> Pre- and post-conditions are attached through the
 * {@code preCondition(...)} / {@code postCondition(...)} overloads. The single-argument
 * {@code preCondition(String registeredConditionId)} / {@code postCondition(...)} forms reference
 * a condition registered on the enclosing state machine through
 * {@link org.transflux.core.StateMachineDef#condition StateMachineDef.condition(...)}; the
 * remaining overloads inline a {@link Condition} instance, class, {@link Predicate}, or SpEL
 * expression under an explicit id. {@code preConditionExpression(String)} /
 * {@code postConditionExpression(String)} accept an inline expression with an auto-derived id.
 * Multiple calls accumulate; conditions are evaluated in declaration order, and the first
 * failure aborts the remainder of the corresponding list.
 *
 * <p><b>Attaching listeners.</b> {@code onStart(...)} / {@code onComplete(...)} /
 * {@code onError(...)} attach observers of this transition's execution, each accepting a
 * {@link TransitionListener} instance, a listener class, or a configurer that also sets metadata.
 * Complete and error partition the outcomes: exactly one of them follows every start
 * notification, and neither occurs without one. Listeners observe only — an exception thrown by
 * one is logged and swallowed, and never affects the {@link TransitionResult}.
 *
 * @param <T> the entity type managed by the enclosing state machine
 * @param <C> the host-supplied context type carried through transition execution
 */
@SuppressWarnings("GrazieInspection")
public interface TransitionDef<T, C> extends Identifiable {

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
     * Returns the context class declared for this transition. Defaults to {@code Object.class}
     * (accepts any non-{@code null} firing context, and also accepts {@code null}) until
     * {@link #usingContext(Class)} re-types the def.
     *
     * @return the declared context class; never {@code null}
     */
    Class<C> getContextType();

    /**
     * Re-types this transition def to carry the supplied context class. Calling this method
     * captures the context type and returns the same underlying def re-generified so that
     * subsequent member declarations (operations, conditions) type-check against {@code C2}.
     * When omitted, a transition defaults to {@code TransitionDef<T, Object>}, accepting any
     * non-{@code null} firing context (and {@code null}); re-type with {@code usingContext(Void.class)}
     * to reject any non-{@code null} firing context.
     *
     * @param contextType the context class; never {@code null}
     * @param <C2> the new context type
     *
     * @return this def, re-typed with the new context type
     *
     * @throws TransfluxValidationException if {@code contextType} is {@code null}
     */
    <C2> TransitionDef<T, C2> usingContext(Class<C2> contextType);

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
     * Attaches a simple operation using a pre-constructed {@link Action} instance.
     *
     * @param id the operation id; never {@code null} or blank
     * @param operation the operation instance; never {@code null}
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank or
     *         {@code operation} is {@code null}
     */
    TransitionDef<T, C> step(String id, Action<T, C> operation);

    /**
     * {@link Identifiable} overload of {@link #step(String, Action)}.
     *
     * @param operationIdentifiable an identifiable supplying the operation id
     * @param operation the operation instance
     *
     * @return this transition def for chaining
     */
    TransitionDef<T, C> step(Identifiable operationIdentifiable, Action<T, C> operation);

    /**
     * Attaches a simple operation using an {@link Action} class. The framework instantiates
     * it via its public no-arg constructor at state machine build time.
     *
     * @param id the operation id; never {@code null} or blank
     * @param operationClass the operation class; never {@code null}
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank or
     *         {@code operationClass} is {@code null}
     */
    TransitionDef<T, C> step(String id, Class<? extends Action<T, C>> operationClass);

    /**
     * {@link Identifiable} overload of {@link #step(String, Class)}.
     *
     * @param operationIdentifiable an identifiable supplying the operation id
     * @param operationClass the operation class
     *
     * @return this transition def for chaining
     */
    TransitionDef<T, C> step(Identifiable operationIdentifiable, Class<? extends Action<T, C>> operationClass);

    /**
     * Attaches a simple operation built through a fluent configurer. Use this form when you
     * want to set {@code name} / {@code description} alongside the operation source.
     * <p>
     * The configurer is invoked synchronously against a freshly-constructed
     * {@link StepDef} carrying the supplied {@code id}; it must call
     * {@code .using(...)} before returning. The def is not exposed to the caller after the
     * lambda returns.
     *
     * @param id the operation id; never {@code null} or blank
     * @param configurer the fluent configurer; never {@code null}
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank,
     *         {@code configurer} is {@code null}, or the configurer leaves the def without
     *         an operation source
     */
    TransitionDef<T, C> step(String id, Consumer<StepDef<T, C>> configurer);

    /**
     * {@link Identifiable} overload of {@link #step(String, Consumer)}.
     *
     * @param operationIdentifiable an identifiable supplying the operation id
     * @param configurer the fluent configurer
     *
     * @return this transition def for chaining
     */
    TransitionDef<T, C> step(Identifiable operationIdentifiable, Consumer<StepDef<T, C>> configurer);

    /**
     * Attaches a composite operation built through a fluent configurer. The composite must
     * declare at least one step.
     * <p>
     * The configurer is invoked synchronously against a freshly-constructed
     * {@link OperationDef} carrying the supplied {@code id}; it must append at least
     * one step before returning. The def is not exposed to the caller after the lambda returns.
     *
     * @param id the operation id; never {@code null} or blank
     * @param configurer the fluent configurer; never {@code null}
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank,
     *         {@code configurer} is {@code null}, or the configurer leaves the composite
     *         without any steps
     */
    TransitionDef<T, C> operation(String id, Consumer<OperationDef<T, C>> configurer);

    /**
     * {@link Identifiable} overload of {@link #operation(String, Consumer)}.
     *
     * @param operationIdentifiable an identifiable supplying the operation id
     * @param configurer the fluent configurer
     *
     * @return this transition def for chaining
     */
    TransitionDef<T, C> operation(Identifiable operationIdentifiable, Consumer<OperationDef<T, C>> configurer);

    /**
     * Attaches an action already registered on the enclosing state machine, whichever form it
     * was authored in. Its id appears verbatim in {@link TransitionResult#getExecutedPath()}
     * when the transition fires; no wrapper is synthesized.
     *
     * <p>The registered action's declared context type must be assignable from this
     * transition's context type - the same pass-through compatibility rule that applies to
     * by-id references inside an operation. {@code Object.class}-typed registrations are always
     * reachable.
     *
     * @param id the registered action id; never {@code null} or blank
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null} or blank, or if at
     *         build time no action is registered under this id, or the registered action's
     *         context type is incompatible with this transition's
     */
    TransitionDef<T, C> run(String id);

    /**
     * {@link Identifiable} overload of {@link #run(String)} - delegates via
     * {@link Identifiable#getId()}.
     *
     * @param registeredAction an identifiable supplying the action id
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if {@code registeredAction} is {@code null}
     */
    TransitionDef<T, C> run(Identifiable registeredAction);

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
     * {@link Identifiable} overload of {@link #preCondition(String)} — delegates via
     * {@link Identifiable#getId()}.
     *
     * @param registeredCondition an identifiable supplying the condition id
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if {@code registeredCondition} is {@code null}
     */
    TransitionDef<T, C> preCondition(Identifiable registeredCondition);

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
     * {@link Identifiable} overload of {@link #preCondition(String, Condition)}.
     *
     * @param conditionIdentifiable an identifiable supplying the condition id
     * @param condition the condition instance
     *
     * @return this transition def for chaining
     */
    TransitionDef<T, C> preCondition(Identifiable conditionIdentifiable, Condition<T, C> condition);

    /**
     * Appends a pre-condition built from a {@link Condition} class under the given id. The
     * class is reflectively instantiated through its public no-arg constructor when the state
     * machine is built.
     *
     * @param id the condition id; never {@code null} or blank
     * @param conditionClass the condition class; never {@code null}
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank or
     *         {@code conditionClass} is {@code null}
     */
    TransitionDef<T, C> preCondition(String id, Class<? extends Condition<T, C>> conditionClass);

    /**
     * {@link Identifiable} overload of {@link #preCondition(String, Class)}.
     *
     * @param conditionIdentifiable an identifiable supplying the condition id
     * @param conditionClass the condition class
     *
     * @return this transition def for chaining
     */
    TransitionDef<T, C> preCondition(Identifiable conditionIdentifiable, Class<? extends Condition<T, C>> conditionClass);

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
     * {@link Identifiable} overload of {@link #preCondition(String, BiPredicate)}.
     *
     * @param conditionIdentifiable an identifiable supplying the condition id
     * @param predicate the predicate
     *
     * @return this transition def for chaining
     */
    TransitionDef<T, C> preCondition(Identifiable conditionIdentifiable, BiPredicate<T, C> predicate);

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
     * {@link Identifiable} overload of {@link #preCondition(String, Predicate)}.
     *
     * @param conditionIdentifiable an identifiable supplying the condition id
     * @param predicate the entity predicate
     *
     * @return this transition def for chaining
     */
    TransitionDef<T, C> preCondition(Identifiable conditionIdentifiable, Predicate<T> predicate);

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
     * {@link Identifiable} overload of {@link #preCondition(String, String)}.
     *
     * @param conditionIdentifiable an identifiable supplying the condition id
     * @param expression the SpEL expression text
     *
     * @return this transition def for chaining
     */
    TransitionDef<T, C> preCondition(Identifiable conditionIdentifiable, String expression);

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
     * {@link Identifiable} overload of {@link #postCondition(String)} — delegates via
     * {@link Identifiable#getId()}.
     *
     * @param registeredCondition an identifiable supplying the condition id
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if {@code registeredCondition} is {@code null}
     */
    TransitionDef<T, C> postCondition(Identifiable registeredCondition);

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
     * {@link Identifiable} overload of {@link #postCondition(String, Condition)}.
     *
     * @param conditionIdentifiable an identifiable supplying the condition id
     * @param condition the condition instance
     *
     * @return this transition def for chaining
     */
    TransitionDef<T, C> postCondition(Identifiable conditionIdentifiable, Condition<T, C> condition);

    /**
     * Appends a post-condition built from a {@link Condition} class under the given id. The
     * class is reflectively instantiated through its public no-arg constructor when the state
     * machine is built.
     *
     * @param id the condition id; never {@code null} or blank
     * @param conditionClass the condition class; never {@code null}
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank or
     *         {@code conditionClass} is {@code null}
     */
    TransitionDef<T, C> postCondition(String id, Class<? extends Condition<T, C>> conditionClass);

    /**
     * {@link Identifiable} overload of {@link #postCondition(String, Class)}.
     *
     * @param conditionIdentifiable an identifiable supplying the condition id
     * @param conditionClass the condition class
     *
     * @return this transition def for chaining
     */
    TransitionDef<T, C> postCondition(Identifiable conditionIdentifiable, Class<? extends Condition<T, C>> conditionClass);

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
     * {@link Identifiable} overload of {@link #postCondition(String, BiPredicate)}.
     *
     * @param conditionIdentifiable an identifiable supplying the condition id
     * @param predicate the predicate
     *
     * @return this transition def for chaining
     */
    TransitionDef<T, C> postCondition(Identifiable conditionIdentifiable, BiPredicate<T, C> predicate);

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
     * {@link Identifiable} overload of {@link #postCondition(String, Predicate)}.
     *
     * @param conditionIdentifiable an identifiable supplying the condition id
     * @param predicate the entity predicate
     *
     * @return this transition def for chaining
     */
    TransitionDef<T, C> postCondition(Identifiable conditionIdentifiable, Predicate<T> predicate);

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
     * {@link Identifiable} overload of {@link #postCondition(String, String)}.
     *
     * @param conditionIdentifiable an identifiable supplying the condition id
     * @param expression the SpEL expression text
     *
     * @return this transition def for chaining
     */
    TransitionDef<T, C> postCondition(Identifiable conditionIdentifiable, String expression);

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
     * {@link Identifiable} overload of {@link #addManualTrigger(String)}.
     *
     * @param triggerIdentifiable an identifiable supplying the trigger id
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if {@code triggerIdentifiable} is {@code null}
     */
    TransitionDef<T, C> addManualTrigger(Identifiable triggerIdentifiable);

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
     * {@link Identifiable} overload of {@link #addManualTrigger(String, Consumer)}.
     *
     * @param triggerIdentifiable an identifiable supplying the trigger id
     * @param configurer the fluent configurer
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if {@code triggerIdentifiable} is {@code null}
     */
    TransitionDef<T, C> addManualTrigger(Identifiable triggerIdentifiable, Consumer<ManualTriggerDef<T, C>> configurer);

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
     * {@link Identifiable} overload of {@link #addEventTrigger(String, String)} — trigger id
     * supplied as an identifiable.
     *
     * @param triggerIdentifiable an identifiable supplying the trigger id
     * @param eventId the event id this trigger listens for
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if {@code triggerIdentifiable} is {@code null}
     */
    TransitionDef<T, C> addEventTrigger(Identifiable triggerIdentifiable, String eventId);

    /**
     * Mixed-form overload of {@link #addEventTrigger(String, String)} — event id supplied as an
     * identifiable.
     *
     * @param id the trigger id; never {@code null} or blank
     * @param event an identifiable supplying the event id
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank or {@code event} is
     *         {@code null}
     */
    TransitionDef<T, C> addEventTrigger(String id, Identifiable event);

    /**
     * {@link Identifiable} overload of {@link #addEventTrigger(String, String)} — both trigger id
     * and event id supplied as identifiables.
     *
     * @param triggerIdentifiable an identifiable supplying the trigger id
     * @param event an identifiable supplying the event id
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if either identifiable is {@code null}
     */
    TransitionDef<T, C> addEventTrigger(Identifiable triggerIdentifiable, Identifiable event);

    /**
     * Attaches an event trigger whose id <b>and</b> listened-for event id are both the given
     * identifiable's id, with no filter. Convenient when one event maps to exactly one trigger and
     * a separate trigger id would be redundant.
     *
     * @param event an identifiable supplying both the trigger id and the event id
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if {@code event} is {@code null}
     */
    TransitionDef<T, C> addEventTrigger(Identifiable event);

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
     * {@link Identifiable} overload of {@link #addEventTrigger(String, Consumer)}.
     *
     * @param triggerIdentifiable an identifiable supplying the trigger id
     * @param configurer the fluent configurer
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if {@code triggerIdentifiable} is {@code null}
     */
    TransitionDef<T, C> addEventTrigger(Identifiable triggerIdentifiable, Consumer<EventTriggerDef<T, C>> configurer);

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
     * {@link Identifiable} overload of {@link #addDataTrigger(String, Consumer)}.
     *
     * @param triggerIdentifiable an identifiable supplying the trigger id
     * @param configurer the fluent configurer
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if {@code triggerIdentifiable} is {@code null}
     */
    TransitionDef<T, C> addDataTrigger(Identifiable triggerIdentifiable, Consumer<DataTriggerDef<T, C>> configurer);

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
     * {@link Identifiable} overload of {@link #onStart(String, TransitionListener)} — delegates
     * via {@link Identifiable#getId()}.
     *
     * @param listenerIdentifiable an identifiable supplying the listener id
     * @param listener the listener instance; never {@code null}
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if either argument is {@code null}, the id is blank,
     *         or another listener is already registered under the same id
     */
    TransitionDef<T, C> onStart(Identifiable listenerIdentifiable, TransitionListener<T, C> listener);

    /**
     * Attaches a listener class notified when this transition starts. The class is instantiated
     * through its public no-arg constructor when the state machine is built.
     *
     * @param listenerId the listener id, unique among all listeners on this state machine
     * @param listenerClass the listener class; never {@code null}
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if either argument is {@code null}, the id is blank,
     *         or another listener is already registered under the same id
     */
    TransitionDef<T, C> onStart(String listenerId, Class<? extends TransitionListener<T, C>> listenerClass);

    /**
     * {@link Identifiable} overload of {@link #onStart(String, Class)}.
     *
     * @param listenerIdentifiable an identifiable supplying the listener id
     * @param listenerClass the listener class; never {@code null}
     *
     * @return this transition def for chaining
     */
    TransitionDef<T, C> onStart(Identifiable listenerIdentifiable,
                                Class<? extends TransitionListener<T, C>> listenerClass);

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
     * {@link Identifiable} overload of {@link #onStart(String, Consumer)}.
     *
     * @param listenerIdentifiable an identifiable supplying the listener id
     * @param configurer callback that configures the listener; never {@code null}
     *
     * @return this transition def for chaining
     */
    TransitionDef<T, C> onStart(Identifiable listenerIdentifiable,
                                Consumer<TransitionListenerDef<T, C>> configurer);

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
     * {@link Identifiable} overload of {@link #onComplete(String, TransitionListener)} — delegates
     * via {@link Identifiable#getId()}.
     *
     * @param listenerIdentifiable an identifiable supplying the listener id
     * @param listener the listener instance; never {@code null}
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if either argument is {@code null}, the id is blank,
     *         or another listener is already registered under the same id
     */
    TransitionDef<T, C> onComplete(Identifiable listenerIdentifiable, TransitionListener<T, C> listener);

    /**
     * Attaches a listener class notified when this transition completes successfully. The class is
     * instantiated through its public no-arg constructor when the state machine is built.
     *
     * @param listenerId the listener id, unique among all listeners on this state machine
     * @param listenerClass the listener class; never {@code null}
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if either argument is {@code null}, the id is blank,
     *         or another listener is already registered under the same id
     */
    TransitionDef<T, C> onComplete(String listenerId, Class<? extends TransitionListener<T, C>> listenerClass);

    /**
     * {@link Identifiable} overload of {@link #onComplete(String, Class)}.
     *
     * @param listenerIdentifiable an identifiable supplying the listener id
     * @param listenerClass the listener class; never {@code null}
     *
     * @return this transition def for chaining
     */
    TransitionDef<T, C> onComplete(Identifiable listenerIdentifiable,
                                   Class<? extends TransitionListener<T, C>> listenerClass);

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
     * {@link Identifiable} overload of {@link #onComplete(String, Consumer)}.
     *
     * @param listenerIdentifiable an identifiable supplying the listener id
     * @param configurer callback that configures the listener; never {@code null}
     *
     * @return this transition def for chaining
     */
    TransitionDef<T, C> onComplete(Identifiable listenerIdentifiable,
                                   Consumer<TransitionListenerDef<T, C>> configurer);

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
     * {@link Identifiable} overload of {@link #onError(String, TransitionListener)} — delegates
     * via {@link Identifiable#getId()}.
     *
     * @param listenerIdentifiable an identifiable supplying the listener id
     * @param listener the listener instance; never {@code null}
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if either argument is {@code null}, the id is blank,
     *         or another listener is already registered under the same id
     */
    TransitionDef<T, C> onError(Identifiable listenerIdentifiable, TransitionListener<T, C> listener);

    /**
     * Attaches a listener class notified when this transition fails. The class is instantiated
     * through its public no-arg constructor when the state machine is built.
     *
     * @param listenerId the listener id, unique among all listeners on this state machine
     * @param listenerClass the listener class; never {@code null}
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if either argument is {@code null}, the id is blank,
     *         or another listener is already registered under the same id
     */
    TransitionDef<T, C> onError(String listenerId, Class<? extends TransitionListener<T, C>> listenerClass);

    /**
     * {@link Identifiable} overload of {@link #onError(String, Class)}.
     *
     * @param listenerIdentifiable an identifiable supplying the listener id
     * @param listenerClass the listener class; never {@code null}
     *
     * @return this transition def for chaining
     */
    TransitionDef<T, C> onError(Identifiable listenerIdentifiable,
                                Class<? extends TransitionListener<T, C>> listenerClass);

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

    /**
     * {@link Identifiable} overload of {@link #onError(String, Consumer)}.
     *
     * @param listenerIdentifiable an identifiable supplying the listener id
     * @param configurer callback that configures the listener; never {@code null}
     *
     * @return this transition def for chaining
     */
    TransitionDef<T, C> onError(Identifiable listenerIdentifiable,
                                Consumer<TransitionListenerDef<T, C>> configurer);
}
