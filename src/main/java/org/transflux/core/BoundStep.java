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

package org.transflux.core;

/**
 * Package-private runtime currency that pairs a pure {@link Step} with framework-owned
 * identity.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
final class BoundStep<T, C> {
    private final String id;
    private final Step<T, C> step;

    private BoundStep(String id, Step<T, C> step) {
        this.id = id;
        this.step = step;
    }

    static <T, C> BoundStep<T, C> of(String id, Step<T, C> step) {
        if (id == null || id.isBlank()) {
            throw new TransfluxValidationException("Bound step ID cannot be null or blank");
        }
        if (step == null) {
            throw new TransfluxValidationException("Bound step cannot be null");
        }
        return new BoundStep<>(id, step);
    }

    String getId() {
        return id;
    }

    Step<T, C> getStep() {
        return step;
    }
}
