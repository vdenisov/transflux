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

import org.transflux.core.exception.TransfluxValidationException;

/**
 * Unified runtime view of a reusable, id-keyed building block that lives in a {@link Registry}.
 * <p>
 * The three permitted variants each wrap one of the framework's bound payloads —
 * {@link BoundStep}, {@link BoundOperation}, or {@link BoundCondition} — and pair it with
 * the framework-owned id and the declared context type the component runs against. Descriptive
 * metadata ({@code name} / {@code description}) lives on the def side, not here.
 *
 * <p>The {@link #validate()} hook is called once per component after the state machine's registry
 * chain has been built and flattened, so a component's rules may depend on the rest of the
 * definition. Every variant checks that its id agrees with its payload's; the hook is also where
 * cross-cutting checks (such as listener-attachment rules) plug in without retouching the registry
 * pipeline.
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
     * Validates the component's internal consistency once, at the end of the state machine build.
     * The default is a no-op; variants override when they need to gate the build.
     *
     * @throws TransfluxValidationException if validation fails
     */
    default void validate() {
    }

    /**
     * Step variant — wraps a {@link BoundStep} payload.
     */
    record Step<T, C>(String id, Class<C> contextType, BoundStep<T, C> bound) implements Component<T> {
        @Override
        public void validate() {
            requireIdMatchesPayload(id, bound == null ? null : bound.id(), "step");
        }
    }

    /**
     * Operation variant — wraps a {@link BoundOperation} payload.
     */
    record Operation<T, C>(String id, Class<C> contextType, BoundOperation<T, C> bound) implements Component<T> {
        @Override
        public void validate() {
            requireIdMatchesPayload(id, bound == null ? null : bound.id(), "operation");
        }
    }

    /**
     * Condition variant — wraps a {@link BoundCondition} payload.
     */
    record Condition<T, C>(String id, Class<C> contextType, BoundCondition<T, C> bound) implements Component<T> {
        @Override
        public void validate() {
            requireIdMatchesPayload(id, bound == null ? null : bound.id(), "condition");
        }
    }

    /**
     * The invariant every variant shares: a component is a registry key paired with the payload
     * that key resolves to, so the two ids must agree. A mismatch means a lookup would hand back a
     * bound record that reports a different id than the one it was found under, and every
     * diagnostic downstream would name the wrong thing.
     */
    private static void requireIdMatchesPayload(String id, String payloadId, String kind) {
        if (payloadId == null) {
            throw new TransfluxValidationException("Component '" + id + "' has no bound " + kind);
        }
        if (!id.equals(payloadId)) {
            throw new TransfluxValidationException(
                "Component '" + id + "' wraps a bound " + kind + " with id '" + payloadId + "'");
        }
    }
}
