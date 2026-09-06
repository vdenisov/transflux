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

import java.util.function.Predicate;
import org.transflux.core.action.ActionKind;
import org.transflux.core.action.AsyncRejectionPolicy;
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
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Implementation of {@link OperationDef}.
 * <p>
 * Holds the composite's member references in declaration order. Building is two passes:
 * {@link #buildBound()} produces the {@link BoundAction} that goes into the enclosing scope, and
 * {@link #bindMembers(StateMachineImpl, String)} resolves the members afterwards. They cannot be one pass
 * - a sibling member may reference this container by id, and such a reference captures the bound
 * action by value, so it must already be in the scope its own members resolve against.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
final class OperationDefImpl<T, C>
    extends ActionDefImpl<T, C, OperationDefImpl<T, C>> implements OperationDef<T, C> {

    private final ActionSequenceSink<T, C, OperationDefImpl<T, C>> members =
        new ActionSequenceSink<>(this, this);

    private CompositeOperationExecutor<T, C> executor;

    OperationDefImpl(String id) {
        this(id, "operation", null);
    }

    OperationDefImpl(String id, Class<C> declaredContextType) {
        this(id, "operation", declaredContextType);
    }

    private OperationDefImpl(String id, String kind, Class<C> declaredContextType) {
        super(id, kind, "Operation ID", declaredContextType);
    }

    /**
     * Creates the member list a transition runs as its body. It is an ordinary container in every
     * respect the build cares about - one scope, one executor, the same member grammar - and
     * differs only in what it calls itself: the transition's id under the kind {@code transition},
     * so every diagnostic reads {@code transition 't'} rather than naming a container the author
     * never wrote.
     *
     * <p>The body claims no id of its own: it is registered in no registry and never enters the
     * canonical table, exactly as the attached action it replaces did not, so nothing can name it
     * and it can lie on no cycle. Its id is not therefore unique - a transition and an action may
     * share a name, and then both key the inline-context table under it, which can make a
     * diagnostic name the wrong declaration. The build fails either way, since the reference does
     * not resolve.
     *
     * @param transitionId the enclosing transition's id
     * @param <T> the entity type the surrounding state machine manages
     * @param <C> the transition's context type
     *
     * @return the body
     */
    static <T, C> OperationDefImpl<T, C> transitionBody(String transitionId) {
        return new OperationDefImpl<>(transitionId, "transition", null);
    }

    /**
     * Reports whether anything was declared here. A transition whose body is empty has no action
     * at all, which is a legal definition, so the body has to be able to say so.
     *
     * @return whether at least one member is declared
     */
    boolean hasMembers() {
        return !members.members().isEmpty();
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
    public OperationDefImpl<T, C> fork(String id, AsyncRejectionPolicy policy) {
        return members.fork(id, policy);
    }

    @Override
    public OperationDefImpl<T, C> fork(String id, String mapperId, AsyncRejectionPolicy policy) {
        return members.fork(id, mapperId, policy);
    }

    @Override
    public OperationDefImpl<T, C> fork(String id, ContextMapper<C, ?> inlineMapper, AsyncRejectionPolicy policy) {
        return members.fork(id, inlineMapper, policy);
    }

    @Override
    public OperationDefImpl<T, C> step(String id, Action<T, C> action) {
        return members.step(id, action, false);
    }

    @Override
    public OperationDefImpl<T, C> step(String id, Consumer<StepDef<T, C>> configurer) {
        return members.step(id, configurer, false);
    }

    @Override
    public OperationDefImpl<T, C> conditional(String id, Consumer<ConditionalOperationDef<T, C>> configurer) {
        return members.conditional(id, configurer, false);
    }

    @Override
    public OperationDefImpl<T, C> operation(String id, Consumer<OperationDef<T, C>> configurer) {
        return members.operation(id, configurer, false);
    }

    @Override
    public OperationDefImpl<T, C> forkStep(String id, Action<T, C> action) {
        return members.step(id, action, true);
    }

    @Override
    public OperationDefImpl<T, C> forkStep(String id, Consumer<StepDef<T, C>> configurer) {
        return members.step(id, configurer, true);
    }

    @Override
    public OperationDefImpl<T, C> forkConditional(String id,
                                                  Consumer<ConditionalOperationDef<T, C>> configurer) {
        return members.conditional(id, configurer, true);
    }

    @Override
    public OperationDefImpl<T, C> forkOperation(String id,
                                                Consumer<OperationDef<T, C>> configurer) {
        return members.operation(id, configurer, true);
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
     * Returns this container's members in declaration order, each with the flag saying whether the
     * position hands it to the executor.
     *
     * @return an unmodifiable view of the member list
     */
    List<ActionSequenceSink.DeclaredMember<T, C>> getMembers() {
        return members.members();
    }

    /**
     * Reports whether any member of this container is forked, which is what tells the build
     * whether an executor is needed at all. Members nested inside a conditional's branches count:
     * a branch member is dispatched through the same path a container member is.
     *
     * @return whether a forked member was declared anywhere in this container's subtree
     */
    @Override
    boolean anyMember(Predicate<ActionSequenceSink.DeclaredMember<?, ?>> test) {
        boolean[] hit = {false};
        members.visitAllMembers(member -> hit[0] |= test.test(member));
        return hit[0];
    }

    /**
     * Returns the ids of every by-id reference in this composite's subtree - the candidate edges
     * for the cycle-detection pass. A reference to an imperative action cannot close a cycle
     * (it binds no children at definition time), so the caller narrows this list to ids that
     * name a declarative container before walking it.
     *
     * <p>The walk descends into a conditional's branches, because a branch member dispatches
     * through the same path a container member does and can close the same cycle. It is
     * over-approximate in the way the detector already was: it does not reason about which
     * branch is selectable, just as it does not reason about whether a container is ever
     * reached.
     *
     * @return the referenced ids in declaration order
     */
    @Override
    List<String> ownByIdReferenceIds() {
        List<String> ids = new ArrayList<>();
        members.visitAllMembers(member -> {
            if (member.ref() instanceof ActionRef.ById<T, C> r) {
                ids.add(r.id());
            }
        });
        return Collections.unmodifiableList(ids);
    }

    /**
     * Visits every member in this container's subtree, in declaration order.
     *
     * @param visitor receives each member
     */
    void visitMembers(Consumer<ActionSequenceSink.DeclaredMember<T, C>> visitor) {
        members.visitAllMembers(visitor);
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
     * Produces the {@link BoundAction} carrying this container's executor, listeners and
     * compensation table. The members it will iterate are installed by
     * {@link #bindMembers(StateMachineImpl, String)}; until then the executor holds none.
     *
     * @return the bound operation
     *
     * @throws TransfluxValidationException if the composite has no members, or its lexical scope
     *         was never wired
     */
    @Override
    BoundAction<T, C> buildBound() {
        if (members.members().isEmpty()) {
            throw new TransfluxValidationException(
                "OperationDef '" + getId()
                    + "' has no members; call run(...), fork(...), step(...), operation(...) or"
                    + " conditional(...) at least once before build");
        }

        if (ownScope() == null) {
            throw new TransfluxValidationException(
                "OperationDef '" + getId()
                    + "' has no scope registry; state-machine construction did not wire it");
        }

        // A fresh executor per build: the members a later pass installs belong to the machine
        // being built, so an earlier machine's container keeps the members it was built with.
        this.executor = new CompositeOperationExecutor<T, C>(ownScope());

        return BoundAction.of(getId(), executor, ActionKind.OPERATION, buildBoundListeners(),
                              buildCompensationRouter(), getAsyncRejectionPolicy());
    }

    @Override
    void visitDefs(Consumer<ActionDefImpl<?, ?, ?>> visitor) {
        super.visitDefs(visitor);
        members.visitDefs(visitor);
    }

    @Override
    void checkRefs(Class<?> scopeContext, String scopeLabel, String contextOwner,
                   List<String> visibleScopes, StateMachineDefImpl<T> smDef) {
        members.checkRefs(scopeContext, scopeLabel, contextOwner, inside(visibleScopes, getId()),
                          smDef);
    }

    /**
     * Resolves each member reference against this container's lexical scope and installs the
     * bound members on the executor, then descends into any conditional a member declares.
     *
     * @param stateMachine the state machine under construction
     * @param positionLabel names this container's position in the definition tree
     *
     * @throws TransfluxValidationException if a referenced id resolves to nothing, or to
     *         something that is not an action
     */
    @Override
    void bindMembers(StateMachineImpl<T> stateMachine, String positionLabel) {
        if (executor == null) {
            throw new TransfluxValidationException(
                "OperationDef '" + getId()
                    + "' has no executor; state-machine construction did not build it");
        }

        List<CompositeMember<T, C>> bound = new ArrayList<>(members.members().size());
        for (ActionSequenceSink.DeclaredMember<T, C> member : members.members()) {
            ActionRef<T, C> ref = member.ref();
            BoundAction<T, C> action = ref.resolve(stateMachine, ownScope(), positionLabel,
                                                  getId());
            ResolvedContextMapping mapping = ref.mapperRef().resolve(stateMachine, getId());
            bound.add(CompositeMember.of(action, mapping, member));

            // Recursing after the member is built names the outer position first when a
            // resolution fails.
            if (ref instanceof ActionRef.Conditional<T, C> conditional) {
                // The conditional owns the scope its branches bind against, so it is what a failure
                // inside them has to name - not the container that happens to hold it.
                conditional.def().bindBranchMembers(
                    stateMachine, positionLabel + " > " + conditional.def().defLabel(),
                    conditional.id());
            } else if (ref instanceof ActionRef.InlineOperation<T, C> nested) {
                nested.def().bindMembers(stateMachine,
                                         positionLabel + " > " + nested.def().defLabel());
            }
        }

        executor.bind(bound);
    }

    @Override
    void collectMemberContexts(Class<?> scopeContext, InlineContextSink sink) {
        members.collectMemberContexts(scopeContext, getId(), sink);
    }

    @Override
    void bindScope(RegistryImpl<T> rootRegistry,
                   Map<String, Object> canonical,
                   Map<String, BoundCondition<T, ?>> conditionRegistry,
                   Class<?> inheritedContext) {
        @SuppressWarnings("unchecked")
        Map<String, BoundCondition<T, C>> typedConditions = (Map<String, BoundCondition<T, C>>) (Map<?, ?>) conditionRegistry;

        bindScopeUnder(rootRegistry, canonical, typedConditions, inheritedContext);
    }

    /**
     * Allocates this container's lexical scope under {@code parentRegistry} and populates it with
     * everything declared inline inside it, descending into nested containers as it goes.
     * <p>
     * The parent is the root registry for a container reached from one of the definition's roots,
     * and the enclosing container's scope for one declared in place - which is the whole of what
     * makes an inline container's ids private to its own subtree.
     *
     * @param parentRegistry the registry this container's scope parents onto
     * @param canonical the per-build canonical-payload table enforcing SM-wide id uniqueness
     * @param conditionRegistry the resolved SM-wide condition registry
     * @param inheritedContext the enclosing position's context type, which this container's own
     *                         inline members are tagged with when it declares none of its own;
     *                         {@code null} at a root. The tag is diagnostic - it names the context
     *                         in the binding log - so inheriting it keeps a nested member's line
     *                         honest without the def itself being rewritten
     */
    void bindScopeUnder(RegistryImpl<T> parentRegistry,
                        Map<String, Object> canonical,
                        Map<String, BoundCondition<T, C>> conditionRegistry,
                        Class<?> inheritedContext) {
        Class<C> tagged = effectiveContext(inheritedContext);

        RegistryImpl<T> scope = new RegistryImpl<>(parentRegistry, getId());
        setScopeRegistry(scope);


        InlineRegistrationSink<T, C> sink = new InlineRegistrationSink<>(
            scope, canonical, tagged, conditionRegistry);
        collectInlineRegistrations(sink);
    }

    @Override
    void visitScopeOwners(Consumer<ActionDefImpl<T, C, ?>> visitor) {
        // visitAllMembers is transitive, so one pass reaches every depth and every position.
        members.visitAllMembers(member -> {
            if (member.ref() instanceof ActionRef.InlineOperation<T, C> inline) {
                visitor.accept(inline.def());
            } else if (member.ref() instanceof ActionRef.Conditional<T, C> conditional) {
                visitor.accept(conditional.def());
            }
        });
    }

    @Override
    void collectNestedCycleNodes(BiConsumer<String, List<String>> sink) {
        // visitAllMembers is transitive, so one pass reaches every depth and every position.
        // Each id deposited here is registered in some scope, so something can name it.
        members.visitAllMembers(member -> {
            if (member.ref() instanceof ActionRef.InlineOperation<T, C> inline) {
                sink.accept(inline.id(), inline.def().ownByIdReferenceIds());
            } else if (member.ref() instanceof ActionRef.Conditional<T, C> conditional) {
                sink.accept(conditional.id(), conditional.def().branchByIdReferenceIds());
            }
        });
    }

    /**
     * Iterates an ordered list of {@link CompositeMember} entries and invokes each one against
     * the supplied {@link ExecutingTransition} through a single unified dispatch path.
     * <p>
     * The members arrive after construction: a container's bound action has to be in the scope
     * its own members resolve against, so binding them is a later pass.
     */
    private static final class CompositeOperationExecutor<T, C> implements Action<T, C> {
        private final Registry<T> scopeRegistry;

        private List<CompositeMember<T, C>> members;

        CompositeOperationExecutor(Registry<T> scopeRegistry) {
            this.scopeRegistry = scopeRegistry;
        }

        void bind(List<CompositeMember<T, C>> members) {
            this.members = members;
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

    @Override
    public <N> OperationDefImpl<T, C> step(String id, Class<N> contextType, Action<T, N> action) {
        return members.step(id, contextType, MapperRef.passThrough(), action, false);
    }

    @Override
    public <N> OperationDefImpl<T, C> step(String id, Class<N> contextType, ContextMapper<C, N> mapper,
                          Action<T, N> action) {
        return members.step(id, contextType, MapperRef.inline(mapper), action, false);
    }

    @Override
    public <N> OperationDefImpl<T, C> step(String id, Class<N> contextType, Consumer<StepDef<T, N>> configurer) {
        return members.step(id, contextType, MapperRef.passThrough(), configurer, false);
    }

    @Override
    public <N> OperationDefImpl<T, C> step(String id, Class<N> contextType, ContextMapper<C, N> mapper,
                          Consumer<StepDef<T, N>> configurer) {
        return members.step(id, contextType, MapperRef.inline(mapper), configurer, false);
    }

    @Override
    public <N> OperationDefImpl<T, C> conditional(String id, Class<N> contextType,
                                 Consumer<ConditionalOperationDef<T, N>> configurer) {
        return members.conditional(id, contextType, MapperRef.passThrough(), configurer, false);
    }

    @Override
    public <N> OperationDefImpl<T, C> conditional(String id, Class<N> contextType, ContextMapper<C, N> mapper,
                                 Consumer<ConditionalOperationDef<T, N>> configurer) {
        return members.conditional(id, contextType, MapperRef.inline(mapper), configurer, false);
    }

    @Override
    public <N> OperationDefImpl<T, C> operation(String id, Class<N> contextType,
                               Consumer<OperationDef<T, N>> configurer) {
        return members.operation(id, contextType, MapperRef.passThrough(), configurer, false);
    }

    @Override
    public <N> OperationDefImpl<T, C> operation(String id, Class<N> contextType, ContextMapper<C, N> mapper,
                               Consumer<OperationDef<T, N>> configurer) {
        return members.operation(id, contextType, MapperRef.inline(mapper), configurer, false);
    }

    @Override
    public <N> OperationDefImpl<T, C> forkStep(String id, Class<N> contextType,
                                               Action<T, N> action) {
        return members.step(id, contextType, MapperRef.passThrough(), action, true);
    }

    @Override
    public <N> OperationDefImpl<T, C> forkStep(String id, Class<N> contextType,
                                               ContextMapper<C, N> mapper, Action<T, N> action) {
        return members.step(id, contextType, MapperRef.inline(mapper), action, true);
    }

    @Override
    public <N> OperationDefImpl<T, C> forkStep(String id, Class<N> contextType,
                                               Consumer<StepDef<T, N>> configurer) {
        return members.step(id, contextType, MapperRef.passThrough(), configurer, true);
    }

    @Override
    public <N> OperationDefImpl<T, C> forkStep(String id, Class<N> contextType,
                                               ContextMapper<C, N> mapper,
                                               Consumer<StepDef<T, N>> configurer) {
        return members.step(id, contextType, MapperRef.inline(mapper), configurer, true);
    }

    @Override
    public <N> OperationDefImpl<T, C> forkConditional(String id, Class<N> contextType,
                                                      Consumer<ConditionalOperationDef<T, N>> configurer) {
        return members.conditional(id, contextType, MapperRef.passThrough(), configurer, true);
    }

    @Override
    public <N> OperationDefImpl<T, C> forkConditional(String id, Class<N> contextType,
                                                      ContextMapper<C, N> mapper,
                                                      Consumer<ConditionalOperationDef<T, N>> configurer) {
        return members.conditional(id, contextType, MapperRef.inline(mapper), configurer, true);
    }

    @Override
    public <N> OperationDefImpl<T, C> forkOperation(String id, Class<N> contextType,
                                                    Consumer<OperationDef<T, N>> configurer) {
        return members.operation(id, contextType, MapperRef.passThrough(), configurer, true);
    }

    @Override
    public <N> OperationDefImpl<T, C> forkOperation(String id, Class<N> contextType,
                                                    ContextMapper<C, N> mapper,
                                                    Consumer<OperationDef<T, N>> configurer) {
        return members.operation(id, contextType, MapperRef.inline(mapper), configurer, true);
    }
}
