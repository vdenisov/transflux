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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BiPredicate;
import java.util.function.Predicate;

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
 * @param <T> the entity type managed by the enclosing state machine
 * @param <C> the host-supplied context type carried through transition execution
 */
class TransitionDefImpl<T, C> implements TransitionDef<T, C> {
    private static final Logger log = LoggerFactory.getLogger(TransitionDefImpl.class);

    private final String id;
    private final String sourceStateId;
    private final String targetStateId;

    private SimpleOperationDefImpl<T, C> operationDef;

    /**
     * Constructs a new TransitionDefImpl with the specified parameters.
     * <p>
     * This package-private constructor is used internally by the framework
     * to create transition definitions during state machine construction.
     *
     * @param id the unique identifier for this transition
     * @param sourceStateId the ID of the source state
     * @param targetStateId the ID of the target state
     *
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
    @Override
    public String getId() {
        return id;
    }

    /**
     * Returns the identifier of the source state for this transition.
     *
     * @return the source state ID
     */
    @Override
    public String getSourceStateId() {
        return sourceStateId;
    }

    /**
     * Returns the identifier of the target state for this transition.
     *
     * @return the target state ID
     */
    @Override
    public String getTargetStateId() {
        return targetStateId;
    }

    Transition<T, C> build() {
        return new TransitionImpl<>(this);
    }

    /**
     * Package-private hook used by {@link TransitionImpl} to materialize the runtime
     * {@link BoundOperation}, or {@code null} when this transition has no operation attached.
     *
     * @return the bound operation, or {@code null}
     */
    BoundOperation<T, C> buildBoundOperation() {
        return operationDef == null ? null : operationDef.build();
    }

    @Override
    public String toString() {
        return "TransitionDefImpl{" +
            "id='" + id + '\'' +
            ", sourceStateId='" + sourceStateId + '\'' +
            ", targetStateId='" + targetStateId + '\'' +
            '}';
    }

    @Override
    public SimpleOperationDef<T, C> operation(String id) {
        if (this.operationDef != null) {
            log.warn("Operation is already defined for transition '{}'; overriding previous value", this.id);
        }
        SimpleOperationDefImpl<T, C> def = new SimpleOperationDefImpl<>(id);
        this.operationDef = def;
        return def;
    }

    @Override
    public TransitionDef<T, C> operation(String id, Operation<T, C> operation) {
        operation(id).using(operation);
        return this;
    }

    @Override
    public TransitionDef<T, C> operation(String id, Class<? extends Operation<T, C>> operationClass) {
        operation(id).using(operationClass);
        return this;
    }

    @Override
    public TransitionDef<T, C> withName(String name) {
        // TODO: Implement name configuration
        return this;
    }

    @Override
    public TransitionDef<T, C> withDescription(String description) {
        // TODO: Implement description configuration
        return this;
    }

    @Override
    public TransitionDef<T, C> addPreCondition(String conditionId) {
        throw new UnsupportedOperationException("Pre-conditions not yet implemented");
    }

    @Override
    public TransitionDef<T, C> addPreCondition(Predicate<T> preCondition) {
        throw new UnsupportedOperationException("Pre-conditions not yet implemented");
    }

    @Override
    public TransitionDef<T, C> addPreCondition(String id, Predicate<T> preCondition) {
        throw new UnsupportedOperationException("Pre-conditions not yet implemented");
    }

    @Override
    public TransitionDef<T, C> addPostCondition(Predicate<T> postCondition) {
        throw new UnsupportedOperationException("Post-conditions not yet implemented");
    }

    @Override
    public TransitionDef<T, C> addPostCondition(String id, Predicate<T> postCondition) {
        throw new UnsupportedOperationException("Post-conditions not yet implemented");
    }

    @Override
    public TransitionDef<T, C> addManualTrigger() {
        throw new UnsupportedOperationException("Triggers not yet implemented");
    }

    @Override
    public TransitionDef<T, C> addManualTrigger(String id) {
        throw new UnsupportedOperationException("Triggers not yet implemented");
    }

    @Override
    public TransitionDef<T, C> addEventTrigger(String id) {
        throw new UnsupportedOperationException("Triggers not yet implemented");
    }

    @Override
    public TransitionDef<T, C> addEventTrigger(String id, String eventId) {
        throw new UnsupportedOperationException("Triggers not yet implemented");
    }

    @Override
    public TransitionDef<T, C> addEventTrigger(Identifiable event) {
        throw new UnsupportedOperationException("Triggers not yet implemented");
    }

    @Override
    public TransitionDef<T, C> addEventTrigger(String id, Identifiable event) {
        throw new UnsupportedOperationException("Triggers not yet implemented");
    }

    @Override
    public TransitionDef<T, C> addEventTrigger(BiPredicate<String, T> condition) {
        throw new UnsupportedOperationException("Triggers not yet implemented");
    }

    @Override
    public TransitionDef<T, C> addEventTrigger(String id, BiPredicate<String, T> condition) {
        throw new UnsupportedOperationException("Triggers not yet implemented");
    }

    @Override
    public TransitionDef<T, C> addDataTrigger(String id) {
        throw new UnsupportedOperationException("Triggers not yet implemented");
    }

    @Override
    public TransitionDef<T, C> addDataTrigger(Predicate<T> condition) {
        throw new UnsupportedOperationException("Triggers not yet implemented");
    }

    @Override
    public TransitionDef<T, C> addDataTrigger(String id, Predicate<T> condition) {
        throw new UnsupportedOperationException("Triggers not yet implemented");
    }
}
