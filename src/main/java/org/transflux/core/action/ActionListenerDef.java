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

package org.transflux.core.action;

import org.transflux.core.ListenerDef;
import org.transflux.core.exception.TransfluxValidationException;

/**
 * Definition builder for an action listener.
 * <p>
 * The def carries the listener's identity and optional metadata; the {@link ActionListener} itself
 * is a pure functional contract with no identity of its own, so the same implementation can be
 * attached any number of times under different ids. A listener is <b>required</b> - its absence is
 * reported when the state machine is built.
 *
 * <p>This def is reached through the configurer overloads of {@code ActionDef.onStart(...)} /
 * {@code onComplete(...)} / {@code onError(...)} and their state-machine-wide siblings. The
 * configurer grants temporary write access; once it returns the def is inert and any further
 * mutation throws {@link TransfluxValidationException}. The shorter overloads that take a listener
 * instance directly are equivalent to a configurer whose only call is {@link #using}.
 *
 * <p><b>Async volume.</b> An action listener is the high-volume hook: it is notified twice per
 * action invocation, at every nesting depth, where a transition and its two states notify at most
 * four times per transition. A global listener declared {@link #withAsync() async} therefore submits
 * twice for every action a transition runs, and a body dispatching an action a thousand times in a
 * loop submits two thousand notifications from one transition - enough to fill the default queue,
 * and under the default policy to lose the overflow. Keep such a listener synchronous, or give one
 * that must not lose notifications {@code CALLER_RUNS} or {@code BLOCK}.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the context type the observed action runs against
 */
public interface ActionListenerDef<T, C>
    extends ListenerDef<ActionListener<T, C>, ActionListenerDef<T, C>> {
}
