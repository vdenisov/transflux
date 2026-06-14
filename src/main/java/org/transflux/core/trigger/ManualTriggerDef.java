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

package org.transflux.core.trigger;

import org.transflux.core.Identifiable;
import org.transflux.core.condition.Condition;
import org.transflux.core.exception.TransfluxValidationException;

import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Definition builder for a manual trigger attached to a transition.
 * <p>
 * A manual trigger is an optional named handle on a transition. Beyond a discoverable id, it
 * carries its own metadata — name, description — and its own pre-conditions, distinct from the
 * transition's defaults. When the trigger is invoked through {@code entity(e).fire(triggerId)},
 * the transition's own pre-conditions are evaluated first, then the trigger's, in declaration
 * order; the first failure aborts the transition.
 *
 * <p>Manual triggers are declared inside a transition's configurer through
 * {@code TransitionDef.addManualTrigger(id, configurer)}. The configurer grants temporary write
 * access to this def; once it returns, the def is inert and any further mutation throws
 * {@link TransfluxValidationException}.
 *
 * <p>The pre-condition overloads mirror the transition's: the single-argument
 * {@code preCondition(String registeredConditionId)} form references a condition registered on
 * the enclosing state machine, while the remaining overloads inline a {@link Condition} instance,
 * class, {@link Predicate}, or SpEL expression under an explicit id.
 * {@code preConditionExpression(String)} accepts an inline expression with an auto-derived id.
 *
 * @param <T> the entity type managed by the enclosing state machine
 * @param <C> the host-supplied context type carried through transition execution
 */
public interface ManualTriggerDef<T, C> extends Identifiable {

    /**
     * Returns the unique identifier of this trigger.
     *
     * @return the trigger id; never {@code null} or blank
     */
    @Override
    String getId();

    /**
     * Returns the human-readable name of this trigger, or {@code null} when unset.
     *
     * @return the optional trigger name
     */
    String getName();

    /**
     * Returns the description of this trigger, or {@code null} when unset.
     *
     * @return the optional trigger description
     */
    String getDescription();

    /**
     * Returns the context class carried by the enclosing transition.
     *
     * @return the context class; never {@code null}
     */
    Class<C> contextType();

    /**
     * Sets the human-readable name of this trigger.
     *
     * @param name the name; may be {@code null} to clear
     *
     * @return this trigger def for chaining
     */
    ManualTriggerDef<T, C> withName(String name);

    /**
     * Sets the description of this trigger.
     *
     * @param description the description; may be {@code null} to clear
     *
     * @return this trigger def for chaining
     */
    ManualTriggerDef<T, C> withDescription(String description);

    /**
     * Appends a pre-condition that references a condition already registered on the enclosing
     * state machine through {@link org.transflux.core.StateMachineDef#condition StateMachineDef.condition(...)}.
     *
     * @param registeredConditionId the registered condition id; never {@code null} or blank
     *
     * @return this trigger def for chaining
     *
     * @throws TransfluxValidationException if {@code registeredConditionId} is {@code null} or blank
     */
    ManualTriggerDef<T, C> preCondition(String registeredConditionId);

    /**
     * {@link Identifiable} overload of {@link #preCondition(String)} — delegates via
     * {@link Identifiable#getId()}.
     *
     * @param registeredCondition an identifiable supplying the condition id
     *
     * @return this trigger def for chaining
     *
     * @throws TransfluxValidationException if {@code registeredCondition} is {@code null}
     */
    ManualTriggerDef<T, C> preCondition(Identifiable registeredCondition);

    /**
     * Appends an inline SpEL pre-condition with an auto-derived id. The id is computed
     * deterministically from the expression text and the descriptor's position. Use
     * {@link #preCondition(String, String)} when an explicit id is preferred.
     *
     * @param expression the SpEL expression text; never {@code null} or blank
     *
     * @return this trigger def for chaining
     *
     * @throws TransfluxValidationException if {@code expression} is {@code null} or blank
     */
    ManualTriggerDef<T, C> preConditionExpression(String expression);

