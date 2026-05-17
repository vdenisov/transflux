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

import org.transflux.core.Identifiable;
import org.transflux.core.exception.TransfluxValidationException;

/**
 * Represents a transition between states in a state machine, defining valid state changes
 * and their associated metadata.
 * <p>
 * Transitions define the allowed paths between states in the state machine, specifying
 * the source state from which the transition can be initiated and the target state
 * to which the entity will move. Transitions can have associated operations, pre-conditions
 * that must be met before execution, post-conditions that must be met after execution,
 * and various types of triggers (manual, event-based, data-based).
 *
 * <p>Transitions are the core mechanism through which entities move through their lifecycle,
 * coordinating complex business logic, error handling, and compensation patterns similar
 * to the Saga pattern.
 *
 * <p>Topology accessors ({@link #getId()}, {@link #getSourceStateId()}, {@link #getTargetStateId()})
 * are stable for the lifetime of the enclosing state machine. {@link #step(String)} is the
 * execution-scoped seam: it is only meaningful when an operation calls it from inside a
 * transition currently being executed, because the framework hands operations a per-execution
 * {@code Transition} view that carries the captured entity, context, and recorder. Calling
 * {@code step} against the static-topology object thrown by {@code getTransition(...)} on a
 * built state machine raises {@link TransfluxValidationException}.
 *
 * <p><b>Example usage from inside an operation:</b>
 * <pre>{@code
 * public class ActivateSubscription implements Operation<Subscription, ActivationContext> {
 *     @Override
 *     public void execute(Subscription entity, ActivationContext context,
 *                         Transition<Subscription, ActivationContext> transition) {
 *         transition.step("validate-payment-method");
 *         transition.step("charge-first-period");
 *         transition.step("provision-entitlements");
 *     }
 * }
 * }</pre>
 *
 * <p>Each {@code step("id")} call resolves the step against the state machine's registry and
 * runs it against the same entity / context / view, with the step's id automatically appended
 * to the executed-step list on the resulting {@link TransitionResult}.
 *
 * <p>Configuration of transitions (operations, conditions, triggers, listeners) is done on
 * {@link TransitionDef} during state machine construction, not on this runtime interface.
 *
 * @param <T> the entity type the enclosing state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
public interface Transition<T, C> extends Identifiable {

    /**
     * Returns the identifier of the source state from which this transition can be initiated.
     * <p>
     * The source state ID must correspond to a state defined in the state machine.
     * Transitions can only be executed when the entity is currently in the source state.
     *
     * @return the source state identifier; never {@code null} or blank
     */
    String getSourceStateId();

    /**
     * Returns the identifier of the target state to which the entity will transition.
     * <p>
     * The target state ID must correspond to a state defined in the state machine.
     * Upon successful completion of the transition, the entity will be in the target state.
     *
     * @return the target state identifier; never {@code null} or blank
     */
    String getTargetStateId();

    /**
     * Executes the registered step against the current execution scope. Only valid while a
     * transition is executing — i.e. on the per-execution {@code Transition} view handed to
     * an {@code Operation}'s {@code execute(...)} method.
     *
     * @param id the registered step id
     *
     * @throws TransfluxValidationException when called outside an active transition execution
     */
    void step(String id);

    //TODO: operation
}
