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

import org.transflux.core.transition.Transition;

/**
 * Pure executable contract for a unit of work that runs while a transition is in flight.
 * <p>
 * An {@code Action} is entity-aware: it receives the entity under transition along with the
 * host-supplied context and the per-execution {@link Transition} view. Actions are functional
 * contracts only - they carry no identity. Identity, declared context type, and metadata live on
 * the def side, which pairs the executable with a framework-owned id. The same {@code Action}
 * class or instance can therefore be registered under multiple ids in the same state machine.
 *
 * <p>An action is authored in one of two forms, and the form is a property of the declaration
 * rather than of this interface. A <em>step</em> is imperative: a Java body, which is what
 * implementations of this interface are. An <em>operation</em> is declarative: an ordered child
 * list whose executable the framework synthesizes, so there is nothing for a host to implement.
 * The authored form travels with the action as an {@link ActionKind} and surfaces in diagnostics;
 * it does not change how the action is executed.
 *
 * <p>The method returns {@code void}: side effects on the entity and any results the caller
 * cares about flow through the host-supplied context object.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
@FunctionalInterface
public interface Action<T, C> {

    /**
     * Runs the action's business logic.
     *
     * @param entity the entity undergoing the transition; never {@code null}
     * @param context the host-supplied context for this execution; may be {@code null} when
     *                the caller opted not to attach one
     * @param transition the per-execution {@link Transition} view; topology accessors are
     *                   stable, and the dispatch methods run another registered action in the
     *                   current execution scope
     */
    void execute(T entity, C context, Transition<T, C> transition);

    /**
     * Returns the {@link Compensation} that rolls back this action's effects, or {@code null}
     * when this action has nothing to roll back.
     * <p>
     * The runtime invokes this method exactly once per invocation, <em>before</em>
     * {@link #execute(Object, Object, Transition)} runs. The returned compensation is pushed
     * onto the per-execution LIFO rollback stack at that point; if the enclosing transition
     * later fails (this action's own {@code execute} throws, or a subsequent one does), the
     * stack is drained in reverse-push order and each compensation runs in turn.
     *
     * <p>Capturing the compensation before {@code execute} is deliberate: an action that throws
     * partway through producing side effects - created remote entities, inserted database rows,
     * published messages - should still have its compensation invoked so the partial work can
     * be cleaned up. The {@code entity} and {@code context} references handed in here are the
     * same references {@code execute} will see; the compensation is free to close over them and
     * read whatever state {@code execute} has accumulated by the time the rollback runs (for
     * example, a list of created ids the action appends to as it goes).
     *
     * <p>If the compensation depends on completion-time state (e.g. a "before" snapshot of the
     * entity), write that state into the entity or context during {@code execute} and have the
     * returned compensation read it back at rollback time.
     *
     * <p>Returning {@code null} means "no compensation registered for this action's effects" and
     * leaves the rollback stack unchanged.
     *
     * @param entity the entity this action is about to be invoked against; never {@code null}
     * @param context the host-supplied context this action is about to be invoked against; may
     *                be {@code null} when the caller opted not to attach one
     *
     * @return the compensation to register against this action's id, or {@code null}
     */
    default Compensation<T, C> getCompensation(T entity, C context) {
        return null;
    }
}
