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

import org.transflux.core.action.BranchDef;
import org.transflux.core.condition.Condition;
import org.transflux.core.condition.ConditionDescriptor;

import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.transflux.core.Preconditions.requireNotBlank;

/**
 * Implementation of {@link BranchDef} used by {@link ChoiceDefImpl}.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
final class BranchDefImpl<T, C> extends ConfigurableDefImpl
    implements BranchDef<T, C>, ActionSequenceDelegate<T, C, BranchDef<T, C>> {
    private final String branchId;
    private final ConditionDescriptorSink<T, C, BranchDef<T, C>> branchCondition =
        new ConditionDescriptorSink<>(this, this, "condition", Loggers.BUILD_VALIDATION);
    private final ActionSequenceSink<T, C> members =
        new ActionSequenceSink<>(this);

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

    void visitMembers(Consumer<ActionSequenceSink.DeclaredMember<T, C>> visitor) {
        members.visitAllMembers(visitor);
    }

    void collectMemberContexts(Class<?> scopeContext, String declaringScope,
                               InlineContextSink sink) {
        members.collectMemberContexts(scopeContext, declaringScope, sink);
    }

    void checkRefs(Class<?> scopeContext, String ownerLabel, String contextOwner,
                   List<String> visibleScopes, StateMachineDefImpl<T> smDef) {
        // The gate runs against the branch's context, which is the choice's - and a
        // choice may have declared one of its own, so this is a real boundary.
        smDef.checkConditionRef(branchCondition.descriptor(), scopeContext, ownerLabel,
                                "branch condition");
        members.checkRefs(scopeContext, ownerLabel, contextOwner, visibleScopes, smDef);
    }

    void collectInlineRegistrations(InlineRegistrationSink<T, C> sink) {
        sink.registerInlineCondition(branchCondition.descriptor());
        members.collectInlineRegistrations(sink);
    }

    void visitDefs(Consumer<ActionDefImpl<?, ?, ?>> visitor) {
        members.visitDefs(visitor);
    }

    @Override
    public BranchDef<T, C> condition(String registeredConditionId) {
        return branchCondition.ref(registeredConditionId);
    }

    @Override
    public BranchDef<T, C> conditionExpression(String expression) {
        return branchCondition.expression(expression);
    }

    @Override
    public BranchDef<T, C> condition(String id, Condition<? super T, C> condition) {
        return branchCondition.instanceBased(id, condition);
    }

    @Override
    public BranchDef<T, C> condition(String id, BiPredicate<? super T, C> predicate) {
        return branchCondition.predicate(id, predicate);
    }

    @Override
    public BranchDef<T, C> condition(String id, Predicate<? super T> predicate) {
        return branchCondition.predicate(id, predicate);
    }

    @Override
    public BranchDef<T, C> condition(String id, String expression) {
        return branchCondition.expression(id, expression);
    }

    @Override
    public ActionSequenceSink<T, C> sequenceSink() {
        return members;
    }

    @Override
    public BranchDef<T, C> sequenceSelf() {
        return this;
    }

}
