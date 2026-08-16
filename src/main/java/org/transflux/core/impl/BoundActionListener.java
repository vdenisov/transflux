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

import org.transflux.core.action.ActionListener;

import static org.transflux.core.Preconditions.requireNotBlank;
import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Runtime binder that pairs a pure {@link ActionListener} with framework-owned identity and
 * metadata. The id is what diagnostics name when a listener misbehaves.
 *
 * @param id the framework-owned listener id; never {@code null} or blank
 * @param name the optional human-readable name; may be {@code null}
 * @param description the optional description; may be {@code null}
 * @param listener the bound {@link ActionListener} executable; never {@code null}
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the context type the observed action runs against
 */
record BoundActionListener<T, C>(String id, String name, String description,
                                 ActionListener<T, C> listener) {

    BoundActionListener {
        requireNotBlank(id, "Bound action listener ID");
        requireNotNull(listener, "Bound action listener");
    }
}
