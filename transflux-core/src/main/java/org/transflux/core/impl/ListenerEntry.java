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

import static org.transflux.core.Preconditions.requireNotBlank;
import static org.transflux.core.Preconditions.requireNotNull;

/**
 * One attachment at a listener hook: a listener declared in place, or the id of one declared
 * elsewhere.
 * <p>
 * Both shapes share a list so that a hook notifies in declaration order however each of its
 * listeners was written - a reference declared between two inline listeners is delivered between
 * them. A reference carries the id alone; what it resolves to is decided at build, which is what
 * lets it name a listener the same owner declares further down.
 *
 * @param id the listener's id - its own when declared here, the target's when a reference
 * @param declared the listener declared at this hook, or {@code null} when this is a reference
 * @param <D> the def type of the category this hook belongs to
 */
record ListenerEntry<D>(String id, D declared) {

    ListenerEntry {
        requireNotBlank(id, "Listener ID");
    }

    static <D> ListenerEntry<D> declared(String id, D def) {
        requireNotNull(def, "Listener def");
        return new ListenerEntry<>(id, def);
    }

    static <D> ListenerEntry<D> reference(String id) {
        return new ListenerEntry<>(id, null);
    }

    boolean isReference() {
        return declared == null;
    }
}
