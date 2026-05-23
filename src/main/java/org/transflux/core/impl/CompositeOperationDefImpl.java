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

import org.transflux.core.operation.*;

import org.springframework.lang.NonNull;
import org.transflux.core.Registry;
import org.transflux.core.impl.RegistryImpl;
import org.transflux.core.impl.StateMachineDefImpl;
import org.transflux.core.impl.StateMachineImpl;
import org.transflux.core.exception.TransfluxValidationException;
import org.transflux.core.transition.Transition;
import org.transflux.core.impl.TransitionView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.transflux.core.impl.ValidationUtils.requireNotBlank;
import static org.transflux.core.impl.ValidationUtils.requireNotNull;

/**
 * Implementation of {@link CompositeOperationDef}.
 *
 * <p>This is framework-internal infrastructure; user code constructs composite operations
 * through the public {@link CompositeOperationDef} fluent API.
 * <p>
 * Holds the composite's member references in declaration order. References are not resolved
 * eagerly; they are resolved against the enclosing state machine's step, operation, and mapper
 * registries when {@link #build(StateMachineImpl)} is invoked during state-machine
 * construction. Inline references contributed by this composite must already have been
 * registered with the state-machine def before that point.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
final class CompositeOperationDefImpl<T, C> extends OperationDefImpl<T, C> implements CompositeOperationDef<T, C> {

    private final List<ActionRef<T, C>> actionRefs = new ArrayList<>();

    private Class<C> declaredContextType;

    private RegistryImpl<T> scopeRegistry;

    public CompositeOperationDefImpl(String id) {
        super(id);
    }

    /**
     * Returns this composite's lexical-scope registry, populated during state-machine
     * construction. By-id refs declared inside this composite resolve against this registry,
     * which walks the parent chain up to the state-machine root.
     *
     * <p>This is framework-internal infrastructure used by Transflux's own runtime; user code
     * should not invoke it directly.
     *
     * @return the scope registry, or {@code null} if the state machine has not yet wired it
     */
    public RegistryImpl<T> getScopeRegistry() {
        return scopeRegistry;
    }

    /**
     * Wires this composite's lexical-scope registry. Called once during state-machine
     * construction, before {@link #build(StateMachineImpl)} runs.
     *
     * <p>This is framework-internal infrastructure used by Transflux's own runtime; user code
     * should not invoke it directly.
     *
     * @param scopeRegistry the scope registry; never {@code null}
     */
    public void setScopeRegistry(RegistryImpl<T> scopeRegistry) {
        this.scopeRegistry = scopeRegistry;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Class<C> contextType() {
        return declaredContextType != null ? declaredContextType : (Class<C>) Object.class;
    }

    @Override
    public CompositeOperationDefImpl<T, C> step(String registeredStepId) {
        actionRefs.add(ActionRef.byId(registeredStepId));
        return this;
    }

    @Override
    public CompositeOperationDefImpl<T, C> step(String registeredStepId, String mapperId) {
        requireNotBlank(registeredStepId, "Step reference ID");
        requireNotBlank(mapperId, "Mapper reference ID");
        actionRefs.add(ActionRef.byId(registeredStepId, MapperRef.byId(mapperId)));
        return this;
    }

    @Override
    public CompositeOperationDefImpl<T, C> step(String registeredStepId, Function<C, ?> inlineMapTo) {
        requireNotBlank(registeredStepId, "Step reference ID");
        requireNotNull(inlineMapTo, "Inline mapper function");
        actionRefs.add(ActionRef.byId(registeredStepId, MapperRef.inline(inlineMapTo)));
        return this;
    }

    @Override
    public CompositeOperationDefImpl<T, C> step(String registeredStepId, ContextMapper<C, ?> inlineMapper) {
        requireNotBlank(registeredStepId, "Step reference ID");
        requireNotNull(inlineMapper, "Inline mapper instance");
        actionRefs.add(ActionRef.byId(registeredStepId, MapperRef.inline(inlineMapper)));
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
    public CompositeOperationDefImpl<T, C> operation(String registeredOperationId, String mapperId) {
        requireNotBlank(registeredOperationId, "Operation reference ID");
        requireNotBlank(mapperId, "Mapper reference ID");
        actionRefs.add(ActionRef.operationById(registeredOperationId, MapperRef.byId(mapperId)));
        return this;
    }

    @Override
    public CompositeOperationDefImpl<T, C> operation(String registeredOperationId, Function<C, ?> inlineMapTo) {
        requireNotBlank(registeredOperationId, "Operation reference ID");
        requireNotNull(inlineMapTo, "Inline mapper function");
        actionRefs.add(ActionRef.operationById(registeredOperationId, MapperRef.inline(inlineMapTo)));
        return this;
    }

    @Override
    public CompositeOperationDefImpl<T, C> operation(String registeredOperationId, ContextMapper<C, ?> inlineMapper) {
        requireNotBlank(registeredOperationId, "Operation reference ID");
        requireNotNull(inlineMapper, "Inline mapper instance");
        actionRefs.add(ActionRef.operationById(registeredOperationId, MapperRef.inline(inlineMapper)));
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
     * Returns the composite's action references in declaration order.
     *
     * <p>This is framework-internal infrastructure used by Transflux's own runtime; user code
     * should not invoke it directly.
     *
     * @return an unmodifiable view of the action ref list
     */
    public List<ActionRef<T, C>> getActionRefs() {
        return Collections.unmodifiableList(actionRefs);
    }

    /**
     * Returns the ids of every step-by-id reference declared by this composite — used by the
     * build-time context-compatibility check.
     *
     * <p>This is framework-internal infrastructure used by Transflux's own runtime; user code
     * should not invoke it directly.
     *
     * @return the referenced step ids in declaration order
     */
    public List<String> getStepByIdReferenceIds() {
        List<String> ids = new ArrayList<>();
        for (ActionRef<T, C> ref : actionRefs) {
            if (ref instanceof ActionRef.ById<T, C> r) {
                ids.add(r.id());
            }
        }
        return Collections.unmodifiableList(ids);
    }

    /**
     * Returns the ids of every operation-by-id reference declared by this composite — used by
     * the build-time context-compatibility check and the cycle-detection pass.
     *
     * <p>This is framework-internal infrastructure used by Transflux's own runtime; user code
     * should not invoke it directly.
     *
     * @return the referenced operation ids in declaration order
     */
    public List<String> getOperationByIdReferenceIds() {
        List<String> ids = new ArrayList<>();
        for (ActionRef<T, C> ref : actionRefs) {
            if (ref instanceof ActionRef.OperationById<T, C> r) {
                ids.add(r.id());
            }
        }
        return Collections.unmodifiableList(ids);
    }

    /**
     * Returns a map of {@code stepId -> Step} for every inline step instance contributed by
     * this composite, in declaration order.
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
     * this composite, in declaration order.
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
     * conditionals when collecting inline step registrations; user code should not invoke it
     * directly.
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
     * instance contributed by this composite, in declaration order.
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
            }
        }

        return Collections.unmodifiableMap(result);
    }

    /**
     * Returns a map of {@code operationId -> operationClass} for every inline nested operation
     * class contributed by this composite, in declaration order.
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
            }
        }

        return Collections.unmodifiableMap(result);
    }

    /**
     * Resolves each member reference against the state machine's step, operation, and mapper
     * registries and produces a {@link BoundOperation} whose underlying {@link Operation}
     * iterates the bound members in declaration order. Step and operation members are
     * dispatched through a unified per-member path that consults the resolved
     * {@link ResolvedContextMapping} carried alongside each bound action.
     *
     * @param stateMachine the enclosing state machine; the step, operation, and mapper
     *                     registries must already contain every referenced id
     *
     * @return the bound operation
     *
     * @throws TransfluxValidationException if the composite has no members, or any referenced
     *         id is not registered on the state machine
     */
    public BoundOperation<T, C> build(StateMachineImpl<T> stateMachine) {
        if (actionRefs.isEmpty()) {
            throw new TransfluxValidationException(
                "CompositeOperationDef '" + getId()
                    + "' has no members; call step(...) or operation(...) at least once before build");
        }

        if (scopeRegistry == null) {
            throw new TransfluxValidationException(
                "CompositeOperationDef '" + getId()
                    + "' has no scope registry; state-machine construction did not wire it");
        }

        List<CompositeMember<T, C>> members = new ArrayList<>(actionRefs.size());
        for (ActionRef<T, C> ref : actionRefs) {
            BoundAction<T, C> bound = ref.resolve(stateMachine, scopeRegistry, getId());
            ResolvedContextMapping mapping = resolveMapping(stateMachine, ref);
            members.add(new CompositeMember<>(bound, mapping));
        }

        Operation<T, C> executor = new CompositeOperationExecutor<>(members, scopeRegistry);

        return BoundOperation.of(getId(), getName(), getDescription(), executor);
    }

    private ResolvedContextMapping resolveMapping(StateMachineImpl<T> stateMachine, ActionRef<T, C> ref) {
        MapperRef mapperRef = ref.mapperRef();

        if (mapperRef instanceof MapperRef.PassThrough) {
            return ResolvedContextMapping.passThrough();
        }

        if (mapperRef instanceof MapperRef.ById byId) {
            var impl = resolveMapperDef(stateMachine, byId);
            return ResolvedContextMapping.mapped(impl.buildMapper());
        }

        if (mapperRef instanceof MapperRef.InlineFunction inlineFn) {
            @SuppressWarnings("unchecked")
            Function<Object, Object> fn = (Function<Object, Object>) inlineFn.fn();
            return ResolvedContextMapping.mapped(new InlineFunctionMapper(fn));
        }

        if (mapperRef instanceof MapperRef.InlineMapper inlineMapper) {
            @SuppressWarnings("unchecked")
            ContextMapper<Object, Object> m = (ContextMapper<Object, Object>) inlineMapper.mapper();
            return ResolvedContextMapping.mapped(m);
        }

        throw new TransfluxValidationException(
            "CompositeOperationDef '" + getId() + "' has unsupported mapper reference: "
                + mapperRef.getClass().getName());
    }

    @NonNull
    private MapperDefImpl<Object, Object> resolveMapperDef(StateMachineImpl<T> stateMachine, MapperRef.ById byId) {
        StateMachineDefImpl<T> def = stateMachine.getDef();
        MapperDef<?, ?> mapperDef = def.getMapperDef(byId.mapperId());
        if (mapperDef == null) {
            throw new TransfluxValidationException(
                "CompositeOperationDef '" + getId() + "' references unknown mapper id '"
                    + byId.mapperId() + "'");
        }
        @SuppressWarnings("unchecked")
        MapperDefImpl<Object, Object> impl = (MapperDefImpl<Object, Object>) mapperDef;
        return impl;
    }

    /**
     * Adapter that exposes a parent-to-child {@link Function} as a {@link ContextMapper} whose
     * {@link ContextMapper#mapFrom(Object, Object) mapFrom} is the default no-op.
     */
    private record InlineFunctionMapper(Function<Object, Object> mapTo) implements ContextMapper<Object, Object> {

        @Override
        public Object mapTo(Object parentContext) {
            return mapTo.apply(parentContext);
        }
    }

    /**
     * Pairs a resolved composite member with its context-mapping configuration. Each member is
     * dispatched uniformly: optional {@link ContextMapper#mapTo(Object) mapTo} before, the
     * bound action's invocation against the (possibly mapped) child context, optional
     * {@link ContextMapper#mapFrom(Object, Object) mapFrom} after on success.
     */
    private record CompositeMember<T, C>(BoundAction<T, C> action, ResolvedContextMapping mapping) {
    }

    /**
     * Iterates an ordered list of {@link CompositeMember} entries and invokes each one against
     * the supplied {@link Transition} view through a single unified dispatch path.
     *
     * <p>Step members in pass-through mode go through
     * {@link StateMachineImpl#runBoundStep(BoundStep, TransitionView)} so that step-id
     * recording is uniform across composite-driven invocations and user-driven
     * {@code transition.step("id")} calls. Step members with a mapper enter a child-context
     * scope: {@code mapTo} produces the child context, the bound step runs against it, then
     * {@code mapFrom} folds any changes back into the parent.
     *
     * <p>Operation members follow the same pattern: pass-through mode runs the bound operation
     * with the parent context verbatim; mapped mode produces a child context, runs the
     * operation against it, then folds back on success.
     *
     * <p>Mapper failure attribution: a {@code mapTo} failure throws before the member starts
     * and therefore surfaces as a parent member failure at the member's position — no child
     * step ids are recorded for it. A {@code mapFrom} failure throws after the member has
     * returned successfully, so any inner step ids the member drove are already on the executed
     * list; the failure attaches to the parent's position and is treated as a parent failure
     * (the child completed successfully — its compensations are not invoked).
     */
    @SuppressWarnings("ClassCanBeRecord")
    private static final class CompositeOperationExecutor<T, C> implements Operation<T, C> {
        private final List<CompositeMember<T, C>> members;
        private final Registry<T> scopeRegistry;

        CompositeOperationExecutor(List<CompositeMember<T, C>> members, Registry<T> scopeRegistry) {
            this.members = members;
            this.scopeRegistry = scopeRegistry;
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
            view.pushScope(scopeRegistry);
            try {
                for (CompositeMember<T, C> member : members) {
                    dispatchMember(view, member);
                }
            } finally {
                view.popScope();
            }
        }

        private void dispatchMember(TransitionView<T, C> view, CompositeMember<T, C> member) {
            BoundAction<T, C> action = member.action();
            ResolvedContextMapping mapping = member.mapping();

            if (action instanceof BoundStep<T, C> boundStep) {
                dispatchStep(view, boundStep, mapping);
            } else if (action instanceof BoundOperation<T, C> boundOperation) {
                dispatchOperation(view, boundOperation, mapping);
            }
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private void dispatchStep(TransitionView<T, C> view, BoundStep<T, C> boundStep,
                                  ResolvedContextMapping mapping) {
            if (mapping.isPassThrough()) {
                StateMachineImpl.runBoundStep(boundStep, view);
                return;
            }
            view.runChildStep((BoundStep) boundStep, mapping.mapper());
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private void dispatchOperation(TransitionView<T, C> view, BoundOperation<T, C> boundOperation,
                                       ResolvedContextMapping mapping) {
            ContextMapper<Object, Object> mapper = mapping.isPassThrough() ? null : mapping.mapper();
            view.runChildOperation((BoundOperation) boundOperation, mapper);
        }
    }
}
