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

package org.transflux.core.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.transflux.core.Identifiable;
import org.transflux.core.exception.TransfluxValidationException;
import org.transflux.core.trigger.EventTriggerDef;

import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.transflux.core.Preconditions.requireNotBlank;
import static org.transflux.core.Preconditions.requireNotNull;
import static org.transflux.core.impl.ReflectionUtils.instantiateNoArg;
import static org.transflux.core.impl.ValidationUtils.warnIfSet;

/**
 * Default {@link EventTriggerDef} implementation.
 * <p>
 * Captures the listened-for event id and the optional payload filter during configuration, then
 * resolves them into a runtime {@link EventTriggerImpl} at build time through
 * {@link #buildBoundTrigger()}. The enclosing transition's id is captured at construction so the
 * resulting trigger knows which transition it fires. The event id is mandatory; its absence is
 * reported when {@link #buildBoundTrigger()} runs.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
final class EventTriggerDefImpl<T, C> extends TriggerDefImpl<T, C, EventTriggerDefImpl<T, C>>
    implements EventTriggerDef<T, C> {

    private static final Logger log = LoggerFactory.getLogger(EventTriggerDefImpl.class);

    private String eventId;
    private Supplier<EventFilter<T>> filterSource;

    EventTriggerDefImpl(String id, TransitionDefImpl<T, C> owner) {
        super(id, "event trigger", owner);
    }

    @Override
    public EventTriggerDef<T, C> onEvent(String eventId) {
        requireConfigurerActive("onEvent");
        requireNotBlank(eventId, "Event ID");
        warnIfSet(this.eventId, eventId, "Event ID", log);
        this.eventId = eventId;
        return this;
    }

    @Override
    public EventTriggerDef<T, C> onEvent(Identifiable event) {
        requireNotNull(event, "Event identifiable");
        return onEvent(event.getId());
    }

    @Override
    public EventTriggerDef<T, C> filter(BiPredicate<Object, T> filter) {
        requireConfigurerActive("filter");
        requireNotNull(filter, "Filter");
        return setFilter(() -> (eventData, entity, context) -> filter.test(eventData, entity));
    }

    @Override
    public EventTriggerDef<T, C> filter(Predicate<Object> filter) {
        requireConfigurerActive("filter");
        requireNotNull(filter, "Filter");
        return setFilter(() -> (eventData, entity, context) -> filter.test(eventData));
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public EventTriggerDef<T, C> filter(Class<? extends BiPredicate<Object, T>> filterClass) {
        requireConfigurerActive("filter");
        requireNotNull(filterClass, "Filter class");
        return setFilter(() -> {
            BiPredicate<Object, T> predicate =
                (BiPredicate<Object, T>) instantiateNoArg((Class) filterClass, "Filter");
            return (eventData, entity, context) -> predicate.test(eventData, entity);
        });
    }

    @Override
    public EventTriggerDef<T, C> filterExpression(String expression) {
        requireConfigurerActive("filterExpression");
        requireNotBlank(expression, "Expression");
        return setFilter(() -> (eventData, entity, context) ->
            SpelConditionEvaluator.shared().evaluateEventFilter(expression, entity, eventData, context));
    }

    /**
     * Resolves this trigger's event id and filter into a runtime {@link EventTriggerImpl}.
     *
     * @return the runtime trigger
     *
     * @throws TransfluxValidationException if no event id was declared
     */
    EventTriggerImpl<T> buildBoundTrigger() {
        if (eventId == null) {
            throw new TransfluxValidationException(
                "Event trigger '" + getId() + "' declares no event id; call onEvent(...) in its configurer");
        }
        EventFilter<T> resolved = resolveFilter();
        return new EventTriggerImpl<>(getId(), getName(), getDescription(), transitionId(), eventId, resolved);
    }

    /**
     * Realizes the declared filter. A trigger with no declared filter fires on every published
     * event of its id; a class-form filter is instantiated here rather than at declaration time.
     *
     * @return the resolved filter, never {@code null}
     */
    private EventFilter<T> resolveFilter() {
        if (filterSource == null) {
            return (eventData, entity, context) -> true;
        }
        return filterSource.get();
    }

    private EventTriggerDef<T, C> setFilter(Supplier<EventFilter<T>> incoming) {
        warnIfSet(filterSource != null, "Filter", defLabel(), log);
        this.filterSource = incoming;
        return this;
    }
}
