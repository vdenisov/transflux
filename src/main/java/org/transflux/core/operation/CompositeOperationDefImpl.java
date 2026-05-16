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
import java.util.function.Consumer;

import static org.transflux.core.ValidationUtils.requireNotBlank;
import static org.transflux.core.ValidationUtils.requireNotNull;

/**
 * Implementation of {@link CompositeOperationDef}.
 *
 * <p>This is framework-internal infrastructure; user code constructs composite operations
 * through the public {@link CompositeOperationDef} fluent API.
 * <p>
 * Holds the composite's member references in declaration order. A member is either a step
 * (the historical kind, recorded via {@link #step(String) step(...)}) or a nested operation
 * (recorded via {@link #operation(String) operation(...)}). References are not resolved
 * eagerly; they are resolved against the enclosing state machine's step and operation
 * registries when {@link #build(StateMachineImpl)} is invoked during state-machine
 * construction. Inline references contributed by this composite must already have been
 * registered with the state-machine def before that point.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
public final class CompositeOperationDefImpl<T, C> extends OperationDefImpl<T, C> implements CompositeOperationDef<T, C> {

    private final List<ActionRef<T, C>> actionRefs = new ArrayList<>();

    private Class<C> declaredContextType;

    public CompositeOperationDefImpl(String id) {
        super(id);
    }

    /**
     * Returns the context type declared on this composite via
     * {@link CompositeOperationDef#usingContext(Class)}, or {@code null} if none was
     * declared.
     *
     * <p>This is framework-internal infrastructure used by Transflux's own runtime; user code
     * should not invoke it directly.
     *
     * @return the declared context class, or {@code null}
     */
    public Class<C> getDeclaredContextType() {
        return declaredContextType;
    }

    @Override
    public CompositeOperationDefImpl<T, C> step(String registeredStepId) {
        actionRefs.add(ActionRef.byId(registeredStepId));
        return this;
    }

    @Override
    public CompositeOperationDefImpl<T, C> step(String id, Step<T, C> step) {
        actionRefs.add(ActionRef.inline(id, step));
        return this;
    }

    @Override
    public CompositeOperationDefImpl<T, C> step(String id, Class<? extends Step<T, C>> stepClass) {
        actionRefs.add(ActionRef.inline(id, stepClass));
        return this;
    }

    @Override
    public CompositeOperationDefImpl<T, C> conditional(String id, Consumer<ConditionalStepDef<T, C>> configurer) {
        requireNotBlank(id, "Conditional step ID");
        requireNotNull(configurer, "Conditional configurer");

        ConditionalStepDefImpl<T, C> def = new ConditionalStepDefImpl<>(id);
        configurer.accept(def);
        actionRefs.add(ActionRef.conditional(id, def));

        return this;
    }

    @Override
    public CompositeOperationDefImpl<T, C> operation(String registeredOperationId) {
        actionRefs.add(ActionRef.operationById(registeredOperationId));
        return this;
    }

    @Override
    public CompositeOperationDefImpl<T, C> operation(String id, Operation<T, C> operation) {
        actionRefs.add(ActionRef.operationInline(id, operation));
        return this;
    }

    @Override
    public CompositeOperationDefImpl<T, C> operation(String id, Class<? extends Operation<T, C>> operationClass) {
        actionRefs.add(ActionRef.operationInline(id, operationClass));
        return this;
    }

    @Override
    public CompositeOperationDefImpl<T, C> operation(String id, Operation<T, ?> operation,
                                                     Consumer<NestedOperationDef<T, C, C>> configurer) {
        requireNotBlank(id, "Operation ID");
        requireNotNull(operation, "Inline operation instance");
        requireNotNull(configurer, "Nested operation configurer");

        NestedOperationDefImpl<T, C, C> def = new NestedOperationDefImpl<>(id);
        configurer.accept(def);
        ResolvedContextMapping mapping = def.toResolvedMapping();

        actionRefs.add(ActionRef.operationInlineConfigured(id, operation, mapping));
        return this;
    }

    @Override
    public CompositeOperationDefImpl<T, C> operation(String id, Class<? extends Operation<T, ?>> operationClass,
                                                     Consumer<NestedOperationDef<T, C, C>> configurer) {
        requireNotBlank(id, "Operation ID");
        requireNotNull(operationClass, "Inline operation class");
        requireNotNull(configurer, "Nested operation configurer");

        NestedOperationDefImpl<T, C, C> def = new NestedOperationDefImpl<>(id);
        configurer.accept(def);
        ResolvedContextMapping mapping = def.toResolvedMapping();

        actionRefs.add(ActionRef.operationInlineConfigured(id, operationClass, mapping));
        return this;
    }

    @Override
    public CompositeOperationDefImpl<T, C> usingContext(Class<C> contextType) {
        requireNotNull(contextType, "Context type");
        if (this.declaredContextType != null && this.declaredContextType != contextType) {
            throw new TransfluxValidationException(
                "CompositeOperationDef '" + getId() + "' usingContext already declared as "
                    + this.declaredContextType.getName() + "; cannot redeclare as "
                    + contextType.getName());
        }
        this.declaredContextType = contextType;
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
     * Returns the member references in declaration order. Used by the enclosing state-machine
     * def to auto-register inline references before resolving any by-id references.
     *
     * @return an unmodifiable view of the member-reference list
     */
    List<ActionRef<T, C>> getActionRefs() {
        return Collections.unmodifiableList(actionRefs);
    }

    /**
     * Returns a map of {@code stepId -> Step} for every inline step instance contributed by
     * this composite, in declaration order. The result excludes by-id step references and all
     * operation members.
     *
     * <p>This is framework-internal infrastructure used by the state-machine def to
     * auto-register inline steps; user code should not invoke it directly.
     *
     * @return an unmodifiable map of step id to inline step instance
     */
    public Map<String, Step<T, C>> getInlineStepInstances() {
        Map<String, Step<T, C>> result = new LinkedHashMap<>();

        for (ActionRef<T, C> ref : actionRefs) {
            if (ref instanceof ActionRef.InlineInstance<T, C> ii) {
                result.put(ii.id(), ii.step());
            }
        }

        return Collections.unmodifiableMap(result);
    }

    /**
     * Returns a map of {@code stepId -> stepClass} for every inline step class contributed by
     * this composite, in declaration order. The result excludes by-id step references and all
     * operation members.
     *
     * <p>This is framework-internal infrastructure used by the state-machine def to
     * auto-register inline steps; user code should not invoke it directly.
     *
     * @return an unmodifiable map of step id to inline step class
     */
    public Map<String, Class<? extends Step<T, C>>> getInlineStepClasses() {
        Map<String, Class<? extends Step<T, C>>> result = new LinkedHashMap<>();

        for (ActionRef<T, C> ref : actionRefs) {
            if (ref instanceof ActionRef.InlineClass<T, C> ic) {
                result.put(ic.id(), ic.stepClass());
            }
        }

        return Collections.unmodifiableMap(result);
    }

    /**
     * Returns an ordered map of {@code conditionalId -> ConditionalStepDefImpl} for every
     * {@link ActionRef.Conditional} reference contributed by this composite, in declaration
     * order.
     *
     * <p>This is framework-internal infrastructure used by the state-machine def to walk into
     * conditionals when collecting inline step registrations and when building the
     * framework-built conditional executor for the step registry; user code should not invoke
     * it directly.
     *
     * @return an unmodifiable map of conditional id to conditional def
     */
    public Map<String, ConditionalStepDefImpl<T, C>> getConditionalDefs() {
        Map<String, ConditionalStepDefImpl<T, C>> result = new LinkedHashMap<>();

        for (ActionRef<T, C> ref : actionRefs) {
            if (ref instanceof ActionRef.Conditional<T, C> cond) {
                result.put(cond.id(), cond.def());
            }
        }

        return Collections.unmodifiableMap(result);
    }

    /**
     * Returns a map of {@code operationId -> Operation} for every inline nested operation
     * instance contributed by this composite, in declaration order. The result excludes by-id
     * operation references and all step members.
     *
     * <p>This is framework-internal infrastructure used by the state-machine def to
     * auto-register inline nested operations; user code should not invoke it directly.
     *
     * @return an unmodifiable map of operation id to inline operation instance
     */
    public Map<String, Operation<T, C>> getInlineOperationInstances() {
        Map<String, Operation<T, C>> result = new LinkedHashMap<>();

        for (ActionRef<T, C> ref : actionRefs) {
            if (ref instanceof ActionRef.OperationInlineInstance<T, C> oi) {
                result.put(oi.id(), oi.operation());
            } else if (ref instanceof ActionRef.OperationInlineInstanceConfigured<T, C> oic) {
                @SuppressWarnings("unchecked")
                Operation<T, C> raw = (Operation<T, C>) oic.operation();
                result.put(oic.id(), raw);
            }
        }

        return Collections.unmodifiableMap(result);
    }

    /**
     * Returns a map of {@code operationId -> operationClass} for every inline nested operation
     * class contributed by this composite, in declaration order. The result excludes by-id
     * operation references and all step members.
     *
     * <p>This is framework-internal infrastructure used by the state-machine def to
     * auto-register inline nested operations; user code should not invoke it directly.
     *
     * @return an unmodifiable map of operation id to inline operation class
     */
    public Map<String, Class<? extends Operation<T, C>>> getInlineOperationClasses() {
        Map<String, Class<? extends Operation<T, C>>> result = new LinkedHashMap<>();

        for (ActionRef<T, C> ref : actionRefs) {
            if (ref instanceof ActionRef.OperationInlineClass<T, C> oc) {
                result.put(oc.id(), oc.operationClass());
            } else if (ref instanceof ActionRef.OperationInlineClassConfigured<T, C> occ) {
                @SuppressWarnings("unchecked")
                Class<? extends Operation<T, C>> raw = (Class<? extends Operation<T, C>>) occ.operationClass();
                result.put(occ.id(), raw);
            }
        }

        return Collections.unmodifiableMap(result);
    }

    /**
     * Resolves each member reference against the state machine's step and operation registries
     * and produces a {@link BoundOperation} whose underlying {@link Operation} iterates the
     * bound members in declaration order. Step members are dispatched through
     * {@link StateMachineImpl#runBoundStep(BoundStep, TransitionView)}; nested operation
     * members are dispatched by entering an operation scope on the view, invoking the
     * operation against the parent's context (pass-through), and exiting the scope so any
     * subsequent step ids are recorded without the nested-op prefix.
     *
     * @param stateMachine the enclosing state machine; the step or operation registry must
     *                     already contain every referenced id
     *
     * @return the bound operation
     *
     * @throws TransfluxValidationException if the composite has no members, or any referenced
     *         id is not registered on the state machine
     */
    public BoundOperation<T, C> build(StateMachineImpl<T, C> stateMachine) {
        if (actionRefs.isEmpty()) {
            throw new TransfluxValidationException(
                "CompositeOperationDef '" + getId()
                    + "' has no members; call step(...) or operation(...) at least once before build");
        }

        if (declaredContextType != null) {
            Class<C> smContextType = stateMachine.getContextType();
            if (smContextType != null && !smContextType.equals(declaredContextType)) {
                throw new TransfluxValidationException(
                    "CompositeOperationDef '" + getId() + "' usingContext("
                        + declaredContextType.getName()
                        + ") does not match the state machine's context type "
                        + smContextType.getName());
            }
        }

        List<CompositeStep<T, C>> steps = new ArrayList<>(actionRefs.size());
        for (ActionRef<T, C> ref : actionRefs) {
            BoundAction<T, C> bound = ref.resolve(stateMachine, getId());
            ResolvedContextMapping mapping = (ref instanceof ActionRef.OperationRef<T, C> or)
                ? or.mapping()
                : ResolvedContextMapping.passThrough();
            steps.add(new CompositeStep<>(bound, mapping));
        }

        Operation<T, C> executor = new CompositeOperationExecutor<>(steps);

        return BoundOperation.of(getId(), getName(), getDescription(), executor);
    }

    /**
     * Pairs a resolved composite member with its context-mapping configuration. Step members
     * always carry a pass-through mapping; operation members carry whatever
     * {@link ActionRef.OperationRef#mapping()} returned for the originating ref.
     */
    private record CompositeStep<T, C>(BoundAction<T, C> action, ResolvedContextMapping mapping) {
    }

    /**
     * Iterates an ordered list of {@link CompositeStep} entries and invokes each one against
     * the supplied {@link Transition} view. {@link BoundStep} members are dispatched through
     * {@link StateMachineImpl#runBoundStep(BoundStep, TransitionView)} so that step-id
     * recording is uniform across composite-driven invocations and user-driven
     * {@code transition.step("id")} calls. {@link BoundOperation} members are dispatched
     * either in pass-through mode (the nested operation receives the parent's context
     * verbatim) or in mapped mode (a {@link ContextMapper#mapTo(Object)} produces a fresh
     * child context, the nested operation runs against that, and on successful return a
     * {@link ContextMapper#mapFrom(Object, Object)} folds any child-side mutations back into
     * the parent).
     *
     * <p>Mapper failure attribution: a {@code mapTo} failure throws before the nested
     * operation starts and therefore surfaces as a parent member failure at the nested
     * operation's position — no child member ids are recorded. A {@code mapFrom} failure
     * throws after the nested operation has returned successfully, so any inner step ids
     * the nested operation drove are already on the executed list; the failure attaches to
     * the nested operation's id.
     */
    @SuppressWarnings("ClassCanBeRecord")
    private static final class CompositeOperationExecutor<T, C> implements Operation<T, C> {
        private final List<CompositeStep<T, C>> steps;

        CompositeOperationExecutor(List<CompositeStep<T, C>> steps) {
            this.steps = steps;
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
            for (CompositeStep<T, C> step : steps) {
                BoundAction<T, C> action = step.action();
                if (action instanceof BoundStep<T, C> boundStep) {
                    StateMachineImpl.runBoundStep(boundStep, view);
                } else if (action instanceof BoundOperation<T, C> boundOperation) {
                    dispatchOperation(entity, context, view, boundOperation, step.mapping());
                }
            }
        }

        private void dispatchOperation(T entity, C context, TransitionView<T, C> view,
                                       BoundOperation<T, C> boundOperation,
                                       ResolvedContextMapping mapping) {
            view.enterOperation(boundOperation.id());
            try {
                if (mapping.isPassThrough()) {
                    boundOperation.operation().execute(entity, context, view);
                } else {
                    ContextMapper<Object, Object> mapper = mapping.mapper();
                    Object childContext = mapper.mapTo(context);

                    @SuppressWarnings("unchecked")
                    Operation<T, Object> rawOp = (Operation<T, Object>) boundOperation.operation();
                    @SuppressWarnings("unchecked")
                    Transition<T, Object> rawTransition = (Transition<T, Object>) (Transition<T, ?>) view;
                    rawOp.execute(entity, childContext, rawTransition);

                    mapper.mapFrom(context, childContext);
                }
            } finally {
                view.exitOperation();
            }
        }
    }
}
