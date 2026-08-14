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
import org.transflux.core.Identifiable;
import org.transflux.core.condition.Condition;
import org.transflux.core.condition.ConditionDescriptor;
import org.transflux.core.exception.TransfluxValidationException;
import org.transflux.core.trigger.DataTriggerDef;

import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

import static org.transflux.core.Preconditions.requireNotBlank;
import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Default {@link DataTriggerDef} implementation.
 * <p>
 * Captures the trigger's single gate descriptor during configuration and resolves it into a
 * {@link BoundCondition} at build time through {@link #buildBoundTrigger(Map)}, producing a runtime
 * {@link DataTriggerImpl}. The enclosing transition's id is captured at construction so the
 * resulting trigger knows which transition it fires. A gate condition is mandatory; its absence is
 * reported when {@link #buildBoundTrigger(Map)} runs.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
final class DataTriggerDefImpl<T, C> extends TriggerDefImpl<T, C, DataTriggerDefImpl<T, C>>
    implements DataTriggerDef<T, C> {

    private static final Logger log = LoggerFactory.getLogger(DataTriggerDefImpl.class);

    private final ConditionDescriptorSink<T, C, DataTriggerDef<T, C>> gate =
        new ConditionDescriptorSink<>(this, this, "condition", log);

    DataTriggerDefImpl(String id, TransitionDefImpl<T, C> owner) {
        super(id, "data trigger", owner);
    }

    @Override
    public DataTriggerDef<T, C> condition(String registeredConditionId) {
        return gate.ref(registeredConditionId);
    }

    @Override
    public DataTriggerDef<T, C> condition(Identifiable registeredCondition) {
        return gate.ref(registeredCondition);
    }

    @Override
    public DataTriggerDef<T, C> conditionExpression(String expression) {
        return gate.expression(expression);
    }

    @Override
    public DataTriggerDef<T, C> condition(String id, Condition<T, C> condition) {
        return gate.instanceBased(id, condition);
    }

    @Override
    public DataTriggerDef<T, C> condition(Identifiable conditionIdentifiable, Condition<T, C> condition) {
        return gate.instanceBased(conditionIdentifiable, condition);
    }

    @Override
    public DataTriggerDef<T, C> condition(String id, Class<? extends Condition<T, C>> conditionClass) {
        return gate.classBased(id, conditionClass);
    }

    @Override
    public DataTriggerDef<T, C> condition(Identifiable conditionIdentifiable, Class<? extends Condition<T, C>> conditionClass) {
        return gate.classBased(conditionIdentifiable, conditionClass);
    }

    @Override
    public DataTriggerDef<T, C> condition(String id, BiPredicate<T, C> predicate) {
        return gate.predicate(id, predicate);
    }

    @Override
    public DataTriggerDef<T, C> condition(Identifiable conditionIdentifiable, BiPredicate<T, C> predicate) {
        return gate.predicate(conditionIdentifiable, predicate);
    }

    @Override
    public DataTriggerDef<T, C> condition(String id, Predicate<T> predicate) {
        return gate.predicate(id, predicate);
    }

    @Override
    public DataTriggerDef<T, C> condition(Identifiable conditionIdentifiable, Predicate<T> predicate) {
        return gate.predicate(conditionIdentifiable, predicate);
    }

    @Override
    public DataTriggerDef<T, C> condition(String id, String expression) {
        return gate.expression(id, expression);
    }

    @Override
    public DataTriggerDef<T, C> condition(Identifiable conditionIdentifiable, String expression) {
        return gate.expression(conditionIdentifiable, expression);
    }

    /**
     * Returns the declared gate descriptor.
     *
     * @return the gate descriptor, or {@code null} when none was declared
     */
    ConditionDescriptor getGateDescriptor() {
        return gate.descriptor();
    }

    /**
     * Resolves this trigger's gate descriptor into a runtime {@link DataTriggerImpl}.
     *
     * @param registry the state machine's resolved condition registry, keyed by id
     *
     * @return the runtime trigger
     *
     * @throws TransfluxValidationException if no gate condition was declared or the descriptor
     *         cannot be resolved
     */
    DataTriggerImpl<T, C> buildBoundTrigger(Map<String, BoundCondition<T, C>> registry) {
        requireNotNull(registry, "Condition registry");
        ConditionDescriptor descriptor = gate.descriptor();
        if (descriptor == null) {
            throw new TransfluxValidationException(
                "Data trigger '" + getId() + "' declares no condition; call condition(...) in its configurer");
        }
        String path = "trigger:" + getId() + ":gate";
        BoundCondition<T, C> bound = ConditionResolver.resolve(descriptor, registry, path);
        return new DataTriggerImpl<>(getId(), getName(), getDescription(), transitionId(), bound);
    }
}
