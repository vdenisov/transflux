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

import org.transflux.core.condition.Condition;
import org.transflux.core.condition.ConditionDescriptor;
import org.transflux.core.action.ConditionalOperationDef;
import org.transflux.core.action.OperationDef;
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

    void visitMembers(Consumer<ActionSequenceSink.DeclaredMember<T, C>> visitor) {
        members.visitAllMembers(visitor);
    }

    void collectMemberContexts(Class<?> scopeContext, String declaringScope,
                               InlineContextSink sink) {
        members.collectMemberContexts(scopeContext, declaringScope, sink);
    }

    void checkRefs(Class<?> scopeContext, String ownerLabel, String contextOwner,
                   List<String> visibleScopes, StateMachineDefImpl<T> smDef) {
        // The gate runs against the branch's context, which is the conditional's - and a
        // conditional may have declared one of its own, so this is a real boundary.
        smDef.checkConditionRef(branchCondition.descriptor(), scopeContext, ownerLabel,
                                "branch condition");
        members.checkRefs(scopeContext, ownerLabel, contextOwner, visibleScopes, smDef);
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
    public BranchDef<T, C> conditionExpression(String expression) {
        return branchCondition.expression(expression);
    }

    @Override
    public BranchDef<T, C> condition(String id, Condition<T, C> condition) {
        return branchCondition.instanceBased(id, condition);
    }

    @Override
    public BranchDef<T, C> condition(String id, BiPredicate<T, C> predicate) {
        return branchCondition.predicate(id, predicate);
    }

    @Override
    public BranchDef<T, C> condition(String id, Predicate<T> predicate) {
        return branchCondition.predicate(id, predicate);
    }

    @Override
    public BranchDef<T, C> condition(String id, String expression) {
        return branchCondition.expression(id, expression);
    }

    @Override
    public BranchDef<T, C> run(String id) {
        return members.run(id);
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
    public BranchDef<T, C> fork(String id) {
        return members.fork(id);
    }

    @Override
    public BranchDef<T, C> fork(String id, String mapperId) {
        return members.fork(id, mapperId);
    }

    @Override
    public BranchDef<T, C> fork(String id, ContextMapper<C, ?> inlineMapper) {
        return members.fork(id, inlineMapper);
    }

    @Override
    public BranchDef<T, C> conditional(String id, Consumer<ConditionalOperationDef<T, C>> configurer) {
        return members.conditional(id, configurer, false);
    }

    @Override
    public BranchDef<T, C> operation(String id, Consumer<OperationDef<T, C>> configurer) {
        return members.operation(id, configurer, false);
    }

    @Override
    public BranchDef<T, C> step(String id, Action<T, C> step) {
        return members.step(id, step, false);
    }

    @Override
    public BranchDef<T, C> step(String id, Consumer<StepDef<T, C>> configurer) {
        return members.step(id, configurer, false);
    }

    @Override
    public BranchDef<T, C> forkStep(String id, Action<T, C> action) {
        return members.step(id, action, true);
    }

    @Override
    public BranchDef<T, C> forkStep(String id, Consumer<StepDef<T, C>> configurer) {
        return members.step(id, configurer, true);
    }

    @Override
    public BranchDef<T, C> forkConditional(String id,
                                           Consumer<ConditionalOperationDef<T, C>> configurer) {
        return members.conditional(id, configurer, true);
    }

    @Override
    public BranchDef<T, C> forkOperation(String id, Consumer<OperationDef<T, C>> configurer) {
        return members.operation(id, configurer, true);
    }

    @Override
    public <N> BranchDef<T, C> step(String id, Class<N> contextType, Action<T, N> action) {
        return members.step(id, contextType, MapperRef.passThrough(), action, false);
    }

    @Override
    public <N> BranchDef<T, C> step(String id, Class<N> contextType, ContextMapper<C, N> mapper,
                          Action<T, N> action) {
        return members.step(id, contextType, MapperRef.inline(mapper), action, false);
    }

    @Override
    public <N> BranchDef<T, C> step(String id, Class<N> contextType, Consumer<StepDef<T, N>> configurer) {
        return members.step(id, contextType, MapperRef.passThrough(), configurer, false);
    }

    @Override
    public <N> BranchDef<T, C> step(String id, Class<N> contextType, ContextMapper<C, N> mapper,
                          Consumer<StepDef<T, N>> configurer) {
        return members.step(id, contextType, MapperRef.inline(mapper), configurer, false);
    }

    @Override
    public <N> BranchDef<T, C> conditional(String id, Class<N> contextType,
                                 Consumer<ConditionalOperationDef<T, N>> configurer) {
        return members.conditional(id, contextType, MapperRef.passThrough(), configurer, false);
    }

    @Override
    public <N> BranchDef<T, C> conditional(String id, Class<N> contextType, ContextMapper<C, N> mapper,
                                 Consumer<ConditionalOperationDef<T, N>> configurer) {
        return members.conditional(id, contextType, MapperRef.inline(mapper), configurer, false);
    }

    @Override
    public <N> BranchDef<T, C> operation(String id, Class<N> contextType,
                               Consumer<OperationDef<T, N>> configurer) {
        return members.operation(id, contextType, MapperRef.passThrough(), configurer, false);
    }

    @Override
    public <N> BranchDef<T, C> operation(String id, Class<N> contextType, ContextMapper<C, N> mapper,
                               Consumer<OperationDef<T, N>> configurer) {
        return members.operation(id, contextType, MapperRef.inline(mapper), configurer, false);
    }

    @Override
    public <N> BranchDef<T, C> forkStep(String id, Class<N> contextType, Action<T, N> action) {
        return members.step(id, contextType, MapperRef.passThrough(), action, true);
    }

    @Override
    public <N> BranchDef<T, C> forkStep(String id, Class<N> contextType,
                                        ContextMapper<C, N> mapper, Action<T, N> action) {
        return members.step(id, contextType, MapperRef.inline(mapper), action, true);
    }

    @Override
    public <N> BranchDef<T, C> forkStep(String id, Class<N> contextType,
                                        Consumer<StepDef<T, N>> configurer) {
        return members.step(id, contextType, MapperRef.passThrough(), configurer, true);
    }

    @Override
    public <N> BranchDef<T, C> forkStep(String id, Class<N> contextType,
                                        ContextMapper<C, N> mapper,
                                        Consumer<StepDef<T, N>> configurer) {
        return members.step(id, contextType, MapperRef.inline(mapper), configurer, true);
    }

    @Override
    public <N> BranchDef<T, C> forkConditional(String id, Class<N> contextType,
                                               Consumer<ConditionalOperationDef<T, N>> configurer) {
        return members.conditional(id, contextType, MapperRef.passThrough(), configurer, true);
    }

    @Override
    public <N> BranchDef<T, C> forkConditional(String id, Class<N> contextType,
                                               ContextMapper<C, N> mapper,
                                               Consumer<ConditionalOperationDef<T, N>> configurer) {
        return members.conditional(id, contextType, MapperRef.inline(mapper), configurer, true);
    }

    @Override
    public <N> BranchDef<T, C> forkOperation(String id, Class<N> contextType,
                                             Consumer<OperationDef<T, N>> configurer) {
        return members.operation(id, contextType, MapperRef.passThrough(), configurer, true);
    }

    @Override
    public <N> BranchDef<T, C> forkOperation(String id, Class<N> contextType,
                                             ContextMapper<C, N> mapper,
                                             Consumer<OperationDef<T, N>> configurer) {
        return members.operation(id, contextType, MapperRef.inline(mapper), configurer, true);
    }
}
