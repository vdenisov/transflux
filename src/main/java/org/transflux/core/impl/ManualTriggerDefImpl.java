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
final class ManualTriggerDefImpl<T, C> extends TriggerDefImpl<T, C, ManualTriggerDefImpl<T, C>>
    implements ManualTriggerDef<T, C> {

    private final ConditionDescriptorSink<T, C, ManualTriggerDef<T, C>> preConditions =
        new ConditionDescriptorSink<>(this, this, "preCondition");

    ManualTriggerDefImpl(String id, TransitionDefImpl<T, C> owner) {
        super(id, "manual trigger", owner);
    }

    @Override
    public ManualTriggerDef<T, C> preCondition(String registeredConditionId) {
        return preConditions.ref(registeredConditionId);
    }

    @Override
    public ManualTriggerDef<T, C> preCondition(Identifiable registeredCondition) {
        return preConditions.ref(registeredCondition);
    }

    @Override
    public ManualTriggerDef<T, C> preConditionExpression(String expression) {
        return preConditions.expression(expression);
    }

    @Override
    public ManualTriggerDef<T, C> preCondition(String id, Condition<T, C> condition) {
        return preConditions.instanceBased(id, condition);
    }

    @Override
    public ManualTriggerDef<T, C> preCondition(Identifiable conditionIdentifiable, Condition<T, C> condition) {
        return preConditions.instanceBased(conditionIdentifiable, condition);
    }

    @Override
    public ManualTriggerDef<T, C> preCondition(String id, Class<? extends Condition<T, C>> conditionClass) {
        return preConditions.classBased(id, conditionClass);
    }

    @Override
    public ManualTriggerDef<T, C> preCondition(Identifiable conditionIdentifiable, Class<? extends Condition<T, C>> conditionClass) {
        return preConditions.classBased(conditionIdentifiable, conditionClass);
    }

    @Override
    public ManualTriggerDef<T, C> preCondition(String id, BiPredicate<T, C> predicate) {
        return preConditions.predicate(id, predicate);
    }

    @Override
    public ManualTriggerDef<T, C> preCondition(Identifiable conditionIdentifiable, BiPredicate<T, C> predicate) {
        return preConditions.predicate(conditionIdentifiable, predicate);
    }

    @Override
    public ManualTriggerDef<T, C> preCondition(String id, Predicate<T> predicate) {
        return preConditions.predicate(id, predicate);
    }

    @Override
    public ManualTriggerDef<T, C> preCondition(Identifiable conditionIdentifiable, Predicate<T> predicate) {
        return preConditions.predicate(conditionIdentifiable, predicate);
    }

    @Override
    public ManualTriggerDef<T, C> preCondition(String id, String expression) {
        return preConditions.expression(id, expression);
    }

    @Override
    public ManualTriggerDef<T, C> preCondition(Identifiable conditionIdentifiable, String expression) {
        return preConditions.expression(conditionIdentifiable, expression);
    }

    /**
     * Returns the declared pre-condition descriptors in declaration order.
     *
     * @return an unmodifiable view of the pre-condition descriptors
     */
    List<ConditionDescriptor> getPreConditionDescriptors() {
        return preConditions.descriptors();
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
        List<ConditionDescriptor> descriptors = preConditions.descriptors();
        List<BoundCondition<T, C>> bound = new ArrayList<>(descriptors.size());
        for (int i = 0; i < descriptors.size(); i++) {
            String path = "trigger:" + getId() + ":pre[" + i + "]";
            bound.add(ConditionResolver.resolve(descriptors.get(i), registry, path));
        }
        return new ManualTriggerImpl<>(getId(), getName(), getDescription(), transitionId(), bound);
    }
}
