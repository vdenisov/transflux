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

package org.transflux.core.operation;

import org.transflux.core.Identifiable;
import org.transflux.core.condition.Condition;
import org.transflux.core.exception.TransfluxValidationException;

import java.util.function.Predicate;

/**
 * Sub-builder for a single conditional branch within a {@link ConditionalStepDef}.
 * <p>
 * A branch carries exactly one condition selector and one or more steps to run when the
 * selector evaluates to {@code true}. The condition overload set mirrors the
 * pre/post-condition grammar offered on transitions: a reference to a registered condition,
 * an inline {@link Condition} instance, a {@link Condition} class, a {@link Predicate} over
 * the entity, or a SpEL expression — either with an auto-derived id or an explicit one.
 *
 * <p>If a configurer calls more than one of the {@code condition(...)} overloads, the last
 * call wins and a warning is logged.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
public interface BranchDef<T, C> {

    /**
     * Sets this branch's condition to a reference to a previously registered condition.
     *
     * @param registeredConditionId the registered condition id
     *
     * @return this branch def for chaining
     *
     * @throws TransfluxValidationException if {@code registeredConditionId} is {@code null}
     *         or blank
     */
    BranchDef<T, C> condition(String registeredConditionId);

    /**
     * {@link Identifiable} overload of {@link #condition(String)} — delegates via
     * {@link Identifiable#getId()}.
     *
     * @param registeredCondition an identifiable supplying the condition id
     *
     * @return this branch def for chaining
     *
     * @throws TransfluxValidationException if {@code registeredCondition} is {@code null}
     */
    BranchDef<T, C> condition(Identifiable registeredCondition);

    /**
     * Sets this branch's condition to a SpEL expression whose id is auto-derived from the
     * expression text and the branch's path within the enclosing state machine.
     *
     * @param expression the SpEL expression text
     *
     * @return this branch def for chaining
     *
     * @throws TransfluxValidationException if {@code expression} is {@code null} or blank
     */
    BranchDef<T, C> conditionExpression(String expression);

    /**
     * Sets this branch's condition to an inline {@link Condition} instance under the supplied
     * id.
     *
     * @param id the condition id
     * @param condition the condition instance
     *
     * @return this branch def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank or
     *         {@code condition} is {@code null}
     */
    BranchDef<T, C> condition(String id, Condition<T, C> condition);

    /**
     * Sets this branch's condition to a {@link Condition} class under the supplied id; the
     * framework reflectively instantiates the class through its public no-arg constructor at
     * state-machine build time.
     *
     * @param id the condition id
     * @param conditionClass the condition class
     *
     * @return this branch def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank or
     *         {@code conditionClass} is {@code null}
     */
    BranchDef<T, C> condition(String id, Class<? extends Condition<T, C>> conditionClass);

    /**
     * Sets this branch's condition to an entity-only {@link Predicate}, adapted into a
     * {@link Condition} that ignores the context and transition view.
     *
     * @param id the condition id
     * @param predicate the predicate over the entity
     *
     * @return this branch def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank or
     *         {@code predicate} is {@code null}
     */
    BranchDef<T, C> condition(String id, Predicate<T> predicate);

    /**
     * Sets this branch's condition to a SpEL expression under an explicit id.
     *
     * @param id the condition id
     * @param expression the SpEL expression text
     *
     * @return this branch def for chaining
     *
     * @throws TransfluxValidationException if {@code id} or {@code expression} is
     *         {@code null} or blank
     */
    BranchDef<T, C> condition(String id, String expression);

    /**
     * Appends a reference to a step registered on the enclosing state machine.
     *
     * @param registeredStepId the registered step id
     *
     * @return this branch def for chaining
     *
     * @throws TransfluxValidationException if {@code registeredStepId} is {@code null} or
     *         blank
     */
    BranchDef<T, C> step(String registeredStepId);

    /**
     * {@link Identifiable} overload of {@link #step(String)} — delegates via
     * {@link Identifiable#getId()}.
     *
     * @param registeredStep an identifiable supplying the step id
     *
     * @return this branch def for chaining
     *
     * @throws TransfluxValidationException if {@code registeredStep} is {@code null}
     */
    BranchDef<T, C> step(Identifiable registeredStep);

    /**
     * Appends an inline {@link Step} instance under the supplied id. The step is
     * auto-registered on the enclosing state machine at build time.
     *
     * @param id the step id
     * @param step the step instance
     *
     * @return this branch def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank or
     *         {@code step} is {@code null}
     */
    BranchDef<T, C> step(String id, Step<T, C> step);

    /**
     * Appends an inline {@link Step} class under the supplied id. The framework reflectively
     * instantiates the class through its public no-arg constructor at state-machine build
     * time and auto-registers it.
     *
     * @param id the step id
     * @param stepClass the step class
     *
     * @return this branch def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank or
     *         {@code stepClass} is {@code null}
     */
    BranchDef<T, C> step(String id, Class<? extends Step<T, C>> stepClass);
}
