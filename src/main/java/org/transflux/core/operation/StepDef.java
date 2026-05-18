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
 * Def-side anchor that pairs a single {@link Step} class or instance with a framework-owned id,
 * its declared context type, and optional metadata.
 * <p>
 * Pure {@link Step} executables carry no identity; identity, context type, and metadata live on
 * the def. The user supplies either an already-constructed {@code Step} instance or a class with
 * a public no-arg constructor; at build time the framework produces a {@link BoundStep} pairing
 * the step with this def's id so the runtime can track which step ran without {@code Step}
 * itself having to carry identity.
 *
 * <p>The {@code id} and {@code contextType} are mandatory. Exactly one of {@link #using(Step)}
 * or {@link #using(Class)} must be called before the enclosing state machine is built; calling
 * {@code using(...)} a second time overrides the prior choice.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type this step requires
 */
public interface StepDef<T, C> extends Identifiable {

    /**
     * Returns the unique identifier of this step def.
     *
     * @return the step id; never {@code null} or blank
     */
    @Override
    String getId();

    /**
     * Returns the human-readable name of this step, or {@code null} when unset.
     *
     * @return the optional step name
     */
    String getName();

    /**
     * Returns the description of this step, or {@code null} when unset.
     *
     * @return the optional step description
     */
    String getDescription();

    /**
     * Returns the context class this step requires. Used at build time to verify that call
     * sites either pass through a compatible parent context or supply a {@link ContextMapper}
     * whose child type matches this class.
     *
     * @return the context class; never {@code null}
     */
    Class<C> contextType();

    /**
     * Wires this def to a pre-constructed {@code Step} instance.
     *
     * @param step the step to invoke; never {@code null}
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code step} is {@code null}
     */
    StepDef<T, C> using(Step<T, C> step);

    /**
     * Wires this def to a {@code Step} class. The framework instantiates it via its public
     * no-arg constructor at build time.
     *
     * @param stepClass the step class; never {@code null}
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code stepClass} is {@code null}
     */
    StepDef<T, C> using(Class<? extends Step<T, C>> stepClass);

    /**
     * Sets the human-readable name of this step.
     *
     * @param name the name; may be {@code null} to clear
     *
     * @return this def for chaining
     */
    StepDef<T, C> withName(String name);

    /**
     * Sets the description of this step.
     *
     * @param description the description; may be {@code null} to clear
     *
     * @return this def for chaining
     */
    StepDef<T, C> withDescription(String description);
}
