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

package org.transflux.core.operation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.transflux.core.StateMachineImpl;
import org.transflux.core.condition.BoundCondition;
import org.transflux.core.condition.ConditionResolver;
import org.transflux.core.exception.TransfluxValidationException;
import org.transflux.core.transition.Transition;
import org.transflux.core.transition.TransitionView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.transflux.core.ValidationUtils.requireNotBlank;
import static org.transflux.core.ValidationUtils.requireNotNull;
import static org.transflux.core.ValidationUtils.warnIfSet;

/**
 * Implementation of {@link ConditionalStepDef}.
 *
 * <p>This is framework-internal infrastructure; user code constructs conditional steps
 * through the public {@link ConditionalStepDef} fluent API.
 *
 * <p>Holds the conditional's branches and optional default branch in declaration order. The
 * branches are validated at build time, not at configurer return — the configurer surface is
 * permissive and validation is centralised in {@link #buildBoundStep(StateMachineImpl, Map)}.
 *
 * <p><b>Build-time resolution.</b> Branch conditions are resolved eagerly against the
 * supplied condition registry. Branch step refs are <em>not</em> resolved eagerly: the
 * executor closes over the {@link StateMachineImpl} and resolves each child step by id at
 * execution time via {@link StateMachineImpl#getBoundStep(String)}. This sidesteps the
 * build-order dependency between the bound-step registry and the conditional executor that
 * lives in that very registry.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
public final class ConditionalStepDefImpl<T, C> implements ConditionalStepDef<T, C> {
    private static final Logger log = LoggerFactory.getLogger(ConditionalStepDefImpl.class);

    private final String id;
    private String name;
    private String description;

    private final List<BranchDefImpl<T, C>> branches = new ArrayList<>();
    private DefaultBranchDefImpl<T, C> defaultBranch;
    private NoMatchBehavior noMatchBehavior = NoMatchBehavior.WARN;

    public ConditionalStepDefImpl(String id) {
        requireNotBlank(id, "Conditional step ID");
        this.id = id;
    }

    @Override
    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public NoMatchBehavior getNoMatchBehavior() {
        return noMatchBehavior;
    }

    @Override
    public ConditionalStepDef<T, C> withName(String name) {
        warnIfSet(this.name, name, "Name", log);
        this.name = name;
        return this;
    }

    @Override
    public ConditionalStepDef<T, C> withDescription(String description) {
        warnIfSet(this.description, description, "Description", log);
        this.description = description;
        return this;
    }

    @Override
    public ConditionalStepDef<T, C> branch(String branchId, Consumer<BranchDef<T, C>> configurer) {
        requireNotBlank(branchId, "Branch ID");
        requireNotNull(configurer, "Branch configurer");
        for (BranchDefImpl<T, C> existing : branches) {
            if (existing.getBranchId().equals(branchId)) {
                throw new TransfluxValidationException(
                    "Branch ID '" + branchId + "' is already declared on conditional step '" + id + "'");
            }
        }
        BranchDefImpl<T, C> branch = new BranchDefImpl<>(branchId);
        configurer.accept(branch);
        branches.add(branch);
        return this;
    }

    @Override
    public ConditionalStepDef<T, C> defaultBranch(Consumer<DefaultBranchDef<T, C>> configurer) {
        requireNotNull(configurer, "Default branch configurer");
        if (this.defaultBranch != null) {
            throw new TransfluxValidationException(
                "Default branch is already declared on conditional step '" + id + "'");
        }
        DefaultBranchDefImpl<T, C> branch = new DefaultBranchDefImpl<>();
        configurer.accept(branch);
        this.defaultBranch = branch;
        return this;
    }

    @Override
    public ConditionalStepDef<T, C> onNoMatch(NoMatchBehavior behavior) {
        requireNotNull(behavior, "No-match behavior");
        this.noMatchBehavior = behavior;
        return this;
    }

    /**
     * Returns an ordered map of {@code stepId -> Step} for every inline step instance held
     * by this conditional, across all branches and the default branch.
     *
     * <p>This is framework-internal infrastructure used by the state-machine def to
     * auto-register inline steps contributed by this conditional; user code should not
     * invoke it directly.
     *
     * @return an unmodifiable map of step id to inline step instance
     */
    public Map<String, Step<T, C>> getInlineStepInstances() {
        Map<String, Step<T, C>> result = new LinkedHashMap<>();
        for (BranchDefImpl<T, C> branch : branches) {
            collectInlineInstances(branch.getActionRefs(), result);
        }
        if (defaultBranch != null) {
            collectInlineInstances(defaultBranch.getActionRefs(), result);
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Returns an ordered map of {@code stepId -> stepClass} for every inline step class held
     * by this conditional, across all branches and the default branch.
     *
     * <p>This is framework-internal infrastructure used by the state-machine def to
     * auto-register inline steps contributed by this conditional; user code should not
     * invoke it directly.
     *
     * @return an unmodifiable map of step id to inline step class
     */
    public Map<String, Class<? extends Step<T, C>>> getInlineStepClasses() {
        Map<String, Class<? extends Step<T, C>>> result = new LinkedHashMap<>();
        for (BranchDefImpl<T, C> branch : branches) {
            collectInlineClasses(branch.getActionRefs(), result);
        }
        if (defaultBranch != null) {
            collectInlineClasses(defaultBranch.getActionRefs(), result);
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Resolves this conditional into a {@link BoundStep} whose executable {@link Step} runs
     * the matching branch's steps against the supplied transition view.
     *
     * <p>This is framework-internal infrastructure used by the state-machine def to build
     * the bound-step entry that the enclosing composite resolves the conditional's id
     * against; user code should not invoke it directly.
     *
     * @param stateMachine the enclosing state machine; needed at execution time to resolve
     *                     each branch's step ids against the step registry
     * @param conditionRegistry the resolved state-machine condition registry, used to bind
     *                          each branch's condition descriptor
     *
     * @return the bound step that wraps this conditional's executor
     *
     * @throws TransfluxValidationException if validation rules on the conditional, its
     *         branches, or the default branch are violated, or if any condition descriptor
     *         cannot be resolved
     */
    public BoundStep<T, C> buildBoundStep(StateMachineImpl<T, C> stateMachine,
                                          Map<String, BoundCondition<T, C>> conditionRegistry) {
        requireNotNull(stateMachine, "State machine");
        requireNotNull(conditionRegistry, "Condition registry");

        if (branches.isEmpty()) {
            throw new TransfluxValidationException(
                "Conditional step '" + id + "' must declare at least one branch");
        }

        Set<String> seen = new HashSet<>();
        List<ResolvedBranch<T, C>> resolvedBranches = new ArrayList<>(branches.size());
        for (int i = 0; i < branches.size(); i++) {
            BranchDefImpl<T, C> branch = branches.get(i);
            if (!seen.add(branch.getBranchId())) {
                throw new TransfluxValidationException(
                    "Branch ID '" + branch.getBranchId()
                        + "' is duplicated on conditional step '" + id + "'");
            }
            if (branch.getDescriptor() == null) {
                throw new TransfluxValidationException(
                    "Branch '" + branch.getBranchId() + "' on conditional step '" + id
                        + "' must declare a condition");
            }
            if (branch.getActionRefs().isEmpty()) {
                throw new TransfluxValidationException(
                    "Branch '" + branch.getBranchId() + "' on conditional step '" + id
                        + "' must declare at least one step");
            }

            String path = "conditional:" + id + ":branch[" + i + "]";
            BoundCondition<T, C> bound = ConditionResolver.resolve(
                branch.getDescriptor(), conditionRegistry, path);

            List<String> stepIds = collectStepIds(branch.getActionRefs());
            resolvedBranches.add(new ResolvedBranch<>(branch.getBranchId(), bound, stepIds));
        }

        List<String> defaultStepIds = null;
        if (defaultBranch != null) {
            if (defaultBranch.getActionRefs().isEmpty()) {
                throw new TransfluxValidationException(
                    "Default branch on conditional step '" + id + "' must declare at least one step");
            }
            defaultStepIds = collectStepIds(defaultBranch.getActionRefs());
        }

        Step<T, C> executor = new ConditionalStepExecutor(stateMachine, resolvedBranches,
                                                          defaultStepIds, noMatchBehavior, id);
        return BoundStep.of(id, executor);
    }

    private static <T, C> void collectInlineInstances(List<ActionRef<T, C>> refs,
                                                      Map<String, Step<T, C>> out) {
        for (ActionRef<T, C> ref : refs) {
            if (ref instanceof ActionRef.InlineInstance<T, C> ii) {
                out.put(ii.id(), ii.step());
            }
        }
    }

    private static <T, C> void collectInlineClasses(List<ActionRef<T, C>> refs,
                                                    Map<String, Class<? extends Step<T, C>>> out) {
        for (ActionRef<T, C> ref : refs) {
            if (ref instanceof ActionRef.InlineClass<T, C> ic) {
                out.put(ic.id(), ic.stepClass());
            }
        }
    }

    private static <T, C> List<String> collectStepIds(List<ActionRef<T, C>> refs) {
        List<String> ids = new ArrayList<>(refs.size());
        for (ActionRef<T, C> ref : refs) {
            ids.add(ref.id());
        }
        return Collections.unmodifiableList(ids);
    }

    /**
     * Framework-built {@link Step} that evaluates the conditional's branches in declaration
     * order and dispatches the first matching branch's steps through the central step
     * runner.
     * <p>
     * Branch steps are resolved by id at execution time rather than at build time: each
     * invocation looks up the bound step on the enclosing state machine. This sidesteps the
     * build-order dependency between the bound-step registry and this executor — by the time
     * {@link #execute(Object, Object, Transition)} runs, the state machine is fully
     * constructed and every referenced id is resolvable.
     */
    private final class ConditionalStepExecutor implements Step<T, C> {
        private final StateMachineImpl<T, C> stateMachine;
        private final List<ResolvedBranch<T, C>> resolvedBranches;
        private final List<String> defaultStepIds;
        private final NoMatchBehavior noMatchBehavior;
        private final String conditionalId;

        ConditionalStepExecutor(StateMachineImpl<T, C> stateMachine,
                                List<ResolvedBranch<T, C>> resolvedBranches,
                                List<String> defaultStepIds,
                                NoMatchBehavior noMatchBehavior,
                                String conditionalId) {
            this.stateMachine = stateMachine;
            this.resolvedBranches = resolvedBranches;
            this.defaultStepIds = defaultStepIds;
            this.noMatchBehavior = noMatchBehavior;
            this.conditionalId = conditionalId;
        }

        @Override
        public void execute(T entity, C context, Transition<T, C> transition) {
            if (!(transition instanceof TransitionView<?, ?> rawView)) {
                throw new TransfluxValidationException(
                    "Conditional step requires a per-execution TransitionView; got "
                        + (transition == null ? "null" : transition.getClass().getName()));
            }
            @SuppressWarnings("unchecked")
            TransitionView<T, C> view = (TransitionView<T, C>) rawView;

            for (ResolvedBranch<T, C> branch : resolvedBranches) {
                if (branch.condition().condition().test(entity, context, view)) {
                    dispatchStepIds(branch.stepIds(), view);
                    return;
                }
            }

            if (defaultStepIds != null) {
                dispatchStepIds(defaultStepIds, view);
                return;
            }

            switch (noMatchBehavior) {
                case ERROR -> throw new TransfluxValidationException(
                    "Conditional step '" + conditionalId + "' had no matching branch and no default");
                case WARN -> log.warn(
                    "Conditional step '{}' had no matching branch and no default; skipping.",
                    conditionalId);
                case SILENT -> { /* skip silently */ }
            }
        }

        private void dispatchStepIds(List<String> stepIds, TransitionView<T, C> view) {
            for (String stepId : stepIds) {
                BoundStep<T, C> bound = stateMachine.getBoundStep(stepId);
                if (bound == null) {
                    throw new TransfluxValidationException(
                        "Conditional step '" + conditionalId + "' references unknown step id '"
                            + stepId + "'");
                }
                StateMachineImpl.runBoundStep(bound, view);
            }
        }
    }
}
