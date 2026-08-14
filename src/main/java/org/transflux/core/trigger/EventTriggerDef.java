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
import org.transflux.core.exception.TransfluxValidationException;

import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Definition builder for an event trigger attached to a transition.
 * <p>
 * An event trigger names the event id it listens for and, optionally, a filter that further gates
 * firing against the event payload. When the host calls
 * {@code entity(e).processEvent(eventId, eventData)}, the framework selects the triggers whose
 * {@link #onEvent(String) event id} equals the published id, evaluates each candidate's filter, and
 * fires the first eligible match.
 *
 * <p>The filter receives the published payload as its first argument and the entity as its second.
 * Four forms are offered: a {@link BiPredicate} over {@code (eventData, entity)}, an entity-blind
 * {@link Predicate} over {@code eventData}, a predicate class, and an SpEL expression. In the SpEL
 * form the entity is the expression root, the payload is bound as {@code #event}, and the
 * host-supplied context (if any) is bound as {@code #context}. A trigger declared with no filter
 * fires on every published event of its id.
 *
 * <p>Event triggers are declared inside a transition's configurer through
 * {@code TransitionDef.addEventTrigger(id, configurer)}. The configurer grants temporary write
 * access to this def; once it returns the def is inert and any further mutation throws
 * {@link TransfluxValidationException}. The event id is mandatory and its absence is reported when
 * the state machine is built.
 *
 * @param <T> the entity type managed by the enclosing state machine
 * @param <C> the host-supplied context type carried through transition execution
 */
public interface EventTriggerDef<T, C> extends Identifiable {

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
    EventTriggerDef<T, C> withName(String name);

    /**
     * Sets the description of this trigger.
     *
     * @param description the description; may be {@code null} to clear
     *
     * @return this trigger def for chaining
     */
    EventTriggerDef<T, C> withDescription(String description);

    /**
     * Declares the id of the event this trigger listens for.
     *
     * @param eventId the event id; never {@code null} or blank
     *
     * @return this trigger def for chaining
     *
     * @throws TransfluxValidationException if {@code eventId} is {@code null} or blank
     */
    EventTriggerDef<T, C> onEvent(String eventId);

    /**
     * {@link Identifiable} overload of {@link #onEvent(String)} — delegates via
     * {@link Identifiable#getId()}.
     *
     * @param event an identifiable supplying the event id
     *
     * @return this trigger def for chaining
     *
     * @throws TransfluxValidationException if {@code event} is {@code null}
     */
    EventTriggerDef<T, C> onEvent(Identifiable event);

    /**
     * Sets a filter over {@code (eventData, entity)} that further gates firing once the event id
     * matches. Replaces any filter previously set on this trigger.
     *
     * @param filter the filter; never {@code null}
     *
     * @return this trigger def for chaining
     *
     * @throws TransfluxValidationException if {@code filter} is {@code null}
     */
    EventTriggerDef<T, C> filter(BiPredicate<Object, T> filter);

    /**
     * Convenience overload of {@link #filter(BiPredicate)} accepting an entity-blind
     * {@link Predicate} over the event payload; the entity is ignored at evaluation time.
     *
     * @param filter the payload predicate; never {@code null}
     *
     * @return this trigger def for chaining
     *
     * @throws TransfluxValidationException if {@code filter} is {@code null}
     */
    EventTriggerDef<T, C> filter(Predicate<Object> filter);

    /**
     * Sets a filter built from a {@link BiPredicate} class over {@code (eventData, entity)}. The
     * class is reflectively instantiated through its public no-arg constructor when the state
     * machine is built.
     *
     * @param filterClass the filter class; never {@code null}
     *
     * @return this trigger def for chaining
     *
     * @throws TransfluxValidationException if {@code filterClass} is {@code null}
     */
    EventTriggerDef<T, C> filter(Class<? extends BiPredicate<Object, T>> filterClass);

    /**
     * Sets a filter expressed as SpEL. The entity is the expression root, the event payload is
     * bound as {@code #event}, and the host-supplied context (if any) is bound as {@code #context}.
     * The expression must evaluate to a boolean.
     *
     * @param expression the SpEL expression text; never {@code null} or blank
     *
     * @return this trigger def for chaining
     *
     * @throws TransfluxValidationException if {@code expression} is {@code null} or blank
     */
    EventTriggerDef<T, C> filterExpression(String expression);
}
