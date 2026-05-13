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
 * Pure executable contract for the business logic that runs while a transition is in flight.
 * <p>
 * {@code Operation} is a functional contract only — it carries no identity. Identity, naming,
 * and description belong to the def side ({@link SimpleOperationDef} / {@code CompositeOperationDef}),
 * which pairs an {@code Operation} with framework-owned metadata into a package-private
 * {@code BoundOperation} that the runtime carries. The same {@code Operation} class can therefore
 * be registered under multiple ids in the same state machine without any per-instance bookkeeping.
 *
 * <p>The method returns {@code void}: side effects on the entity and any results the caller
 * cares about flow through the host-supplied context object.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
@FunctionalInterface
public interface Operation<T, C> {

    /**
     * Runs the operation's business logic.
     *
     * @param entity the entity undergoing the transition; never {@code null}
     * @param context the host-supplied context for this execution; may be {@code null} when the
     *                caller opted not to attach one
     * @param transition the per-execution {@link Transition} view; topology accessors are stable,
     *                   and {@code transition.step(id)} runs a registered step in the current
     *                   execution scope
     */
    void execute(T entity, C context, Transition<T, C> transition);
}
