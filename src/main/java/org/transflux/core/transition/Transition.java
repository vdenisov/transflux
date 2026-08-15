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
import org.transflux.core.action.ContextMapper;

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
 * are stable for the lifetime of the enclosing state machine. The {@code run(...)} methods are
 * execution-scoped: they are only meaningful when an action calls them from inside a transition
 * currently being executed, because the framework hands actions a per-execution
 * {@code Transition} view that carries the captured entity, context, and recorder. Calling them
 * against a static-topology object outside an active execution raises
 * {@link TransfluxValidationException}.
 *
 * <p>{@code run(...)} names a callee and nothing more. Which authoring form the callee was
 * declared in - imperative or declarative - is a property of its own registration, not of this
 * call site, so there is one verb rather than one per form.
 *
 * <p><b>Mapper-aware overloads.</b> {@code run(...)} accepts an optional mapper specification -
 * a registered {@code mapper} by id, an inline {@link Function} for read-only projection, or a
 * fully-supplied {@link ContextMapper} instance - that bridges the active context to whatever
 * the referenced action requires. Pass-through forms (mapper-less) require the called action's
 * context type to be assignable from the active context.
 *
 * <p><b>Example usage from inside an action:</b>
 * <pre>{@code
 * public class ActivateSubscription implements Action<Subscription, ActivationContext> {
 *     @Override
 *     public void execute(Subscription entity, ActivationContext context,
 *                         Transition<Subscription, ActivationContext> transition) {
 *         transition.run("validate-payment-method");
 *         transition.run("charge-first-period");
 *         transition.run("provision-entitlements");
 *     }
 * }
 * }</pre>
 *
 * <p>Each {@code run("id")} call resolves the action against the state machine's registry and
 * runs it against the same entity / context / view, with the action's id automatically appended
 * to the executed path on the resulting {@link TransitionResult}.
 *
 * <p>Configuration of transitions (actions, conditions, triggers, listeners) is done on
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
     * Runs the action registered under {@code id} in pass-through mode. The action's context
     * type must be assignable from the active context.
     *
     * @param id the registered action id
     *
     * @throws TransfluxValidationException when called outside an active transition execution,
     *         when no action is registered under {@code id} in the active scope, or when the
     *         action's context type is not assignable from the active context
     */
    void run(String id);

    /**
     * Runs the action registered under {@code id}, applying the registered mapper identified by
     * {@code mapperId} at the call boundary.
     *
     * @param id the registered action id
     * @param mapperId the registered mapper id
     *
     * @throws TransfluxValidationException when {@code mapperId} is blank, when no action is
     *         registered under {@code id}, or when no mapper is registered under {@code mapperId}
     */
    void run(String id, String mapperId);

    /**
     * Runs the action registered under {@code id} with an inline read-only parent-to-child
     * projection. The supplied function is wrapped as a {@link ContextMapper} whose
     * {@link ContextMapper#mapFrom(Object, Object) mapFrom} is a no-op.
     *
     * @param id the registered action id
     * @param inlineMapTo the parent-to-child projection
     *
     * @throws TransfluxValidationException when {@code inlineMapTo} is {@code null} or no action
     *         is registered under {@code id}
     */
    void run(String id, Function<C, ?> inlineMapTo);

    /**
     * Runs the action registered under {@code id} with an inline fully-supplied
     * {@link ContextMapper}.
     *
     * @param id the registered action id
     * @param inlineMapper the mapper to apply at the boundary
     *
     * @throws TransfluxValidationException when {@code inlineMapper} is {@code null} or no
     *         action is registered under {@code id}
     */
    void run(String id, ContextMapper<C, ?> inlineMapper);

    /**
     * {@link Identifiable} overload of {@link #run(String)} - delegates via
     * {@link Identifiable#getId()}.
     *
     * @param registeredAction an identifiable supplying the action id
     *
     * @throws TransfluxValidationException if {@code registeredAction} is {@code null}
     */
    void run(Identifiable registeredAction);

    /**
     * {@link Identifiable} overload of {@link #run(String, String)} - both action and mapper
     * supplied as identifiables.
     *
     * @param registeredAction an identifiable supplying the action id
     * @param mapper an identifiable supplying the mapper id
     *
     * @throws TransfluxValidationException if either argument is {@code null}
     */
    void run(Identifiable registeredAction, Identifiable mapper);

    /**
     * Mixed-form overload of {@link #run(String, String)} - action identifiable + mapper id.
     *
     * @param registeredAction an identifiable supplying the action id
     * @param mapperId the registered mapper id
     *
     * @throws TransfluxValidationException if {@code registeredAction} is {@code null} or
     *         {@code mapperId} is {@code null}/blank
     */
    void run(Identifiable registeredAction, String mapperId);

    /**
     * Mixed-form overload of {@link #run(String, String)} - action id + mapper identifiable.
     *
     * @param id the registered action id
     * @param mapper an identifiable supplying the mapper id
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank or
     *         {@code mapper} is {@code null}
     */
    void run(String id, Identifiable mapper);
}
