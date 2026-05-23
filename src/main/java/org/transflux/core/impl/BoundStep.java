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

import static org.transflux.core.impl.ValidationUtils.requireNotBlank;
import static org.transflux.core.impl.ValidationUtils.requireNotNull;

/**
 * Runtime binder that pairs a pure {@link Step} with framework-owned identity.
 *
 * <p>This is framework-internal infrastructure; user code should not construct or
 * inspect bound steps directly.
 *
 * @param id the framework-owned step id; never {@code null} or blank
 * @param step the bound {@link Step} executable; never {@code null}
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
record BoundStep<T, C>(String id, Step<T, C> step) implements BoundAction<T, C> {

    public BoundStep {
        requireNotBlank(id, "Bound step ID");
        requireNotNull(step, "Bound step");
    }

    /**
     * Convenience factory equivalent to the canonical constructor.
     *
     * @param id the step id
     * @param step the step executable
     * @param <T> the entity type
     * @param <C> the context type
     *
     * @return a fresh bound step
     */
    public static <T, C> BoundStep<T, C> of(String id, Step<T, C> step) {
        return new BoundStep<>(id, step);
    }
}
