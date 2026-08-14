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

import org.transflux.core.Identifiable;
import org.transflux.core.exception.TransfluxValidationException;
import org.transflux.core.operation.ContextMapper;
import org.transflux.core.transition.Transition;

import java.util.function.Function;

import static org.transflux.core.Preconditions.requireNotNull;

/**
 * A {@link Transition} that exposes static topology only.
 * <p>
 * The dispatch methods are execution-scoped: they need the captured entity, context, compensation
 * stack and recorder that only a live execution carries. This view is handed to code that runs
 * before any execution has been entered — a data trigger's gate condition — so it answers the
 * topology accessors and rejects every {@code step} and {@code operation} call. Rejecting is what
 * keeps such code honest: a gate that dispatched a step would run real side effects outside the
 * reentrancy guard, and any compensation it captured would be discarded unexecuted.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
final class TopologyTransition<T, C> implements Transition<T, C> {

    private final BoundTransition<T, C> boundTransition;

    TopologyTransition(BoundTransition<T, C> boundTransition) {
        requireNotNull(boundTransition, "Bound transition");
        this.boundTransition = boundTransition;
    }

    @Override
    public String getId() {
        return boundTransition.id();
    }

    @Override
    public String getSourceStateId() {
        return boundTransition.sourceStateId();
    }

    @Override
    public String getTargetStateId() {
        return boundTransition.targetStateId();
    }

    @Override
    public void step(String registeredStepId) {
        throw outsideExecution("step(String)");
    }

    @Override
    public void step(String registeredStepId, String mapperId) {
        throw outsideExecution("step(String, String)");
    }

    @Override
    public void step(String registeredStepId, Function<C, ?> mapTo) {
        throw outsideExecution("step(String, Function)");
    }

    @Override
    public void step(String registeredStepId, ContextMapper<C, ?> mapper) {
        throw outsideExecution("step(String, ContextMapper)");
    }

    @Override
    public void step(Identifiable registeredStep) {
        throw outsideExecution("step(Identifiable)");
    }

    @Override
    public void step(Identifiable registeredStep, Identifiable mapper) {
        throw outsideExecution("step(Identifiable, Identifiable)");
    }

    @Override
    public void step(Identifiable registeredStep, String mapperId) {
        throw outsideExecution("step(Identifiable, String)");
    }

    @Override
    public void step(String registeredStepId, Identifiable mapper) {
        throw outsideExecution("step(String, Identifiable)");
    }

    @Override
    public void operation(String registeredOperationId) {
        throw outsideExecution("operation(String)");
    }

    @Override
    public void operation(String registeredOperationId, String mapperId) {
        throw outsideExecution("operation(String, String)");
    }

    @Override
    public void operation(String registeredOperationId, Function<C, ?> mapTo) {
        throw outsideExecution("operation(String, Function)");
    }

    @Override
    public void operation(String registeredOperationId, ContextMapper<C, ?> mapper) {
        throw outsideExecution("operation(String, ContextMapper)");
    }

    @Override
    public void operation(Identifiable registeredOperation) {
        throw outsideExecution("operation(Identifiable)");
    }

    @Override
    public void operation(Identifiable registeredOperation, Identifiable mapper) {
        throw outsideExecution("operation(Identifiable, Identifiable)");
    }

    @Override
    public void operation(Identifiable registeredOperation, String mapperId) {
        throw outsideExecution("operation(Identifiable, String)");
    }

    @Override
    public void operation(String registeredOperationId, Identifiable mapper) {
        throw outsideExecution("operation(String, Identifiable)");
    }

    private TransfluxValidationException outsideExecution(String method) {
        return new TransfluxValidationException(
            "Transition '" + boundTransition.id() + "' is a read-only topology view; '" + method
                + "' is only available to code running inside an active transition execution");
    }
}
