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
import org.transflux.core.action.OperationDef;
import org.transflux.core.action.ConditionalOperationDef;
import org.transflux.core.action.ContextMapper;
import org.transflux.core.action.Action;
import org.transflux.core.action.StepDef;
import org.transflux.core.transition.ExecutingTransition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.transflux.core.Preconditions.requireNotBlank;
import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Implementation of {@link OperationDef}.
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
final class OperationDefImpl<T, C>
    extends ActionDefImpl<T, C, OperationDefImpl<T, C>> implements OperationDef<T, C> {

    private final List<ActionRef<T, C>> actionRefs = new ArrayList<>();

    private Class<C> declaredContextType;

    private RegistryImpl<T> scopeRegistry;

    OperationDefImpl(String id) {
        super(id, "operation", "Operation ID");
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
    public OperationDefImpl<T, C> run(String id) {
        requireConfigurerActive("run");
        actionRefs.add(ActionRef.byId(id));
        return this;
    }

    @Override
    public OperationDefImpl<T, C> run(String id, String mapperId) {
        requireConfigurerActive("run");
        requireNotBlank(id, "Action reference ID");
        requireNotBlank(mapperId, "Mapper reference ID");
        actionRefs.add(ActionRef.byId(id, MapperRef.byId(mapperId)));
        return this;
    }

    @Override
    public OperationDefImpl<T, C> run(String id, Function<C, ?> inlineMapTo) {
        requireConfigurerActive("run");
        requireNotBlank(id, "Action reference ID");
        requireNotNull(inlineMapTo, "Inline mapper function");
        actionRefs.add(ActionRef.byId(id, MapperRef.inline(inlineMapTo)));
        return this;
    }

    @Override
    public OperationDefImpl<T, C> run(String id, ContextMapper<C, ?> inlineMapper) {
        requireConfigurerActive("run");
        requireNotBlank(id, "Action reference ID");
        requireNotNull(inlineMapper, "Inline mapper instance");
        actionRefs.add(ActionRef.byId(id, MapperRef.inline(inlineMapper)));
        return this;
    }

    @Override
    public OperationDefImpl<T, C> run(Identifiable registeredAction) {
        requireNotNull(registeredAction, "Action identifiable");
        return run(registeredAction.getId());
    }

    @Override
    public OperationDefImpl<T, C> run(Identifiable registeredAction, Identifiable mapper) {
        requireNotNull(registeredAction, "Action identifiable");
        requireNotNull(mapper, "Mapper identifiable");
        return run(registeredAction.getId(), mapper.getId());
    }

    @Override
    public OperationDefImpl<T, C> run(Identifiable registeredAction, String mapperId) {
        requireNotNull(registeredAction, "Action identifiable");
        return run(registeredAction.getId(), mapperId);
    }

    @Override
    public OperationDefImpl<T, C> run(String id, Identifiable mapper) {
        requireNotNull(mapper, "Mapper identifiable");
        return run(id, mapper.getId());
    }

    @Override
    public OperationDefImpl<T, C> step(String id, Action<T, C> action) {
        requireConfigurerActive("step");
        actionRefs.add(ActionRef.inline(id, action, ActionKind.STEP));
        return this;
    }

    @Override
    public OperationDefImpl<T, C> step(Identifiable actionIdentifiable, Action<T, C> action) {
        requireNotNull(actionIdentifiable, "Step identifiable");
        return step(actionIdentifiable.getId(), action);
    }

    @Override
    public OperationDefImpl<T, C> step(String id, Class<? extends Action<T, C>> actionClass) {
        requireConfigurerActive("step");
        actionRefs.add(ActionRef.inline(id, actionClass, ActionKind.STEP));
        return this;
    }

    @Override
    public OperationDefImpl<T, C> step(Identifiable actionIdentifiable, Class<? extends Action<T, C>> actionClass) {
        requireNotNull(actionIdentifiable, "Step identifiable");
        return step(actionIdentifiable.getId(), actionClass);
    }

    @Override
    public OperationDefImpl<T, C> step(String id, Consumer<StepDef<T, C>> configurer) {
        requireConfigurerActive("step");
        requireNotBlank(id, "Step ID");
        requireNotNull(configurer, "Step configurer");
        StepDefImpl<T, C> def = new StepDefImpl<>(id);
        ConfigurableDefImpl.runConfigurer(def, configurer);
        actionRefs.add(ActionRef.inline(id, def));
        return this;
    }

    @Override
    public OperationDefImpl<T, C> step(Identifiable actionIdentifiable, Consumer<StepDef<T, C>> configurer) {
        requireNotNull(actionIdentifiable, "Step identifiable");
        return step(actionIdentifiable.getId(), configurer);
    }

    @Override
    public OperationDefImpl<T, C> conditional(String id, Consumer<ConditionalOperationDef<T, C>> configurer) {
        requireConfigurerActive("conditional");
        requireNotBlank(id, "Conditional operation ID");
        requireNotNull(configurer, "Conditional configurer");

        ConditionalOperationDefImpl<T, C> def = new ConditionalOperationDefImpl<>(id);
        ConfigurableDefImpl.runConfigurer(def, configurer);
        actionRefs.add(ActionRef.conditional(id, def));

        return this;
    }

    @Override
    public OperationDefImpl<T, C> conditional(Identifiable conditionalIdentifiable, Consumer<ConditionalOperationDef<T, C>> configurer) {
        requireNotNull(conditionalIdentifiable, "Conditional identifiable");
        return conditional(conditionalIdentifiable.getId(), configurer);
    }

    @Override
    public OperationDefImpl<T, C> usingContext(Class<C> contextType) {
        requireConfigurerActive("usingContext");
        requireNotNull(contextType, "Context type");

        if (this.declaredContextType != null && this.declaredContextType != contextType) {
            throw new TransfluxValidationException(
                "OperationDef '" + getId() + "' usingContext already declared as "
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
                "OperationDef '" + getId()
                    + "' has no members; call run(...), step(...) or conditional(...) at least"
                    + " once before build");
        }

        if (scopeRegistry == null) {
            throw new TransfluxValidationException(
                "OperationDef '" + getId()
                    + "' has no scope registry; state-machine construction did not wire it");
        }

        List<CompositeMember<T, C>> members = new ArrayList<>(actionRefs.size());
        for (ActionRef<T, C> ref : actionRefs) {
            BoundAction<T, C> bound = ref.resolve(stateMachine, scopeRegistry, getId());
            ResolvedContextMapping mapping = ref.mapperRef().resolve(stateMachine, getId());
            members.add(new CompositeMember<>(bound, mapping));
        }

        Action<T, C> executor = new CompositeOperationExecutor<>(members, scopeRegistry);

        return BoundAction.of(getId(), executor, ActionKind.OPERATION, buildBoundListeners(),
                              buildDeclaredCompensation());
    }

    @Override
    void collectListenerIds(BiConsumer<String, String> sink) {
        emitOwnListenerIds(sink);
        for (ActionRef<T, C> ref : actionRefs) {
            ref.collectListenerIds(sink);
        }
    }

    @Override
    void checkRefs(Class<?> scopeContext, String scopeLabel, StateMachineDefImpl<T> smDef) {
        Class<?> effectiveScope = scopeContext != null ? scopeContext : Object.class;

        for (ActionRef<T, C> ref : actionRefs) {
            if (ref instanceof ActionRef.ById<T, ?> byId) {
                Class<?> componentCtx = smDef.componentContextTypeOrDefault(byId.id());
                byId.mapperRef().validateAgainst(effectiveScope, scopeLabel, "action",
                    byId.id(), componentCtx, smDef.getMapperRegistrations());
            } else if (ref instanceof ActionRef.Conditional<T, C> conditional) {
                conditional.def().checkRefs(effectiveScope, smDef);
            }
        }
    }

    @Override
    void checkBranchRefs() {
        if (scopeRegistry == null) {
            return;
        }
        for (ActionRef<T, C> ref : actionRefs) {
            if (ref instanceof ActionRef.Conditional<T, C> conditional) {
                conditional.def().checkBranchRefs(scopeRegistry);
            }
        }
    }

    @Override
    void bindScope(RegistryImpl<T> rootRegistry,
                   Map<String, Object> canonical,
                   Map<String, BoundCondition<T, ?>> conditionRegistry) {
        @SuppressWarnings("unchecked")
        Map<String, BoundCondition<T, C>> typedConditions = (Map<String, BoundCondition<T, C>>) (Map<?, ?>) conditionRegistry;

        RegistryImpl<T> scope = new RegistryImpl<>(rootRegistry, getId());
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
     * the supplied {@link ExecutingTransition} through a single unified dispatch path.
     *
     * <p>Every member goes through {@link ExecutingTransitionImpl#runAction}, whatever form it was
     * authored in, so compensation capture, id recording and nesting are identical here and at
     * every other dispatch site. Pass-through mode runs the member against the parent context
     * verbatim; mapped mode produces a child context via {@code mapTo}, runs against it, then
     * folds back through {@code mapFrom} on success.
     *
     * <p>Mapper failure attribution: a {@code mapTo} failure throws before the member starts
     * and therefore surfaces as a parent member failure at the member's position — no child
     * step ids are recorded for it, and no compensation is captured for it either. A
     * {@code mapFrom} failure throws after the member has returned successfully, so any inner
     * step ids the member drove are already on the executed list; the failure attaches to the
     * parent's position and is treated as a parent failure. The child's own completion stands,
     * but its compensations still run: a compensation is captured before the action executes and
     * the enclosing transition drains the whole stack on any failure, whatever completed.
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
        public void execute(T entity, C context, ExecutingTransition<T, C> transition) {
            if (!(transition instanceof ExecutingTransitionImpl<?, ?> rawView)) {
                throw new TransfluxValidationException(
                    "Composite operation must run against the framework's own executing transition; got "
                        + (transition == null ? "null" : transition.getClass().getName()));
            }

            @SuppressWarnings("unchecked")
            ExecutingTransitionImpl<T, C> view = (ExecutingTransitionImpl<T, C>) rawView;
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
        private void dispatchMember(ExecutingTransitionImpl<T, C> view, CompositeMember<T, C> member) {
            ResolvedContextMapping mapping = member.mapping();
            ContextMapper<Object, Object> mapper = mapping.isPassThrough() ? null : mapping.mapper();

            view.runAction((BoundAction) member.action(), mapper);
        }
    }
}
