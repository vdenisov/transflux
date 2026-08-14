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

package org.transflux.core.state;

/**
 * Identifies which side of a state change a {@link StateListener} was notified for.
 * <p>
 * A listener attached to a single state through {@code onEntry} / {@code onExit} already knows
 * its own phase; the discriminator matters to listeners registered globally, which observe both
 * hooks across every state.
 */
public enum StatePhase {

    /** The entity is entering the state, and the transition has already been committed. */
    ENTRY,

    /** The entity is leaving the state, and the transition has not yet run its operation. */
    EXIT
}
