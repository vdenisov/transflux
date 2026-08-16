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

/**
 * Observes individual actions starting, completing, and failing.
 * <p>
 * Where a transition listener reports that a unit of work ran, an action listener reports what
 * happened inside it: which actions ran, in what order, under which context, and which one threw.
 * That is the capture a metrics collector cannot give - counters and timings answer "how often" and
 * "how long", not "what did this particular invocation see".
 *
 * <p><b>A listener attaches to the action, not to the call site.</b> It is declared on the action's
 * own def, through {@code onStart(...)} / {@code onComplete(...)} / {@code onError(...)}, and fires
 * at every invocation of that action - as a transition's attachment, as a container member, as a
 * conditional branch member, and when another action's body dispatches it by id. A reference
 * carries nothing, because which observers an action has is a property of the action, exactly as
 * its compensation is. The state-machine-wide {@code onAnyActionStart(...)} /
 * {@code onAnyActionComplete(...)} / {@code onAnyActionError(...)} registrations observe every
 * action instead, and run after the action's own listeners at each hook.
 *
 * <p><b>Every action notifies, at every nesting depth</b>, whichever form it was authored in.
 * {@link ActionExecution#kind()} tells a declarative container from an imperative step, and
 * {@link ActionExecution#path()} gives the qualified position in the execution tree - the same
 * value the invocation contributes to {@code TransitionResult.getExecutedPath()}. Filtering the
 * noise is the listener's job, and those two fields are what it filters on.
 *
 * <p><b>Listeners observe; they do not gate.</b> An exception thrown out of {@link #onAction} is
 * caught and logged, and never reaches the caller: it does not fail the action, does not trigger
 * compensation, does not suppress the listeners declared after it, and leaves
 * {@code TransitionResult} untouched. Use a pre-condition to reject a transition.
 *
 * <p><b>The context is the action's own.</b> When the call site maps the context, a listener sees
 * the mapped child context - the very object the action's body is handed - rather than the
 * enclosing transition's.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the context type the observed action runs against; {@code Object} for listeners
 *            registered against every action, which span actions that differ
 */
@FunctionalInterface
public interface ActionListener<T, C> {

    /**
     * Called when the observed action starts, completes, or fails.
     *
     * @param entity the entity the surrounding transition is running against; never {@code null}
     * @param context the context the action itself runs against; may be {@code null}
     * @param execution the phase, the qualified path, the authored form, the transition, and the
     *                  failure when there is one; never {@code null}
     */
    void onAction(T entity, C context, ActionExecution<T> execution);
}
