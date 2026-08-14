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

import org.transflux.core.trigger.EventTrigger;

import static org.transflux.core.Preconditions.requireNotBlank;
import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Runtime event trigger paired with its resolved payload filter.
 * <p>
 * Implements the public {@link EventTrigger} catalog view. The filter is resolved at build time;
 * {@link #matches(Object, Object, Object)} is consulted during {@code processEvent} dispatch after
 * the event id has matched. The accessor is package-private so only the dispatch path reads it.
 *
 * @param <T> the entity type the surrounding state machine manages
 */
final class EventTriggerImpl<T> extends TriggerImpl implements EventTrigger {

    private final String eventId;
    private final EventFilter<T> filter;

    EventTriggerImpl(String id, String name, String description, String transitionId,
                     String eventId, EventFilter<T> filter) {
        super(id, name, description, transitionId);
        requireNotBlank(eventId, "Trigger event ID");
        requireNotNull(filter, "Trigger filter");
        this.eventId = eventId;
        this.filter = filter;
    }

    @Override
    public String getEventId() {
        return eventId;
    }

    @Override
    String kindPhrase() {
        return "an event trigger";
    }

    @Override
    String dispatchEntryPoint() {
        return "processEvent(eventId, eventData[, context])";
    }

    /**
     * Evaluates this trigger's filter against the published payload and the current scope.
     *
     * @param eventData the published event payload; may be {@code null}
     * @param entity the entity the event is being processed for; never {@code null}
     * @param context the host-supplied firing context; may be {@code null}
     *
     * @return {@code true} if the filter holds and the trigger may fire
     */
    boolean matches(Object eventData, T entity, Object context) {
        return filter.test(eventData, entity, context);
    }
}
