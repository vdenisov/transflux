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
import org.transflux.core.operation.BranchDef;
import org.transflux.core.operation.Step;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

import static org.transflux.core.Preconditions.requireNotBlank;
import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Implementation of {@link BranchDef} used by {@link ConditionalStepDefImpl}.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
final class BranchDefImpl<T, C> implements BranchDef<T, C> {
    private static final Logger log = LoggerFactory.getLogger(BranchDefImpl.class);

    private final String branchId;
    private ConditionDescriptor descriptor;
    private final List<ActionRef<T, C>> actionRefs = new ArrayList<>();

    BranchDefImpl(String branchId) {
        requireNotBlank(branchId, "Branch ID");
        this.branchId = branchId;
    }

    String getBranchId() {
        return branchId;
    }

    ConditionDescriptor getDescriptor() {
        return descriptor;
    }

    List<ActionRef<T, C>> getActionRefs() {
        return Collections.unmodifiableList(actionRefs);
    }

    @Override
    public BranchDef<T, C> condition(String registeredConditionId) {
        requireNotBlank(registeredConditionId, "Registered condition ID");
        return setDescriptor(ConditionDescriptor.ref(registeredConditionId));
    }

    @Override
    public BranchDef<T, C> condition(Identifiable registeredCondition) {
        requireNotNull(registeredCondition, "Condition identifiable");
        return condition(registeredCondition.getId());
    }

    @Override
    public BranchDef<T, C> conditionExpression(String expression) {
        requireNotBlank(expression, "Expression");
        return setDescriptor(ConditionDescriptor.expression(expression));
    }

    @Override
    public BranchDef<T, C> condition(String id, Condition<T, C> condition) {
        requireNotBlank(id, "Condition ID");
        requireNotNull(condition, "Condition");
        return setDescriptor(ConditionDescriptor.instanceBased(id, condition));
    }

    @Override
    public BranchDef<T, C> condition(String id, Class<? extends Condition<T, C>> conditionClass) {
        requireNotBlank(id, "Condition ID");
        requireNotNull(conditionClass, "Condition class");
        return setDescriptor(ConditionDescriptor.classBased(id, conditionClass));
    }

    @Override
    public BranchDef<T, C> condition(String id, Predicate<T> predicate) {
        requireNotBlank(id, "Condition ID");
        requireNotNull(predicate, "Predicate");
        return setDescriptor(ConditionDescriptor.predicate(id, predicate));
    }

    @Override
    public BranchDef<T, C> condition(String id, String expression) {
        requireNotBlank(id, "Condition ID");
        requireNotBlank(expression, "Expression");
        return setDescriptor(ConditionDescriptor.expression(id, expression));
    }

    @Override
    public BranchDef<T, C> step(String registeredStepId) {
        actionRefs.add(ActionRef.byId(registeredStepId));
        return this;
    }

    @Override
    public BranchDef<T, C> step(Identifiable registeredStep) {
        requireNotNull(registeredStep, "Step identifiable");
        return step(registeredStep.getId());
    }

    @Override
    public BranchDef<T, C> step(String id, Step<T, C> step) {
        actionRefs.add(ActionRef.inline(id, step));
        return this;
    }

    @Override
    public BranchDef<T, C> step(String id, Class<? extends Step<T, C>> stepClass) {
        actionRefs.add(ActionRef.inline(id, stepClass));
        return this;
    }

    private BranchDef<T, C> setDescriptor(ConditionDescriptor incoming) {
        if (this.descriptor != null) {
            log.warn("Condition is already defined for branch '{}'; overriding previous value", branchId);
        }
        this.descriptor = incoming;
        return this;
    }
}
