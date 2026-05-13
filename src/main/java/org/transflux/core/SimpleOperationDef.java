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
 * Def-side anchor that pairs a single {@link Operation} class or instance with a framework-owned id.
 * <p>
 * {@code SimpleOperationDef} is the simple-case counterpart to {@code CompositeOperationDef}.
 * The user supplies either an already-constructed {@code Operation} instance or a class with
 * a no-arg constructor; at build time the framework produces a package-private bound record
 * pairing the operation with this def's id/name/description so the runtime can track which
 * operation ran without the {@code Operation} itself having to carry identity.
 *
 * <p>The id is mandatory. Exactly one of {@link #using(Operation)} or
 * {@link #using(Class)} must be called before the enclosing transition is built; calling
 * {@code using(...)} a second time overrides the prior choice (the resolver / applier
 * override-with-warning pattern used elsewhere in the builder).
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
public interface SimpleOperationDef<T, C> extends OperationDef<T, C> {

    /**
     * Wires this def to a pre-constructed {@code Operation} instance.
     *
     * @param operation the operation to invoke; never {@code null}
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code operation} is {@code null}
     */
    SimpleOperationDef<T, C> using(Operation<T, C> operation);

    /**
     * Wires this def to an {@code Operation} class. The framework instantiates it via its
     * public no-arg constructor at build time.
     *
     * @param operationClass the operation class; never {@code null}
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code operationClass} is {@code null}
     */
    SimpleOperationDef<T, C> using(Class<? extends Operation<T, C>> operationClass);

    @Override
    SimpleOperationDef<T, C> name(String name);

    @Override
    SimpleOperationDef<T, C> description(String description);
}
