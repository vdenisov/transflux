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
import org.transflux.core.action.ActionListener;
import org.transflux.core.action.ActionListenerDef;
import org.transflux.core.action.ActionPhase;
import org.transflux.core.Identifiable;
import org.transflux.core.exception.TransfluxValidationException;
import org.transflux.core.action.BranchDef;
import org.transflux.core.action.Compensation;
import org.transflux.core.action.CompensationRouteDef;
import org.transflux.core.action.ConditionalOperationDef;
import org.transflux.core.action.DefaultBranchDef;
import org.transflux.core.action.NoMatchBehavior;
import org.transflux.core.action.Action;
import org.transflux.core.transition.ExecutingTransition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.transflux.core.Preconditions.requireNotBlank;
import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Implementation of {@link ConditionalOperationDef}.
 *
 * <p>Holds the conditional's branches and optional default branch in declaration order. The
 * branches are validated at build time, not at configurer return — the configurer surface is
 * permissive and validation is centralized in {@link #buildBoundAction(Map)}.
 *
 * <p><b>Building takes two passes.</b> {@link #buildBoundAction(Map)} validates the shape,
 * resolves the branch conditions and produces the {@link BoundAction} that goes into the
 * enclosing operation's scope; {@link #bindBranchMembers} then resolves the branch members and
 * installs them. They are separate because the bound action has to be in that scope before its
 * own members can be resolved — a sibling member may reference the conditional by id, and such a
 * reference captures the bound action by value — while resolving the members needs every scope
 * populated and the state machine in existence. A branch member that names an unknown id
 * therefore fails the build rather than the first execution that reaches that branch; its
 * pass-through context compatibility is checked earlier still, by {@link #checkRefs}, alongside
 * the enclosing operation's own members.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
final class ConditionalOperationDefImpl<T, C>
    extends ActionDefImpl<T, C, ConditionalOperationDefImpl<T, C>> implements ConditionalOperationDef<T, C> {

    private final List<BranchDefImpl<T, C>> branches = new ArrayList<>();
    private DefaultBranchDefImpl<T, C> defaultBranch;
    private NoMatchBehavior noMatchBehavior = NoMatchBehavior.WARN;
    private ConditionalBranchExecutor executor;

    /**
     * Captured by {@link #bindScope} so {@link #buildBound()} can resolve the branch conditions.
     * The hook takes no arguments, and the registry is only available two build phases earlier.
     */
    private Map<String, BoundCondition<T, C>> boundConditions;

    ConditionalOperationDefImpl(String id) {
        super(id, "conditional operation", "Conditional operation ID");
    }

    NoMatchBehavior getNoMatchBehavior() {
        return noMatchBehavior;
    }

    @Override
    public ConditionalOperationDef<T, C> branch(String branchId, Consumer<BranchDef<T, C>> configurer) {
        requireConfigurerActive("branch");
        requireNotBlank(branchId, "Branch ID");
        requireNotNull(configurer, "Branch configurer");
        for (BranchDefImpl<T, C> existing : branches) {
            if (existing.getBranchId().equals(branchId)) {
                throw new TransfluxValidationException(
                    "Branch ID '" + branchId + "' is already declared on conditional operation '" + getId() + "'");
            }
        }
        BranchDefImpl<T, C> branch = new BranchDefImpl<>(branchId);
        ConfigurableDefImpl.runConfigurer(branch, configurer);
        branches.add(branch);
        return this;
    }

    @Override
    public ConditionalOperationDef<T, C> branch(Identifiable branchIdentifiable, Consumer<BranchDef<T, C>> configurer) {
        requireNotNull(branchIdentifiable, "Branch identifiable");
        return branch(branchIdentifiable.getId(), configurer);
    }

    @Override
    public ConditionalOperationDef<T, C> defaultBranch(Consumer<DefaultBranchDef<T, C>> configurer) {
        requireConfigurerActive("defaultBranch");
        requireNotNull(configurer, "Default branch configurer");
        if (this.defaultBranch != null) {
            throw new TransfluxValidationException(
                "Default branch is already declared on conditional operation '" + getId() + "'");
        }
        DefaultBranchDefImpl<T, C> branch = new DefaultBranchDefImpl<>();
        ConfigurableDefImpl.runConfigurer(branch, configurer);
        this.defaultBranch = branch;
        return this;
    }

    @Override
    public ConditionalOperationDef<T, C> onNoMatch(NoMatchBehavior behavior) {
        requireConfigurerActive("onNoMatch");
        requireNotNull(behavior, "No-match behavior");
        this.noMatchBehavior = behavior;
        return this;
    }

    /**
     * Build-time hook: reports every listener id declared on this conditional and on the actions
     * declared inside its branches.
     *
     * @param sink receives {@code (listenerId, ownerLabel)} for each declared listener
     */
    @Override
    void collectListenerIds(BiConsumer<String, String> sink) {
        emitOwnListenerIds(sink);

        for (BranchDefImpl<T, C> branch : branches) {
            branch.collectListenerIds(sink);
        }
        if (defaultBranch != null) {
            defaultBranch.collectListenerIds(sink);
        }
    }

    /**
     * Walks every branch (and the default branch, if present) and forwards each branch's
     * action refs to the supplied sink. Used by {@link OperationDefImpl#bindScope}
     * to populate the enclosing composite's scope with the conditional's inline step
     * registrations.
     */
    void collectInlineRegistrations(InlineRegistrationSink<T, C> sink) {
        for (BranchDefImpl<T, C> branch : branches) {
            branch.collectInlineRegistrations(sink);
        }
        if (defaultBranch != null) {
            defaultBranch.collectInlineRegistrations(sink);
        }
    }

    /**
     * Returns the ids this conditional reaches by reference, across every branch and the default
     * one - its outgoing edges for cycle detection. A conditional is registered under its own id
     * in the enclosing scope, so one of its branches naming it closes a cycle.
     *
     * @return the referenced ids in declaration order
     */
    List<String> branchByIdReferenceIds() {
        List<String> ids = new ArrayList<>();
        visitBranchMembers(member -> {
            if (member.ref() instanceof ActionRef.ById<T, C> byId) {
                ids.add(byId.id());
            }
        });
        return Collections.unmodifiableList(ids);
    }

    /**
     * Visits every member of every branch, and the default branch's, recursing into any
     * conditional nested inside one.
     *
     * @param visitor receives each member, in declaration order
     */
    void visitBranchMembers(Consumer<ActionSequenceSink.DeclaredMember<T, C>> visitor) {
        for (BranchDefImpl<T, C> branch : branches) {
            branch.visitMembers(visitor);
        }
        if (defaultBranch != null) {
            defaultBranch.visitMembers(visitor);
        }
    }

    /**
     * Build-time hook: runs the shared member check over every branch, exactly as the enclosing
     * operation runs it over its own members.
     *
     * @param scopeContext the enclosing operation's context type
     * @param enclosingLabel names the position that declared this conditional; each branch label
     *                       extends it, so a message locates the branch rather than only naming it
     * @param enclosingOperationId the id of the operation that declared this conditional
     * @param smDef the state-machine def whose component registrations the check consults
     */
    void checkRefs(Class<?> scopeContext, String enclosingLabel, String enclosingOperationId,
                   StateMachineDefImpl<T> smDef) {
        Class<?> effectiveScope = scopeContext != null ? scopeContext : Object.class;

        for (BranchDefImpl<T, C> branch : branches) {
            branch.checkRefs(effectiveScope,
                             branchLabel(enclosingLabel, "branch '" + branch.getBranchId() + "'"),
                             enclosingOperationId, smDef);
        }
        if (defaultBranch != null) {
            defaultBranch.checkRefs(effectiveScope, branchLabel(enclosingLabel, "default branch"),
                                    enclosingOperationId, smDef);
        }
    }

    /**
     * Second build pass: resolves every branch member against the enclosing operation's lexical
     * scope and installs the bound members on the executor built by the first pass.
     *
     * <p>The two passes exist because the conditional's {@link BoundAction} has to be in the
     * enclosing scope before its own members can be resolved — a sibling member may reference
     * the conditional by id, and such a reference captures the bound action by value. The
     * identity is therefore fixed while the scopes are still being populated, and only the
     * members can wait until every scope is complete and the state machine exists.
     *
     * @param stateMachine the state machine under construction, whose mapper registry and
     *                     diagnostics the resolution consults
     * @param scope the enclosing operation's scope registry; resolution walks the parent chain
     *              up to the state-machine root
     * @param enclosingLabel names the position that declared this conditional; each branch label
     *                        extends it
     * @param enclosingOperationId the id of the operation that declared this conditional
     *
     * @throws TransfluxValidationException if a branch names an id that no action in scope
     *         carries, or if no executor was built for this conditional
     */
    void bindBranchMembers(StateMachineImpl<T> stateMachine,
                           String enclosingLabel, String enclosingOperationId) {
        if (executor == null) {
            throw new TransfluxValidationException(
                "Conditional operation '" + getId()
                    + "' has no executor; state-machine construction did not build it");
        }

        List<ResolvedBranch<T, C>> resolved = new ArrayList<>(branches.size());
        for (int i = 0; i < branches.size(); i++) {
            BranchDefImpl<T, C> branch = branches.get(i);
            resolved.add(new ResolvedBranch<>(
                branch.getBranchId(),
                executor.conditions.get(i),
                bindMembers(branch.getMembers(), stateMachine,
                            branchLabel(enclosingLabel, "branch '" + branch.getBranchId() + "'"),
                            enclosingOperationId)));
        }

        List<CompositeMember<T, C>> defaultMembers = defaultBranch == null ? null
            : bindMembers(defaultBranch.getMembers(), stateMachine,
                          branchLabel(enclosingLabel, "default branch"), enclosingOperationId);

        executor.bind(resolved, defaultMembers);
    }

    /**
     * First build pass: validates this conditional's shape, resolves each branch's condition, and
     * produces the {@link BoundAction} whose executable {@link Action} runs the matching branch
     * against the supplied transition view. The branch members are filled in later, by
     * {@link #bindBranchMembers}.
     *
     * @param conditionRegistry the resolved state-machine condition registry, used to bind
     *                          each branch's condition descriptor
     *
     * @return the bound action wrapping this conditional's executor, carrying
     *         {@link ActionKind#OPERATION} - a conditional is a declarative action, differing
     *         from a plain container only in its "first matching branch" ordering rule
     *
     * @throws TransfluxValidationException if validation rules on the conditional, its
     *         branches, or the default branch are violated, or if any condition descriptor
     *         cannot be resolved
     */
    BoundAction<T, C> buildBoundAction(Map<String, BoundCondition<T, C>> conditionRegistry) {
        requireNotNull(conditionRegistry, "Condition registry");

        if (branches.isEmpty()) {
            throw new TransfluxValidationException(
                "Conditional operation '" + getId() + "' must declare at least one branch");
        }

        Set<String> seen = new HashSet<>();
        List<BoundCondition<T, C>> conditions = new ArrayList<>(branches.size());
        for (int i = 0; i < branches.size(); i++) {
            BranchDefImpl<T, C> branch = branches.get(i);
            if (!seen.add(branch.getBranchId())) {
                throw new TransfluxValidationException(
                    "Branch ID '" + branch.getBranchId()
                        + "' is duplicated on conditional operation '" + getId() + "'");
            }
            if (branch.getDescriptor() == null) {
                throw new TransfluxValidationException(
                    "Branch '" + branch.getBranchId() + "' on conditional operation '" + getId()
                        + "' must declare a condition");
            }
            if (branch.getMembers().isEmpty()) {
                throw new TransfluxValidationException(
                    "Branch '" + branch.getBranchId() + "' on conditional operation '" + getId()
                        + "' must declare at least one action");
            }

            String path = "conditional:" + getId() + ":branch[" + i + "]";
            conditions.add(ConditionResolver.resolve(branch.getDescriptor(), conditionRegistry, path));
        }

        if (defaultBranch != null && defaultBranch.getMembers().isEmpty()) {
            throw new TransfluxValidationException(
                "Default branch on conditional operation '" + getId() + "' must declare at least one action");
        }

        // A fresh executor per build: the members a later pass installs belong to the machine
        // being built, so an earlier machine's conditional keeps the members it was built with.
        this.executor = new ConditionalBranchExecutor(conditions, ownScope());
        return BoundAction.of(getId(), executor, ActionKind.OPERATION, buildBoundListeners(),
                              buildCompensationRouter());
    }

    private List<CompositeMember<T, C>> bindMembers(List<ActionSequenceSink.DeclaredMember<T, C>> declared,
                                                    StateMachineImpl<T> stateMachine,
                                                    String ownerLabel,
                                                    String enclosingOperationId) {
        Registry<T> scope = ownScope();
        List<CompositeMember<T, C>> bound = new ArrayList<>(declared.size());
        for (ActionSequenceSink.DeclaredMember<T, C> member : declared) {
            ActionRef<T, C> ref = member.ref();
            bound.add(new CompositeMember<>(
                ref.resolve(stateMachine, scope, ownerLabel, enclosingOperationId),
                ref.mapperRef().resolve(stateMachine, enclosingOperationId),
                member.forked()));

            // Each nested form binds against its own scope. Recursing after the member is built
            // names the outer position first when a resolution fails.
            if (ref instanceof ActionRef.Conditional<T, C> nested) {
                nested.def().bindBranchMembers(stateMachine, ownerLabel, enclosingOperationId);
            } else if (ref instanceof ActionRef.InlineOperation<T, C> nested) {
                // A container declared in a branch does own a scope, and binds against its own.
                nested.def().bindMembers(stateMachine, ownerLabel + " > " + nested.def().defLabel());
            }
        }
        return Collections.unmodifiableList(bound);
    }

    /**
     * Names one branch as a position in the definition tree: the enclosing position, then this
     * conditional, then the branch. A nested conditional extends the same chain, so a message
     * locates a branch rather than only naming it.
     */
    private String branchLabel(String enclosingLabel, String branchPart) {
        return enclosingLabel + " > conditional operation '" + getId() + "' > " + branchPart;
    }

    @Override
    BoundAction<T, C> buildBound() {
        if (boundConditions == null) {
            throw new TransfluxValidationException(
                "Conditional operation '" + getId()
                    + "' has no condition registry; state-machine construction did not wire it");
        }
        return buildBoundAction(boundConditions);
    }

    @Override
    void bindScope(RegistryImpl<T> rootRegistry,
                   Map<String, Object> canonical,
                   Map<String, BoundCondition<T, ?>> conditionRegistry) {
        @SuppressWarnings("unchecked")
        Map<String, BoundCondition<T, C>> typed =
            (Map<String, BoundCondition<T, C>>) (Map<?, ?>) conditionRegistry;
        bindScopeUnder(rootRegistry, canonical, typed, null);
    }

    /**
     * Allocates this conditional's lexical scope under {@code parentRegistry} and populates it
     * with everything its branches declare inline.
     * <p>
     * The scope is what makes a conditional's members shared between its own branches and private
     * from outside it: every branch resolves against this one registry and then up the chain, so a
     * step declared in one branch is reachable from another, while a sibling of the conditional
     * cannot see in. The conditional's own bound action is registered by the caller into the
     * <em>enclosing</em> scope, so naming the conditional by id still works from either side.
     *
     * @param parentRegistry the registry this scope parents onto
     * @param canonical the per-build canonical-payload table enforcing SM-wide id uniqueness
     * @param conditionRegistry the resolved SM-wide condition registry, also captured for
     *                          {@link #buildBound()}
     * @param inheritedContext the enclosing position's context type, used to tag what this
     *                         conditional registers; {@code null} at a root
     */
    void bindScopeUnder(RegistryImpl<T> parentRegistry,
                        Map<String, Object> canonical,
                        Map<String, BoundCondition<T, C>> conditionRegistry,
                        Class<?> inheritedContext) {
        this.boundConditions = conditionRegistry;

        @SuppressWarnings("unchecked")
        Class<C> tagged = (Class<C>) (inheritedContext != null ? inheritedContext : Object.class);

        RegistryImpl<T> scope = new RegistryImpl<>(parentRegistry, getId());
        setScopeRegistry(scope);

        collectInlineRegistrations(
            new InlineRegistrationSink<>(scope, canonical, tagged, conditionRegistry));
    }

    @Override
    void checkRefs(Class<?> scopeContext, String scopeLabel, StateMachineDefImpl<T> smDef) {
        // At a root position this conditional is itself the enclosing one.
        checkRefs(scopeContext, scopeLabel, getId(), smDef);
    }

    @Override
    void bindMembers(StateMachineImpl<T> stateMachine, String positionLabel) {
        bindBranchMembers(stateMachine, positionLabel, getId());
    }

    @Override
    void visitScopeOwners(Consumer<ActionDefImpl<T, C, ?>> visitor) {
        visitBranchMembers(member -> {
            if (member.ref() instanceof ActionRef.InlineOperation<T, C> inline) {
                visitor.accept(inline.def());
            } else if (member.ref() instanceof ActionRef.Conditional<T, C> nested) {
                visitor.accept(nested.def());
            }
        });
    }

    @Override
    void collectNestedCycleNodes(BiConsumer<String, List<String>> sink) {
        visitBranchMembers(member -> {
            if (member.ref() instanceof ActionRef.InlineOperation<T, C> inline) {
                sink.accept(inline.id(), inline.def().ownByIdReferenceIds());
            } else if (member.ref() instanceof ActionRef.Conditional<T, C> nested) {
                sink.accept(nested.id(), nested.def().ownByIdReferenceIds());
            }
        });
    }

    @Override
    boolean declaresFork() {
        boolean[] forks = {false};
        visitBranchMembers(member -> forks[0] |= member.forked());
        return forks[0];
    }

    @Override
    List<String> ownByIdReferenceIds() {
        return branchByIdReferenceIds();
    }

    /**
     * Framework-built {@link Action} that evaluates the conditional's branches in declaration
     * order and dispatches the first matching branch's members through the central action
     * runner.
     * <p>
     * The branch conditions arrive with the executor; the members are installed afterwards by
     * {@link #bindBranchMembers}, once every scope is populated and the state machine exists.
     * Both are in place before any execution — the machine is not handed to a host until its
     * constructor returns.
     */
    private final class ConditionalBranchExecutor implements Action<T, C> {
        private final List<BoundCondition<T, C>> conditions;

        /**
         * Captured at construction, not read from the def at dispatch: the def's field is
         * overwritten by the next build, and an earlier machine must keep the scope it was built
         * with. The sibling container executor captures for the same reason.
         */
        private final Registry<T> scopeRegistry;

        private List<ResolvedBranch<T, C>> resolvedBranches;
        private List<CompositeMember<T, C>> defaultMembers;

        ConditionalBranchExecutor(List<BoundCondition<T, C>> conditions, Registry<T> scopeRegistry) {
            this.conditions = conditions;
            this.scopeRegistry = scopeRegistry;
        }

        void bind(List<ResolvedBranch<T, C>> resolvedBranches, List<CompositeMember<T, C>> defaultMembers) {
            this.resolvedBranches = resolvedBranches;
            this.defaultMembers = defaultMembers;
        }

        @Override
        public void execute(T entity, C context, ExecutingTransition<T, C> transition) {
            if (!(transition instanceof ExecutingTransitionImpl<?, ?> rawView)) {
                throw new TransfluxValidationException(
                    "Conditional operation must run against the framework's own executing transition; got "
                        + (transition == null ? "null" : transition.getClass().getName()));
            }
            @SuppressWarnings("unchecked")
            ExecutingTransitionImpl<T, C> view = (ExecutingTransitionImpl<T, C>) rawView;

            for (ResolvedBranch<T, C> branch : resolvedBranches) {
                if (branch.condition().evaluate(BoundCondition.Role.BRANCH, entity, context,
                                                view.asReadOnly())) {
                    dispatchMembers(branch.members(), view);
                    return;
                }
            }

            if (defaultMembers != null) {
                dispatchMembers(defaultMembers, view);
                return;
            }

            switch (noMatchBehavior) {
                case ERROR -> throw new TransfluxValidationException(
                    "Conditional operation '" + getId() + "' had no matching branch and no default");
                case WARN -> Loggers.EXECUTION_CONDITION.warn(
                    "Conditional matched no branch and has no default, conditionalId={}", getId());
                case SILENT -> { /* skip silently */ }
            }
        }

        private void dispatchMembers(List<CompositeMember<T, C>> members, ExecutingTransitionImpl<T, C> view) {
            // The conditional's own scope, so an id dispatched from inside a branch member's body
            // resolves against what the branches share before walking out to the enclosing chain.
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
