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
import org.transflux.core.condition.Condition;
import org.transflux.core.action.ConditionalOperationDef;
import org.transflux.core.action.OperationDef;
import org.transflux.core.action.Action;
import org.transflux.core.action.StepDef;

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
    public ContextScope<T, C> step(String id, Action<T, C> step) {
        requireConfigurerActive("step");
        requireNotBlank(id, "Step ID");
        requireNotNull(step, "Step");
        smd.registerScopedStep(id, step, contextType);
        return this;
    }

    @Override
    public ContextScope<T, C> step(String id, Consumer<StepDef<T, C>> configurer) {
        requireConfigurerActive("step");
        requireNotBlank(id, "Step ID");
        requireNotNull(configurer, "Step configurer");
        smd.registerScopedStep(id, configurer, contextType);
        return this;
    }

    @Override
    public ContextScope<T, C> condition(String id, Condition<T, C> condition) {
        requireConfigurerActive("condition");
        requireNotBlank(id, "Condition ID");
        requireNotNull(condition, "Condition");
        smd.registerScopedCondition(id, condition, contextType);
        return this;
    }

    @Override
    public ContextScope<T, C> condition(String id, Class<? extends Condition<T, C>> conditionClass) {
        requireConfigurerActive("condition");
        requireNotBlank(id, "Condition ID");
        requireNotNull(conditionClass, "Condition class");
        smd.registerScopedCondition(id, conditionClass, contextType);
        return this;
    }

    @Override
    public ContextScope<T, C> condition(String id, BiPredicate<T, C> predicate) {
        requireConfigurerActive("condition");
        requireNotBlank(id, "Condition ID");
        requireNotNull(predicate, "Predicate");
        smd.registerScopedCondition(id, predicate, contextType);
        return this;
    }

    @Override
    public ContextScope<T, C> condition(String id, Predicate<T> predicate) {
        requireConfigurerActive("condition");
        requireNotBlank(id, "Condition ID");
        requireNotNull(predicate, "Predicate");
        BiPredicate<T, C> adapted = (entity, ctx) -> predicate.test(entity);
        smd.registerScopedCondition(id, adapted, contextType);
        return this;
    }

    @Override
    public ContextScope<T, C> condition(String id, String spelExpression) {
        requireConfigurerActive("condition");
        requireNotBlank(id, "Condition ID");
        requireNotBlank(spelExpression, "SpEL expression");
        smd.registerScopedCondition(id, spelExpression, contextType);
        return this;
    }

    @Override
    public ContextScope<T, C> operation(String id, Consumer<OperationDef<T, C>> configurer) {
        requireConfigurerActive("operation");
        requireNotBlank(id, "Composite operation ID");
        requireNotNull(configurer, "Composite operation configurer");
        smd.registerScopedCompositeOperation(id, configurer, contextType);
        return this;
    }

    @Override
    public ContextScope<T, C> conditional(String id, Consumer<ConditionalOperationDef<T, C>> configurer) {
        requireConfigurerActive("conditional");
        requireNotBlank(id, "Conditional operation ID");
        requireNotNull(configurer, "Conditional operation configurer");
        smd.registerScopedConditional(id, configurer, contextType);
        return this;
    }


}
