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

package org.transflux.core.impl;

/**
 * Unified runtime view of a reusable, id-keyed building block that lives in a {@link Registry}.
 * <p>
 * The three permitted variants each wrap one of the framework's bound payloads —
 * {@link BoundStep}, {@link BoundOperation}, or {@link BoundCondition} — and pair it with
 * the framework-owned id and the declared context type the component runs against. Descriptive
 * metadata ({@code name} / {@code description}) lives on the def side, not here.
 *
 * <p>The {@link #validate()} hook is called once during registration. Phase 2.5 leaves the
 * variant overrides empty; subsequent phases (notably Phase 3, when listeners attach to
 * steps) plug their cross-cutting checks in here without retouching the registry pipeline.
 *
 * @param <T> the entity type the surrounding state machine manages
 */
sealed interface Component<T> permits Component.Step, Component.Operation, Component.Condition {

    /**
     * Returns the framework-owned id of this component.
     *
     * @return the component id; never {@code null} or blank
     */
    String id();

    /**
     * Returns the context type this component was declared against, or {@code null} when
     * the enclosing scope did not declare one.
     *
     * @return the declared context class, or {@code null}
     */
    Class<?> contextType();

    /**
     * Validates the component's internal consistency once at registration time. The default
     * is a no-op; variants override when they need to gate registration.
     *
     * @throws org.transflux.core.exception.TransfluxValidationException if validation fails
     */
    default void validate() {
    }

    /**
     * Step variant — wraps a {@link BoundStep} payload.
     */
    record Step<T, C>(String id, Class<C> contextType, BoundStep<T, C> bound) implements Component<T> {
    }

    /**
     * Operation variant — wraps a {@link BoundOperation} payload.
     */
    record Operation<T, C>(String id, Class<C> contextType, BoundOperation<T, C> bound) implements Component<T> {
    }

    /**
     * Condition variant — wraps a {@link BoundCondition} payload.
     */
    record Condition<T, C>(String id, Class<C> contextType, BoundCondition<T, C> bound) implements Component<T> {
    }
}
