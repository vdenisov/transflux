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
 * Def-side anchor for an operation attached to a transition.
 * <p>
 * {@code OperationDef} carries the framework-owned identity and metadata that pure
 * {@link Operation} executables do not. Two concrete sub-types exist: {@link SimpleOperationDef}
 * (a single {@code Operation} class or instance) and {@link CompositeOperationDef}
 * (an ordered list of bound steps).
 *
 * <p>The {@code id} is mandatory and must be unique within its enclosing transition.
 * {@code name} and {@code description} are optional metadata for diagnostics and tooling.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
public interface OperationDef<T, C> extends Identifiable {

    /**
     * Returns the unique identifier of this operation def.
     *
     * @return the operation id; never {@code null} or blank
     */
    @Override
    String getId();

    /**
     * Returns the human-readable name of this operation, or {@code null} when unset.
     *
     * @return the optional operation name
     */
    String getName();

    /**
     * Returns the description of this operation, or {@code null} when unset.
     *
     * @return the optional operation description
     */
    String getDescription();

    /**
     * Sets the human-readable name of this operation.
     *
     * @param name the name; may be {@code null} to clear
     *
     * @return this def for chaining
     */
    OperationDef<T, C> withName(String name);

    /**
     * Sets the description of this operation.
     *
     * @param description the description; may be {@code null} to clear
     *
     * @return this def for chaining
     */
    OperationDef<T, C> withDescription(String description);
}
