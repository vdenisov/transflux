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

package org.transflux.core.action;

import org.transflux.core.exception.TransfluxValidationException;

import java.util.function.Consumer;

/**
 * Definition surface for a multi-branch choice.
 * <p>
 * A choice holds an ordered list of {@link BranchDef branches}; at execution time
 * the framework walks the list in declaration order, evaluates each branch's condition, and
 * runs the actions of the first branch whose condition returned {@code true}. If no branch
 * matches and a {@link DefaultBranchDef default branch} is configured, its actions run.
 * Otherwise the {@link NoMatchBehavior} attached to this choice determines whether it is
 * silently skipped (with a warning) or fails the enclosing transition.
 *
 * <p>A choice is a declarative action — its ordering rule is "first matching branch"
 * rather than "all members, in order", which is the only thing separating it from
 * {@link OperationDef}. Being an action, it occupies any position one can: a member of a
 * container or of a branch, and a transition's attachment, where it attaches directly rather than
 * through a wrapping operation. The actions it dispatches go through the same runner as any other
 * action, so executed-id tracking and compensation registration stay uniform however an action was
 * reached.
 *
 * <p>A choice owns the lexical scope its branches declare into, so every branch reaches what
 * any branch declares - a step common to several is declared once - while nothing outside the
 * choice can reach any of it.
 *
 * <p>The choice must declare at least one regular branch; a default branch alone is not
 * a valid configuration. Branch ids must be unique within the choice.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
public interface ChoiceDef<T, C> extends ActionDef<T, C> {

    /**
     * Sets the optional human-readable name for this choice.
     *
     * @param name the human-readable name
     *
     * @return this choice def for chaining
     */
    ChoiceDef<T, C> withName(String name);

    /**
     * Sets the optional description for this choice.
     *
     * @param description the description
     *
     * @return this choice def for chaining
     */
    ChoiceDef<T, C> withDescription(String description);

    @Override
    ChoiceDef<T, C> withCompensation(Compensation<? super T, C> compensation);

    @Override
    ChoiceDef<T, C> withAsyncRejectionPolicy(AsyncRejectionPolicy policy);

    @Override
    <X extends Throwable> CompensationRouteDef<T, C, X, ? extends ChoiceDef<T, C>>
        forException(Class<X> exceptionType);

    /**
     * Defines a regular choice branch. The supplied configurer must set exactly one
     * condition on the branch and append at least one step to it.
     *
     * @param branchId the branch id; must be unique within this choice and non-blank
     * @param configurer callback that configures the new branch
     *
     * @return this choice def for chaining
     *
     * @throws TransfluxValidationException if {@code branchId} is {@code null} or blank,
     *         {@code configurer} is {@code null}, or {@code branchId} is already declared
     */
    ChoiceDef<T, C> branch(String branchId, Consumer<BranchDef<T, C>> configurer);

    /**
     * Defines the default branch. The supplied configurer must append at least one step.
     * The default branch may be declared at most once.
     *
     * @param configurer callback that configures the default branch
     *
     * @return this choice def for chaining
     *
     * @throws TransfluxValidationException if {@code configurer} is {@code null} or the
     *         default branch has already been declared
     */
    ChoiceDef<T, C> defaultBranch(Consumer<DefaultBranchDef<T, C>> configurer);

    /**
     * Sets the behavior used when no branch matches and no default branch is declared.
     * <p>
     * Choices: {@link NoMatchBehavior#WARN} (log and continue), {@link NoMatchBehavior#SILENT}
     * (continue without logging — the guard pattern), or {@link NoMatchBehavior#ERROR} (raise
     * an error and fail the transition). The default is {@code WARN}.
     *
     * @param behavior the no-match behavior
     *
     * @return this choice def for chaining
     *
     * @throws TransfluxValidationException if {@code behavior} is {@code null}
     */
    ChoiceDef<T, C> onNoMatch(NoMatchBehavior behavior);

    @Override
    ChoiceDef<T, C> onStart(String listenerId, ActionListener<? super T, C> listener);

    @Override
    ChoiceDef<T, C> onStart(String listenerId,
                            Consumer<ActionListenerDef<T, C>> configurer);

    @Override
    ChoiceDef<T, C> onComplete(String listenerId, ActionListener<? super T, C> listener);

    @Override
    ChoiceDef<T, C> onComplete(String listenerId,
                               Consumer<ActionListenerDef<T, C>> configurer);

    @Override
    ChoiceDef<T, C> onError(String listenerId, ActionListener<? super T, C> listener);

    @Override
    ChoiceDef<T, C> onError(String listenerId,
                            Consumer<ActionListenerDef<T, C>> configurer);

    @Override
    ChoiceDef<T, C> disableGlobalListener(String listenerId);

    @Override
    ChoiceDef<T, C> disableGlobalListeners(String... listenerIds);

    @Override
    ChoiceDef<T, C> disableAllGlobalListeners();

}
