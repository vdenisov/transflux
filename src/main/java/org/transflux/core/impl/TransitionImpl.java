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

import org.transflux.core.transition.*;

import org.transflux.core.impl.StateMachineImpl;
import org.transflux.core.impl.BoundCondition;
import org.transflux.core.exception.TransfluxValidationException;
import org.transflux.core.impl.BoundOperation;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.transflux.core.Preconditions.requireNotBlank;
import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Default implementation of the {@link Transition} interface — the static-topology view.
 * <p>
 * This implementation provides a straightforward concrete transition that can be constructed
 * from a {@link TransitionDef} definition. It stores the transition's identifier, source
 * state ID, and target state ID as immutable properties and provides standard implementations
 * for equality and hash code based on the transition identifier.
 *
 * <p>One {@code TransitionImpl} is created per declared transition during state machine
 * construction and is shared across all executions. The per-execution wrapper that supports
 * {@link Transition#step(String)} is {@link TransitionView}, built fresh for each execution
 * by {@link StateMachineImpl}.
 *
 * <p>{@code TransitionImpl} instances are typically created internally by the framework during
 * state machine construction and should not be instantiated directly by client code.
 *
 * @param <T> the entity type the enclosing state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
class TransitionImpl<T, C> implements Transition<T, C> {
    private final String id;
    private final String sourceStateId;
    private final String targetStateId;
    private final Class<C> contextType;
    private final BoundOperation<T, C> boundOperation;
    private final List<BoundCondition<T, C>> boundPreConditions;
    private final List<BoundCondition<T, C>> boundPostConditions;

    /**
     * Constructs a new TransitionImpl from the provided transition definition.
     * <p>
     * This package-private constructor is used internally by the framework to create
     * transition instances during state machine construction. It validates the transition
     * definition and extracts the necessary properties.
     *
     * @param transitionDef the transition definition to construct this transition from
     * @param stateMachine the enclosing state machine; required by composite operations to
     *                     resolve step references against the step registry
     * @param conditionRegistry the resolved state-machine condition registry, used to bind
     *                          any descriptor-based pre/post conditions on this transition
     *
     * @throws TransfluxValidationException if the transition definition is null or has invalid properties
     */
    public TransitionImpl(TransitionDefImpl<T, C> transitionDef, StateMachineImpl<T> stateMachine,
                          Map<String, BoundCondition<T, C>> conditionRegistry) {
        validateTransitionDef(transitionDef);
        requireNotNull(conditionRegistry, "Condition registry");
        this.id = transitionDef.getId();
        this.sourceStateId = transitionDef.getSourceStateId();
        this.targetStateId = transitionDef.getTargetStateId();
        this.contextType = transitionDef.getContextType();
        this.boundOperation = transitionDef.buildBoundOperation(stateMachine);
        this.boundPreConditions = transitionDef.buildBoundPreConditions(conditionRegistry);
        this.boundPostConditions = transitionDef.buildBoundPostConditions(conditionRegistry);
    }

    /**
     * Validates the provided transition definition to ensure it contains valid properties.
     *
     * @param transitionDef the transition definition to validate
     *
     * @throws TransfluxValidationException if the transition definition is null or has invalid properties
     */
    private void validateTransitionDef(TransitionDefImpl<T, C> transitionDef) {
        requireNotNull(transitionDef, "Transition definition");
        requireNotBlank(transitionDef.getId(), "Transition ID");
        requireNotBlank(transitionDef.getSourceStateId(), "Source state ID");
        requireNotBlank(transitionDef.getTargetStateId(), "Target state ID");
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
    public void step(String id) {
        throw new TransfluxValidationException("Step invocation is only valid during transition execution");
    }

    public BoundOperation<T, C> getBoundOperation() {
        return boundOperation;
    }

    /**
     * Returns the context class declared for this transition. Defaults to {@code Void.class}
     * when {@link TransitionDef#usingContext(Class)} was not called.
     *
     * @return the declared context class; never {@code null}
     */
    public Class<C> getContextType() {
        return contextType;
    }

    /**
     * Returns the resolved pre-conditions for this transition, in declaration order.
     *
     * @return an unmodifiable list of bound pre-conditions; never {@code null}
     */
    public List<BoundCondition<T, C>> getBoundPreConditions() {
        return Collections.unmodifiableList(boundPreConditions);
    }

    /**
     * Returns the resolved post-conditions for this transition, in declaration order.
     *
     * @return an unmodifiable list of bound post-conditions; never {@code null}
     */
    public List<BoundCondition<T, C>> getBoundPostConditions() {
        return Collections.unmodifiableList(boundPostConditions);
    }

    @Override
    public final boolean equals(Object o) {
        if (!(o instanceof TransitionImpl<?, ?> that)) return false;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "TransitionImpl{" +
            "id='" + id + '\'' +
            ", sourceStateId='" + sourceStateId + '\'' +
            ", targetStateId='" + targetStateId + '\'' +
            '}';
    }
}
