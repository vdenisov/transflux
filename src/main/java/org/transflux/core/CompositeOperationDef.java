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

package org.transflux.core;

/**
 * Def-side anchor that builds an {@link Operation} from an ordered sequence of bound steps.
 * <p>
 * {@code CompositeOperationDef} is the composite counterpart to {@code SimpleOperationDef}.
 * The user appends step references in declaration order; at build time the framework resolves
 * each reference against the state machine's step registry (auto-registering any inline
 * references) and emits an executor {@link Operation} that invokes each {@link Step} in turn,
 * passing the entity, context, and per-execution {@link Transition} view through to each one.
 * Each executed step's id is recorded uniformly, whether the step is driven by the composite
 * executor or by an explicit {@code transition.step("id")} call from a user operation.
 *
 * <p>The composite's own {@code id} is mandatory; {@code name} and {@code description} are
 * optional metadata. The composite must declare at least one step before the enclosing
 * transition is built.
 *
 * <p>The three {@code step(...)} overloads may be called in any order and any number of times.
 * Each call appends one step to the end of the composite's step list.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
public interface CompositeOperationDef<T, C> extends OperationDef<T, C> {

    /**
     * Appends a reference to a step that is registered on the enclosing state machine. The
     * referenced id must be registered (or auto-registered through another composite's inline
     * reference) by the time the state machine is built.
     *
     * @param registeredStepId the registered step id
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code registeredStepId} is {@code null} or blank
     */
    CompositeOperationDef<T, C> step(String registeredStepId);

    /**
     * Appends an inline step instance. The step is auto-registered on the enclosing state
     * machine under {@code id} at build time and can be referenced by id from elsewhere.
     *
     * @param id the step id
     * @param step the step instance; never {@code null}
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank or {@code step}
     *         is {@code null}
     */
    CompositeOperationDef<T, C> step(String id, Step<T, C> step);

    /**
     * Appends an inline step class. The framework reflectively instantiates the class via its
     * public no-arg constructor at state-machine build time and auto-registers it under
     * {@code id}.
     *
     * @param id the step id
     * @param stepClass the step class; never {@code null}
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank or
     *         {@code stepClass} is {@code null}
     */
    CompositeOperationDef<T, C> step(String id, Class<? extends Step<T, C>> stepClass);

    @Override
    CompositeOperationDef<T, C> name(String name);

    @Override
    CompositeOperationDef<T, C> description(String description);
}
