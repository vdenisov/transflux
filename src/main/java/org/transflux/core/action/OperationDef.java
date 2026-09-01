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
    OperationDef<T, C> onStart(String listenerId, Class<? extends ActionListener<T, C>> listenerClass);

    @Override
    OperationDef<T, C> onStart(String listenerId, Consumer<ActionListenerDef<T, C>> configurer);

    @Override
    OperationDef<T, C> onComplete(String listenerId, ActionListener<T, C> listener);

    @Override
    OperationDef<T, C> onComplete(String listenerId,
                                  Class<? extends ActionListener<T, C>> listenerClass);

    @Override
    OperationDef<T, C> onComplete(String listenerId, Consumer<ActionListenerDef<T, C>> configurer);

    @Override
    OperationDef<T, C> onError(String listenerId, ActionListener<T, C> listener);

    @Override
    OperationDef<T, C> onError(String listenerId, Class<? extends ActionListener<T, C>> listenerClass);

    @Override
    OperationDef<T, C> onError(String listenerId, Consumer<ActionListenerDef<T, C>> configurer);

}
