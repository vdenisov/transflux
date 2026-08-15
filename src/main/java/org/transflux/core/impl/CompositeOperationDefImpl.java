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
import org.transflux.core.action.CompositeOperationDef;
import org.transflux.core.action.ConditionalStepDef;
import org.transflux.core.action.ContextMapper;
import org.transflux.core.action.Action;
import org.transflux.core.transition.Transition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        requireConfigurerActive("step");
        actionRefs.add(ActionRef.byId(registeredStepId));
        return this;
    }

    @Override
    public CompositeOperationDefImpl<T, C> step(String registeredStepId, String mapperId) {
        requireConfigurerActive("step");
        requireNotBlank(registeredStepId, "Step reference ID");
        requireNotBlank(mapperId, "Mapper reference ID");
        actionRefs.add(ActionRef.byId(registeredStepId, MapperRef.byId(mapperId)));
        return this;
    }

    @Override
    public CompositeOperationDefImpl<T, C> step(String registeredStepId, Function<C, ?> inlineMapTo) {
        requireConfigurerActive("step");
        requireNotBlank(registeredStepId, "Step reference ID");
        requireNotNull(inlineMapTo, "Inline mapper function");
        actionRefs.add(ActionRef.byId(registeredStepId, MapperRef.inline(inlineMapTo)));
        return this;
    }

    @Override
    public CompositeOperationDefImpl<T, C> step(String registeredStepId, ContextMapper<C, ?> inlineMapper) {
        requireConfigurerActive("step");
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
    public CompositeOperationDefImpl<T, C> step(String id, Action<T, C> step) {
        requireConfigurerActive("step");
        actionRefs.add(ActionRef.inline(id, step, ActionKind.STEP));
        return this;
    }

    @Override
    public CompositeOperationDefImpl<T, C> step(Identifiable stepIdentifiable, Action<T, C> step) {
        requireNotNull(stepIdentifiable, "Step identifiable");
        return step(stepIdentifiable.getId(), step);
    }

    @Override
    public CompositeOperationDefImpl<T, C> step(String id, Class<? extends Action<T, C>> stepClass) {
        requireConfigurerActive("step");
        actionRefs.add(ActionRef.inline(id, stepClass, ActionKind.STEP));
        return this;
    }

    @Override
    public CompositeOperationDefImpl<T, C> step(Identifiable stepIdentifiable, Class<? extends Action<T, C>> stepClass) {
        requireNotNull(stepIdentifiable, "Step identifiable");
        return step(stepIdentifiable.getId(), stepClass);
    }

    @Override
    public CompositeOperationDefImpl<T, C> conditional(String id, Consumer<ConditionalStepDef<T, C>> configurer) {
        requireConfigurerActive("conditional");
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
        requireConfigurerActive("operation");
        actionRefs.add(ActionRef.byId(registeredOperationId));
        return this;
    }

    @Override
    public CompositeOperationDefImpl<T, C> operation(String registeredOperationId, String mapperId) {
        requireConfigurerActive("operation");
        requireNotBlank(registeredOperationId, "Operation reference ID");
        requireNotBlank(mapperId, "Mapper reference ID");
        actionRefs.add(ActionRef.byId(registeredOperationId, MapperRef.byId(mapperId)));
        return this;
    }

    @Override
    public CompositeOperationDefImpl<T, C> operation(String registeredOperationId, Function<C, ?> inlineMapTo) {
        requireConfigurerActive("operation");
        requireNotBlank(registeredOperationId, "Operation reference ID");
        requireNotNull(inlineMapTo, "Inline mapper function");
        actionRefs.add(ActionRef.byId(registeredOperationId, MapperRef.inline(inlineMapTo)));
        return this;
    }

    @Override
    public CompositeOperationDefImpl<T, C> operation(String registeredOperationId, ContextMapper<C, ?> inlineMapper) {
        requireConfigurerActive("operation");
        requireNotBlank(registeredOperationId, "Operation reference ID");
        requireNotNull(inlineMapper, "Inline mapper instance");
        actionRefs.add(ActionRef.byId(registeredOperationId, MapperRef.inline(inlineMapper)));
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
    public CompositeOperationDefImpl<T, C> operation(String id, Action<T, C> operation) {
        requireConfigurerActive("operation");
        actionRefs.add(ActionRef.inline(id, operation, ActionKind.OPERATION));
        return this;
    }

    @Override
    public CompositeOperationDefImpl<T, C> operation(Identifiable operationIdentifiable, Action<T, C> operation) {
        requireNotNull(operationIdentifiable, "Operation identifiable");
        return operation(operationIdentifiable.getId(), operation);
    }

    @Override
    public CompositeOperationDefImpl<T, C> operation(String id, Class<? extends Action<T, C>> operationClass) {
        requireConfigurerActive("operation");
        actionRefs.add(ActionRef.inline(id, operationClass, ActionKind.OPERATION));
        return this;
    }

    @Override
    public CompositeOperationDefImpl<T, C> operation(Identifiable operationIdentifiable, Class<? extends Action<T, C>> operationClass) {
        requireNotNull(operationIdentifiable, "Operation identifiable");
        return operation(operationIdentifiable.getId(), operationClass);
    }

    @Override
    public CompositeOperationDefImpl<T, C> usingContext(Class<C> contextType) {
        requireConfigurerActive("usingContext");
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
     * Returns the ids of every by-id reference declared by this composite - the candidate edges
     * for the cycle-detection pass. A reference to an imperative action cannot close a cycle
     * (it binds no children at definition time), so the caller narrows this list to ids that
     * name a declarative container before walking it.
     *
     * @return the referenced ids in declaration order
     */
    List<String> getByIdReferenceIds() {
        List<String> ids = new ArrayList<>();
        for (ActionRef<T, C> ref : actionRefs) {
            if (ref instanceof ActionRef.ById<T, C> r) {
                ids.add(r.id());
            }
        }
        return Collections.unmodifiableList(ids);
    }

    /**
     * Walks this composite's action refs and forwards each to the supplied sink. By-id refs
     * no-op; inline refs push themselves; conditional refs recurse into their branches and then
     * register their own bound action. Drives the scope-binding pass in {@link #bindScope}.
     */
    void collectInlineRegistrations(InlineRegistrationSink<T, C> sink) {
        for (ActionRef<T, C> ref : actionRefs) {
            ref.collectInlineRegistrations(sink);
        }
    }

    /**
     * Resolves each member reference against the state machine's step, operation, and mapper
     * registries and produces a {@link BoundAction} whose underlying {@link Action}
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
    BoundAction<T, C> buildBound(StateMachineImpl<T> stateMachine) {
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

        Action<T, C> executor = new CompositeOperationExecutor<>(members, scopeRegistry);

        return BoundAction.of(getId(), executor, ActionKind.OPERATION);
    }

    @Override
    void checkRefs(Class<?> scopeContext, String scopeLabel, StateMachineDefImpl<T> smDef) {
        Class<?> effectiveScope = scopeContext != null ? scopeContext : Object.class;

        for (ActionRef<T, C> ref : actionRefs) {
            if (ref instanceof ActionRef.ById<T, ?> byId) {
                Class<?> componentCtx = smDef.componentContextTypeOrDefault(byId.id());
                byId.mapperRef().validateAgainst(effectiveScope, scopeLabel, "action",
                    byId.id(), componentCtx, smDef.getMapperRegistrations());
            }
        }
    }

    @Override
    void bindScope(RegistryImpl<T> rootRegistry,
                   Map<String, Object> canonical,
                   Map<String, BoundCondition<T, ?>> conditionRegistry) {
        @SuppressWarnings("unchecked")
        Map<String, BoundCondition<T, C>> typedConditions = (Map<String, BoundCondition<T, C>>) (Map<?, ?>) conditionRegistry;

        RegistryImpl<T> scope = new RegistryImpl<>(rootRegistry);
        setScopeRegistry(scope);

        InlineRegistrationSink<T, C> sink = new InlineRegistrationSink<>(
            scope, canonical, contextType(), typedConditions);
        collectInlineRegistrations(sink);
    }

    @Override
    void flattenScope() {
        if (scopeRegistry != null) {
            scopeRegistry.flatten();
        }
    }

    @Override
    Optional<String> scanScopeFor(String id, String excludingId) {
        if (!getId().equals(excludingId) && scopeRegistry != null && scopeRegistry.get(id).isPresent()) {
            return Optional.of(getId());
        }
        return Optional.empty();
    }

    @Override
    Registry<T> getScopeRegistry() {
        return scopeRegistry;
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
     * <p>Which of the two runners a member takes is decided by the form it was <em>registered</em>
     * in, read off the bound record, not by the verb used at this call site. An imperative member
     * in pass-through mode goes through
     * {@link StateMachineImpl#runBoundStep(BoundAction, TransitionView)} so that id recording is
     * uniform across composite-driven invocations and user-driven dispatch from inside an action
     * body. With a mapper it enters a child-context scope instead: {@code mapTo} produces the
     * child context, the action runs against it, then {@code mapFrom} folds any changes back into
     * the parent.
     *
     * <p>A declarative member follows the same pattern through
     * {@link TransitionView#runChildOperation}: pass-through mode runs it with the parent context
     * verbatim; mapped mode produces a child context, runs against it, then folds back on success.
     *
     * <p>Mapper failure attribution: a {@code mapTo} failure throws before the member starts
     * and therefore surfaces as a parent member failure at the member's position — no child
     * step ids are recorded for it. A {@code mapFrom} failure throws after the member has
     * returned successfully, so any inner step ids the member drove are already on the executed
     * list; the failure attaches to the parent's position and is treated as a parent failure
     * (the child completed successfully — its compensations are not invoked).
     */
    @SuppressWarnings("ClassCanBeRecord")
    private static final class CompositeOperationExecutor<T, C> implements Action<T, C> {
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

        @SuppressWarnings({"unchecked", "rawtypes"})
        private void dispatchMember(TransitionView<T, C> view, CompositeMember<T, C> member) {
            BoundAction<T, C> action = member.action();
            ResolvedContextMapping mapping = member.mapping();

            if (action.kind() == ActionKind.STEP) {
                if (mapping.isPassThrough()) {
                    StateMachineImpl.runBoundStep(action, view);
                    return;
                }
                view.runChildStep((BoundAction) action, mapping.mapper());
                return;
            }

            ContextMapper<Object, Object> mapper = mapping.isPassThrough() ? null : mapping.mapper();
            view.runChildOperation((BoundAction) action, mapper);
        }
    }
}
