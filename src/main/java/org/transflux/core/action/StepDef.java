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

import java.util.function.Consumer;

/**
 * Def-side anchor for an <em>imperative</em> action - one authored as a Java body, supplied as an
 * {@link Action} instance or as a class the framework instantiates.
 * <p>
 * Pure {@code Action} executables carry no identity; identity, context type and metadata live
 * here. At build time the framework pairs the executable with this def's id so the runtime can
 * report which action ran without {@code Action} itself having to carry identity.
 *
 * <p>This is the counterpart to {@link OperationDef}, which declares the other authoring form:
 * an ordered list of members rather than a body. A step and an operation are the same thing at
 * runtime and are dispatched identically; the distinction is what the author wrote, and it
 * survives only as metadata in diagnostics.
 *
 * <p>The {@code id} is mandatory. Exactly one of {@link #using(Action)} or {@link #using(Class)}
 * must be called before the enclosing state machine is built; calling {@code using(...)} a second
 * time overrides the prior choice.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type this action requires
 */
public interface StepDef<T, C> extends ActionDef<T, C> {

    /**
     * Wires this def to a pre-constructed {@link Action} instance.
     *
     * @param action the action to invoke; never {@code null}
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code action} is {@code null}
     */
    StepDef<T, C> using(Action<T, C> action);

    /**
     * Wires this def to an {@link Action} class. The framework instantiates it via its public
     * no-arg constructor at build time.
     *
     * @param actionClass the action class; never {@code null}
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code actionClass} is {@code null}
     */
    StepDef<T, C> using(Class<? extends Action<T, C>> actionClass);

    @Override
    StepDef<T, C> withName(String name);

    @Override
    StepDef<T, C> withDescription(String description);

    @Override
    StepDef<T, C> withCompensation(Compensation<T, C> compensation);

    @Override
    StepDef<T, C> withCompensation(Class<? extends Compensation<T, C>> compensationClass);

    @Override
    StepDef<T, C> onStart(String listenerId, ActionListener<T, C> listener);

    @Override
    StepDef<T, C> onStart(Identifiable listenerIdentifiable, ActionListener<T, C> listener);

    @Override
    StepDef<T, C> onStart(String listenerId, Class<? extends ActionListener<T, C>> listenerClass);

    @Override
    StepDef<T, C> onStart(Identifiable listenerIdentifiable,
                          Class<? extends ActionListener<T, C>> listenerClass);

    @Override
    StepDef<T, C> onStart(String listenerId, Consumer<ActionListenerDef<T, C>> configurer);

    @Override
    StepDef<T, C> onStart(Identifiable listenerIdentifiable,
                          Consumer<ActionListenerDef<T, C>> configurer);

    @Override
    StepDef<T, C> onComplete(String listenerId, ActionListener<T, C> listener);

    @Override
    StepDef<T, C> onComplete(Identifiable listenerIdentifiable, ActionListener<T, C> listener);

    @Override
    StepDef<T, C> onComplete(String listenerId, Class<? extends ActionListener<T, C>> listenerClass);

    @Override
    StepDef<T, C> onComplete(Identifiable listenerIdentifiable,
                             Class<? extends ActionListener<T, C>> listenerClass);

    @Override
    StepDef<T, C> onComplete(String listenerId, Consumer<ActionListenerDef<T, C>> configurer);

    @Override
    StepDef<T, C> onComplete(Identifiable listenerIdentifiable,
                             Consumer<ActionListenerDef<T, C>> configurer);

    @Override
    StepDef<T, C> onError(String listenerId, ActionListener<T, C> listener);

    @Override
    StepDef<T, C> onError(Identifiable listenerIdentifiable, ActionListener<T, C> listener);

    @Override
    StepDef<T, C> onError(String listenerId, Class<? extends ActionListener<T, C>> listenerClass);

    @Override
    StepDef<T, C> onError(Identifiable listenerIdentifiable,
                          Class<? extends ActionListener<T, C>> listenerClass);

    @Override
    StepDef<T, C> onError(String listenerId, Consumer<ActionListenerDef<T, C>> configurer);

    @Override
    StepDef<T, C> onError(Identifiable listenerIdentifiable,
                          Consumer<ActionListenerDef<T, C>> configurer);
}
