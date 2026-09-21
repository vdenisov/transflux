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

import java.util.List;

/**
 * Runtime view of a trigger, as surfaced by the state machine's trigger catalog.
 * <p>
 * A trigger is a named handle that initiates a transition. It carries identity and optional
 * metadata; the executable gating (if any) lives behind the handle and is applied when the
 * trigger fires. One trigger may sit on several transitions - it is declared in place on one, or
 * registered once and attached by id wherever it is wanted - and {@link #getTransitionIds()}
 * reports them all.
 *
 * <p>Triggers are enumerated through the catalog methods on
 * {@link org.transflux.core.StateMachine} and, for manual triggers, invoked through
 * {@code entity(e).fire(triggerId)}.
 */
public interface Trigger {

    /**
     * Returns the unique identifier of this trigger. Trigger ids are unique among the triggers of
     * a single state machine.
     *
     * @return the trigger id; never {@code null} or blank
     */
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
     * Returns the transitions this trigger is attached to, in the order they declared it.
     * <p>
     * A trigger declared in place on a transition reports that one. A registered trigger reports
     * every transition that attached it by id - it is still one trigger, listed once in the
     * catalog - and reports none at all when nothing attached it.
     *
     * @return the transition ids; never {@code null}, possibly empty
     */
    List<String> getTransitionIds();
}
