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

package org.transflux.core.transition;

/**
 * Observes a transition starting, completing, and failing.
 * <p>
 * A listener is attached either to a single transition, through {@code TransitionDef.onStart} /
 * {@code onComplete} / {@code onError}, or to every transition, through
 * {@code StateMachineDef.onAnyTransitionStart} / {@code onAnyTransitionComplete} /
 * {@code onAnyTransitionError}. At each hook the transition's own listeners run first, in
 * declaration order, followed by the ones registered against every transition, also in
 * declaration order.
 *
 * <p><b>When the hooks fire.</b> The start hook fires once the pre-conditions have passed and
 * before the operation runs. The complete hook fires after the post-conditions have passed and
 * the {@link org.transflux.core.state.StateApplier} has committed the new state. The error hook
 * fires when the transition fails and rollback has finished; the payload's
 * {@link TransitionResult#getCompensatedPath()} reports which compensations ran.
 *
 * <p><b>Start pairs with exactly one terminal hook.</b> Complete and error partition the
 * outcomes: every start notification is followed by one or the other, and neither occurs without
 * a start. Two consequences follow. A transition rejected by a pre-condition notifies
 * nothing — it never started, and the host already learns of the rejection from the returned
 * {@link TransitionResult}. And a completion listener needs no success check, because a failed
 * transition reaches the error hook instead.
 *
 * <p><b>Listeners observe; they do not gate.</b> An exception thrown out of
 * {@link #onTransition} is caught and logged, and never reaches the caller: it does not fail the
 * transition, does not trigger compensation, does not suppress the listeners registered after it,
 * and leaves {@link TransitionResult} untouched. Use a pre-condition to reject a transition.
 *
 * <p><b>The context is typed.</b> A transition declares exactly one context type, so a listener
 * attached to one receives that type directly. Listeners registered against <i>every</i>
 * transition are the exception: they span transitions with differing context types and therefore
 * take {@code Object}. An observer that needs no context at all, only the knowledge that an
 * entity entered or left a state, is better served by
 * {@link org.transflux.core.state.StateListener}.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
@FunctionalInterface
public interface TransitionListener<T, C> {

    /**
     * Called when the transition this listener is attached to starts, completes, or fails.
     *
     * @param entity the entity being transitioned; never {@code null}
     * @param context the context the host supplied when firing the transition; may be
     *                {@code null}
     * @param execution the phase, the transition, the trigger that fired it, and — at the
     *                  terminal hooks — the outcome; never {@code null}
     */
    void onTransition(T entity, C context, TransitionExecution<T> execution);
}
