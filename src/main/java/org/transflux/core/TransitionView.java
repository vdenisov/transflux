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
 * Package-private per-execution view of a {@link Transition}.
 * <p>
 * The framework builds a fresh {@code TransitionView} for each transition execution and
 * hands it to the underlying {@link Operation} as the {@code transition} parameter. Topology
 * accessors delegate to the static {@link TransitionImpl}; {@link #step(String)} runs against
 * the captured execution scope (entity, context, step-id recorder, compensation stack).
 *
 * @param <T> the entity type the enclosing state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
class TransitionView<T, C> implements Transition<T, C> {
    private final TransitionImpl<T, C> staticTransition;
    private final T entity;
    private final C context;

    // TODO: thread a StepIdRecorder through here so step("id") and the composite executor
    //  append executed ids in one place.
    // TODO: thread the per-execution compensation stack through here.

    TransitionView(TransitionImpl<T, C> staticTransition, T entity, C context) {
        if (staticTransition == null) {
            throw new TransfluxValidationException("Static transition cannot be null");
        }
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
        // TODO: resolve `id` against the state machine's step registry and execute the bound
        //  step against this view's captured scope.
        throw new TransfluxValidationException("Step lookup not yet wired");
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
}
