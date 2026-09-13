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

import org.transflux.core.exception.TransfluxValidationException;
import org.transflux.core.action.AsyncRejectionPolicy;
import org.transflux.core.action.ContextMapper;


/**
 * A {@link Transition} that is currently executing — the handle an
 * {@link org.transflux.core.action.Action Action} receives, adding the ability to dispatch other
 * actions by id, in line or on the executor.
 * <p>
 * The framework builds one of these per execution and hands it to the action's body as the
 * {@code transition} parameter. It carries the execution scope dispatch needs: the entity and
 * context under transition, the executed-path recorder, the compensation stack, and the lexical
 * scope an id resolves against. That is why the capability lives here rather than on
 * {@link Transition} — a condition or a listener has no execution to dispatch into, and work it
 * dispatched anyway would leave compensations that could never run.
 *
 * <p>{@code run(...)} and {@code fork(...)} name a callee and nothing more. Which authoring form
 * the callee was declared in - imperative or declarative - is a property of its own registration,
 * not of this call site, so there is one verb per waiting mode rather than one per form.
 * Declaring a new action is not among them: a body that wants one declares it in the sequence
 * that encloses it.
 *
 * <p><b>Mapper-aware overloads.</b> Both verbs accept an optional mapper specification -
 * a registered {@code mapper} by id, or an inline {@link ContextMapper}, which a lambda satisfies
 * for the read-only projection case - that bridges the active context to whatever the referenced
 * action requires. Pass-through forms (mapper-less) require the called action's
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
 *         transition.fork("emit-audit-record");
 *     }
 * }
 * }</pre>
 *
 * <p>Each {@code run("id")} call resolves the action against the state machine's registry and
 * runs it against the same entity / context / view, with the action's id automatically appended
 * to the executed path on the resulting {@link TransitionResult}.
 *
 * <p><b>Forked dispatch.</b> {@code fork(...)} hands the action to the state machine's executor
 * and returns; the body carries on without it. The branch is fire-and-forget: it reaches neither
 * {@link TransitionResult#getExecutedPath()} nor {@link TransitionResult#getCompensatedPath()},
 * its failure never reaches the caller, and it owns its own compensation stack. It inherits the
 * calling body's lexical position, so its qualified path nests under the calling action and an id
 * it dispatches resolves against the same scope. Hosts observe a branch through the action
 * listeners attached to the action itself, which fire on it exactly as they do in line.
 *
 * <p>Two things a forked call site must know, both following from one fact - a fork written in a
 * Java body is invisible to the build. The executor has to be asked for: a state machine whose
 * only forks are imperative builds no pool unless its definition declares
 * {@code withAsyncPool(...)} or {@code withAsyncExecutor(...)}. And what a refused submission does
 * is the declared {@link AsyncRejectionPolicy} - this call site first, then the action's own def,
 * then the state machine's default - of which {@link AsyncRejectionPolicy#BLOCK} needs the pool
 * the framework builds for itself, and so is refused at the submission against a host-supplied
 * executor rather than at build time.
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
     * Hands the action registered under {@code id} to the executor in pass-through mode and
     * returns without waiting for it. The action's context type must be assignable from the
     * active context, and the branch shares that context reference unless it implements
     * {@link org.transflux.core.action.ForkableContext ForkableContext}.
     *
     * @param id the registered action id
     *
     * @throws TransfluxValidationException when no action is registered under {@code id} in the
     *         active scope, when the action's context type is not assignable from the active
     *         context, or when this state machine has no executor to fork onto
     */
    void fork(String id);

    /**
     * Hands the action registered under {@code id} to the executor, answering a refused
     * submission with {@code policy} rather than with the action's own declaration or the state
     * machine's default.
     *
     * @param id the registered action id
     * @param policy what to do when the executor cannot take the submission
     *
     * @throws TransfluxValidationException when no action is registered under {@code id}, when
     *         {@code policy} is {@code null}, when this state machine has no executor to fork
     *         onto, or when {@code policy} is {@link AsyncRejectionPolicy#BLOCK} and the executor
     *         was supplied by the host
     */
    void fork(String id, AsyncRejectionPolicy policy);

    /**
     * Hands the action registered under {@code id} to the executor, applying the registered
     * mapper identified by {@code mapperId} at the call boundary. The mapper runs on the calling
     * thread, before the branch is submitted; its {@code mapFrom} is not applied, because a
     * forked outcome does not merge back into the parent context.
     *
     * @param id the registered action id
     * @param mapperId the registered mapper id
     *
     * @throws TransfluxValidationException when {@code mapperId} is blank, when no action is
     *         registered under {@code id}, when no mapper is registered under {@code mapperId},
     *         when the mapper produces a context the action's declared type does not accept, or
     *         when this state machine has no executor to fork onto
     */
    void fork(String id, String mapperId);

    /**
     * Hands the action registered under {@code id} to the executor through the registered mapper
     * {@code mapperId}, answering a refused submission with {@code policy}.
     *
     * @param id the registered action id
     * @param mapperId the registered mapper id
     * @param policy what to do when the executor cannot take the submission
     *
     * @throws TransfluxValidationException on the conditions {@link #fork(String, String)} and
     *         {@link #fork(String, AsyncRejectionPolicy)} each carry
     */
    void fork(String id, String mapperId, AsyncRejectionPolicy policy);

    /**
     * Hands the action registered under {@code id} to the executor with an inline fully-supplied
     * {@link ContextMapper}, applied on the calling thread before the branch is submitted.
     *
     * @param id the registered action id
     * @param inlineMapper the mapper to apply at the boundary
     *
     * @throws TransfluxValidationException when {@code inlineMapper} is {@code null}, when no
     *         action is registered under {@code id}, when the mapper produces a context the
     *         action's declared type does not accept, or when this state machine has no executor
     *         to fork onto
     */
    void fork(String id, ContextMapper<C, ?> inlineMapper);

    /**
     * Hands the action registered under {@code id} to the executor through {@code inlineMapper},
     * answering a refused submission with {@code policy}.
     *
     * @param id the registered action id
     * @param inlineMapper the mapper to apply at the boundary
     * @param policy what to do when the executor cannot take the submission
     *
     * @throws TransfluxValidationException on the conditions {@link #fork(String, ContextMapper)}
     *         and {@link #fork(String, AsyncRejectionPolicy)} each carry
     */
    void fork(String id, ContextMapper<C, ?> inlineMapper, AsyncRejectionPolicy policy);

}
