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
import org.transflux.core.operation.CompositeOperationDef;
import org.transflux.core.operation.Operation;
import org.transflux.core.operation.SimpleOperationDef;
import org.transflux.core.operation.Step;

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
 * <p><b>Attaching operations.</b> A transition carries at most one operation. The operation
 * is either <i>simple</i> (a single {@link Operation} executing all the business logic on its
 * own) or <i>composite</i> (an ordered list of reusable {@link Step}s). The fluent API exposes
 * both kinds symmetrically:
 *
 * <pre>{@code
 * // Simple, no extra configuration (class form):
 * .simpleOperation("activate", ActivateOperation.class)
 *
 * // Simple, no extra configuration (instance form):
 * .simpleOperation("activate", new ActivateOperation())
 *
 * // Simple, with name/description set inside the configurer:
 * .simpleOperation("activate", op -> op
 *     .name("Activate Subscription")
 *     .description("Marks the subscription active and bills the first period")
 *     .using(ActivateOperation.class))
 *
 * // Composite, with an ordered list of steps:
 * .compositeOperation("validate-and-pay", composite -> composite
 *     .name("Validate and Charge")
 *     .step("validate-cart")
 *     .step("compute-total")
 *     .step("charge", ChargeStep.class))
 *
 * // Sugar for a single-step composite that references a registered step:
 * .step("validate-cart")
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
 * @param <T> the entity type managed by the enclosing state machine
 * @param <C> the host-supplied context type carried through transition execution
 */
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
     * Attaches a simple operation using a pre-constructed {@link Operation} instance.
     *
     * @param id the operation id; never {@code null} or blank
     * @param operation the operation instance; never {@code null}
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank or
     *         {@code operation} is {@code null}
     */
    TransitionDef<T, C> simpleOperation(String id, Operation<T, C> operation);

    /**
     * Attaches a simple operation using an {@link Operation} class. The framework instantiates
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
    TransitionDef<T, C> simpleOperation(String id, Class<? extends Operation<T, C>> operationClass);

    /**
     * Attaches a simple operation built through a fluent configurer. Use this form when you
     * want to set {@code name} / {@code description} alongside the operation source.
     * <p>
     * The configurer is invoked synchronously against a freshly-constructed
     * {@link SimpleOperationDef} carrying the supplied {@code id}; it must call
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
    TransitionDef<T, C> simpleOperation(String id, Consumer<SimpleOperationDef<T, C>> configurer);

    /**
     * Attaches a composite operation built through a fluent configurer. The composite must
     * declare at least one step.
     * <p>
     * The configurer is invoked synchronously against a freshly-constructed
     * {@link CompositeOperationDef} carrying the supplied {@code id}; it must append at least
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
    TransitionDef<T, C> compositeOperation(String id, Consumer<CompositeOperationDef<T, C>> configurer);

    /**
     * Convenience: attaches a single-step composite operation that references a step already
     * registered on the enclosing state machine. The composite is assigned a deterministic id
     * derived from this transition's id ({@code "transition-{transitionId}-op"}).
     *
     * @param registeredStepId the registered step id
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if {@code registeredStepId} is {@code null} or blank
     */
    TransitionDef<T, C> step(String registeredStepId);

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
     * Appends a pre-condition built from a {@link Predicate} over the entity under the given
     * id. The predicate is adapted into a {@link Condition} that ignores the context and the
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
     * Appends a post-condition built from a {@link Predicate} over the entity under the given
     * id. The predicate is adapted into a {@link Condition} that ignores the context and the
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

    // TODO: trigger framework
    TransitionDef<T, C> addManualTrigger();
    // TODO: trigger framework
    TransitionDef<T, C> addManualTrigger(String id);

    // TODO: trigger framework
    TransitionDef<T, C> addEventTrigger(String id);
    // TODO: trigger framework
    TransitionDef<T, C> addEventTrigger(String id, String eventId);
    // TODO: trigger framework
    TransitionDef<T, C> addEventTrigger(Identifiable event);
    // TODO: trigger framework
    TransitionDef<T, C> addEventTrigger(String id, Identifiable event);
    // TODO: trigger framework
    TransitionDef<T, C> addEventTrigger(BiPredicate<String, T> condition);
    // TODO: trigger framework
    TransitionDef<T, C> addEventTrigger(String id, BiPredicate<String, T> condition);

    // TODO: trigger framework
    TransitionDef<T, C> addDataTrigger(String id);
    // TODO: trigger framework
    TransitionDef<T, C> addDataTrigger(Predicate<T> condition);
    // TODO: trigger framework
    TransitionDef<T, C> addDataTrigger(String id, Predicate<T> condition);
}
