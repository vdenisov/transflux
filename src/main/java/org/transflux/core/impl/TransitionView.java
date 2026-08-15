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

import org.transflux.core.action.ActionKind;
import org.transflux.core.Identifiable;
import org.transflux.core.exception.TransfluxValidationException;
import org.transflux.core.action.Compensation;
import org.transflux.core.action.ContextMapper;
import org.transflux.core.action.MapperDef;
import org.transflux.core.action.Action;
import org.transflux.core.transition.ActionPath;
import org.transflux.core.transition.Transition;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.function.Function;

import static org.transflux.core.Preconditions.requireNotBlank;
import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Per-execution view of a {@link Transition}.
 * <p>
 * The framework builds a fresh {@code TransitionView} for each transition execution and hands
 * it to the underlying {@link Action} as the {@code transition} parameter. Topology
 * accessors delegate to the {@link BoundTransition} record that carries the resolved
 * per-transition data; the dispatch methods declared on {@link Transition} run against the
 * captured execution scope (entity, context, step-id recorder, compensation stack) by
 * resolving the id against the enclosing state machine's registries.
 *
 * <p>This is framework-internal runtime infrastructure intended only for use by Transflux's
 * own runtime; user code should not reference it directly.
 *
 * @param <T> the entity type the enclosing state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
class TransitionView<T, C> implements Transition<T, C> {
    private final StateMachineImpl<T> stateMachine;
    private final BoundTransition<T, C> boundTransition;

    private final T entity;
    private final C context;

    private final Deque<Object> contextOverrideStack = new ArrayDeque<>();

    private final Deque<Registry<T>> scopeStack = new ArrayDeque<>();

    private final List<ActionPath> executedPath = new ArrayList<>();

    private final Deque<BoundCompensation<T, C>> compensationStack = new ArrayDeque<>();

    private final Deque<String> operationStack = new ArrayDeque<>();

