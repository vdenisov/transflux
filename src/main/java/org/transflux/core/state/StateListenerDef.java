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

import org.transflux.core.Identifiable;
import org.transflux.core.exception.TransfluxValidationException;

/**
 * Definition builder for a state listener.
 * <p>
 * The def carries the listener's identity and optional metadata; the {@link StateListener} itself
 * is a pure functional contract with no identity of its own, so the same implementation can be
 * attached any number of times under different ids. A listener is <b>required</b> — its absence is
 * reported when the state machine is built.
 *
 * <p>This def is reached through the configurer overloads of {@code StateDef.onEntry(...)} /
 * {@code StateDef.onExit(...)} and their state-machine-wide siblings. The configurer grants
 * temporary write access; once it returns the def is inert and any further mutation throws
 * {@link TransfluxValidationException}. The shorter overloads that take a listener instance or
 * class directly are equivalent to a configurer whose only call is {@link #using}.
 *
 * @param <T> the entity type the surrounding state machine manages
 */
public interface StateListenerDef<T> extends Identifiable {

    /**
     * Returns this listener's identifier.
     *
     * @return the listener id; never {@code null} or blank
     */
    @Override
    String getId();

    /**
     * Returns this listener's optional human-readable name.
     *
     * @return the name, or {@code null} if none was set
     */
    String getName();

    /**
     * Returns this listener's optional description.
     *
     * @return the description, or {@code null} if none was set
     */
    String getDescription();

    /**
     * Sets the human-readable name for this listener.
     *
     * @param name the human-readable name
     *
     * @return this listener def for chaining
     */
    StateListenerDef<T> withName(String name);

    /**
     * Sets the description for this listener.
     *
     * @param description the description
     *
     * @return this listener def for chaining
     */
    StateListenerDef<T> withDescription(String description);

    /**
     * Attaches a pre-built listener instance. Mutually exclusive with {@link #using(Class)};
     * re-declaring replaces the previous declaration.
     *
     * @param listener the listener instance; never {@code null}
     *
     * @return this listener def for chaining
     *
     * @throws TransfluxValidationException if {@code listener} is {@code null}
     */
    StateListenerDef<T> using(StateListener<T> listener);

    /**
     * Attaches a listener class, instantiated through its public no-arg constructor when the state
     * machine is built. Mutually exclusive with {@link #using(StateListener)}; re-declaring
     * replaces the previous declaration.
     *
     * @param listenerClass the listener class; never {@code null}
     *
     * @return this listener def for chaining
     *
     * @throws TransfluxValidationException if {@code listenerClass} is {@code null}
     */
    StateListenerDef<T> using(Class<? extends StateListener<T>> listenerClass);
}
