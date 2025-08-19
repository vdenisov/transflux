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
 * Definition implementation class for transitions between states in a state machine.
 * <p>
 * TransitionDef represents the configuration and metadata for a transition,
 * including the unique identifier, source state, and target state. This class
 * is used internally during state machine construction to store transition
 * definitions before they are converted into concrete {@link Transition} instances.
 * 
 * <p>TransitionDef instances are created internally by the framework when
 * transitions are registered through the fluent API and should not be
 * instantiated directly by client code.
 * 
 * @param <T> the type of business entity used by the state machine this transition belongs to
 */
class TransitionDefImpl<T> implements TransitionDef<T> {
    private final String id;
    private final String sourceStateId;
    private final String targetStateId;

    /**
     * Constructs a new TransitionDefImpl with the specified parameters.
     * <p>
     * This package-private constructor is used internally by the framework
     * to create transition definitions during state machine construction.
     * 
     * @param id the unique identifier for this transition
     * @param sourceStateId the ID of the source state
     * @param targetStateId the ID of the target state
     * @throws TransfluxValidationException if any parameter is null or blank
     */
    TransitionDefImpl(String id, String sourceStateId, String targetStateId) {
        if (id == null || id.isBlank()) {
            throw new TransfluxValidationException("Transition ID cannot be null or blank");
        }

        if (sourceStateId == null || sourceStateId.isBlank()) {
            throw new TransfluxValidationException("Source state ID cannot be null or blank");
        }

        if (targetStateId == null || targetStateId.isBlank()) {
            throw new TransfluxValidationException("Target state ID cannot be null or blank");
        }

        this.id = id;
        this.sourceStateId = sourceStateId;
        this.targetStateId = targetStateId;
    }

    /**
     * Returns the unique identifier of this transition.
     * 
     * @return the transition ID
     */
    public String getId() {
        return id;
    }

    /**
     * Returns the identifier of the source state for this transition.
     * 
     * @return the source state ID
     */
    public String getSourceStateId() {
        return sourceStateId;
    }

    /**
     * Returns the identifier of the target state for this transition.
     * 
     * @return the target state ID
     */
    public String getTargetStateId() {
        return targetStateId;
    }

    @Override
    public Transition<T> build() {
        return new TransitionImpl<>(this);
    }

    @Override
    public String toString() {
        return "TransitionDefImpl{" +
            "id='" + id + '\'' +
            ", sourceStateId='" + sourceStateId + '\'' +
            ", targetStateId='" + targetStateId + '\'' +
            '}';
    }
}
