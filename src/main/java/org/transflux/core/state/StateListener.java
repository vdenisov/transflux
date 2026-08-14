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

package org.transflux.core.state;

/**
 * Observes entities entering and leaving a state.
 * <p>
 * A listener is attached either to a single state, through {@code StateDef.onEntry(...)} /
 * {@code StateDef.onExit(...)}, or to every state, through
 * {@code StateMachineDef.onAnyStateEntry(...)} / {@code StateMachineDef.onAnyStateExit(...)}. At
 * each hook the state's own listeners run first, in declaration order, followed by the global
 * listeners, also in declaration order.
 *
 * <p><b>When the hooks fire.</b> The exit hook fires on the source state once the transition's
 * pre-conditions have passed, before its operation runs. The entry hook fires on the target state
 * after the post-conditions have passed and the {@link StateApplier} has committed the new state.
 * Two consequences follow: a transition rejected by a pre-condition notifies neither hook, and an
 * exit notification does <b>not</b> mean the transition went on to succeed — a later failure
 * leaves the exit hook fired and the entry hook silent.
 *
 * <p><b>Listeners observe; they do not gate.</b> An exception thrown out of
 * {@link #onState} is caught and logged, and never reaches the caller: it does not fail the
 * transition, does not trigger compensation, and leaves {@code TransitionResult} untouched. Use a
 * pre-condition to reject a transition.
 *
 * <p><b>The context arrives untyped.</b> A state can be entered from transitions carrying
 * different context types, so there is no single type to bind, and {@code context} is whatever the
 * host passed to the entry point that fired the transition — possibly {@code null}. A listener
 * that genuinely needs a typed context belongs on a transition, where exactly one context type
 * applies; a listener that treats the context generically (serialising it to an audit trail, say)
 * can take it as it comes.
 *
 * @param <T> the entity type the surrounding state machine manages
 */
@FunctionalInterface
public interface StateListener<T> {

    /**
     * Called when the entity enters or leaves the state this listener is attached to.
     *
     * @param entity the entity undergoing the state change; never {@code null}
     * @param context the context the host supplied when firing the transition; may be
     *                {@code null}
     * @param change the phase, the state, and a read-only view of the transition responsible;
     *               never {@code null}
     */
    void onState(T entity, Object context, StateChange<T> change);
}
