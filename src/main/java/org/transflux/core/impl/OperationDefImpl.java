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

    private final ActionSequenceSink<T, C, OperationDefImpl<T, C>> members =
        new ActionSequenceSink<>(this, this);

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
        return members.run(id);
    }

    @Override
    public OperationDefImpl<T, C> run(String id, String mapperId) {
        return members.run(id, mapperId);
    }

    @Override
    public OperationDefImpl<T, C> run(String id, ContextMapper<C, ?> inlineMapper) {
        return members.run(id, inlineMapper);
    }

    @Override
    public OperationDefImpl<T, C> run(Identifiable registeredAction) {
        return members.run(registeredAction);
    }

    @Override
    public OperationDefImpl<T, C> run(Identifiable registeredAction, Identifiable mapper) {
        return members.run(registeredAction, mapper);
    }

    @Override
    public OperationDefImpl<T, C> run(Identifiable registeredAction, String mapperId) {
        return members.run(registeredAction, mapperId);
    }

    @Override
    public OperationDefImpl<T, C> run(String id, Identifiable mapper) {
        return members.run(id, mapper);
    }

    @Override
    public OperationDefImpl<T, C> fork(String id) {
        return members.fork(id);
    }

    @Override
    public OperationDefImpl<T, C> fork(String id, String mapperId) {
        return members.fork(id, mapperId);
    }

    @Override
    public OperationDefImpl<T, C> fork(String id, ContextMapper<C, ?> inlineMapper) {
        return members.fork(id, inlineMapper);
    }

    @Override
    public OperationDefImpl<T, C> fork(Identifiable registeredAction) {
        return members.fork(registeredAction);
    }

    @Override
    public OperationDefImpl<T, C> fork(Identifiable registeredAction, Identifiable mapper) {
        return members.fork(registeredAction, mapper);
    }

    @Override
    public OperationDefImpl<T, C> fork(Identifiable registeredAction, String mapperId) {
        return members.fork(registeredAction, mapperId);
    }

    @Override
    public OperationDefImpl<T, C> fork(String id, Identifiable mapper) {
        return members.fork(id, mapper);
    }

    @Override
    public OperationDefImpl<T, C> step(String id, Action<T, C> action) {
        return members.step(id, action);
    }

    @Override
    public OperationDefImpl<T, C> step(Identifiable actionIdentifiable, Action<T, C> action) {
        return members.step(actionIdentifiable, action);
    }

    @Override
    public OperationDefImpl<T, C> step(String id, Class<? extends Action<T, C>> actionClass) {
        return members.step(id, actionClass);
    }

    @Override
    public OperationDefImpl<T, C> step(Identifiable actionIdentifiable, Class<? extends Action<T, C>> actionClass) {
        return members.step(actionIdentifiable, actionClass);
    }

    @Override
    public OperationDefImpl<T, C> step(String id, Consumer<StepDef<T, C>> configurer) {
        return members.step(id, configurer);
    }

    @Override
    public OperationDefImpl<T, C> step(Identifiable actionIdentifiable, Consumer<StepDef<T, C>> configurer) {
        return members.step(actionIdentifiable, configurer);
    }

    @Override
    public OperationDefImpl<T, C> conditional(String id, Consumer<ConditionalOperationDef<T, C>> configurer) {
        return members.conditional(id, configurer);
    }

    @Override
    public OperationDefImpl<T, C> conditional(Identifiable conditionalIdentifiable, Consumer<ConditionalOperationDef<T, C>> configurer) {
        return members.conditional(conditionalIdentifiable, configurer);
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
        return members.members().stream().map(ActionSequenceSink.DeclaredMember::ref).toList();
    }

    /**
     * Reports whether any member of this container is forked, which is what tells the build
     * whether an executor is needed at all.
     *
     * @return whether a forked member was declared
     */
    boolean declaresFork() {
        return members.members().stream().anyMatch(ActionSequenceSink.DeclaredMember::forked);
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
        for (ActionSequenceSink.DeclaredMember<T, C> member : members.members()) {
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
        members.collectInlineRegistrations(sink);
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
        if (members.members().isEmpty()) {
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

        List<CompositeMember<T, C>> bound = new ArrayList<>(members.members().size());
        for (ActionSequenceSink.DeclaredMember<T, C> member : members.members()) {
            ActionRef<T, C> ref = member.ref();
            BoundAction<T, C> action = ref.resolve(stateMachine, scopeRegistry,
                                                  "OperationDef '" + getId() + "'", getId());
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
        members.collectListenerIds(sink);
    }

    @Override
    void checkRefs(Class<?> scopeContext, String scopeLabel, StateMachineDefImpl<T> smDef) {
        members.checkRefs(scopeContext, scopeLabel, getId(), smDef);
    }

    @Override
    void bindBranchMembers(StateMachineImpl<T> stateMachine) {
        if (scopeRegistry == null) {
            return;
        }
        for (ActionSequenceSink.DeclaredMember<T, C> member : members.members()) {
            if (member.ref() instanceof ActionRef.Conditional<T, C> conditional) {
                conditional.def().bindBranchMembers(stateMachine, scopeRegistry, getId());
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
     * Iterates an ordered list of {@link CompositeMember} entries and invokes each one against
     * the supplied {@link ExecutingTransition} through a single unified dispatch path.
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
                    member.dispatch(view);
                }
            } finally {
                view.popScope();
            }
        }
    }
}
