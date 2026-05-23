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

import org.transflux.core.Identifiable;
import org.transflux.core.exception.TransfluxValidationException;

import java.util.function.Consumer;

/**
 * Definition surface for a multi-branch conditional step inside a composite operation.
 * <p>
 * A conditional step holds an ordered list of {@link BranchDef branches}; at execution time
 * the framework walks the list in declaration order, evaluates each branch's condition, and
 * runs the steps of the first branch whose condition returned {@code true}. If no branch
 * matches and a {@link DefaultBranchDef default branch} is configured, its steps run.
 * Otherwise the {@link NoMatchBehavior} attached to this conditional determines whether the
 * step is silently skipped (with a warning) or fails the enclosing transition.
 *
 * <p>The conditional itself is a step within its enclosing composite — its id appears in
 * the composite's declaration-order step list, and the branch steps it runs are dispatched
 * through the same shared runner as any other step so executed-id tracking and compensation
 * registration stay uniform across composite-driven and conditional-driven invocations.
 *
 * <p>The conditional must declare at least one regular branch; a default branch alone is not
 * a valid configuration. Branch ids must be unique within the conditional.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
public interface ConditionalStepDef<T, C> extends Identifiable {

    /**
     * Sets the optional human-readable name for this conditional step.
     *
     * @param name the human-readable name
     *
     * @return this conditional def for chaining
     */
    ConditionalStepDef<T, C> withName(String name);

    /**
     * Sets the optional description for this conditional step.
     *
     * @param description the description
     *
     * @return this conditional def for chaining
     */
    ConditionalStepDef<T, C> withDescription(String description);

    /**
     * Defines a regular conditional branch. The supplied configurer must set exactly one
     * condition on the branch and append at least one step to it.
     *
     * @param branchId the branch id; must be unique within this conditional and non-blank
     * @param configurer callback that configures the new branch
     *
     * @return this conditional def for chaining
     *
     * @throws TransfluxValidationException if {@code branchId} is {@code null} or blank,
     *         {@code configurer} is {@code null}, or {@code branchId} is already declared
     */
    ConditionalStepDef<T, C> branch(String branchId, Consumer<BranchDef<T, C>> configurer);

    /**
     * {@link Identifiable} overload of {@link #branch(String, Consumer)}.
     *
     * @param branchIdentifiable an identifiable supplying the branch id
     * @param configurer callback that configures the new branch
     *
     * @return this conditional def for chaining
     *
     * @throws TransfluxValidationException if {@code branchIdentifiable} is {@code null}
     */
    ConditionalStepDef<T, C> branch(Identifiable branchIdentifiable, Consumer<BranchDef<T, C>> configurer);

    /**
     * Defines the default branch. The supplied configurer must append at least one step.
     * The default branch may be declared at most once.
     *
     * @param configurer callback that configures the default branch
     *
     * @return this conditional def for chaining
     *
     * @throws TransfluxValidationException if {@code configurer} is {@code null} or the
     *         default branch has already been declared
     */
    ConditionalStepDef<T, C> defaultBranch(Consumer<DefaultBranchDef<T, C>> configurer);

    /**
     * Sets the behavior used when no branch matches and no default branch is declared.
     * <p>
     * Choices: {@link NoMatchBehavior#WARN} (log and continue), {@link NoMatchBehavior#SILENT}
     * (continue without logging — the guard pattern), or {@link NoMatchBehavior#ERROR} (raise
     * an error and fail the transition). The default is {@code WARN}.
     *
     * @param behavior the no-match behavior
     *
     * @return this conditional def for chaining
     *
     * @throws TransfluxValidationException if {@code behavior} is {@code null}
     */
    ConditionalStepDef<T, C> onNoMatch(NoMatchBehavior behavior);
}
