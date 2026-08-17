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
 * <p><b>Build-time resolution.</b> Branch conditions are resolved eagerly against the
 * supplied condition registry. Branch action refs are <em>not</em> resolved eagerly: the
 * executor resolves each member by id at execution time via
 * {@link ExecutingTransitionImpl#run(String)}, which consults the active scope and walks the parent
 * chain up to the root registry. This sidesteps the build-order dependency between the bound
 * action registry and the conditional executor that lives in that very registry.
 *
 * <p>Deferring <em>resolution</em> does not mean deferring <em>validation</em>. Two build-time
 * passes cover branch members: {@link #checkRefs} validates their pass-through context
 * compatibility alongside the enclosing operation's own members, and {@link #checkBranchRefs}
 * verifies each id resolves once the scope registries are populated and flattened. A typo in a
 * branch therefore fails the build rather than the first execution that reaches that branch.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
final class ConditionalOperationDefImpl<T, C>
    extends IdentifiedDefImpl<ConditionalOperationDefImpl<T, C>> implements ConditionalOperationDef<T, C> {

    private final List<BranchDefImpl<T, C>> branches = new ArrayList<>();
    private final ActionListenerSink<T, C, ConditionalOperationDef<T, C>> listeners =
        new ActionListenerSink<>(this, this);
    private final InstanceOrClassSource<Compensation<T, C>> compensation =
        new InstanceOrClassSource<>(Loggers.BUILD_VALIDATION, "Compensation source", defLabel());
    private DefaultBranchDefImpl<T, C> defaultBranch;
    private NoMatchBehavior noMatchBehavior = NoMatchBehavior.WARN;

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
        requireConfigurerActive("withCompensation");
        requireNotNull(compensation, "Compensation");
        this.compensation.setInstance(compensation);
        return this;
    }

    @Override
    public ConditionalOperationDef<T, C> withCompensation(
            Class<? extends Compensation<T, C>> compensationClass) {
        requireConfigurerActive("withCompensation");
        requireNotNull(compensationClass, "Compensation class");
        this.compensation.setClass(compensationClass);
        return this;
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
     * Build-time hook: validates the pass-through context compatibility of every by-id branch
     * member, exactly as {@link OperationDefImpl#checkRefs} does for its own members. Branch
     * members never carry a mapper - {@code run} on a branch has no mapper-bearing overload - so
     * every reference here is a pass-through crossing.
     *
     * @param scopeContext the enclosing operation's context type
     * @param smDef the state-machine def whose component registrations the check consults
     */
    void checkRefs(Class<?> scopeContext, StateMachineDefImpl<T> smDef) {
        Class<?> effectiveScope = scopeContext != null ? scopeContext : Object.class;

        for (BranchDefImpl<T, C> branch : branches) {
            checkRefContexts(branch.getActionRefs(), effectiveScope,
                branchLabel("branch '" + branch.getBranchId() + "'"), smDef);
        }
        if (defaultBranch != null) {
            checkRefContexts(defaultBranch.getActionRefs(), effectiveScope,
                branchLabel("default branch"), smDef);
        }
    }

    /**
     * Build-time hook: verifies that every by-id branch member resolves to an action in the
     * enclosing operation's lexical scope.
     * <p>
     * This runs after the scope registries have been populated and flattened, which is why it is
     * a separate pass from {@link #checkRefs} rather than part of it. It checks presence only and
     * does not bind anything, so the lazy resolution the executor relies on is unaffected.
     *
     * @param scope the enclosing operation's scope registry; resolution walks the parent chain
     *              up to the state-machine root
     *
     * @throws TransfluxValidationException if a branch names an id that no action in scope carries
     */
    void checkBranchRefs(Registry<T> scope) {
        for (BranchDefImpl<T, C> branch : branches) {
            checkRefsResolvable(branch.getActionRefs(), scope,
                branchLabel("branch '" + branch.getBranchId() + "'"));
        }
        if (defaultBranch != null) {
            checkRefsResolvable(defaultBranch.getActionRefs(), scope, branchLabel("default branch"));
        }
    }

    /**
     * Resolves this conditional into a {@link BoundAction} whose executable {@link Action} runs
     * the matching branch's steps against the supplied transition view.
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
        List<ResolvedBranch<T, C>> resolvedBranches = new ArrayList<>(branches.size());
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
            if (branch.getActionRefs().isEmpty()) {
                throw new TransfluxValidationException(
                    "Branch '" + branch.getBranchId() + "' on conditional operation '" + getId()
                        + "' must declare at least one action");
            }

            String path = "conditional:" + getId() + ":branch[" + i + "]";
            BoundCondition<T, C> bound = ConditionResolver.resolve(
                branch.getDescriptor(), conditionRegistry, path);

            List<String> stepIds = collectStepIds(branch.getActionRefs());
            resolvedBranches.add(new ResolvedBranch<>(branch.getBranchId(), bound, stepIds));
        }

        List<String> defaultStepIds = null;
        if (defaultBranch != null) {
            if (defaultBranch.getActionRefs().isEmpty()) {
                throw new TransfluxValidationException(
                    "Default branch on conditional operation '" + getId() + "' must declare at least one action");
            }
            defaultStepIds = collectStepIds(defaultBranch.getActionRefs());
        }

        Action<T, C> executor = new ConditionalBranchExecutor(resolvedBranches,
                                                              defaultStepIds, noMatchBehavior, getId());
        // Deliberately the same call ActionDefImpl.buildDeclaredCompensation makes: a conditional
        // sits outside that sealed hierarchy, so nothing but this shared helper keeps the two
        // declaration channels resolving by the same rule.
        return BoundAction.of(getId(), executor, ActionKind.OPERATION, listeners.buildBound(),
                              compensation.resolveOptional("Compensation"));
    }

    private static <T, C> List<String> collectStepIds(List<ActionRef<T, C>> refs) {
        List<String> ids = new ArrayList<>(refs.size());
        for (ActionRef<T, C> ref : refs) {
            ids.add(ref.id());
        }
        return Collections.unmodifiableList(ids);
    }

    private String branchLabel(String branchPart) {
        return "conditional operation '" + getId() + "' " + branchPart;
    }

    private void checkRefContexts(List<ActionRef<T, C>> refs, Class<?> scopeContext,
                                  String label, StateMachineDefImpl<T> smDef) {
        for (ActionRef<T, C> ref : refs) {
            if (ref instanceof ActionRef.ById<T, ?> byId) {
                Class<?> componentCtx = smDef.componentContextTypeOrDefault(byId.id());
                byId.mapperRef().validateAgainst(scopeContext, label, "action",
                    byId.id(), componentCtx, smDef.getMapperRegistrations());
            }
        }
    }

    private void checkRefsResolvable(List<ActionRef<T, C>> refs, Registry<T> scope, String label) {
        for (ActionRef<T, C> ref : refs) {
            if (!(ref instanceof ActionRef.ById<T, C>)) {
                continue;
            }
            Component<T> component = scope.resolve(ref.id())
                .orElseThrow(() -> new TransfluxValidationException(
                    label + " references unknown action id '" + ref.id() + "' in its scope"));
            if (!(component instanceof Component.Action<T, ?>)) {
                throw new TransfluxValidationException(
                    label + " references id '" + ref.id() + "' which is registered as a "
                        + component.getClass().getSimpleName().toLowerCase() + ", not an action");
            }
        }
    }

    /**
     * Framework-built {@link Action} that evaluates the conditional's branches in declaration
     * order and dispatches the first matching branch's members through the central action
     * runner.
     * <p>
     * Branch members are resolved by id at execution time via {@link ExecutingTransitionImpl#run(String)},
     * which consults the active scope and walks the parent chain up to the root registry. This
     * sidesteps the build-order dependency between the bound action registry and this executor —
     * by the time {@link #execute(Object, Object, ExecutingTransition)} runs, the state machine is fully
     * constructed and every referenced id is resolvable. That the ids <em>are</em> resolvable is
     * established at build time by {@link #checkBranchRefs}.
     */
    private final class ConditionalBranchExecutor implements Action<T, C> {
        private final List<ResolvedBranch<T, C>> resolvedBranches;
        private final List<String> defaultStepIds;
        private final NoMatchBehavior noMatchBehavior;
        private final String conditionalId;

        ConditionalBranchExecutor(List<ResolvedBranch<T, C>> resolvedBranches,
                                  List<String> defaultStepIds,
                                  NoMatchBehavior noMatchBehavior,
                                  String conditionalId) {
            this.resolvedBranches = resolvedBranches;
            this.defaultStepIds = defaultStepIds;
            this.noMatchBehavior = noMatchBehavior;
            this.conditionalId = conditionalId;
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
                if (branch.condition().condition().test(entity, context, view.asReadOnly())) {
                    dispatchActionIds(branch.stepIds(), view);
                    return;
                }
            }

            if (defaultStepIds != null) {
                dispatchActionIds(defaultStepIds, view);
                return;
            }

            switch (noMatchBehavior) {
                case ERROR -> throw new TransfluxValidationException(
                    "Conditional operation '" + conditionalId + "' had no matching branch and no default");
                case WARN -> Loggers.EXECUTION_CONDITION.warn(
                    "Conditional matched no branch and has no default, conditionalId={}",
                    conditionalId);
                case SILENT -> { /* skip silently */ }
            }
        }

        private void dispatchActionIds(List<String> actionIds, ExecutingTransitionImpl<T, C> view) {
            for (String actionId : actionIds) {
                view.run(actionId);
            }
        }
    }
}
