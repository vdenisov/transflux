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
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through this operation's execution
 */
public interface OperationDef<T, C> extends ActionDef<T, C>,
                                          ActionSequence<T, C, OperationDef<T, C>> {

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
