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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.transflux.core.exception.TransfluxValidationException;
import org.transflux.core.operation.Operation;
import org.transflux.core.operation.SimpleOperationDef;

import java.util.Map;
import java.util.Optional;

import static org.transflux.core.Preconditions.requireNotNull;
import static org.transflux.core.impl.ReflectionUtils.instantiateNoArg;

/**
 * Package-private implementation of {@link SimpleOperationDef}.
 * <p>
 * Holds either an {@link Operation} instance or an {@code Operation} class; the two are
 * mutually exclusive and last-write-wins (matches the {@code withStateResolver} /
 * {@code withStateApplier} override-with-warning pattern in {@link StateMachineDefImpl}).
 * {@link #build()} reflectively instantiates the class form when needed and produces a
 * {@link BoundOperation}.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
public final class SimpleOperationDefImpl<T, C> extends OperationDefImpl<T, C> implements SimpleOperationDef<T, C> {
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
    public SimpleOperationDefImpl<T, C> withName(String name) {
        super.withName(name);
        return this;
    }

    @Override
    public SimpleOperationDefImpl<T, C> withDescription(String description) {
        super.withDescription(description);
        return this;
    }

    @Override
    BoundOperation<T, C> buildBound(StateMachineImpl<T> stateMachine) {
        if (operationInstance == null && operationClass == null) {
            throw new TransfluxValidationException(
                "SimpleOperationDef '" + getId() + "' has no operation set; call using(...) before build");
        }

        Operation<T, C> resolved = operationInstance != null
            ? operationInstance
            : instantiateNoArg(operationClass, "Operation");

        return BoundOperation.of(getId(), getName(), getDescription(), resolved);
    }

    @Override
    void checkRefs(Class<?> scopeContext, String scopeLabel, StateMachineDefImpl<T> smDef) {
        // Simple operations have no member references to validate.
    }

    @Override
    void bindScope(StateMachineImpl<T> stateMachine,
                   RegistryImpl<T> rootRegistry,
                   Map<String, Object> canonical,
                   Map<String, BoundCondition<T, ?>> conditionRegistry) {
        // Simple operations have no scope registry.
    }

    @Override
    void flattenScope() {
        // Simple operations have no scope registry to flatten.
    }

    @Override
     Optional<String> scanScopeFor(String id, String excludingId) {
        return Optional.empty();
    }
}
