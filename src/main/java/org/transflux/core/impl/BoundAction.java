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

import org.transflux.core.operation.*;

/**
 * Sealed marker type implemented by both {@link BoundStep} and {@link BoundOperation},
 * letting a composite operation executor iterate a single ordered list of heterogeneous
 * actions and dispatch each one against its bound runtime.
 *
 * <p>This is framework-internal infrastructure; user code should not construct or inspect
 * bound actions directly.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
public sealed interface BoundAction<T, C> permits BoundStep, BoundOperation {

    /**
     * Returns the framework-owned id of this action.
     *
     * @return the action id; never {@code null} or blank
     */
    String id();
}
