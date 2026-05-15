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

package org.transflux.core.transition;

import org.transflux.core.StateMachineImpl;
import org.transflux.core.exception.TransfluxValidationException;
import org.transflux.core.operation.BoundCompensation;
import org.transflux.core.operation.BoundStep;
import org.transflux.core.operation.Compensation;
import org.transflux.core.operation.Operation;
import org.transflux.core.operation.StepPath;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

import static org.transflux.core.ValidationUtils.requireNotBlank;
import static org.transflux.core.ValidationUtils.requireNotNull;

/**
 * Per-execution view of a {@link Transition}.
 * <p>
 * The framework builds a fresh {@code TransitionView} for each transition execution and
 * hands it to the underlying {@link Operation} as the {@code transition} parameter. Topology
 * accessors delegate to the static {@link TransitionImpl}; {@link #step(String)} runs against
 * the captured execution scope (entity, context, step-id recorder) by resolving the id against
 * the enclosing state machine's step registry.
 *
 * <p>This is framework-internal runtime infrastructure intended only for use by Transflux's
 * own runtime; user code should not reference it directly.
 *
 * @param <T> the entity type the enclosing state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
public class TransitionView<T, C> implements Transition<T, C> {
    private final StateMachineImpl<T, C> stateMachine;
    private final TransitionImpl<T, C> staticTransition;

    private final T entity;
    private final C context;

    private final List<StepPath> executedStepIds = new ArrayList<>();

    private final Deque<BoundCompensation<T, C>> compensationStack = new ArrayDeque<>();

    private final Deque<String> operationStack = new ArrayDeque<>();

    public TransitionView(StateMachineImpl<T, C> stateMachine, TransitionImpl<T, C> staticTransition,
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

    public T getEntity() {
        return entity;
    }

    public C getContext() {
        return context;
    }

    public TransitionImpl<T, C> getStaticTransition() {
        return staticTransition;
    }

    public StateMachineImpl<T, C> getStateMachine() {
        return stateMachine;
    }

    public void recordExecutedStepId(String localStepId) {
        executedStepIds.add(qualifyStepPath(localStepId));
    }

    public List<StepPath> getExecutedStepIds() {
        return Collections.unmodifiableList(executedStepIds);
    }

    /**
     * Pushes the supplied nested-operation id onto this view's operation-nesting stack. While
     * the stack is non-empty every {@link #recordExecutedStepId(String)} call records the step
     * id under a {@code parent-op-id/.../child-step-id} qualified path. Each push must be
     * paired with a matching {@link #exitOperation()} call.
     *
     * <p>This is framework-internal infrastructure used by Transflux's own runtime; user code
     * should not invoke it directly.
     *
     * @param operationId the nested-operation id to push; must be non-blank
     */
    public void enterOperation(String operationId) {
        requireNotBlank(operationId, "Operation ID");
        operationStack.push(operationId);
    }

    /**
     * Pops the most recently pushed nested-operation id from this view's operation-nesting
     * stack.
     *
     * <p>This is framework-internal infrastructure used by Transflux's own runtime; user code
     * should not invoke it directly.
     *
     * @throws TransfluxValidationException if the nesting stack is empty
     */
    public void exitOperation() {
        if (operationStack.isEmpty()) {
            throw new TransfluxValidationException(
                "exitOperation() called with no matching enterOperation()");
        }
        operationStack.pop();
    }

    private StepPath qualifyStepPath(String localStepId) {
        requireNotBlank(localStepId, "Step ID");

        if (operationStack.isEmpty()) {
            return StepPath.of(localStepId);
        }

        List<String> segments = new ArrayList<>(operationStack.size() + 1);
        Iterator<String> descending = operationStack.descendingIterator();
        while (descending.hasNext()) {
            segments.add(descending.next());
        }
        segments.add(localStepId);

        return new StepPath(segments);
    }

    /**
     * Pushes a {@link Compensation} onto this view's LIFO rollback stack under the supplied
     * step id. A {@code null} compensation is a no-op; this lets callers forward the result of
     * {@link org.transflux.core.operation.Step#getCompensation(Object, Object)} unconditionally
     * without first checking it for {@code null}.
     *
     * <p>This is framework-internal infrastructure used by Transflux's own runtime; user code
     * should not invoke it directly.
     *
     * @param stepId the id of the step the compensation rolls back; must be non-blank
     * @param compensation the compensation callback; ignored when {@code null}
     */
    public void pushCompensation(String localStepId, Compensation<T, C> compensation) {
        requireNotBlank(localStepId, "Step ID");
        if (compensation == null) {
            return;
        }
        compensationStack.push(new BoundCompensation<>(qualifyStepPath(localStepId), compensation));
    }

    /**
     * Drains the rollback stack and returns its contents in pop order, i.e. reverse order of
     * registration (LIFO). The stack is empty when this method returns.
     *
     * <p>This is framework-internal infrastructure used by Transflux's own runtime; user code
     * should not invoke it directly.
     *
     * @return an unmodifiable list of the popped compensations in LIFO order
     */
    public List<BoundCompensation<T, C>> drainCompensationsLifo() {
        if (compensationStack.isEmpty()) {
            return Collections.emptyList();
        }

        List<BoundCompensation<T, C>> drained = new ArrayList<>(compensationStack.size());
        while (!compensationStack.isEmpty()) {
            drained.add(compensationStack.pop());
        }

        return Collections.unmodifiableList(drained);
    }
}
