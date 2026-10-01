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

import org.transflux.core.action.DefaultBranchDef;

import java.util.List;
import java.util.function.Consumer;


/**
 * Implementation of {@link DefaultBranchDef} used by {@link ChoiceDefImpl}.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
final class DefaultBranchDefImpl<T, C> extends ConfigurableDefImpl
    implements DefaultBranchDef<T, C>, ActionSequenceDelegate<T, C, DefaultBranchDef<T, C>> {

    private final ActionSequenceSink<T, C> members =
        new ActionSequenceSink<>(this);

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

    void collectMemberContexts(Class<?> scopeContext, String declaringScope,
                               InlineContextSink sink) {
        members.collectMemberContexts(scopeContext, declaringScope, sink);
    }

    void checkRefs(Class<?> scopeContext, String ownerLabel, String contextOwner,
                   List<String> visibleScopes, StateMachineDefImpl<T> smDef) {
        members.checkRefs(scopeContext, ownerLabel, contextOwner, visibleScopes, smDef);
    }

    void collectInlineRegistrations(InlineRegistrationSink<T, C> sink) {
        members.collectInlineRegistrations(sink);
    }

    void visitDefs(Consumer<ActionDefImpl<?, ?, ?>> visitor) {
        members.visitDefs(visitor);
    }

    @Override
    public ActionSequenceSink<T, C> sequenceSink() {
        return members;
    }

    @Override
    public DefaultBranchDef<T, C> sequenceSelf() {
        return this;
    }

}
