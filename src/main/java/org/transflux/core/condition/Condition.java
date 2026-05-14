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

package org.transflux.core.condition;

import org.transflux.core.transition.Transition;

/**
 * Pure executable contract for a boolean predicate that participates in a transition's
 * pre- or post-condition checks.
 * <p>
 * A {@code Condition} is entity-aware: it receives the entity under transition along with the
 * host-supplied context and the per-execution {@link Transition} view. Conditions are
 * functional contracts only — they carry no identity. Identity belongs to the def side: a
 * condition is registered against a state machine (or authored inline through a
 * {@link ConditionDescriptor}) under a framework-owned id, and the runtime pairs the
 * condition with that id through a package-private bound record. The same {@code Condition}
 * class or instance can therefore be registered under multiple ids in the same state machine.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
@FunctionalInterface
public interface Condition<T, C> {

    /**
     * Evaluates the condition against the current execution scope.
     *
     * @param entity the entity undergoing the transition; never {@code null}
     * @param context the host-supplied context for this execution; may be {@code null} when
     *                the caller opted not to attach one
     * @param transition the per-execution {@link Transition} view; topology accessors are
     *                   stable for the lifetime of the execution
     *
     * @return {@code true} if the condition holds, {@code false} otherwise
     */
    boolean test(T entity, C context, Transition<T, C> transition);
}