    TransitionView(StateMachineImpl<T> stateMachine, BoundTransition<T, C> boundTransition,
                   T entity, C context) {
        requireNotNull(stateMachine, "State machine");
        requireNotNull(boundTransition, "Bound transition");

        this.stateMachine = stateMachine;
        this.boundTransition = boundTransition;
        this.entity = entity;
        this.context = context;
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
    public void step(String id) {
        dispatchResolved(resolveAction(id), null);
    }

    @Override
    public void step(String id, String mapperId) {
        requireNotBlank(mapperId, "Mapper reference ID");
        dispatchResolved(resolveAction(id), resolveRegisteredMapper(mapperId));
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void step(String id, Function<C, ?> inlineMapTo) {
        requireNotNull(inlineMapTo, "Inline mapper function");
        dispatchResolved(resolveAction(id), wrapFunction((Function) inlineMapTo));
    }

    @Override
    @SuppressWarnings("unchecked")
    public void step(String id, ContextMapper<C, ?> inlineMapper) {
        requireNotNull(inlineMapper, "Inline mapper instance");
        dispatchResolved(resolveAction(id), (ContextMapper<Object, Object>) inlineMapper);
    }

    @Override
    public void step(Identifiable registeredStep) {
        requireNotNull(registeredStep, "Step identifiable");
        step(registeredStep.getId());
    }

    @Override
    public void step(Identifiable registeredStep, Identifiable mapper) {
        requireNotNull(registeredStep, "Step identifiable");
        requireNotNull(mapper, "Mapper identifiable");
        step(registeredStep.getId(), mapper.getId());
    }

    @Override
    public void step(Identifiable registeredStep, String mapperId) {
        requireNotNull(registeredStep, "Step identifiable");
        step(registeredStep.getId(), mapperId);
    }

    @Override
    public void step(String id, Identifiable mapper) {
        requireNotNull(mapper, "Mapper identifiable");
        step(id, mapper.getId());
    }

    @Override
    public void operation(String id) {
        dispatchResolved(resolveAction(id), null);
    }

    @Override
    public void operation(String id, String mapperId) {
        requireNotBlank(mapperId, "Mapper reference ID");
        dispatchResolved(resolveAction(id), resolveRegisteredMapper(mapperId));
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void operation(String id, Function<C, ?> inlineMapTo) {
        requireNotNull(inlineMapTo, "Inline mapper function");
        dispatchResolved(resolveAction(id), wrapFunction((Function) inlineMapTo));
    }

    @Override
    @SuppressWarnings("unchecked")
    public void operation(String id, ContextMapper<C, ?> inlineMapper) {
        requireNotNull(inlineMapper, "Inline mapper instance");
        dispatchResolved(resolveAction(id), (ContextMapper<Object, Object>) inlineMapper);
    }

    @Override
    public void operation(Identifiable registeredOperation) {
        requireNotNull(registeredOperation, "Operation identifiable");
        operation(registeredOperation.getId());
    }

    @Override
    public void operation(Identifiable registeredOperation, Identifiable mapper) {
        requireNotNull(registeredOperation, "Operation identifiable");
        requireNotNull(mapper, "Mapper identifiable");
        operation(registeredOperation.getId(), mapper.getId());
    }

    @Override
    public void operation(Identifiable registeredOperation, String mapperId) {
        requireNotNull(registeredOperation, "Operation identifiable");
        operation(registeredOperation.getId(), mapperId);
    }

    @Override
    public void operation(String id, Identifiable mapper) {
        requireNotNull(mapper, "Mapper identifiable");
        operation(id, mapper.getId());
    }

    T getEntity() {
        return entity;
    }

    @SuppressWarnings("unchecked")
    C getContext() {
        return contextOverrideStack.isEmpty() ? context : (C) contextOverrideStack.peek();
    }

    void recordExecutedId(String localStepId) {
        executedPath.add(qualifyActionPath(localStepId));
    }

    List<ActionPath> getExecutedPath() {
        return Collections.unmodifiableList(executedPath);
    }

    /**
     * Pushes the supplied nested-operation id onto this view's operation-nesting stack. While
     * the stack is non-empty every {@link #recordExecutedId(String)} call records the step
     * id under a {@code parent-op-id/.../child-step-id} qualified path. Each push must be
     * paired with a matching {@link #exitOperation()} call.
     *
     * @param operationId the nested-operation id to push; must be non-blank
     */
    void enterOperation(String operationId) {
        requireNotBlank(operationId, "Operation ID");
        operationStack.push(operationId);
    }

    /**
     * Pops the most recently pushed nested-operation id from this view's operation-nesting
     * stack.
     *
     * @throws TransfluxValidationException if the nesting stack is empty
     */
    void exitOperation() {
        if (operationStack.isEmpty()) {
            throw new TransfluxValidationException(
                "exitOperation() called with no matching enterOperation()");
        }
        operationStack.pop();
    }

    /**
     * Runs a bound step under a freshly-mapped child context. The child context produced by
     * {@code mapper.mapTo(activeContext)} is pushed onto the view's context-override stack for
     * the duration of the step's execution, and on successful return
     * {@link ContextMapper#mapFrom(Object, Object) mapFrom} folds any child-side changes back
     * into the active context.
     *
     * @param boundStep the bound step to run; never {@code null}
     * @param mapper the mapper to apply at the boundary; never {@code null}
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    void runChildStep(BoundAction<T, Object> boundStep, ContextMapper<Object, Object> mapper) {
        Object active = getContext();
        Object child = mapper.mapTo(active);
        contextOverrideStack.push(child);
        try {
            StateMachineImpl.runBoundStep((BoundAction) boundStep, (TransitionView) this);
        } finally {
            contextOverrideStack.pop();
        }
        mapper.mapFrom(active, child);
    }

    /**
     * Runs a bound operation under a freshly-mapped child context, recording the operation's
     * id on the operation-nesting stack so any step ids the operation drives are qualified
     * with this parent prefix. When {@code mapper} is {@code null} the operation runs
     * pass-through against the active context.
     * <p>
     * The operation's own {@link org.transflux.core.action.Action#getCompensation(Object, Object)}
     * is captured before execution and pushed against the operation's id, so an action authored
     * as a step but dispatched here still has its rollback registered. Capture happens before
     * {@link #enterOperation(String)}, so the compensation is recorded at the operation's own
     * path rather than nested beneath it, and against the same context {@code execute} will see.
     *
     * @param boundOperation the bound operation to run; never {@code null}
     * @param mapper the mapper to apply at the boundary, or {@code null} for pass-through
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    void runChildOperation(BoundAction<T, Object> boundOperation, ContextMapper<Object, Object> mapper) {
        Object active = getContext();
        Object child = mapper == null ? null : mapper.mapTo(active);
        Action<T, Object> action = boundOperation.action();
        pushCompensation(boundOperation.id(),
                         (Compensation) action.getCompensation(entity, mapper == null ? active : child));
        recordExecutedId(boundOperation.id());
        enterOperation(boundOperation.id());
        try {
            if (mapper == null) {
                ((Action) boundOperation.action()).execute(entity, active, this);
                return;
            }
            contextOverrideStack.push(child);
            try {
                ((Action) boundOperation.action()).execute(entity, child, this);
            } finally {
                contextOverrideStack.pop();
            }
            mapper.mapFrom(active, child);
        } finally {
            exitOperation();
        }
    }

    private BoundAction<T, ?> resolveAction(String id) {
        requireNotBlank(id, "Action ID");

        Component<T> component = activeScope().resolve(id)
            .orElseThrow(() -> new TransfluxValidationException(
                "No action registered with id '" + id + "' in the active scope"));

        if (!(component instanceof Component.Action<T, ?> action)) {
            throw new TransfluxValidationException(
                "Id '" + id + "' is registered as a " + component.getClass().getSimpleName().toLowerCase()
                    + ", not an action");
        }

        return action.bound();
    }

    /**
     * Runs a resolved action, choosing the runner from the form the action was <em>registered</em>
     * in rather than from the verb the caller used. The two are independent: a dispatch site names
     * a callee, and how that callee was authored is a property of its own declaration.
     *
     * @param bound the resolved action; never {@code null}
     * @param mapper the boundary mapper, or {@code null} for pass-through
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void dispatchResolved(BoundAction<T, ?> bound, ContextMapper<Object, Object> mapper) {
        if (bound.kind() == ActionKind.STEP) {
            if (mapper == null) {
                StateMachineImpl.runBoundStep((BoundAction) bound, (TransitionView) this);
            } else {
                runChildStep((BoundAction) bound, mapper);
            }
            return;
        }

        runChildOperation((BoundAction) bound, mapper);
    }

    /**
     * Pushes {@code scopeRegistry} as the active lexical scope for subsequent imperative
     * {@code step(...)} / {@code operation(...)} resolution. Composite executors push their
     * own scope on entry to {@code execute} and pop on exit; simple operations do not push.
     *
     * @param scopeRegistry the composite's scope registry; never {@code null}
     */
    void pushScope(Registry<T> scopeRegistry) {
        requireNotNull(scopeRegistry, "Scope registry");
        scopeStack.push(scopeRegistry);
    }

    /**
     * Pops the most recently pushed scope registry. Must be paired with a preceding
     * {@link #pushScope(Registry)} call.
     *
     * @throws TransfluxValidationException if the scope stack is empty
     */
    void popScope() {
        if (scopeStack.isEmpty()) {
            throw new TransfluxValidationException(
                "popScope() called with no matching pushScope()");
        }
        scopeStack.pop();
    }

    /**
     * Returns the registry that {@code step(...)} / {@code operation(...)} resolution should
     * consult. When the scope stack is empty (e.g. a simple operation directly invoking
     * {@code view.step("id")}), this falls back to the state machine's root registry.
     *
     * @return the active scope; never {@code null}
     */
    Registry<T> activeScope() {
        return scopeStack.isEmpty() ? stateMachine.getComponentRegistry() : scopeStack.peek();
    }

    private ContextMapper<Object, Object> resolveRegisteredMapper(String mapperId) {
        StateMachineDefImpl<T> def = stateMachine.getDef();
        MapperDef<?, ?> mapperDef = def.getMapperDef(mapperId);
        if (mapperDef == null) {
            throw new TransfluxValidationException(
                "No mapper registered with id '" + mapperId + "'");
        }
        @SuppressWarnings("unchecked")
        MapperDefImpl<Object, Object> impl = (MapperDefImpl<Object, Object>) mapperDef;
        return impl.buildMapper();
    }

    private ContextMapper<Object, Object> wrapFunction(Function<Object, Object> fn) {
        return fn::apply;
    }

    private ActionPath qualifyActionPath(String localStepId) {
        requireNotBlank(localStepId, "Step ID");

        if (operationStack.isEmpty()) {
            return ActionPath.of(localStepId);
        }

        List<String> segments = new ArrayList<>(operationStack.size() + 1);
        Iterator<String> descending = operationStack.descendingIterator();
        while (descending.hasNext()) {
            segments.add(descending.next());
        }
        segments.add(localStepId);

        return new ActionPath(segments);
    }

    /**
     * Pushes a {@link Compensation} onto this view's LIFO rollback stack under the supplied
     * step id. A {@code null} compensation is a no-op; this lets callers forward the result of
     * {@link org.transflux.core.action.Action#getCompensation(Object, Object)} unconditionally
     * without first checking it for {@code null}.
     *
     * @param localStepId the id of the step the compensation rolls back; must be non-blank
     * @param compensation the compensation callback; ignored when {@code null}
     */
    void pushCompensation(String localStepId, Compensation<T, C> compensation) {
        requireNotBlank(localStepId, "Step ID");
        if (compensation == null) {
            return;
        }
        compensationStack.push(new BoundCompensation<>(qualifyActionPath(localStepId), compensation));
    }

    /**
     * Drains the rollback stack and returns its contents in pop order, i.e. reverse order of
     * registration (LIFO). The stack is empty when this method returns.
     *
     * @return an unmodifiable list of the popped compensations in LIFO order
     */
    List<BoundCompensation<T, C>> drainCompensationsLifo() {
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
