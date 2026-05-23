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

import org.transflux.core.operation.Operation;
import org.transflux.core.operation.OperationDef;

import static org.transflux.core.Preconditions.requireNotBlank;

/**
 * Sealed base for concrete {@link OperationDef} implementations.
 * <p>
 * Holds the metadata shared by every concrete operation def kind. The mandatory {@code id}
 * is validated in the constructor; {@code name} and {@code description} are optional and can
 * be set through fluent setters that subclasses expose with a covariant return type.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
sealed abstract class OperationDefImpl<T, C> implements OperationDef<T, C>
    permits SimpleOperationDefImpl, CompositeOperationDefImpl {
    private final String id;
    private String name;
    private String description;

    protected OperationDefImpl(String id) {
        requireNotBlank(id, "Operation ID");
        this.id = id;
    }

    @Override
    public final String getId() {
        return id;
    }

    @Override
    public final String getName() {
        return name;
    }

    @Override
    public final String getDescription() {
        return description;
    }

    @Override
    public OperationDef<T, C> withName(String name) {
        this.name = name;
        return this;
    }

    @Override
    public OperationDef<T, C> withDescription(String description) {
        this.description = description;
        return this;
    }
}
