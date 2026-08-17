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

/**
 * A transition between two states — the path an entity may take out of one state and into
 * another, together with the operation, conditions, triggers and listeners declared on it.
 * <p>
 * This is the read-only runtime view: it answers what the transition <em>is</em>, and nothing
 * it exposes can run work or change anything. Its accessors are stable for the lifetime of the
 * enclosing state machine, so a view handed out once stays valid.
 *
 * <p>Transitions are the core mechanism through which entities move through their lifecycle,
 * coordinating business logic, error handling, and compensation patterns similar to the Saga
 * pattern.
 *
 * <p><b>Where dispatch lives.</b> Running another action by id is not a property of a transition;
 * it needs a live execution — the captured entity and context, the executed-path recorder, and
 * the compensation stack. That capability is {@link ExecutingTransition}, which extends this
 * interface and is handed to an {@link org.transflux.core.action.Action Action}'s body and to
 * nothing else. Conditions and listeners receive this interface instead, so code positioned
 * where dispatched work could not be rolled back cannot dispatch it in the first place.
 *
 * <p>Configuration of transitions (actions, conditions, triggers, listeners) is done on
 * {@link TransitionDef} during state machine construction, not on this runtime interface.
 */
public interface Transition extends Identifiable {

    /**
     * Returns the identifier of the source state from which this transition can be initiated.
     * <p>
     * The source state ID must correspond to a state defined in the state machine.
     * Transitions can only be executed when the entity is currently in the source state.
     *
     * @return the source state identifier; never {@code null} or blank
     */
    String getSourceStateId();

    /**
     * Returns the identifier of the target state to which the entity will transition.
     * <p>
     * The target state ID must correspond to a state defined in the state machine.
     * Upon successful completion of the transition, the entity will be in the target state.
     *
     * @return the target state identifier; never {@code null} or blank
     */
    String getTargetStateId();
}
