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

import static org.transflux.core.ValidationUtils.requireNotBlank;
import static org.transflux.core.ValidationUtils.requireNotNull;

/**
 * Package-private runtime currency that pairs a pure {@link Operation} with framework-owned
 * identity and metadata.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
record BoundOperation<T, C>(String id, String name, String description, Operation<T, C> operation) {

    BoundOperation {
        requireNotBlank(id, "Bound operation ID");
        requireNotNull(operation, "Bound operation");
    }

    static <T, C> BoundOperation<T, C> of(String id, String name, String description,
                                          Operation<T, C> operation) {
        return new BoundOperation<>(id, name, description, operation);
    }
}