    /**
     * Appends a pre-condition built from a {@link Condition} instance under the given id.
     *
     * @param id the condition id; never {@code null} or blank
     * @param condition the condition instance; never {@code null}
     *
     * @return this trigger def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank or
     *         {@code condition} is {@code null}
     */
    ManualTriggerDef<T, C> preCondition(String id, Condition<T, C> condition);

    /**
     * {@link Identifiable} overload of {@link #preCondition(String, Condition)}.
     *
     * @param conditionIdentifiable an identifiable supplying the condition id
     * @param condition the condition instance
     *
     * @return this trigger def for chaining
     */
    ManualTriggerDef<T, C> preCondition(Identifiable conditionIdentifiable, Condition<T, C> condition);

    /**
     * Appends a pre-condition built from a {@link Condition} class under the given id. The class
     * is reflectively instantiated through its public no-arg constructor when the state machine
     * is built.
     *
     * @param id the condition id; never {@code null} or blank
     * @param conditionClass the condition class; never {@code null}
     *
     * @return this trigger def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank or
     *         {@code conditionClass} is {@code null}
     */
    ManualTriggerDef<T, C> preCondition(String id, Class<? extends Condition<T, C>> conditionClass);

    /**
     * {@link Identifiable} overload of {@link #preCondition(String, Class)}.
     *
     * @param conditionIdentifiable an identifiable supplying the condition id
     * @param conditionClass the condition class
     *
     * @return this trigger def for chaining
     */
    ManualTriggerDef<T, C> preCondition(Identifiable conditionIdentifiable, Class<? extends Condition<T, C>> conditionClass);

    /**
     * Appends a pre-condition built from a {@link BiPredicate} over {@code (entity, context)}
     * under the given id. The predicate is adapted into a {@link Condition} that ignores the
     * transition view.
     *
     * @param id the condition id; never {@code null} or blank
     * @param predicate the predicate; never {@code null}
     *
     * @return this trigger def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank or
     *         {@code predicate} is {@code null}
     */
    ManualTriggerDef<T, C> preCondition(String id, BiPredicate<T, C> predicate);

    /**
     * {@link Identifiable} overload of {@link #preCondition(String, BiPredicate)}.
     *
     * @param conditionIdentifiable an identifiable supplying the condition id
     * @param predicate the predicate
     *
     * @return this trigger def for chaining
     */
    ManualTriggerDef<T, C> preCondition(Identifiable conditionIdentifiable, BiPredicate<T, C> predicate);

    /**
     * Convenience overload of {@link #preCondition(String, BiPredicate)} accepting an entity-only
     * {@link Predicate}; the context is ignored at evaluation time.
     *
     * @param id the condition id; never {@code null} or blank
     * @param predicate the entity predicate; never {@code null}
     *
     * @return this trigger def for chaining
     */
    ManualTriggerDef<T, C> preCondition(String id, Predicate<T> predicate);

    /**
     * {@link Identifiable} overload of {@link #preCondition(String, Predicate)}.
     *
     * @param conditionIdentifiable an identifiable supplying the condition id
     * @param predicate the entity predicate
     *
     * @return this trigger def for chaining
     */
    ManualTriggerDef<T, C> preCondition(Identifiable conditionIdentifiable, Predicate<T> predicate);

    /**
     * Appends a pre-condition built from a SpEL expression under the given id.
     *
     * @param id the condition id; never {@code null} or blank
     * @param expression the SpEL expression text; never {@code null} or blank
     *
     * @return this trigger def for chaining
     *
     * @throws TransfluxValidationException if {@code id} or {@code expression} is {@code null} or blank
     */
    ManualTriggerDef<T, C> preCondition(String id, String expression);

    /**
     * {@link Identifiable} overload of {@link #preCondition(String, String)}.
     *
     * @param conditionIdentifiable an identifiable supplying the condition id
     * @param expression the SpEL expression text
     *
     * @return this trigger def for chaining
     */
    ManualTriggerDef<T, C> preCondition(Identifiable conditionIdentifiable, String expression);
}
