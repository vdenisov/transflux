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
import org.transflux.core.action.ForkableContext;
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

    private final List<DeclaredMember<T, C>> members = new ArrayList<>();

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
        return reference("run", id, false);
    }

    @Override
    public OperationDefImpl<T, C> run(String id, String mapperId) {
        return reference("run", id, mapperId, false);
    }

    @Override
    public OperationDefImpl<T, C> run(String id, ContextMapper<C, ?> inlineMapper) {
        return reference("run", id, inlineMapper, false);
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
    public OperationDefImpl<T, C> fork(String id) {
        return reference("fork", id, true);
    }

    @Override
    public OperationDefImpl<T, C> fork(String id, String mapperId) {
        return reference("fork", id, mapperId, true);
    }

    @Override
    public OperationDefImpl<T, C> fork(String id, ContextMapper<C, ?> inlineMapper) {
        return reference("fork", id, inlineMapper, true);
    }

    @Override
    public OperationDefImpl<T, C> fork(Identifiable registeredAction) {
        requireNotNull(registeredAction, "Action identifiable");
        return fork(registeredAction.getId());
    }

    @Override
    public OperationDefImpl<T, C> fork(Identifiable registeredAction, Identifiable mapper) {
        requireNotNull(registeredAction, "Action identifiable");
        requireNotNull(mapper, "Mapper identifiable");
        return fork(registeredAction.getId(), mapper.getId());
    }

    @Override
    public OperationDefImpl<T, C> fork(Identifiable registeredAction, String mapperId) {
        requireNotNull(registeredAction, "Action identifiable");
        return fork(registeredAction.getId(), mapperId);
    }

    @Override
    public OperationDefImpl<T, C> fork(String id, Identifiable mapper) {
        requireNotNull(mapper, "Mapper identifiable");
        return fork(id, mapper.getId());
    }

    @Override
    public OperationDefImpl<T, C> step(String id, Action<T, C> action) {
        requireConfigurerActive("step");
        members.add(new DeclaredMember<>(ActionRef.inline(id, action, ActionKind.STEP), false));
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
        members.add(new DeclaredMember<>(ActionRef.inline(id, actionClass, ActionKind.STEP), false));
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
        members.add(new DeclaredMember<>(ActionRef.inline(id, def), false));
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
        members.add(new DeclaredMember<>(ActionRef.conditional(id, def), false));

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
        return members.stream().map(DeclaredMember::ref).toList();
    }

    /**
     * Reports whether any member of this container is forked, which is what tells the build
     * whether an executor is needed at all.
     *
     * @return whether a forked member was declared
     */
    boolean declaresFork() {
        return members.stream().anyMatch(DeclaredMember::forked);
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
        for (DeclaredMember<T, C> member : members) {
            if (member.ref() instanceof ActionRef.ById<T, C> r) {
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
        for (DeclaredMember<T, C> member : members) {
            member.ref().collectInlineRegistrations(sink);
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
        if (members.isEmpty()) {
            throw new TransfluxValidationException(
                "OperationDef '" + getId()
                    + "' has no members; call run(...), fork(...), step(...) or conditional(...)"
                    + " at least once before build");
        }

        if (scopeRegistry == null) {
            throw new TransfluxValidationException(
                "OperationDef '" + getId()
                    + "' has no scope registry; state-machine construction did not wire it");
        }

        List<CompositeMember<T, C>> bound = new ArrayList<>(members.size());
        for (DeclaredMember<T, C> member : members) {
            ActionRef<T, C> ref = member.ref();
            BoundAction<T, C> action = ref.resolve(stateMachine, scopeRegistry, getId());
            ResolvedContextMapping mapping = ref.mapperRef().resolve(stateMachine, getId());
            bound.add(new CompositeMember<>(action, mapping, member.forked()));
        }

        Action<T, C> executor = new CompositeOperationExecutor<>(bound, scopeRegistry);

        return BoundAction.of(getId(), executor, ActionKind.OPERATION, buildBoundListeners(),
                              buildCompensationRouter());
    }

    @Override
    void collectListenerIds(BiConsumer<String, String> sink) {
        emitOwnListenerIds(sink);
        for (DeclaredMember<T, C> member : members) {
            member.ref().collectListenerIds(sink);
        }
    }

    @Override
    void checkRefs(Class<?> scopeContext, String scopeLabel, StateMachineDefImpl<T> smDef) {
        Class<?> effectiveScope = scopeContext != null ? scopeContext : Object.class;

        for (DeclaredMember<T, C> member : members) {
            ActionRef<T, C> ref = member.ref();
            if (ref instanceof ActionRef.ById<T, ?> byId) {
                Class<?> componentCtx = smDef.componentContextTypeOrDefault(byId.id());
                byId.mapperRef().validateAgainst(effectiveScope, scopeLabel, "action",
                    byId.id(), componentCtx, smDef.getMapperRegistrations());
            } else if (ref instanceof ActionRef.Conditional<T, C> conditional) {
                conditional.def().checkRefs(effectiveScope, smDef);
            }

            if (member.forked()) {
                checkForkBoundary(ref, effectiveScope);
            }
        }
    }

    @Override
    void checkBranchRefs() {
        if (scopeRegistry == null) {
            return;
        }
        for (DeclaredMember<T, C> member : members) {
            if (member.ref() instanceof ActionRef.Conditional<T, C> conditional) {
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
     * Appends a by-id member in pass-through mode. The four {@code reference} overloads are what
     * keeps {@code run} and {@code fork} from drifting apart: the two verbs differ only in the
     * flag they pass.
     *
     * @param verb the DSL verb, named in the configurer-guard message
     * @param id the referenced action id
     * @param forked whether the member is handed to the executor rather than run in line
     *
     * @return this def for chaining
     */
    private OperationDefImpl<T, C> reference(String verb, String id, boolean forked) {
        requireConfigurerActive(verb);
        members.add(new DeclaredMember<>(ActionRef.byId(id), forked));
        return this;
    }

    /**
     * Appends a by-id member mapped by a registered mapper.
     *
     * @param verb the DSL verb, named in the configurer-guard message
     * @param id the referenced action id
     * @param mapperId the registered mapper id
     * @param forked whether the member is handed to the executor rather than run in line
     *
     * @return this def for chaining
     */
    private OperationDefImpl<T, C> reference(String verb, String id, String mapperId, boolean forked) {
        requireConfigurerActive(verb);
        requireNotBlank(id, "Action reference ID");
        requireNotBlank(mapperId, "Mapper reference ID");
        members.add(new DeclaredMember<>(ActionRef.byId(id, MapperRef.byId(mapperId)), forked));
        return this;
    }


    /**
     * Appends a by-id member mapped by an inline mapper instance.
     *
     * @param verb the DSL verb, named in the configurer-guard message
     * @param id the referenced action id
     * @param inlineMapper the mapper to apply at the boundary
     * @param forked whether the member is handed to the executor rather than run in line
     *
     * @return this def for chaining
     */
    private OperationDefImpl<T, C> reference(String verb, String id, ContextMapper<C, ?> inlineMapper,
                                             boolean forked) {
        requireConfigurerActive(verb);
        requireNotBlank(id, "Action reference ID");
        requireNotNull(inlineMapper, "Inline mapper instance");
        members.add(new DeclaredMember<>(ActionRef.byId(id, MapperRef.inline(inlineMapper)), forked));
        return this;
    }

    /**
     * Warns when a forked member will share the enclosing context rather than being handed one of
     * its own.
     * <p>
     * Nothing is rejected here. A mapper that overrides
     * {@link ContextMapper#mapFrom(Object, Object) mapFrom} is simply not applied to a forked
     * member - the branch runs with the mapping already done and no mapper to write back with - and
     * the framework cannot tell a mapper that overrides it from a proxy that merely appears to, so
     * refusing the definition would fail builds over an implementation detail of the host's
     * container.
     *
     * @param ref the forked member's reference
     * @param scopeContext this operation's effective context type
     */
    private void checkForkBoundary(ActionRef<T, C> ref, Class<?> scopeContext) {
        // A mapper produces the branch's own context, so there is nothing left to share.
        if (!(ref.mapperRef() instanceof MapperRef.PassThrough)
                || scopeContext == Void.class
                || ForkableContext.class.isAssignableFrom(scopeContext)) {
            return;
        }

        if (scopeContext == Object.class) {
            Loggers.BUILD_VALIDATION.warn(
                "Forked member may share the enclosing context; the operation declares no context"
                    + " type, so forkability cannot be checked - declare usingContext(...),"
                    + " implement ForkableContext, or map at the call site, operationId={},"
                    + " actionId={}", getId(), ref.id());
            return;
        }

        Loggers.BUILD_VALIDATION.warn(
            "Forked member shares the enclosing context; implement ForkableContext or map at the"
                + " call site, operationId={}, actionId={}, contextType={}",
            getId(), ref.id(), scopeContext.getName());
    }

    /**
     * A member as declared: the reference itself, and whether the declaring verb was {@code fork}.
     * <p>
     * The flag rides beside the reference rather than inside it because forking is a property of
     * the call site, exactly as the call-site mapper is - the same registered action is forked at
     * one position and run in line at another.
     *
     * @param ref the member reference
     * @param forked whether this position hands the member to the executor
     * @param <T> the entity type the surrounding state machine manages
     * @param <C> the host-supplied context type carried through transition execution
     */
    private record DeclaredMember<T, C>(ActionRef<T, C> ref, boolean forked) {
    }

    /**
     * Pairs a resolved composite member with its context-mapping configuration. Each member is
     * dispatched uniformly: optional {@link ContextMapper#mapTo(Object) mapTo} before, the
     * bound action's invocation against the (possibly mapped) child context, optional
     * {@link ContextMapper#mapFrom(Object, Object) mapFrom} after on success.
     */
    private record CompositeMember<T, C>(BoundAction<T, C> action, ResolvedContextMapping mapping,
                                         boolean forked) {
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

            if (member.forked()) {
                // Returns as soon as the branch is handed over, so the members after it start
                // without waiting - the position in this list is when the work begins, not when
                // it ends.
                view.submitBranch((BoundAction) member.action(), mapping);
                return;
            }

            ContextMapper<Object, Object> mapper = mapping.isPassThrough() ? null : mapping.mapper();
            view.runAction((BoundAction) member.action(), mapper);
        }
    }
}
