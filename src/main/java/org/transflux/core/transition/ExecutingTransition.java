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
 * A {@link Transition} that is currently executing — the handle an
 * {@link org.transflux.core.action.Action Action} receives, adding the ability to run other
 * actions by id.
 * <p>
 * The framework builds one of these per execution and hands it to the action's body as the
 * {@code transition} parameter. It carries the execution scope that {@code run(...)} needs: the
 * entity and context under transition, the executed-path recorder, the compensation stack, and
 * the lexical scope an id resolves against. That is why the capability lives here rather than on
 * {@link Transition} — a condition or a listener has no execution to dispatch into, and work it
 * dispatched anyway would leave compensations that could never run.
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
 *                         ExecutingTransition<Subscription, ActivationContext> transition) {
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
 * @param <T> the entity type the enclosing state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
public interface ExecutingTransition<T, C> extends Transition {

    /**
     * Runs the action registered under {@code id} in pass-through mode. The action's context
     * type must be assignable from the active context.
     *
     * @param id the registered action id
     *
     * @throws TransfluxValidationException when no action is registered under {@code id} in the
     *         active scope, or when the action's context type is not assignable from the active
     *         context
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
