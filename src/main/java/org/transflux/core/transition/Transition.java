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
import org.transflux.core.operation.ContextMapper;

import java.util.function.Function;

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
 * are stable for the lifetime of the enclosing state machine. The {@code step(...)} and
 * {@code operation(...)} dispatch methods are execution-scoped: they are only meaningful when
 * an operation calls them from inside a transition currently being executed, because the
 * framework hands operations a per-execution {@code Transition} view that carries the
 * captured entity, context, and recorder. Calling them against a static-topology object
 * outside an active execution raises {@link TransfluxValidationException}.
 *
 * <p><b>Mapper-aware overloads.</b> Both {@code step(...)} and {@code operation(...)} accept an
 * optional mapper specification — a registered {@code mapper} by id, an inline {@link Function}
 * for read-only projection, or a fully-supplied {@link ContextMapper} instance — that bridges
 * the active context to whatever the referenced step or operation requires. Pass-through forms
 * (mapper-less) require the called step or operation's context type to be assignable from the
 * active context.
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
     * Dispatches a registered step under {@code id} in pass-through mode. The step's context
     * type must be assignable from the active context.
     *
     * @param id the registered step id
     *
     * @throws TransfluxValidationException when called outside an active transition execution,
     *         when no step is registered under {@code id} in the active scope, or when the
     *         step's context type is not assignable from the active context
     */
    void step(String id);

    /**
     * Dispatches a registered step under {@code id}, applying the registered mapper identified
     * by {@code mapperId} at the call boundary.
     *
     * @param id the registered step id
     * @param mapperId the registered mapper id
     *
     * @throws TransfluxValidationException when {@code mapperId} is blank, when no step is
     *         registered under {@code id}, or when no mapper is registered under {@code mapperId}
     */
    void step(String id, String mapperId);

    /**
     * Dispatches a registered step under {@code id} with an inline read-only parent-to-child
     * projection. The supplied function is wrapped as a {@link ContextMapper} whose
     * {@link ContextMapper#mapFrom(Object, Object) mapFrom} is a no-op.
     *
     * @param id the registered step id
     * @param inlineMapTo the parent-to-child projection
     *
     * @throws TransfluxValidationException when {@code inlineMapTo} is {@code null} or no step
     *         is registered under {@code id}
     */
    void step(String id, Function<C, ?> inlineMapTo);

    /**
     * Dispatches a registered step under {@code id} with an inline fully-supplied
     * {@link ContextMapper}.
     *
     * @param id the registered step id
     * @param inlineMapper the mapper to apply at the boundary
     *
     * @throws TransfluxValidationException when {@code inlineMapper} is {@code null} or no
     *         step is registered under {@code id}
     */
    void step(String id, ContextMapper<C, ?> inlineMapper);

    /**
     * Dispatches a registered operation under {@code id} in pass-through mode. The operation's
     * context type must be assignable from the active context.
     *
     * @param id the registered operation id
     *
     * @throws TransfluxValidationException when called outside an active transition execution,
     *         when no operation is registered under {@code id} in the active scope, or when the
     *         operation's context type is not assignable from the active context
     */
    void operation(String id);

    /**
     * Dispatches a registered operation under {@code id}, applying the registered mapper
     * identified by {@code mapperId} at the call boundary.
     *
     * @param id the registered operation id
     * @param mapperId the registered mapper id
     *
     * @throws TransfluxValidationException when {@code mapperId} is blank, when no operation
     *         is registered under {@code id}, or when no mapper is registered under
     *         {@code mapperId}
     */
    void operation(String id, String mapperId);

    /**
     * Dispatches a registered operation under {@code id} with an inline read-only
     * parent-to-child projection. The supplied function is wrapped as a {@link ContextMapper}
     * whose {@link ContextMapper#mapFrom(Object, Object) mapFrom} is a no-op.
     *
     * @param id the registered operation id
     * @param inlineMapTo the parent-to-child projection
     *
     * @throws TransfluxValidationException when {@code inlineMapTo} is {@code null} or no
     *         operation is registered under {@code id}
     */
    void operation(String id, Function<C, ?> inlineMapTo);

    /**
     * Dispatches a registered operation under {@code id} with an inline fully-supplied
     * {@link ContextMapper}.
     *
     * @param id the registered operation id
     * @param inlineMapper the mapper to apply at the boundary
     *
     * @throws TransfluxValidationException when {@code inlineMapper} is {@code null} or no
     *         operation is registered under {@code id}
     */
    void operation(String id, ContextMapper<C, ?> inlineMapper);
}
