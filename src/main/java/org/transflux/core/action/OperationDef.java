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

import org.transflux.core.Identifiable;
import org.transflux.core.exception.TransfluxValidationException;
import org.transflux.core.transition.Transition;

import java.util.function.Consumer;

/**
 * Def-side anchor for a <em>declarative</em> action - one authored as an ordered list of members
 * rather than as a Java body.
 * <p>
 * {@code OperationDef} is the counterpart to {@link StepDef}. Declaration order is execution
 * order: at build time the framework resolves each member and emits an {@link Action} that
 * invokes them in turn, passing the entity, context and per-execution {@link Transition} view
 * through. There is no body for a host to implement.
 *
 * <p>The member grammar itself - how a position names or declares an action - is declared once on
 * {@link ActionSequence}, which a conditional's branches share. What this def adds is everything
 * that follows from a container also being an action: an id, a context type, compensation and
 * listeners.
 *
 * <p><b>{@link #fork(String) fork(id)} references an action too, and lets it go.</b> The member is
 * submitted to the state machine's executor and this operation moves on without waiting, so the
 * position in the list is the moment the work starts rather than the moment it finishes. Everything
 * a forked member does - its context, its rollback, its outcome - is separate from the path that
 * spawned it.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through this operation's execution
 */
public interface OperationDef<T, C> extends ActionDef<T, C>,
                                          ActionSequence<T, C, OperationDef<T, C>> {

    /**
     * Appends a <em>forked</em> reference to the action registered under {@code id}: the member is
     * handed to the state machine's executor and runs on another thread, while this operation
     * carries on to its next member without waiting.
     * <p>
     * A forked member is fire-and-forget. Nothing joins it and nothing cancels it, and its outcome
     * reaches neither the caller nor the
     * {@link org.transflux.core.transition.TransitionResult TransitionResult} - it appears on
     * neither the executed nor the compensated path. Observe one through an {@link ActionListener}
     * attached to the action itself, which fires for a forked invocation exactly as it does for a
     * synchronous one.
     *
     * <p>It owns its rollback. A compensation registered while the branch runs unwinds that branch
     * alone, and a failure inside it never rolls back this operation. The converse holds too:
     * submission is the commitment point, so once the branch is handed over it runs even if this
     * transition fails immediately afterwards.
     *
     * <p>The branch's context is decided here: a mapper supplied at this call site produces it,
     * otherwise a context implementing {@link ForkableContext} is forked, otherwise the branch
     * shares this operation's context reference. Sharing is legitimate for work that only reads,
     * and the build warns where it cannot establish that much.
     *
     * <p><b>The entity is shared either way.</b> A branch receives the same entity the transition
     * is running against, and the framework adds no locking around it - so a branch writing to the
     * entity while the enclosing path also writes to it is a data race the host owns, exactly as
     * two concurrent transitions on one entity already are. {@link ForkableContext} isolates the
     * context; there is no equivalent for the entity, deliberately, since it belongs to the host.
     *
     * <p>An action reached this way may not drive the state machine that spawned it.
     *
     * @param id the registered action id
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null} or blank
     */
    OperationDef<T, C> fork(String id);

    /**
     * Forked form of {@link #run(String, String)} - see {@link #fork(String)} for what forking
     * changes. The registered mapper produces the branch's context, so {@link ForkableContext} is
     * not consulted, and its {@link ContextMapper#mapFrom(Object, Object) mapFrom} is not applied:
     * there is no moment at which a forked member could write back.
     *
     * @param id the registered action id
     * @param mapperId the registered mapper id
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if either argument is {@code null} or blank
     */
    OperationDef<T, C> fork(String id, String mapperId);

    /**
     * Forked form of {@link #run(String, ContextMapper)} - see {@link #fork(String)} for what
     * forking changes. A lambda is the projection form here too, and it runs on the submitting
     * thread before the branch starts.
     *
     * @param id the registered action id
     * @param inlineMapper the mapper to apply at the boundary
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is blank or {@code inlineMapper} is
     *         {@code null}
     */
    OperationDef<T, C> fork(String id, ContextMapper<C, ?> inlineMapper);

    /**
     * {@link Identifiable} overload of {@link #fork(String)}.
     *
     * @param registeredAction an identifiable supplying the action id
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code registeredAction} is {@code null}
     */
    OperationDef<T, C> fork(Identifiable registeredAction);

    /**
     * {@link Identifiable} overload of {@link #fork(String, String)} - both action and mapper
     * supplied as identifiables.
     *
     * @param registeredAction an identifiable supplying the action id
     * @param mapper an identifiable supplying the mapper id
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if either argument is {@code null}
     */
    OperationDef<T, C> fork(Identifiable registeredAction, Identifiable mapper);

    /**
     * Mixed-form overload of {@link #fork(String, String)} - action identifiable + mapper id.
     *
     * @param registeredAction an identifiable supplying the action id
     * @param mapperId the registered mapper id
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code registeredAction} is {@code null} or
     *         {@code mapperId} is {@code null}/blank
     */
    OperationDef<T, C> fork(Identifiable registeredAction, String mapperId);

    /**
     * Mixed-form overload of {@link #fork(String, String)} - action id + mapper identifiable.
     *
     * @param id the registered action id
     * @param mapper an identifiable supplying the mapper id
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank or {@code mapper}
     *         is {@code null}
     */
    OperationDef<T, C> fork(String id, Identifiable mapper);

    /**
     * Declares a multi-branch conditional at this position - a declarative action whose ordering
     * rule is "first matching branch" rather than "all, in order".
     *
     * @param id the conditional's id; must be unique across the state machine
     * @param configurer callback that declares the branches
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is blank or {@code configurer} is
     *         {@code null}
     */
    OperationDef<T, C> conditional(String id, Consumer<ConditionalOperationDef<T, C>> configurer);

    /**
     * {@link Identifiable} overload of {@link #conditional(String, Consumer)}.
     *
     * @param conditionalIdentifiable an identifiable supplying the conditional's id
     * @param configurer callback that declares the branches
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code conditionalIdentifiable} is {@code null}
     */
    OperationDef<T, C> conditional(Identifiable conditionalIdentifiable, Consumer<ConditionalOperationDef<T, C>> configurer);

    /**
     * Declares the context type this operation's members run against.
     *
     * @param contextType the context class
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code contextType} is {@code null}
     */
    OperationDef<T, C> usingContext(Class<C> contextType);

    @Override
    OperationDef<T, C> withName(String name);

    @Override
    OperationDef<T, C> withDescription(String description);

    @Override
    OperationDef<T, C> withCompensation(Compensation<T, C> compensation);

    @Override
    OperationDef<T, C> withCompensation(Class<? extends Compensation<T, C>> compensationClass);

    @Override
    <X extends Throwable> CompensationRouteDef<T, C, X, ? extends OperationDef<T, C>> forException(
        Class<X> exceptionType);

    @Override
    OperationDef<T, C> onStart(String listenerId, ActionListener<T, C> listener);

    @Override
    OperationDef<T, C> onStart(Identifiable listenerIdentifiable, ActionListener<T, C> listener);

    @Override
    OperationDef<T, C> onStart(String listenerId, Class<? extends ActionListener<T, C>> listenerClass);

    @Override
    OperationDef<T, C> onStart(Identifiable listenerIdentifiable,
                               Class<? extends ActionListener<T, C>> listenerClass);

    @Override
    OperationDef<T, C> onStart(String listenerId, Consumer<ActionListenerDef<T, C>> configurer);

    @Override
    OperationDef<T, C> onStart(Identifiable listenerIdentifiable,
                               Consumer<ActionListenerDef<T, C>> configurer);

    @Override
    OperationDef<T, C> onComplete(String listenerId, ActionListener<T, C> listener);

    @Override
    OperationDef<T, C> onComplete(Identifiable listenerIdentifiable, ActionListener<T, C> listener);

    @Override
    OperationDef<T, C> onComplete(String listenerId,
                                  Class<? extends ActionListener<T, C>> listenerClass);

    @Override
    OperationDef<T, C> onComplete(Identifiable listenerIdentifiable,
                                  Class<? extends ActionListener<T, C>> listenerClass);

    @Override
    OperationDef<T, C> onComplete(String listenerId, Consumer<ActionListenerDef<T, C>> configurer);

    @Override
    OperationDef<T, C> onComplete(Identifiable listenerIdentifiable,
                                  Consumer<ActionListenerDef<T, C>> configurer);

    @Override
    OperationDef<T, C> onError(String listenerId, ActionListener<T, C> listener);

    @Override
    OperationDef<T, C> onError(Identifiable listenerIdentifiable, ActionListener<T, C> listener);

    @Override
    OperationDef<T, C> onError(String listenerId, Class<? extends ActionListener<T, C>> listenerClass);

    @Override
    OperationDef<T, C> onError(Identifiable listenerIdentifiable,
                               Class<? extends ActionListener<T, C>> listenerClass);

    @Override
    OperationDef<T, C> onError(String listenerId, Consumer<ActionListenerDef<T, C>> configurer);

    @Override
    OperationDef<T, C> onError(Identifiable listenerIdentifiable,
                               Consumer<ActionListenerDef<T, C>> configurer);
}
