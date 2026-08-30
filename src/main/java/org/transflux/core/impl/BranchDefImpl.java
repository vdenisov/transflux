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
import org.transflux.core.action.ContextMapper;
import org.transflux.core.action.BranchDef;
import org.transflux.core.action.Action;
import org.transflux.core.action.StepDef;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.transflux.core.Preconditions.requireNotBlank;

/**
 * Implementation of {@link BranchDef} used by {@link ConditionalOperationDefImpl}.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
final class BranchDefImpl<T, C> extends ConfigurableDefImpl implements BranchDef<T, C> {
    private final String branchId;
    private final ConditionDescriptorSink<T, C, BranchDef<T, C>> branchCondition =
        new ConditionDescriptorSink<>(this, this, "condition", Loggers.BUILD_VALIDATION);
    private final ActionSequenceSink<T, C, BranchDef<T, C>> members =
        new ActionSequenceSink<>(this, this);
    BranchDefImpl(String branchId) {
        requireNotBlank(branchId, "Branch ID");
        this.branchId = branchId;
    }

    @Override
    protected String defLabel() {
        return "branch '" + branchId + "'";
    }

    String getBranchId() {
        return branchId;
    }

    ConditionDescriptor getDescriptor() {
        return branchCondition.descriptor();
    }

    List<ActionSequenceSink.DeclaredMember<T, C>> getMembers() {
        return members.members();
    }

    List<ActionRef<T, C>> getActionRefs() {
        return members.members().stream().map(ActionSequenceSink.DeclaredMember::ref).toList();
    }

    void visitMembers(java.util.function.Consumer<ActionSequenceSink.DeclaredMember<T, C>> visitor) {
        members.visitAllMembers(visitor);
    }

    void checkRefs(Class<?> scopeContext, String ownerLabel, String enclosingOperationId,
                   StateMachineDefImpl<T> smDef) {
        members.checkRefs(scopeContext, ownerLabel, enclosingOperationId, smDef);
    }

    void collectInlineRegistrations(InlineRegistrationSink<T, C> sink) {
        sink.registerInlineCondition(branchCondition.descriptor());
        members.collectInlineRegistrations(sink);
    }

    void collectListenerIds(BiConsumer<String, String> sink) {
        members.collectListenerIds(sink);
    }

    @Override
    public BranchDef<T, C> condition(String registeredConditionId) {
        return branchCondition.ref(registeredConditionId);
    }

    @Override
    public BranchDef<T, C> condition(Identifiable registeredCondition) {
        return branchCondition.ref(registeredCondition);
    }

    @Override
    public BranchDef<T, C> conditionExpression(String expression) {
        return branchCondition.expression(expression);
    }

    @Override
    public BranchDef<T, C> condition(String id, Condition<T, C> condition) {
        return branchCondition.instanceBased(id, condition);
    }

    @Override
    public BranchDef<T, C> condition(Identifiable conditionIdentifiable, Condition<T, C> condition) {
        return branchCondition.instanceBased(conditionIdentifiable, condition);
    }

    @Override
    public BranchDef<T, C> condition(String id, Class<? extends Condition<T, C>> conditionClass) {
        return branchCondition.classBased(id, conditionClass);
    }

    @Override
    public BranchDef<T, C> condition(Identifiable conditionIdentifiable, Class<? extends Condition<T, C>> conditionClass) {
        return branchCondition.classBased(conditionIdentifiable, conditionClass);
    }

    @Override
    public BranchDef<T, C> condition(String id, BiPredicate<T, C> predicate) {
        return branchCondition.predicate(id, predicate);
    }

    @Override
    public BranchDef<T, C> condition(Identifiable conditionIdentifiable, BiPredicate<T, C> predicate) {
        return branchCondition.predicate(conditionIdentifiable, predicate);
    }

    @Override
    public BranchDef<T, C> condition(String id, Predicate<T> predicate) {
        return branchCondition.predicate(id, predicate);
    }

    @Override
    public BranchDef<T, C> condition(Identifiable conditionIdentifiable, Predicate<T> predicate) {
        return branchCondition.predicate(conditionIdentifiable, predicate);
    }

    @Override
    public BranchDef<T, C> condition(String id, String expression) {
        return branchCondition.expression(id, expression);
    }

    @Override
    public BranchDef<T, C> condition(Identifiable conditionIdentifiable, String expression) {
        return branchCondition.expression(conditionIdentifiable, expression);
    }

    @Override
    public BranchDef<T, C> run(String id) {
        return members.run(id);
    }

    @Override
    public BranchDef<T, C> run(Identifiable registeredAction) {
        return members.run(registeredAction);
    }

    @Override
    public BranchDef<T, C> run(String id, String mapperId) {
        return members.run(id, mapperId);
    }

    @Override
    public BranchDef<T, C> run(String id, ContextMapper<C, ?> inlineMapper) {
        return members.run(id, inlineMapper);
    }

    @Override
    public BranchDef<T, C> run(Identifiable registeredAction, Identifiable mapper) {
        return members.run(registeredAction, mapper);
    }

    @Override
    public BranchDef<T, C> run(Identifiable registeredAction, String mapperId) {
        return members.run(registeredAction, mapperId);
    }

    @Override
    public BranchDef<T, C> run(String id, Identifiable mapper) {
        return members.run(id, mapper);
    }

    @Override
    public BranchDef<T, C> step(String id, Action<T, C> step) {
        return members.step(id, step);
    }

    @Override
    public BranchDef<T, C> step(Identifiable stepIdentifiable, Action<T, C> step) {
        return members.step(stepIdentifiable, step);
    }

    @Override
    public BranchDef<T, C> step(String id, Class<? extends Action<T, C>> stepClass) {
        return members.step(id, stepClass);
    }

    @Override
    public BranchDef<T, C> step(Identifiable stepIdentifiable, Class<? extends Action<T, C>> stepClass) {
        return members.step(stepIdentifiable, stepClass);
    }

    @Override
    public BranchDef<T, C> step(String id, Consumer<StepDef<T, C>> configurer) {
        return members.step(id, configurer);
    }

    @Override
    public BranchDef<T, C> step(Identifiable stepIdentifiable, Consumer<StepDef<T, C>> configurer) {
        return members.step(stepIdentifiable, configurer);
    }
}
