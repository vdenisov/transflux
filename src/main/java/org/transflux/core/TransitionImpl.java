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
 * Default implementation of the {@link Transition} interface.
 * <p>
 * This implementation provides a straightforward concrete transition that can be constructed
 * from a {@link TransitionDef} definition. It stores the transition's identifier, source
 * state ID, and target state ID as immutable properties and provides standard implementations
 * for equality and hash code based on the transition identifier.
 * 
 * <p>{@code TransitionImpl} instances are typically created internally by the framework during
 * state machine construction and should not be instantiated directly by client code.
 * 
 */
public class TransitionImpl implements Transition {
    private final String id;
    private final String sourceStateId;
    private final String targetStateId;

    /**
     * Constructs a new TransitionImpl from the provided transition definition.
     * <p>
     * This package-private constructor is used internally by the framework to create
     * transition instances during state machine construction. It validates the transition
     * definition and extracts the necessary properties.
     * 
     * @param transitionDef the transition definition to construct this transition from
     *
     * @throws TransfluxValidationException if the transition definition is null or has invalid properties
     */
    TransitionImpl(TransitionDef transitionDef) {
        validateTransitionDef(transitionDef);
        this.id = transitionDef.getId();
        this.sourceStateId = transitionDef.getSourceStateId();
        this.targetStateId = transitionDef.getTargetStateId();
    }

    /**
     * Validates the provided transition definition to ensure it contains valid properties.
     * 
     * @param transitionDef the transition definition to validate
     *
     * @throws TransfluxValidationException if the transition definition is null or has invalid properties
     */
    private void validateTransitionDef(TransitionDef transitionDef) {
        if (transitionDef == null) {
            throw new TransfluxValidationException("Transition definition cannot be null");
        }

        if (transitionDef.getId() == null || transitionDef.getId().isBlank()) {
            throw new TransfluxValidationException("Transition ID cannot be null or blank");
        }

        if (transitionDef.getSourceStateId() == null || transitionDef.getSourceStateId().isBlank()) {
            throw new TransfluxValidationException("Source state ID cannot be null or blank");
        }

        if (transitionDef.getTargetStateId() == null || transitionDef.getTargetStateId().isBlank()) {
            throw new TransfluxValidationException("Target state ID cannot be null or blank");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getId() {
        return id;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getSourceStateId() {
        return sourceStateId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getTargetStateId() {
        return targetStateId;
    }

    @Override
    public final boolean equals(Object o) {
        if (!(o instanceof TransitionImpl)) return false;

        TransitionImpl that = (TransitionImpl) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
