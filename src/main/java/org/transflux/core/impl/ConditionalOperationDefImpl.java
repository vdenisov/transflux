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
    extends IdentifiedDefImpl<ConditionalOperationDefImpl<T, C>> implements ConditionalOperationDef<T, C> {

    private final List<BranchDefImpl<T, C>> branches = new ArrayList<>();
    private final ActionListenerSink<T, C, ConditionalOperationDef<T, C>> listeners =
        new ActionListenerSink<>(this, this);
    private final CompensationSink<T, C, ConditionalOperationDef<T, C>> compensation =
        new CompensationSink<>(this, this);
    private DefaultBranchDefImpl<T, C> defaultBranch;
    private NoMatchBehavior noMatchBehavior = NoMatchBehavior.WARN;
    private ConditionalBranchExecutor executor;

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
    public ConditionalOperationDef<T, C> withCompensation(Compensation<T, C> compensation) {
        return this.compensation.withCompensationInstance(compensation);
    }

    @Override
    public ConditionalOperationDef<T, C> withCompensation(
            Class<? extends Compensation<T, C>> compensationClass) {
        return this.compensation.withCompensationClass(compensationClass);
    }

    @Override
    public <X extends Throwable> CompensationRouteDef<T, C, X, ConditionalOperationDef<T, C>>
            forException(Class<X> exceptionType) {
        return compensation.forException(exceptionType);
    }

    @Override
    public ConditionalOperationDef<T, C> onNoMatch(NoMatchBehavior behavior) {
        requireConfigurerActive("onNoMatch");
        requireNotNull(behavior, "No-match behavior");
        this.noMatchBehavior = behavior;
        return this;
    }

    @Override
    public ConditionalOperationDef<T, C> onStart(String listenerId, ActionListener<T, C> listener) {
        return listeners.instanceBased(ActionPhase.START, listenerId, listener);
    }

    @Override
    public ConditionalOperationDef<T, C> onStart(Identifiable listenerIdentifiable,
                                                 ActionListener<T, C> listener) {
        return listeners.instanceBased(ActionPhase.START, listenerIdentifiable, listener);
    }

    @Override
    public ConditionalOperationDef<T, C> onStart(String listenerId,
                                                 Class<? extends ActionListener<T, C>> listenerClass) {
        return listeners.classBased(ActionPhase.START, listenerId, listenerClass);
    }

    @Override
    public ConditionalOperationDef<T, C> onStart(Identifiable listenerIdentifiable,
                                                 Class<? extends ActionListener<T, C>> listenerClass) {
        return listeners.classBased(ActionPhase.START, listenerIdentifiable, listenerClass);
    }

    @Override
    public ConditionalOperationDef<T, C> onStart(String listenerId,
                                                 Consumer<ActionListenerDef<T, C>> configurer) {
        return listeners.configured(ActionPhase.START, listenerId, configurer);
    }

    @Override
    public ConditionalOperationDef<T, C> onStart(Identifiable listenerIdentifiable,
                                                 Consumer<ActionListenerDef<T, C>> configurer) {
        return listeners.configured(ActionPhase.START, listenerIdentifiable, configurer);
    }

    @Override
    public ConditionalOperationDef<T, C> onComplete(String listenerId, ActionListener<T, C> listener) {
        return listeners.instanceBased(ActionPhase.COMPLETE, listenerId, listener);
    }

    @Override
    public ConditionalOperationDef<T, C> onComplete(Identifiable listenerIdentifiable,
                                                    ActionListener<T, C> listener) {
        return listeners.instanceBased(ActionPhase.COMPLETE, listenerIdentifiable, listener);
    }

    @Override
    public ConditionalOperationDef<T, C> onComplete(String listenerId,
                                                    Class<? extends ActionListener<T, C>> listenerClass) {
        return listeners.classBased(ActionPhase.COMPLETE, listenerId, listenerClass);
    }

    @Override
    public ConditionalOperationDef<T, C> onComplete(Identifiable listenerIdentifiable,
                                                    Class<? extends ActionListener<T, C>> listenerClass) {
        return listeners.classBased(ActionPhase.COMPLETE, listenerIdentifiable, listenerClass);
    }

    @Override
    public ConditionalOperationDef<T, C> onComplete(String listenerId,
                                                    Consumer<ActionListenerDef<T, C>> configurer) {
        return listeners.configured(ActionPhase.COMPLETE, listenerId, configurer);
    }

    @Override
    public ConditionalOperationDef<T, C> onComplete(Identifiable listenerIdentifiable,
                                                    Consumer<ActionListenerDef<T, C>> configurer) {
        return listeners.configured(ActionPhase.COMPLETE, listenerIdentifiable, configurer);
    }

    @Override
    public ConditionalOperationDef<T, C> onError(String listenerId, ActionListener<T, C> listener) {
        return listeners.instanceBased(ActionPhase.ERROR, listenerId, listener);
    }

    @Override
    public ConditionalOperationDef<T, C> onError(Identifiable listenerIdentifiable,
                                                 ActionListener<T, C> listener) {
        return listeners.instanceBased(ActionPhase.ERROR, listenerIdentifiable, listener);
    }

    @Override
    public ConditionalOperationDef<T, C> onError(String listenerId,
                                                 Class<? extends ActionListener<T, C>> listenerClass) {
        return listeners.classBased(ActionPhase.ERROR, listenerId, listenerClass);
    }

    @Override
    public ConditionalOperationDef<T, C> onError(Identifiable listenerIdentifiable,
                                                 Class<? extends ActionListener<T, C>> listenerClass) {
        return listeners.classBased(ActionPhase.ERROR, listenerIdentifiable, listenerClass);
    }

    @Override
    public ConditionalOperationDef<T, C> onError(String listenerId,
                                                 Consumer<ActionListenerDef<T, C>> configurer) {
        return listeners.configured(ActionPhase.ERROR, listenerId, configurer);
    }

    @Override
    public ConditionalOperationDef<T, C> onError(Identifiable listenerIdentifiable,
                                                 Consumer<ActionListenerDef<T, C>> configurer) {
        return listeners.configured(ActionPhase.ERROR, listenerIdentifiable, configurer);
    }

    /**
     * Returns the listener defs collected for one hook, in declaration order.
     *
     * @param phase the hook to read
     *
     * @return that hook's listener defs
     */
    List<ActionListenerDefImpl<T, C>> getListeners(ActionPhase phase) {
        return listeners.forPhase(phase);
    }

    /**
     * Build-time hook: reports every listener id declared on this conditional and on the actions
     * declared inside its branches.
     *
     * @param sink receives {@code (listenerId, ownerLabel)} for each declared listener
     */
    void collectListenerIds(BiConsumer<String, String> sink) {
        for (ActionPhase phase : ActionPhase.values()) {
            for (ActionListenerDefImpl<T, C> ld : listeners.forPhase(phase)) {
                sink.accept(ld.getId(), defLabel() + " via " + ActionListenerSink.hook(phase));
            }
        }

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
    void bindBranchMembers(StateMachineImpl<T> stateMachine, Registry<T> scope,
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
                bindMembers(branch.getMembers(), stateMachine, scope,
                            branchLabel(enclosingLabel, "branch '" + branch.getBranchId() + "'"),
                            enclosingOperationId)));
        }

        List<CompositeMember<T, C>> defaultMembers = defaultBranch == null ? null
            : bindMembers(defaultBranch.getMembers(), stateMachine, scope,
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
        this.executor = new ConditionalBranchExecutor(conditions);
        // A conditional sits outside the sealed ActionDefImpl hierarchy, so it cannot inherit
        // buildCompensationRouter - it drives the same sink that method delegates to.
        return BoundAction.of(getId(), executor, ActionKind.OPERATION, listeners.buildBound(),
                              compensation.buildRouter());
    }

    private List<CompositeMember<T, C>> bindMembers(List<ActionSequenceSink.DeclaredMember<T, C>> declared,
                                                    StateMachineImpl<T> stateMachine,
                                                    Registry<T> scope,
                                                    String ownerLabel,
                                                    String enclosingOperationId) {
        List<CompositeMember<T, C>> bound = new ArrayList<>(declared.size());
        for (ActionSequenceSink.DeclaredMember<T, C> member : declared) {
            ActionRef<T, C> ref = member.ref();
            bound.add(new CompositeMember<>(
                ref.resolve(stateMachine, scope, ownerLabel, enclosingOperationId),
                ref.mapperRef().resolve(stateMachine, enclosingOperationId),
                member.forked()));

            // A conditional nested in a branch owns no scope and does not re-type, so it binds
            // against the same two. Recursing after the member is built names the outer position
            // first when a resolution fails.
            if (ref instanceof ActionRef.Conditional<T, C> nested) {
                nested.def().bindBranchMembers(stateMachine, scope, ownerLabel, enclosingOperationId);
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
        private List<ResolvedBranch<T, C>> resolvedBranches;
        private List<CompositeMember<T, C>> defaultMembers;

        ConditionalBranchExecutor(List<BoundCondition<T, C>> conditions) {
            this.conditions = conditions;
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
            for (CompositeMember<T, C> member : members) {
                member.dispatch(view);
            }
        }
    }
}
