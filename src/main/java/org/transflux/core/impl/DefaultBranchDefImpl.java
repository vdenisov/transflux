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

import org.transflux.core.action.ConditionalOperationDef;
import org.transflux.core.action.OperationDef;
import org.transflux.core.action.ContextMapper;
import org.transflux.core.action.DefaultBranchDef;
import org.transflux.core.action.Action;
import org.transflux.core.action.StepDef;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;


/**
 * Implementation of {@link DefaultBranchDef} used by {@link ConditionalOperationDefImpl}.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
final class DefaultBranchDefImpl<T, C> extends ConfigurableDefImpl implements DefaultBranchDef<T, C> {

    private final ActionSequenceSink<T, C, DefaultBranchDef<T, C>> members =
        new ActionSequenceSink<>(this, this);
    DefaultBranchDefImpl() {
    }

    @Override
    protected String defLabel() {
        return "default branch";
    }

    List<ActionSequenceSink.DeclaredMember<T, C>> getMembers() {
        return members.members();
    }

    void visitMembers(Consumer<ActionSequenceSink.DeclaredMember<T, C>> visitor) {
        members.visitAllMembers(visitor);
    }

    void checkRefs(Class<?> scopeContext, String ownerLabel, String enclosingOperationId,
                   StateMachineDefImpl<T> smDef) {
        members.checkRefs(scopeContext, ownerLabel, enclosingOperationId, smDef);
    }

    void collectInlineRegistrations(InlineRegistrationSink<T, C> sink) {
        members.collectInlineRegistrations(sink);
    }

    void collectListenerIds(BiConsumer<String, String> sink) {
        members.collectListenerIds(sink);
    }

    @Override
    public DefaultBranchDef<T, C> run(String id) {
        return members.run(id);
    }

    @Override
    public DefaultBranchDef<T, C> run(String id, String mapperId) {
        return members.run(id, mapperId);
    }

    @Override
    public DefaultBranchDef<T, C> run(String id, ContextMapper<C, ?> inlineMapper) {
        return members.run(id, inlineMapper);
    }

    @Override
    public DefaultBranchDef<T, C> fork(String id) {
        return members.fork(id);
    }

    @Override
    public DefaultBranchDef<T, C> fork(String id, String mapperId) {
        return members.fork(id, mapperId);
    }

    @Override
    public DefaultBranchDef<T, C> fork(String id, ContextMapper<C, ?> inlineMapper) {
        return members.fork(id, inlineMapper);
    }

    @Override
    public DefaultBranchDef<T, C> conditional(String id, Consumer<ConditionalOperationDef<T, C>> configurer) {
        return members.conditional(id, configurer);
    }

    @Override
    public DefaultBranchDef<T, C> operation(String id, Consumer<OperationDef<T, C>> configurer) {
        return members.operation(id, configurer);
    }

    @Override
    public DefaultBranchDef<T, C> step(String id, Action<T, C> step) {
        return members.step(id, step);
    }

    @Override
    public DefaultBranchDef<T, C> step(String id, Class<? extends Action<T, C>> stepClass) {
        return members.step(id, stepClass);
    }

    @Override
    public DefaultBranchDef<T, C> step(String id, Consumer<StepDef<T, C>> configurer) {
        return members.step(id, configurer);
    }

}
