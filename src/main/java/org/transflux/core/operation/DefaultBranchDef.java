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

package org.transflux.core.operation;

import org.transflux.core.Identifiable;
import org.transflux.core.exception.TransfluxValidationException;

/**
 * Sub-builder for the default branch of a {@link ConditionalStepDef}.
 * <p>
 * The default branch carries no condition; the framework runs its steps when every
 * preceding {@link BranchDef} evaluated to {@code false}. The default branch must declare
 * at least one step.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
public interface DefaultBranchDef<T, C> {

    /**
     * Appends a reference to a step registered on the enclosing state machine.
     *
     * @param registeredStepId the registered step id
     *
     * @return this default branch def for chaining
     *
     * @throws TransfluxValidationException if {@code registeredStepId} is {@code null} or
     *         blank
     */
    DefaultBranchDef<T, C> step(String registeredStepId);

    /**
     * {@link Identifiable} overload of {@link #step(String)} — delegates via
     * {@link Identifiable#getId()}.
     *
     * @param registeredStep an identifiable supplying the step id
     *
     * @return this default branch def for chaining
     *
     * @throws TransfluxValidationException if {@code registeredStep} is {@code null}
     */
    DefaultBranchDef<T, C> step(Identifiable registeredStep);

    /**
     * Appends an inline {@link Step} instance under the supplied id. The step is
     * auto-registered on the enclosing state machine at build time.
     *
     * @param id the step id
     * @param step the step instance
     *
     * @return this default branch def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank or
     *         {@code step} is {@code null}
     */
    DefaultBranchDef<T, C> step(String id, Step<T, C> step);

    /**
     * Appends an inline {@link Step} class under the supplied id. The framework reflectively
     * instantiates the class through its public no-arg constructor at state-machine build
     * time and auto-registers it.
     *
     * @param id the step id
     * @param stepClass the step class
     *
     * @return this default branch def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank or
     *         {@code stepClass} is {@code null}
     */
    DefaultBranchDef<T, C> step(String id, Class<? extends Step<T, C>> stepClass);
}
