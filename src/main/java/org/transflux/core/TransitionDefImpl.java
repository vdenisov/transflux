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
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.transflux.core.ValidationUtils.requireNotBlank;
import static org.transflux.core.ValidationUtils.requireNotNull;

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

    private OperationDefImpl<T, C> operationDef;

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
        requireNotBlank(id, "Transition ID");
        requireNotBlank(sourceStateId, "Source state ID");
        requireNotBlank(targetStateId, "Target state ID");

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

    /**
     * Package-private hook used by {@link TransitionImpl} to materialize the runtime
     * {@link BoundOperation}, or {@code null} when this transition has no operation attached.
     *
     * @param stateMachine the enclosing state machine; required by composite operations to
     *                     resolve step references against the step registry
     *
     * @return the bound operation, or {@code null}
     */
    BoundOperation<T, C> buildBoundOperation(StateMachineImpl<T, C> stateMachine) {
        if (operationDef == null) {
            return null;
        }
        if (operationDef instanceof SimpleOperationDefImpl) {
            return ((SimpleOperationDefImpl<T, C>) operationDef).build();
        }
        if (operationDef instanceof CompositeOperationDefImpl) {
            return ((CompositeOperationDefImpl<T, C>) operationDef).build(stateMachine);
        }
        throw new TransfluxValidationException(
            "Unsupported operation def kind: " + operationDef.getClass().getName());
    }

    OperationDefImpl<T, C> getOperationDef() {
        return operationDef;
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
    public TransitionDef<T, C> simpleOperation(String id, Operation<T, C> operation) {
        SimpleOperationDefImpl<T, C> def = newSimpleOperationDef(id);
        def.using(operation);
        attachOperation(def);
        return this;
    }

    @Override
    public TransitionDef<T, C> simpleOperation(String id, Class<? extends Operation<T, C>> operationClass) {
        SimpleOperationDefImpl<T, C> def = newSimpleOperationDef(id);
        def.using(operationClass);
        attachOperation(def);
        return this;
    }

    @Override
    public TransitionDef<T, C> simpleOperation(String id, Consumer<SimpleOperationDef<T, C>> configurer) {
        requireNotNull(configurer, "Simple operation configurer");
        SimpleOperationDefImpl<T, C> def = newSimpleOperationDef(id);
        configurer.accept(def);
        attachOperation(def);
        return this;
    }

    @Override
    public TransitionDef<T, C> compositeOperation(String id, Consumer<CompositeOperationDef<T, C>> configurer) {
        requireNotNull(configurer, "Composite operation configurer");
        CompositeOperationDefImpl<T, C> composite = new CompositeOperationDefImpl<>(id);
        configurer.accept(composite);
        attachOperation(composite);
        return this;
    }

    @Override
    public TransitionDef<T, C> step(String registeredStepId) {
        requireNotBlank(registeredStepId, "Step ID");
        CompositeOperationDefImpl<T, C> composite =
            new CompositeOperationDefImpl<>("transition-" + this.id + "-op");
        composite.step(registeredStepId);
        attachOperation(composite);
        return this;
    }

    private SimpleOperationDefImpl<T, C> newSimpleOperationDef(String operationId) {
        return new SimpleOperationDefImpl<>(operationId);
    }

    private void attachOperation(OperationDefImpl<T, C> def) {
        if (this.operationDef != null) {
            log.warn("Operation is already defined for transition '{}'; overriding previous value", this.id);
        }
        this.operationDef = def;
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
