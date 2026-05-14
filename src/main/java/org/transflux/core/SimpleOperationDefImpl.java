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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;

import static org.transflux.core.ValidationUtils.requireNotNull;

/**
 * Package-private implementation of {@link SimpleOperationDef}.
 * <p>
 * Holds either an {@code Operation} instance or an {@code Operation} class; the two are
 * mutually exclusive and last-write-wins (matches the {@code withStateResolver} /
 * {@code withStateApplier} override-with-warning pattern in {@code StateMachineDefImpl}).
 * {@link #build()} reflectively instantiates the class form when needed and produces a
 * {@link BoundOperation}.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
class SimpleOperationDefImpl<T, C> extends OperationDefImpl<T, C> implements SimpleOperationDef<T, C> {
    private static final Logger log = LoggerFactory.getLogger(SimpleOperationDefImpl.class);

    private Operation<T, C> operationInstance;
    private Class<? extends Operation<T, C>> operationClass;

    SimpleOperationDefImpl(String id) {
        super(id);
    }

    @Override
    public SimpleOperationDefImpl<T, C> using(Operation<T, C> operation) {
        requireNotNull(operation, "Operation");
        if (this.operationInstance != null || this.operationClass != null) {
            log.warn("Operation source already defined for SimpleOperationDef '{}'; overriding previous value",
                getId());
        }
        this.operationInstance = operation;
        this.operationClass = null;
        return this;
    }

    @Override
    public SimpleOperationDefImpl<T, C> using(Class<? extends Operation<T, C>> operationClass) {
        requireNotNull(operationClass, "Operation class");
        if (this.operationInstance != null || this.operationClass != null) {
            log.warn("Operation source already defined for SimpleOperationDef '{}'; overriding previous value",
                getId());
        }
        this.operationClass = operationClass;
        this.operationInstance = null;
        return this;
    }

    @Override
    public SimpleOperationDefImpl<T, C> name(String name) {
        super.name(name);
        return this;
    }

    @Override
    public SimpleOperationDefImpl<T, C> description(String description) {
        super.description(description);
        return this;
    }

    BoundOperation<T, C> build() {
        if (operationInstance == null && operationClass == null) {
            throw new TransfluxValidationException(
                "SimpleOperationDef '" + getId() + "' has no operation set; call using(...) before build");
        }

        Operation<T, C> resolved = operationInstance != null
            ? operationInstance
            : instantiate(operationClass);

        return BoundOperation.of(getId(), getName(), getDescription(), resolved);
    }

    private Operation<T, C> instantiate(Class<? extends Operation<T, C>> cls) {
        try {
            return cls.getDeclaredConstructor().newInstance();
        } catch (NoSuchMethodException e) {
            throw new TransfluxValidationException(
                "Operation class '" + cls.getName() + "' has no accessible no-arg constructor", e);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new TransfluxValidationException(
                "Failed to instantiate operation class '" + cls.getName() + "'", e);
        }
    }
}
