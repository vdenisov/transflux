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
import org.transflux.core.action.ContextMapper;
import org.transflux.core.transition.Transition;

import java.util.function.Function;

import static org.transflux.core.Preconditions.requireNotNull;

/**
 * A {@link Transition} that exposes static topology only.
 * <p>
 * The dispatch methods are execution-scoped: they need the captured entity, context, compensation
 * stack and recorder that only a live execution carries. This view is handed to code for which
 * dispatch could never be honoured — a data trigger's gate condition, evaluated before any
 * execution has been entered, and a state listener, notified either before the operation starts or
 * after the transition has been committed — so it answers the topology accessors and rejects every
 * {@code step} and {@code operation} call. Rejecting is what keeps such code honest: work it
 * dispatched would run real side effects whose compensations would be discarded unexecuted.
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
    public void run(String registeredActionId) {
        throw outsideExecution("run(String)");
    }

    @Override
    public void run(String registeredActionId, String mapperId) {
        throw outsideExecution("run(String, String)");
    }

    @Override
    public void run(String registeredActionId, Function<C, ?> mapTo) {
        throw outsideExecution("run(String, Function)");
    }

    @Override
    public void run(String registeredActionId, ContextMapper<C, ?> mapper) {
        throw outsideExecution("run(String, ContextMapper)");
    }

    @Override
    public void run(Identifiable registeredAction) {
        throw outsideExecution("run(Identifiable)");
    }

    @Override
    public void run(Identifiable registeredAction, Identifiable mapper) {
        throw outsideExecution("run(Identifiable, Identifiable)");
    }

    @Override
    public void run(Identifiable registeredAction, String mapperId) {
        throw outsideExecution("run(Identifiable, String)");
    }

    @Override
    public void run(String registeredActionId, Identifiable mapper) {
        throw outsideExecution("run(String, Identifiable)");
    }

    private TransfluxValidationException outsideExecution(String method) {
        return new TransfluxValidationException(
            "Transition '" + boundTransition.id() + "' is a read-only topology view; '" + method
                + "' is only available to code running inside an active transition execution");
    }
}
