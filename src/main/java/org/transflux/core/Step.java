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

package org.transflux.core;

/**
 * Pure executable contract for a reusable unit of work that participates in a transition.
 * <p>
 * A {@code Step} is entity-aware: it receives the entity under transition along with the
 * host-supplied context and the per-execution {@link Transition} view. Steps are functional
 * contracts only — they carry no identity. Identity belongs to the registration side: a step
 * is registered against a state machine (or auto-registered through an inline reference inside
 * a composite operation) under a framework-owned id, and the runtime pairs the step with that
 * id through a package-private bound record. The same {@code Step} class or instance can
 * therefore be registered under multiple ids in the same state machine.
 *
 * <p>The method returns {@code void}: side effects on the entity and any results the caller
 * cares about flow through the host-supplied context object.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
@FunctionalInterface
public interface Step<T, C> {

    /**
     * Runs the step's business logic.
     *
     * @param entity the entity undergoing the transition; never {@code null}
     * @param context the host-supplied context for this execution; may be {@code null} when
     *                the caller opted not to attach one
     * @param transition the per-execution {@link Transition} view; topology accessors are
     *                   stable, and {@code transition.step(id)} dispatches to another
     *                   registered step in the current execution scope
     */
    void execute(T entity, C context, Transition<T, C> transition);
}
