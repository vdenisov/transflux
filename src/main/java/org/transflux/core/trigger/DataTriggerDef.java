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
 * Definition builder for a data trigger attached to a transition.
 * <p>
 * A data trigger gates on a single condition over the entity (and the host-supplied context). When
 * the host calls {@code entity(e).processDataChange()}, the framework evaluates each eligible data
 * trigger's condition and fires the first whose gate holds. The library performs no field watching
 * and no background evaluation — re-evaluation happens only on an explicit {@code processDataChange}
 * call.
 *
 * <p>The condition is authored through the standard Condition Descriptor grammar: a reference to a
 * condition registered on the enclosing state machine, an inline {@link Condition} instance or
 * class, a {@link BiPredicate} / {@link Predicate}, or an SpEL expression. The gate is a
 * <b>single</b> condition; re-declaring it replaces the previous declaration. A condition is
 * <b>required</b> — its absence is reported when the state machine is built.
 *
 * <p>Data triggers are declared inside a transition's configurer through
 * {@code TransitionDef.addDataTrigger(id, configurer)}. The configurer grants temporary write
 * access to this def; once it returns the def is inert and any further mutation throws
 * {@link TransfluxValidationException}.
 *
 * @param <T> the entity type managed by the enclosing state machine
 * @param <C> the host-supplied context type carried through transition execution
 */
public interface DataTriggerDef<T, C> extends Identifiable {

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
    DataTriggerDef<T, C> withName(String name);

    /**
     * Sets the description of this trigger.
     *
     * @param description the description; may be {@code null} to clear
     *
     * @return this trigger def for chaining
     */
    DataTriggerDef<T, C> withDescription(String description);

    /**
     * Sets the gate to a reference to a condition already registered on the enclosing state machine
     * through {@link org.transflux.core.StateMachineDef#condition StateMachineDef.condition(...)}.
     *
     * @param registeredConditionId the registered condition id; never {@code null} or blank
     *
     * @return this trigger def for chaining
     *
     * @throws TransfluxValidationException if {@code registeredConditionId} is {@code null} or blank
     */
    DataTriggerDef<T, C> condition(String registeredConditionId);

    /**
     * {@link Identifiable} overload of {@link #condition(String)} — delegates via
     * {@link Identifiable#getId()}.
     *
     * @param registeredCondition an identifiable supplying the condition id
     *
     * @return this trigger def for chaining
     *
     * @throws TransfluxValidationException if {@code registeredCondition} is {@code null}
     */
    DataTriggerDef<T, C> condition(Identifiable registeredCondition);

    /**
     * Sets the gate to an inline SpEL expression with an auto-derived id. The id is computed
     * deterministically from the expression text and the descriptor's position. Use
     * {@link #condition(String, String)} when an explicit id is preferred.
     *
     * @param expression the SpEL expression text; never {@code null} or blank
     *
     * @return this trigger def for chaining
     *
     * @throws TransfluxValidationException if {@code expression} is {@code null} or blank
     */
    DataTriggerDef<T, C> conditionExpression(String expression);

    /**
     * Sets the gate to a {@link Condition} instance under the given id.
     *
     * @param id the condition id; never {@code null} or blank
     * @param condition the condition instance; never {@code null}
     *
     * @return this trigger def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank or
     *         {@code condition} is {@code null}
     */
    DataTriggerDef<T, C> condition(String id, Condition<T, C> condition);

    /**
     * {@link Identifiable} overload of {@link #condition(String, Condition)}.
     *
     * @param conditionIdentifiable an identifiable supplying the condition id
     * @param condition the condition instance
     *
     * @return this trigger def for chaining
     */
    DataTriggerDef<T, C> condition(Identifiable conditionIdentifiable, Condition<T, C> condition);

    /**
     * Sets the gate to a {@link Condition} class under the given id. The class is reflectively
     * instantiated through its public no-arg constructor when the state machine is built.
     *
     * @param id the condition id; never {@code null} or blank
     * @param conditionClass the condition class; never {@code null}
     *
     * @return this trigger def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank or
     *         {@code conditionClass} is {@code null}
     */
    DataTriggerDef<T, C> condition(String id, Class<? extends Condition<T, C>> conditionClass);

    /**
     * {@link Identifiable} overload of {@link #condition(String, Class)}.
     *
     * @param conditionIdentifiable an identifiable supplying the condition id
     * @param conditionClass the condition class
     *
     * @return this trigger def for chaining
     */
    DataTriggerDef<T, C> condition(Identifiable conditionIdentifiable, Class<? extends Condition<T, C>> conditionClass);

    /**
     * Sets the gate to a {@link BiPredicate} over {@code (entity, context)} under the given id. The
     * predicate is adapted into a {@link Condition} that ignores the transition view.
     *
     * @param id the condition id; never {@code null} or blank
     * @param predicate the predicate; never {@code null}
     *
     * @return this trigger def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank or
     *         {@code predicate} is {@code null}
     */
    DataTriggerDef<T, C> condition(String id, BiPredicate<T, C> predicate);

    /**
     * {@link Identifiable} overload of {@link #condition(String, BiPredicate)}.
     *
     * @param conditionIdentifiable an identifiable supplying the condition id
     * @param predicate the predicate
     *
     * @return this trigger def for chaining
     */
    DataTriggerDef<T, C> condition(Identifiable conditionIdentifiable, BiPredicate<T, C> predicate);

    /**
     * Convenience overload of {@link #condition(String, BiPredicate)} accepting an entity-only
     * {@link Predicate}; the context is ignored at evaluation time.
     *
     * @param id the condition id; never {@code null} or blank
     * @param predicate the entity predicate; never {@code null}
     *
     * @return this trigger def for chaining
     */
    DataTriggerDef<T, C> condition(String id, Predicate<T> predicate);

    /**
     * {@link Identifiable} overload of {@link #condition(String, Predicate)}.
     *
     * @param conditionIdentifiable an identifiable supplying the condition id
     * @param predicate the entity predicate
     *
     * @return this trigger def for chaining
     */
    DataTriggerDef<T, C> condition(Identifiable conditionIdentifiable, Predicate<T> predicate);

    /**
     * Sets the gate to a SpEL expression under the given id.
     *
     * @param id the condition id; never {@code null} or blank
     * @param expression the SpEL expression text; never {@code null} or blank
     *
     * @return this trigger def for chaining
     *
     * @throws TransfluxValidationException if {@code id} or {@code expression} is {@code null} or blank
     */
    DataTriggerDef<T, C> condition(String id, String expression);

    /**
     * {@link Identifiable} overload of {@link #condition(String, String)}.
     *
     * @param conditionIdentifiable an identifiable supplying the condition id
     * @param expression the SpEL expression text
     *
     * @return this trigger def for chaining
     */
    DataTriggerDef<T, C> condition(Identifiable conditionIdentifiable, String expression);
}
