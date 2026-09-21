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

/**
 * Runtime view of an event trigger — a transition initiated by a host-published event.
 * <p>
 * An event trigger declares the event id it listens for. The host pushes events into the state
 * machine through {@code entity(e).processEvent(eventId, eventData)}; the framework selects the
 * triggers whose {@link #getEventId()} equals the published id, applies each candidate's optional
 * payload filter, and fires the first eligible match. The library performs no background polling
 * and no event subscription of its own — event delivery is host-driven.
 */
public interface EventTrigger extends Trigger {

    /**
     * Returns the id of the event this trigger listens for.
     *
     * @return the event id; never {@code null} or blank
     */
    String getEventId();
}
