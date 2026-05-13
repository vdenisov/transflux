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
 * Default implementation of the {@link State} interface.
 * <p>
 * This implementation provides a straightforward concrete state that can be constructed
 * from a {@link StateDef} definition. It stores the state's identifier, name, and
 * description as immutable properties and provides standard implementations for
 * equality and hash code based on the state identifier.
 * 
 * <p>StateImpl instances are typically created internally by the framework during
 * state machine construction and should not be instantiated directly by client code.
 * 
 */
public class StateImpl<T> implements State<T> {
    private final String id;
    private final String name;
    private final String description;

    /**
     * Constructs a new {@code StateImpl} from the provided state definition.
     * <p>
     * This package-private constructor is used internally by the framework to create
     * state instances during state machine construction. It validates the state
     * definition and extracts the necessary properties.
     * 
     * @param stateDef the state definition to construct this state from
     *
     * @throws TransfluxValidationException if the state definition is null or has invalid properties
     */
    StateImpl(StateDefImpl<T, ?> stateDef) {
        validateStateDef(stateDef);
        this.id = stateDef.getId();
        this.name = stateDef.getName();
        this.description = stateDef.getDescription();
    }

    /**
     * Validates the provided state definition to ensure it contains valid properties.
     * 
     * @param stateDef the state definition to validate
     *
     * @throws TransfluxValidationException if the state definition is null or has invalid properties
     */
    private void validateStateDef(StateDefImpl<T, ?> stateDef) {
        if (stateDef == null) {
            throw new TransfluxValidationException("State definition cannot be null");
        }

        if (stateDef.getId() == null || stateDef.getId().isBlank()) {
            throw new TransfluxValidationException("State ID cannot be null or blank");
        }
    }

    /**
     * {@inheritDoc}
     */
    public String getId() {
        return id;
    }

    /**
     * {@inheritDoc}
     */
    public String getName() {
        return name;
    }

    /**
     * {@inheritDoc}
     */
    public String getDescription() {
        return description;
    }

    @Override
    public String toString() {
        return "StateImpl{" +
            "id='" + id + '\'' +
            ", name='" + name + '\'' +
            ", description='" + description + '\'' +
            '}';
    }

    @Override
    public final boolean equals(Object o) {
        if (!(o instanceof StateImpl)) return false;

        StateImpl<?> state = (StateImpl<?>) o;
        return id.equals(state.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}

