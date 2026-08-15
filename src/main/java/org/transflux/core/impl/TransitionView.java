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
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void run(String id) {
        runAction((BoundAction) resolveAction(id), null);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void run(String id, String mapperId) {
        requireNotBlank(mapperId, "Mapper reference ID");
        runAction((BoundAction) resolveAction(id), resolveRegisteredMapper(mapperId));
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void run(String id, Function<C, ?> inlineMapTo) {
        requireNotNull(inlineMapTo, "Inline mapper function");
        runAction((BoundAction) resolveAction(id), wrapFunction((Function) inlineMapTo));
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void run(String id, ContextMapper<C, ?> inlineMapper) {
        requireNotNull(inlineMapper, "Inline mapper instance");
        runAction((BoundAction) resolveAction(id), (ContextMapper<Object, Object>) inlineMapper);
    }

    @Override
    public void run(Identifiable registeredAction) {
        requireNotNull(registeredAction, "Action identifiable");
        run(registeredAction.getId());
    }

    @Override
    public void run(Identifiable registeredAction, Identifiable mapper) {
        requireNotNull(registeredAction, "Action identifiable");
        requireNotNull(mapper, "Mapper identifiable");
        run(registeredAction.getId(), mapper.getId());
    }

    @Override
    public void run(Identifiable registeredAction, String mapperId) {
        requireNotNull(registeredAction, "Action identifiable");
        run(registeredAction.getId(), mapperId);
    }

    @Override
    public void run(String id, Identifiable mapper) {
        requireNotNull(mapper, "Mapper identifiable");
        run(id, mapper.getId());
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
     * Runs a bound action. This is the single execution path: every action reaches the runtime
     * through here, whether it was authored imperatively or declaratively, dispatched as a
     * container member, referenced from inside another action's body, or attached to the
     * transition itself.
     * <p>
     * The order is fixed and uniform:
     * <ol>
     *   <li>capture the action's {@link Action#getCompensation(Object, Object) compensation} and
     *       push it onto the rollback stack, against the same context {@code execute} will see;</li>
     *   <li>record the action's id on the executed path;</li>
     *   <li>push the id onto the operation-nesting stack;</li>
     *   <li>execute;</li>
     *   <li>pop the nesting stack.</li>
     * </ol>
     * Capturing and recording <em>before</em> the nesting push puts both at the action's own
     * qualified path rather than one level beneath it. Recording before {@code execute} means an
     * action that throws still appears on the executed path - it did run - which keeps the
     * executed and compensated paths consistent with each other. Pushing the nesting stack for
     * every action means anything it dispatches is qualified underneath it, so the reported tree
     * matches the tree that actually ran at every level.
     *
     * <p>With a mapper, {@code mapTo} produces the child context before the action starts and
     * {@link ContextMapper#mapFrom(Object, Object) mapFrom} folds child-side changes back into
     * the parent on successful return only.
     *
     * @param bound the bound action to run; never {@code null}
     * @param mapper the mapper to apply at the boundary, or {@code null} for pass-through
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    void runAction(BoundAction<T, Object> bound, ContextMapper<Object, Object> mapper) {
        Object active = getContext();
        Object child = mapper == null ? null : mapper.mapTo(active);
        Object effective = mapper == null ? active : child;

        pushCompensation(bound.id(), (Compensation) bound.action().getCompensation(entity, effective));
        recordExecutedId(bound.id());
        enterOperation(bound.id());
        try {
            if (mapper == null) {
                ((Action) bound.action()).execute(entity, active, this);
                return;
            }
            contextOverrideStack.push(child);
            try {
                ((Action) bound.action()).execute(entity, child, this);
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
     * Pushes {@code scopeRegistry} as the active lexical scope for subsequent imperative
     * {@code run(...)} resolution. Composite executors push their
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
     * Returns the registry that {@code run(...)} resolution should
     * consult. When the scope stack is empty (e.g. a simple operation directly invoking
     * {@code view.run("id")}), this falls back to the state machine's root registry.
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
