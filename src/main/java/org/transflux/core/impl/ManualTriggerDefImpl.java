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

import org.transflux.core.Identifiable;
import org.transflux.core.condition.Condition;
import org.transflux.core.condition.ConditionDescriptor;
import org.transflux.core.exception.TransfluxValidationException;
import org.transflux.core.trigger.ManualTriggerDef;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

import static org.transflux.core.Preconditions.requireNotBlank;
import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Default {@link ManualTriggerDef} implementation.
 * <p>
 * Accumulates the trigger's pre-condition descriptors during configuration and resolves them into
 * {@link BoundCondition} instances at build time through {@link #buildBoundTrigger(Map)}, producing
 * a runtime {@link ManualTriggerImpl}. The enclosing transition's id is captured at construction so
 * the resulting trigger knows which transition it fires.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
final class ManualTriggerDefImpl<T, C> extends IdentifiedDefImpl<ManualTriggerDefImpl<T, C>>
    implements ManualTriggerDef<T, C> {

    private final String transitionId;
    private final Class<C> contextType;
    private final List<ConditionDescriptor> preConditions = new ArrayList<>();

    ManualTriggerDefImpl(String id, String transitionId, Class<C> contextType) {
        super(id, "manual trigger", "Trigger ID");
        requireNotBlank(transitionId, "Trigger transition ID");
        requireNotNull(contextType, "Trigger context type");
        this.transitionId = transitionId;
        this.contextType = contextType;
    }

    @Override
    public Class<C> contextType() {
        return contextType;
    }

    @Override
    public ManualTriggerDef<T, C> preCondition(String registeredConditionId) {
        requireConfigurerActive("preCondition");
        requireNotBlank(registeredConditionId, "Registered condition ID");
        return append(ConditionDescriptor.ref(registeredConditionId));
    }

    @Override
    public ManualTriggerDef<T, C> preCondition(Identifiable registeredCondition) {
        requireNotNull(registeredCondition, "Condition identifiable");
        return preCondition(registeredCondition.getId());
    }

    @Override
    public ManualTriggerDef<T, C> preConditionExpression(String expression) {
        requireConfigurerActive("preConditionExpression");
        requireNotBlank(expression, "Expression");
        return append(ConditionDescriptor.expression(expression));
    }

    @Override
    public ManualTriggerDef<T, C> preCondition(String id, Condition<T, C> condition) {
        requireConfigurerActive("preCondition");
        requireNotBlank(id, "Condition ID");
        requireNotNull(condition, "Condition");
        return append(ConditionDescriptor.instanceBased(id, condition));
    }

    @Override
    public ManualTriggerDef<T, C> preCondition(Identifiable conditionIdentifiable, Condition<T, C> condition) {
        requireNotNull(conditionIdentifiable, "Condition identifiable");
        return preCondition(conditionIdentifiable.getId(), condition);
    }

    @Override
    public ManualTriggerDef<T, C> preCondition(String id, Class<? extends Condition<T, C>> conditionClass) {
        requireConfigurerActive("preCondition");
        requireNotBlank(id, "Condition ID");
        requireNotNull(conditionClass, "Condition class");
        return append(ConditionDescriptor.classBased(id, conditionClass));
    }

    @Override
    public ManualTriggerDef<T, C> preCondition(Identifiable conditionIdentifiable, Class<? extends Condition<T, C>> conditionClass) {
        requireNotNull(conditionIdentifiable, "Condition identifiable");
        return preCondition(conditionIdentifiable.getId(), conditionClass);
    }

    @Override
    public ManualTriggerDef<T, C> preCondition(String id, BiPredicate<T, C> predicate) {
        requireConfigurerActive("preCondition");
        requireNotBlank(id, "Condition ID");
        requireNotNull(predicate, "Predicate");
        return append(ConditionDescriptor.predicate(id, predicate));
    }

    @Override
    public ManualTriggerDef<T, C> preCondition(Identifiable conditionIdentifiable, BiPredicate<T, C> predicate) {
        requireNotNull(conditionIdentifiable, "Condition identifiable");
        return preCondition(conditionIdentifiable.getId(), predicate);
    }

    @Override
    public ManualTriggerDef<T, C> preCondition(String id, Predicate<T> predicate) {
        requireConfigurerActive("preCondition");
        requireNotBlank(id, "Condition ID");
        requireNotNull(predicate, "Predicate");
        return append(ConditionDescriptor.predicate(id, predicate));
    }

    @Override
    public ManualTriggerDef<T, C> preCondition(Identifiable conditionIdentifiable, Predicate<T> predicate) {
        requireNotNull(conditionIdentifiable, "Condition identifiable");
        return preCondition(conditionIdentifiable.getId(), predicate);
    }

    @Override
    public ManualTriggerDef<T, C> preCondition(String id, String expression) {
        requireConfigurerActive("preCondition");
        requireNotBlank(id, "Condition ID");
        requireNotBlank(expression, "Expression");
        return append(ConditionDescriptor.expression(id, expression));
    }

    @Override
    public ManualTriggerDef<T, C> preCondition(Identifiable conditionIdentifiable, String expression) {
        requireNotNull(conditionIdentifiable, "Condition identifiable");
        return preCondition(conditionIdentifiable.getId(), expression);
    }

    /**
     * Resolves this trigger's pre-condition descriptors into a runtime {@link ManualTriggerImpl}.
     *
     * @param registry the state machine's resolved condition registry, keyed by id
     *
     * @return the runtime trigger
     *
     * @throws TransfluxValidationException if any descriptor cannot be resolved
     */
    ManualTriggerImpl<T, C> buildBoundTrigger(Map<String, BoundCondition<T, C>> registry) {
        requireNotNull(registry, "Condition registry");
        List<BoundCondition<T, C>> bound = new ArrayList<>(preConditions.size());
        for (int i = 0; i < preConditions.size(); i++) {
            String path = "trigger:" + getId() + ":pre[" + i + "]";
            bound.add(ConditionResolver.resolve(preConditions.get(i), registry, path));
        }
        return new ManualTriggerImpl<>(getId(), getName(), getDescription(), transitionId, bound);
    }

    private ManualTriggerDef<T, C> append(ConditionDescriptor descriptor) {
        preConditions.add(descriptor);
        return this;
    }
}
