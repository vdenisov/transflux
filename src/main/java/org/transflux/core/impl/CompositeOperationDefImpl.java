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
import org.transflux.core.operation.CompositeOperationDef;
import org.transflux.core.operation.ConditionalStepDef;
import org.transflux.core.operation.ContextMapper;
import org.transflux.core.operation.Operation;
import org.transflux.core.operation.Step;
import org.transflux.core.transition.Transition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.transflux.core.Preconditions.requireNotBlank;
import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Implementation of {@link CompositeOperationDef}.
 * <p>
 * Holds the composite's member references in declaration order. References are not resolved
 * eagerly; they are resolved against the enclosing state machine's step, operation, and mapper
 * registries when {@link #buildBound(StateMachineImpl)} is invoked during state-machine
 * construction. Inline references contributed by this composite must already have been
 * registered with the state-machine def before that point.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
final class CompositeOperationDefImpl<T, C>
    extends OperationDefImpl<T, C, CompositeOperationDefImpl<T, C>> implements CompositeOperationDef<T, C> {

    private final List<ActionRef<T, C>> actionRefs = new ArrayList<>();

    private Class<C> declaredContextType;

    private RegistryImpl<T> scopeRegistry;

    CompositeOperationDefImpl(String id) {
        super(id);
    }

    /**
     * Wires this composite's lexical-scope registry. Called once during state-machine
     * construction, before {@link #buildBound(StateMachineImpl)} runs.
     *
     * @param scopeRegistry the scope registry; never {@code null}
     */
    void setScopeRegistry(RegistryImpl<T> scopeRegistry) {
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
    public CompositeOperationDefImpl<T, C> step(Identifiable registeredStep) {
        requireNotNull(registeredStep, "Step identifiable");
        return step(registeredStep.getId());
    }

    @Override
    public CompositeOperationDefImpl<T, C> step(Identifiable registeredStep, Identifiable mapper) {
        requireNotNull(registeredStep, "Step identifiable");
        requireNotNull(mapper, "Mapper identifiable");
        return step(registeredStep.getId(), mapper.getId());
    }

    @Override
    public CompositeOperationDefImpl<T, C> step(Identifiable registeredStep, String mapperId) {
        requireNotNull(registeredStep, "Step identifiable");
        return step(registeredStep.getId(), mapperId);
    }

    @Override
    public CompositeOperationDefImpl<T, C> step(String registeredStepId, Identifiable mapper) {
        requireNotNull(mapper, "Mapper identifiable");
        return step(registeredStepId, mapper.getId());
    }

    @Override
    public CompositeOperationDefImpl<T, C> step(String id, Step<T, C> step) {
        actionRefs.add(ActionRef.inline(id, step));
        return this;
    }

    @Override
    public CompositeOperationDefImpl<T, C> step(Identifiable stepIdentifiable, Step<T, C> step) {
        requireNotNull(stepIdentifiable, "Step identifiable");
        return step(stepIdentifiable.getId(), step);
    }

    @Override
    public CompositeOperationDefImpl<T, C> step(String id, Class<? extends Step<T, C>> stepClass) {
        actionRefs.add(ActionRef.inline(id, stepClass));
        return this;
    }

    @Override
    public CompositeOperationDefImpl<T, C> step(Identifiable stepIdentifiable, Class<? extends Step<T, C>> stepClass) {
        requireNotNull(stepIdentifiable, "Step identifiable");
        return step(stepIdentifiable.getId(), stepClass);
    }

    @Override
    public CompositeOperationDefImpl<T, C> conditional(String id, Consumer<ConditionalStepDef<T, C>> configurer) {
        requireNotBlank(id, "Conditional step ID");
        requireNotNull(configurer, "Conditional configurer");

        ConditionalStepDefImpl<T, C> def = new ConditionalStepDefImpl<>(id);
        ConfigurableDefImpl.runConfigurer(def, configurer);
        actionRefs.add(ActionRef.conditional(id, def));

        return this;
    }

    @Override
    public CompositeOperationDefImpl<T, C> conditional(Identifiable conditionalIdentifiable, Consumer<ConditionalStepDef<T, C>> configurer) {
        requireNotNull(conditionalIdentifiable, "Conditional identifiable");
        return conditional(conditionalIdentifiable.getId(), configurer);
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
    public CompositeOperationDefImpl<T, C> operation(Identifiable registeredOperation) {
        requireNotNull(registeredOperation, "Operation identifiable");
        return operation(registeredOperation.getId());
    }

    @Override
    public CompositeOperationDefImpl<T, C> operation(Identifiable registeredOperation, Identifiable mapper) {
        requireNotNull(registeredOperation, "Operation identifiable");
        requireNotNull(mapper, "Mapper identifiable");
        return operation(registeredOperation.getId(), mapper.getId());
    }

    @Override
    public CompositeOperationDefImpl<T, C> operation(Identifiable registeredOperation, String mapperId) {
        requireNotNull(registeredOperation, "Operation identifiable");
        return operation(registeredOperation.getId(), mapperId);
    }

    @Override
    public CompositeOperationDefImpl<T, C> operation(String registeredOperationId, Identifiable mapper) {
        requireNotNull(mapper, "Mapper identifiable");
        return operation(registeredOperationId, mapper.getId());
    }

    @Override
    public CompositeOperationDefImpl<T, C> operation(String id, Operation<T, C> operation) {
        actionRefs.add(ActionRef.operationInline(id, operation));
        return this;
    }

    @Override
    public CompositeOperationDefImpl<T, C> operation(Identifiable operationIdentifiable, Operation<T, C> operation) {
        requireNotNull(operationIdentifiable, "Operation identifiable");
        return operation(operationIdentifiable.getId(), operation);
    }

    @Override
    public CompositeOperationDefImpl<T, C> operation(String id, Class<? extends Operation<T, C>> operationClass) {
        actionRefs.add(ActionRef.operationInline(id, operationClass));
        return this;
    }

    @Override
    public CompositeOperationDefImpl<T, C> operation(Identifiable operationIdentifiable, Class<? extends Operation<T, C>> operationClass) {
        requireNotNull(operationIdentifiable, "Operation identifiable");
        return operation(operationIdentifiable.getId(), operationClass);
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

    /**
     * Returns the composite's action references in declaration order.
     *
     * @return an unmodifiable view of the action ref list
     */
    List<ActionRef<T, C>> getActionRefs() {
        return Collections.unmodifiableList(actionRefs);
    }

    /**
     * Returns the ids of every operation-by-id reference declared by this composite — used by
     * the cycle-detection pass.
     *
     * @return the referenced operation ids in declaration order
     */
    List<String> getOperationByIdReferenceIds() {
        List<String> ids = new ArrayList<>();
        for (ActionRef<T, C> ref : actionRefs) {
            if (ref instanceof ActionRef.OperationById<T, C> r) {
                ids.add(r.id());
            }
        }
        return Collections.unmodifiableList(ids);
    }

    /**
     * Walks this composite's action refs and forwards each to the supplied sink. By-id refs
     * no-op; inline step / operation refs push themselves; conditional refs recurse into their
     * branches and then register their own bound step. Drives the scope-binding pass in
     * {@link #bindScope}.
     */
    void collectInlineRegistrations(InlineRegistrationSink<T, C> sink) {
        for (ActionRef<T, C> ref : actionRefs) {
            ref.collectInlineRegistrations(sink);
        }
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
    @Override
    BoundOperation<T, C> buildBound(StateMachineImpl<T> stateMachine) {
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
            ResolvedContextMapping mapping = ref.mapperRef().resolve(stateMachine, getId());
            members.add(new CompositeMember<>(bound, mapping));
        }

        Operation<T, C> executor = new CompositeOperationExecutor<>(members, scopeRegistry);

        return BoundOperation.of(getId(), getName(), getDescription(), executor);
    }

    @Override
    void checkRefs(Class<?> scopeContext, String scopeLabel, StateMachineDefImpl<T> smDef) {
        Class<?> effectiveScope = scopeContext != null ? scopeContext : Object.class;

        for (ActionRef<T, C> ref : actionRefs) {
            if (ref instanceof ActionRef.ById<T, ?> stepRef) {
                Class<?> componentCtx = smDef.componentContextTypeOrDefault(stepRef.id());
                stepRef.mapperRef().validateAgainst(effectiveScope, scopeLabel, "step",
                    stepRef.id(), componentCtx, smDef.getMapperRegistrations());
            } else if (ref instanceof ActionRef.OperationById<T, ?> opRef) {
                Class<?> componentCtx = smDef.componentContextTypeOrDefault(opRef.id());
                opRef.mapperRef().validateAgainst(effectiveScope, scopeLabel, "operation",
                    opRef.id(), componentCtx, smDef.getMapperRegistrations());
            }
        }
    }

    @Override
    void bindScope(StateMachineImpl<T> stateMachine,
                   RegistryImpl<T> rootRegistry,
                   Map<String, Object> canonical,
                   Map<String, BoundCondition<T, ?>> conditionRegistry) {
        @SuppressWarnings("unchecked")
        Map<String, BoundCondition<T, C>> typedConditions = (Map<String, BoundCondition<T, C>>) (Map<?, ?>) conditionRegistry;

        RegistryImpl<T> scope = new RegistryImpl<>(rootRegistry);
        setScopeRegistry(scope);

        InlineRegistrationSink<T, C> sink = new InlineRegistrationSink<>(
            stateMachine, scope, canonical, contextType(), typedConditions);
        collectInlineRegistrations(sink);
    }

    @Override
    void flattenScope() {
        if (scopeRegistry != null) {
            scopeRegistry.flatten();
        }
    }

    @Override
    java.util.Optional<String> scanScopeFor(String id, String excludingId) {
        if (!getId().equals(excludingId) && scopeRegistry != null && scopeRegistry.get(id).isPresent()) {
            return java.util.Optional.of(getId());
        }
        return java.util.Optional.empty();
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
