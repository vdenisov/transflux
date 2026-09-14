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

import org.transflux.core.ListenerDef;
import org.transflux.core.exception.TransfluxValidationException;

/**
 * Definition builder for a transition listener.
 * <p>
 * The def carries the listener's identity and optional metadata; the {@link TransitionListener}
 * itself is a pure functional contract with no identity of its own, so the same implementation can
 * be attached any number of times under different ids. A listener is <b>required</b> — its absence
 * is reported when the state machine is built.
 *
 * <p>This def is reached through the configurer overloads of {@code TransitionDef.onStart(...)} /
 * {@code onComplete(...)} / {@code onError(...)} and their state-machine-wide siblings. The
 * configurer grants temporary write access; once it returns the def is inert and any further
 * mutation throws {@link TransfluxValidationException}. The shorter overloads that take a listener
 * instance directly are equivalent to a configurer whose only call is {@link #using}.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
public interface TransitionListenerDef<T, C>
    extends ListenerDef<TransitionListener<T, C>, TransitionListenerDef<T, C>> {
}
