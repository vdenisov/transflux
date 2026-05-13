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
 * A simple, single-step operation that can be executed during state transitions.
 * <p>
 * SimpleOperation represents the most basic form of operation in Transflux, containing
 * a single unit of business logic that is executed atomically during a state transition.
 * Unlike composite operations, simple operations cannot be broken down into smaller steps.
 *
 * <p><b>Note:</b> This is currently a placeholder implementation that will be enhanced
 * with full operation execution capabilities in Phase 1.5.
 *
 * @param <T> the type of entity this operation acts upon
 * @param <C> the type of context used during operation execution
 */
public class SimpleOperation<T, C> implements Operation<T, C> {
    private final String id;

    /**
     * Package-private constructor for creating simple operations.
     * <p>
     * This constructor will be enhanced in Phase 1.5 to accept operation definitions
     * and properly initialize the operation with its execution logic.
     *
     * @param id the unique identifier for this operation
     */
    SimpleOperation(String id) {
        this.id = id;
    }

    @Override
    public String getId() {
        return id;
    }
}
