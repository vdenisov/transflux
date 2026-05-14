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

package org.transflux.core.operation;

import org.transflux.core.StateMachineImpl;
import org.transflux.core.exception.TransfluxValidationException;
import org.transflux.core.transition.Transition;
import org.transflux.core.transition.TransitionView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Implementation of {@link CompositeOperationDef}.
 *
 * <p>This is framework-internal infrastructure; user code constructs composite operations
 * through the public {@link CompositeOperationDef} fluent API.
 * <p>
 * Holds the composite's step references in declaration order. The references are not resolved
 * eagerly; they are resolved against the enclosing state machine's step registry when
 * {@link #build(StateMachineImpl)} is invoked during state-machine construction. Inline
 * references contributed by this composite must already have been registered with the
 * state-machine def before that point.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
public final class CompositeOperationDefImpl<T, C> extends OperationDefImpl<T, C> implements CompositeOperationDef<T, C> {

    private final List<StepRef<T, C>> stepRefs = new ArrayList<>();

    public CompositeOperationDefImpl(String id) {
        super(id);
    }

    @Override
    public CompositeOperationDefImpl<T, C> step(String registeredStepId) {
        stepRefs.add(StepRef.byId(registeredStepId));
        return this;
    }

    @Override
    public CompositeOperationDefImpl<T, C> step(String id, Step<T, C> step) {
        stepRefs.add(StepRef.inline(id, step));
        return this;
    }

    @Override
    public CompositeOperationDefImpl<T, C> step(String id, Class<? extends Step<T, C>> stepClass) {
        stepRefs.add(StepRef.inline(id, stepClass));
        return this;
    }

    @Override
    public CompositeOperationDefImpl<T, C> withName(String name) {
        super.withName(name);
        return this;
    }

    @Override
    public CompositeOperationDefImpl<T, C> withDescription(String description) {
        super.withDescription(description);
        return this;
    }

    /**
     * Returns the step references in declaration order. Used by the enclosing state-machine
     * def to auto-register inline references before resolving any by-id references.
     *
     * @return an unmodifiable view of the step-reference list
     */
    List<StepRef<T, C>> getStepRefs() {
        return Collections.unmodifiableList(stepRefs);
    }

    /**
     * Returns a map of {@code stepId -> Step} for every inline step instance contributed by
     * this composite, in declaration order. The result excludes by-id references.
     *
     * <p>This is framework-internal infrastructure used by the state-machine def to
     * auto-register inline steps; user code should not invoke it directly.
     *
     * @return an unmodifiable map of step id to inline step instance
     */
    public Map<String, Step<T, C>> getInlineStepInstances() {
        Map<String, Step<T, C>> result = new LinkedHashMap<>();
        for (StepRef<T, C> ref : stepRefs) {
            if (ref instanceof StepRef.InlineInstance<T, C> ii) {
                result.put(ii.id(), ii.step());
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Returns a map of {@code stepId -> stepClass} for every inline step class contributed by
     * this composite, in declaration order. The result excludes by-id references.
     *
     * <p>This is framework-internal infrastructure used by the state-machine def to
     * auto-register inline steps; user code should not invoke it directly.
     *
     * @return an unmodifiable map of step id to inline step class
     */
    public Map<String, Class<? extends Step<T, C>>> getInlineStepClasses() {
        Map<String, Class<? extends Step<T, C>>> result = new LinkedHashMap<>();
        for (StepRef<T, C> ref : stepRefs) {
            if (ref instanceof StepRef.InlineClass<T, C> ic) {
                result.put(ic.id(), ic.stepClass());
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Resolves each step reference against the state machine's step registry and produces a
     * {@link BoundOperation} whose underlying {@link Operation} iterates the bound steps in
     * declaration order.
     *
     * @param stateMachine the enclosing state machine; the registry must already contain
     *                     every referenced id
     *
     * @return the bound operation
     *
     * @throws TransfluxValidationException if the composite has no steps, or any referenced
     *         id is not registered on the state machine
     */
    public BoundOperation<T, C> build(StateMachineImpl<T, C> stateMachine) {
        if (stepRefs.isEmpty()) {
            throw new TransfluxValidationException(
                "CompositeOperationDef '" + getId() + "' has no steps; call step(...) at least once before build");
        }

        List<BoundStep<T, C>> boundSteps = new ArrayList<>(stepRefs.size());
        for (StepRef<T, C> ref : stepRefs) {
            BoundStep<T, C> bound = stateMachine.getBoundStep(ref.id());
            if (bound == null) {
                throw new TransfluxValidationException(
                    "CompositeOperationDef '" + getId() + "' references unknown step id '" + ref.id() + "'");
            }
            boundSteps.add(bound);
        }

        Operation<T, C> executor = new CompositeOperationExecutor<>(stateMachine, boundSteps);
        return BoundOperation.of(getId(), getName(), getDescription(), executor);
    }

    /**
     * Iterates an ordered list of {@link BoundStep} instances and invokes each one against the
     * supplied {@link Transition} view. Each step is dispatched through
     * {@link StateMachineImpl#runBoundStep(BoundStep, TransitionView)} so that step-id
     * recording is uniform across composite-driven invocations and user-driven
     * {@code transition.step("id")} calls.
     */
    private static final class CompositeOperationExecutor<T, C> implements Operation<T, C> {
        private final StateMachineImpl<T, C> stateMachine;
        private final List<BoundStep<T, C>> boundSteps;

        CompositeOperationExecutor(StateMachineImpl<T, C> stateMachine, List<BoundStep<T, C>> boundSteps) {
            this.stateMachine = stateMachine;
            this.boundSteps = boundSteps;
        }

        @Override
        public void execute(T entity, C context, Transition<T, C> transition) {
            if (!(transition instanceof TransitionView<?, ?> rawView)) {
                throw new TransfluxValidationException(
                    "Composite operation requires a per-execution TransitionView; got "
                        + (transition == null ? "null" : transition.getClass().getName()));
            }
            @SuppressWarnings("unchecked")
            TransitionView<T, C> view = (TransitionView<T, C>) rawView;
            for (BoundStep<T, C> boundStep : boundSteps) {
                StateMachineImpl.runBoundStep(boundStep, view);
            }
        }
    }
}
