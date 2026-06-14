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

import org.transflux.core.ContextScope;
import org.transflux.core.Identifiable;
import org.transflux.core.condition.Condition;
import org.transflux.core.operation.CompositeOperationDef;
import org.transflux.core.operation.Operation;
import org.transflux.core.operation.Step;

import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.transflux.core.Preconditions.requireNotBlank;
import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Default {@link ContextScope} implementation. Forwards registrations to the enclosing
 * {@link StateMachineDefImpl}, tagging each registered id with this scope's context class so
 * the build pipeline can verify context compatibility for by-id references.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the context class this scope binds to
 */
final class ContextScopeImpl<T, C> extends ConfigurableDefImpl implements ContextScope<T, C> {

    private final StateMachineDefImpl<T> smd;
    private final Class<C> contextType;

    ContextScopeImpl(StateMachineDefImpl<T> smd, Class<C> contextType) {
        this.smd = smd;
        this.contextType = contextType;
    }

    @Override
    protected String defLabel() {
        return "forContext scope for " + contextType.getSimpleName();
    }

    @Override
    public ContextScope<T, C> step(String id, Step<T, C> step) {
        requireNotBlank(id, "Step ID");
        requireNotNull(step, "Step");
        smd.registerScopedStep(id, step, contextType);
        return this;
    }

    @Override
    public ContextScope<T, C> step(String id, Class<? extends Step<T, C>> stepClass) {
        requireNotBlank(id, "Step ID");
        requireNotNull(stepClass, "Step class");
        smd.registerScopedStep(id, stepClass, contextType);
        return this;
    }

    @Override
    public ContextScope<T, C> condition(String id, Condition<T, C> condition) {
        requireNotBlank(id, "Condition ID");
        requireNotNull(condition, "Condition");
        smd.registerScopedCondition(id, condition, contextType);
        return this;
    }

    @Override
    public ContextScope<T, C> condition(String id, Class<? extends Condition<T, C>> conditionClass) {
        requireNotBlank(id, "Condition ID");
        requireNotNull(conditionClass, "Condition class");
        smd.registerScopedCondition(id, conditionClass, contextType);
        return this;
    }

    @Override
    public ContextScope<T, C> condition(String id, BiPredicate<T, C> predicate) {
        requireNotBlank(id, "Condition ID");
        requireNotNull(predicate, "Predicate");
        smd.registerScopedCondition(id, predicate, contextType);
        return this;
    }

    @Override
    public ContextScope<T, C> condition(String id, Predicate<T> predicate) {
        requireNotBlank(id, "Condition ID");
        requireNotNull(predicate, "Predicate");
        BiPredicate<T, C> adapted = (entity, ctx) -> predicate.test(entity);
        smd.registerScopedCondition(id, adapted, contextType);
        return this;
    }

    @Override
    public ContextScope<T, C> condition(String id, String spelExpression) {
        requireNotBlank(id, "Condition ID");
        requireNotBlank(spelExpression, "SpEL expression");
        smd.registerScopedCondition(id, spelExpression, contextType);
        return this;
    }

    @Override
    public ContextScope<T, C> compositeOperation(String id, Consumer<CompositeOperationDef<T, C>> configurer) {
        requireNotBlank(id, "Composite operation ID");
        requireNotNull(configurer, "Composite operation configurer");
        smd.registerScopedCompositeOperation(id, configurer, contextType);
        return this;
    }

    @Override
    public ContextScope<T, C> operation(String id, Operation<T, C> operation) {
        requireNotBlank(id, "Operation ID");
        requireNotNull(operation, "Operation");
        smd.registerScopedOperation(id, operation, contextType);
        return this;
    }

    @Override
    public ContextScope<T, C> operation(String id, Class<? extends Operation<T, C>> operationClass) {
        requireNotBlank(id, "Operation ID");
        requireNotNull(operationClass, "Operation class");
        smd.registerScopedOperation(id, operationClass, contextType);
        return this;
    }

    @Override
    public ContextScope<T, C> step(Identifiable stepIdentifiable, Step<T, C> step) {
        requireNotNull(stepIdentifiable, "Step identifiable");
        return step(stepIdentifiable.getId(), step);
    }

    @Override
    public ContextScope<T, C> step(Identifiable stepIdentifiable, Class<? extends Step<T, C>> stepClass) {
        requireNotNull(stepIdentifiable, "Step identifiable");
        return step(stepIdentifiable.getId(), stepClass);
    }

    @Override
    public ContextScope<T, C> condition(Identifiable conditionIdentifiable, Condition<T, C> condition) {
        requireNotNull(conditionIdentifiable, "Condition identifiable");
        return condition(conditionIdentifiable.getId(), condition);
    }

    @Override
    public ContextScope<T, C> condition(Identifiable conditionIdentifiable, Class<? extends Condition<T, C>> conditionClass) {
        requireNotNull(conditionIdentifiable, "Condition identifiable");
        return condition(conditionIdentifiable.getId(), conditionClass);
    }

    @Override
    public ContextScope<T, C> condition(Identifiable conditionIdentifiable, BiPredicate<T, C> predicate) {
        requireNotNull(conditionIdentifiable, "Condition identifiable");
        return condition(conditionIdentifiable.getId(), predicate);
    }

    @Override
    public ContextScope<T, C> condition(Identifiable conditionIdentifiable, Predicate<T> predicate) {
        requireNotNull(conditionIdentifiable, "Condition identifiable");
        return condition(conditionIdentifiable.getId(), predicate);
    }

    @Override
    public ContextScope<T, C> condition(Identifiable conditionIdentifiable, String spelExpression) {
        requireNotNull(conditionIdentifiable, "Condition identifiable");
        return condition(conditionIdentifiable.getId(), spelExpression);
    }

    @Override
    public ContextScope<T, C> compositeOperation(Identifiable operationIdentifiable, Consumer<CompositeOperationDef<T, C>> configurer) {
        requireNotNull(operationIdentifiable, "Operation identifiable");
        return compositeOperation(operationIdentifiable.getId(), configurer);
    }

    @Override
    public ContextScope<T, C> operation(Identifiable operationIdentifiable, Operation<T, C> operation) {
        requireNotNull(operationIdentifiable, "Operation identifiable");
        return operation(operationIdentifiable.getId(), operation);
    }

    @Override
    public ContextScope<T, C> operation(Identifiable operationIdentifiable, Class<? extends Operation<T, C>> operationClass) {
        requireNotNull(operationIdentifiable, "Operation identifiable");
        return operation(operationIdentifiable.getId(), operationClass);
    }
}
