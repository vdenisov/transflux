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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.transflux.core.ValidationUtils.requireNotBlank;
import static org.transflux.core.ValidationUtils.requireNotNull;

/**
 * Package-private per-execution view of a {@link Transition}.
 * <p>
 * The framework builds a fresh {@code TransitionView} for each transition execution and
 * hands it to the underlying {@link Operation} as the {@code transition} parameter. Topology
 * accessors delegate to the static {@link TransitionImpl}; {@link #step(String)} runs against
 * the captured execution scope (entity, context, step-id recorder) by resolving the id against
 * the enclosing state machine's step registry.
 *
 * @param <T> the entity type the enclosing state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
class TransitionView<T, C> implements Transition<T, C> {
    private final StateMachineImpl<T, C> stateMachine;
    private final TransitionImpl<T, C> staticTransition;
    private final T entity;
    private final C context;
    private final List<String> executedStepIds = new ArrayList<>();

    // TODO: thread the per-execution compensation stack through here.

    TransitionView(StateMachineImpl<T, C> stateMachine, TransitionImpl<T, C> staticTransition,
                   T entity, C context) {
        requireNotNull(stateMachine, "State machine");
        requireNotNull(staticTransition, "Static transition");
        this.stateMachine = stateMachine;
        this.staticTransition = staticTransition;
        this.entity = entity;
        this.context = context;
    }

    @Override
    public String getId() {
        return staticTransition.getId();
    }

    @Override
    public String getSourceStateId() {
        return staticTransition.getSourceStateId();
    }

    @Override
    public String getTargetStateId() {
        return staticTransition.getTargetStateId();
    }

    @Override
    public void step(String id) {
        requireNotBlank(id, "Step ID");
        BoundStep<T, C> boundStep = stateMachine.getBoundStep(id);
        if (boundStep == null) {
            throw new TransfluxValidationException("No step registered with id '" + id + "'");
        }
        StateMachineImpl.runBoundStep(boundStep, this);
    }

    T getEntity() {
        return entity;
    }

    C getContext() {
        return context;
    }

    TransitionImpl<T, C> getStaticTransition() {
        return staticTransition;
    }

    StateMachineImpl<T, C> getStateMachine() {
        return stateMachine;
    }

    void recordExecutedStepId(String id) {
        executedStepIds.add(id);
    }

    List<String> getExecutedStepIds() {
        return Collections.unmodifiableList(executedStepIds);
    }
}
