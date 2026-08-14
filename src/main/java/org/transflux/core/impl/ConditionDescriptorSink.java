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
import org.transflux.core.Identifiable;
import org.transflux.core.condition.Condition;
import org.transflux.core.condition.ConditionDescriptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

import static org.transflux.core.Preconditions.requireNotBlank;
import static org.transflux.core.Preconditions.requireNotNull;
import static org.transflux.core.impl.ValidationUtils.warnIfSet;

/**
 * Shared implementation and storage for the condition-descriptor overload family that every
 * condition-bearing def exposes — transition pre- and post-conditions, manual-trigger
 * pre-conditions, data-trigger gates, and conditional branches.
 * <p>
 * The family is thirteen overloads wide: seven {@code (String id, ...)} primaries covering the
 * authoring forms of {@link ConditionDescriptor}, plus six {@link Identifiable} siblings that
 * delegate through {@link Identifiable#getId()}. Each owning def declares one sink per condition
 * slot and implements its public methods as one-line delegates, so validation order, argument
 * labels, and the configurer guard are written once.
 * <p>
 * A sink also owns the descriptors it collects. A multi-descriptor slot appends in declaration
 * order; a single-descriptor slot keeps the last write and warns on replacement.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 * @param <D> the def interface returned by every overload, for fluent chaining
 */
final class ConditionDescriptorSink<T, C, D> {

    private final ConfigurableDefImpl owner;
    private final D self;
    private final String dslMethod;
    private final Logger log;

    private final List<ConditionDescriptor> descriptors = new ArrayList<>();

    /**
     * Creates a multi-descriptor sink that appends in declaration order.
     *
     * @param owner the def whose configurer guard gates every mutation
     * @param self the value returned by every overload
     * @param dslMethod the DSL method name, surfaced by the configurer guard
     */
    ConditionDescriptorSink(ConfigurableDefImpl owner, D self, String dslMethod) {
        this(owner, self, dslMethod, null);
    }

    /**
     * Creates a sink for a slot that holds a single descriptor, keeping the last write and warning
     * on replacement. A {@code null} logger selects multi-descriptor semantics instead.
     *
     * @param owner the def whose configurer guard gates every mutation
     * @param self the value returned by every overload
     * @param dslMethod the DSL method name, surfaced by the configurer guard
     * @param log the logger the override warning is emitted on
     */
    ConditionDescriptorSink(ConfigurableDefImpl owner, D self, String dslMethod, Logger log) {
        this.owner = owner;
        this.self = self;
        this.dslMethod = dslMethod;
        this.log = log;
    }

    D ref(String registeredConditionId) {
        owner.requireConfigurerActive(dslMethod);
        requireNotBlank(registeredConditionId, "Registered condition ID");
        return store(ConditionDescriptor.ref(registeredConditionId));
    }

    D ref(Identifiable registeredCondition) {
        requireNotNull(registeredCondition, "Condition identifiable");
        return ref(registeredCondition.getId());
    }

    /**
     * Records an id-less expression descriptor, whose id is derived from the expression and its
     * descriptor path. Guards under {@code dslMethod + "Expression"}, matching the naming rule the
     * DSL follows at every family.
     *
     * @param expression the SpEL expression
     *
     * @return the owning def, for chaining
     */
    D expression(String expression) {
        owner.requireConfigurerActive(dslMethod + "Expression");
        requireNotBlank(expression, "Expression");
        return store(ConditionDescriptor.expression(expression));
    }

    D instanceBased(String id, Condition<T, C> condition) {
        owner.requireConfigurerActive(dslMethod);
        requireNotBlank(id, "Condition ID");
        requireNotNull(condition, "Condition");
        return store(ConditionDescriptor.instanceBased(id, condition));
    }

    D instanceBased(Identifiable conditionIdentifiable, Condition<T, C> condition) {
        requireNotNull(conditionIdentifiable, "Condition identifiable");
        return instanceBased(conditionIdentifiable.getId(), condition);
    }

    D classBased(String id, Class<? extends Condition<T, C>> conditionClass) {
        owner.requireConfigurerActive(dslMethod);
        requireNotBlank(id, "Condition ID");
        requireNotNull(conditionClass, "Condition class");
        return store(ConditionDescriptor.classBased(id, conditionClass));
    }

    D classBased(Identifiable conditionIdentifiable, Class<? extends Condition<T, C>> conditionClass) {
        requireNotNull(conditionIdentifiable, "Condition identifiable");
        return classBased(conditionIdentifiable.getId(), conditionClass);
    }

    D predicate(String id, BiPredicate<T, C> predicate) {
        owner.requireConfigurerActive(dslMethod);
        requireNotBlank(id, "Condition ID");
        requireNotNull(predicate, "Predicate");
        return store(ConditionDescriptor.predicate(id, predicate));
    }

    D predicate(Identifiable conditionIdentifiable, BiPredicate<T, C> predicate) {
        requireNotNull(conditionIdentifiable, "Condition identifiable");
        return predicate(conditionIdentifiable.getId(), predicate);
    }

    D predicate(String id, Predicate<T> predicate) {
        owner.requireConfigurerActive(dslMethod);
        requireNotBlank(id, "Condition ID");
        requireNotNull(predicate, "Predicate");
        return store(ConditionDescriptor.predicate(id, predicate));
    }

    D predicate(Identifiable conditionIdentifiable, Predicate<T> predicate) {
        requireNotNull(conditionIdentifiable, "Condition identifiable");
        return predicate(conditionIdentifiable.getId(), predicate);
    }

    D expression(String id, String expression) {
        owner.requireConfigurerActive(dslMethod);
        requireNotBlank(id, "Condition ID");
        requireNotBlank(expression, "Expression");
        return store(ConditionDescriptor.expression(id, expression));
    }

    D expression(Identifiable conditionIdentifiable, String expression) {
        requireNotNull(conditionIdentifiable, "Condition identifiable");
        return expression(conditionIdentifiable.getId(), expression);
    }

    /**
     * Returns the collected descriptors in declaration order.
     *
     * @return an unmodifiable view of the collected descriptors
     */
    List<ConditionDescriptor> descriptors() {
        return Collections.unmodifiableList(descriptors);
    }

    /**
     * Returns the descriptor held by a single-descriptor slot.
     *
     * @return the collected descriptor, or {@code null} when none was declared
     */
    ConditionDescriptor descriptor() {
        return descriptors.isEmpty() ? null : descriptors.get(0);
    }

    private D store(ConditionDescriptor descriptor) {
        if (log != null) {
            warnIfSet(descriptor(), descriptor, "Condition on " + owner.defLabel(), log);
            descriptors.clear();
        }
        descriptors.add(descriptor);
        return self;
    }
}
