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

import org.transflux.core.action.Action;
import org.transflux.core.action.ActionSequence;
import org.transflux.core.action.AsyncRejectionPolicy;
import org.transflux.core.action.ChoiceDef;
import org.transflux.core.action.ContextMapper;
import org.transflux.core.action.OperationDef;
import org.transflux.core.action.StepDef;

import java.util.function.Consumer;

/**
 * The member grammar every position holding an ordered member list shares, written once: each
 * verb records its member on the position's {@link ActionSequenceSink} and returns the position,
 * so the fork flag and the call-site mapper of a form cannot differ between positions.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the context type the members run against
 * @param <SELF> the position's public def type, which every verb returns
 */
interface ActionSequenceDelegate<T, C, SELF extends ActionSequence<T, C, SELF>>
    extends ActionSequence<T, C, SELF> {

    /**
     * Returns the sink this position's members are recorded on.
     *
     * @return the sink
     */
    ActionSequenceSink<T, C> sequenceSink();

    /**
     * Returns the position itself, for the verbs to return.
     *
     * @return this position
     */
    SELF sequenceSelf();

    @Override
    default SELF run(String id) {
        sequenceSink().run(id);
        return sequenceSelf();
    }

    @Override
    default SELF run(String id, String mapperId) {
        sequenceSink().run(id, mapperId);
        return sequenceSelf();
    }

    @Override
    default SELF run(String id, ContextMapper<C, ?> inlineMapper) {
        sequenceSink().run(id, inlineMapper);
        return sequenceSelf();
    }

    @Override
    default SELF fork(String id) {
        sequenceSink().fork(id);
        return sequenceSelf();
    }

    @Override
    default SELF fork(String id, String mapperId) {
        sequenceSink().fork(id, mapperId);
        return sequenceSelf();
    }

    @Override
    default SELF fork(String id, ContextMapper<C, ?> inlineMapper) {
        sequenceSink().fork(id, inlineMapper);
        return sequenceSelf();
    }

    @Override
    default SELF fork(String id, AsyncRejectionPolicy policy) {
        sequenceSink().fork(id, policy);
        return sequenceSelf();
    }

    @Override
    default SELF fork(String id, String mapperId, AsyncRejectionPolicy policy) {
        sequenceSink().fork(id, mapperId, policy);
        return sequenceSelf();
    }

    @Override
    default SELF fork(String id, ContextMapper<C, ?> inlineMapper, AsyncRejectionPolicy policy) {
        sequenceSink().fork(id, inlineMapper, policy);
        return sequenceSelf();
    }

    @Override
    default SELF step(String id, Action<? super T, C> action) {
        sequenceSink().step(id, action, false);
        return sequenceSelf();
    }

    @Override
    default SELF step(String id, Consumer<StepDef<T, C>> configurer) {
        sequenceSink().step(id, configurer, false);
        return sequenceSelf();
    }

    @Override
    default SELF choice(String id, Consumer<ChoiceDef<T, C>> configurer) {
        sequenceSink().choice(id, configurer, false);
        return sequenceSelf();
    }

    @Override
    default SELF operation(String id, Consumer<OperationDef<T, C>> configurer) {
        sequenceSink().operation(id, configurer, false);
        return sequenceSelf();
    }

    @Override
    default SELF forkStep(String id, Action<? super T, C> action) {
        sequenceSink().step(id, action, true);
        return sequenceSelf();
    }

    @Override
    default SELF forkStep(String id, Consumer<StepDef<T, C>> configurer) {
        sequenceSink().step(id, configurer, true);
        return sequenceSelf();
    }

    @Override
    default SELF forkChoice(String id, Consumer<ChoiceDef<T, C>> configurer) {
        sequenceSink().choice(id, configurer, true);
        return sequenceSelf();
    }

    @Override
    default SELF forkOperation(String id, Consumer<OperationDef<T, C>> configurer) {
        sequenceSink().operation(id, configurer, true);
        return sequenceSelf();
    }

    @Override
    default <N> SELF step(String id, Class<N> contextType, Action<? super T, N> action) {
        sequenceSink().step(id, contextType, MapperRef.passThrough(), action, false);
        return sequenceSelf();
    }

    @Override
    default <N> SELF step(String id, Class<N> contextType, ContextMapper<C, N> mapper, Action<? super T, N> action) {
        sequenceSink().step(id, contextType, MapperRef.inline(mapper), action, false);
        return sequenceSelf();
    }

    @Override
    default <N> SELF step(String id, Class<N> contextType, Consumer<StepDef<T, N>> configurer) {
        sequenceSink().step(id, contextType, MapperRef.passThrough(), configurer, false);
        return sequenceSelf();
    }

    @Override
    default <N> SELF step(String id, Class<N> contextType, ContextMapper<C, N> mapper,
                          Consumer<StepDef<T, N>> configurer) {
        sequenceSink().step(id, contextType, MapperRef.inline(mapper), configurer, false);
        return sequenceSelf();
    }

    @Override
    default <N> SELF choice(String id, Class<N> contextType, Consumer<ChoiceDef<T, N>> configurer) {
        sequenceSink().choice(id, contextType, MapperRef.passThrough(), configurer, false);
        return sequenceSelf();
    }

    @Override
    default <N> SELF choice(String id, Class<N> contextType, ContextMapper<C, N> mapper,
                            Consumer<ChoiceDef<T, N>> configurer) {
        sequenceSink().choice(id, contextType, MapperRef.inline(mapper), configurer, false);
        return sequenceSelf();
    }

    @Override
    default <N> SELF operation(String id, Class<N> contextType, Consumer<OperationDef<T, N>> configurer) {
        sequenceSink().operation(id, contextType, MapperRef.passThrough(), configurer, false);
        return sequenceSelf();
    }

    @Override
    default <N> SELF operation(String id, Class<N> contextType, ContextMapper<C, N> mapper,
                               Consumer<OperationDef<T, N>> configurer) {
        sequenceSink().operation(id, contextType, MapperRef.inline(mapper), configurer, false);
        return sequenceSelf();
    }

    @Override
    default <N> SELF forkStep(String id, Class<N> contextType, Action<? super T, N> action) {
        sequenceSink().step(id, contextType, MapperRef.passThrough(), action, true);
        return sequenceSelf();
    }

    @Override
    default <N> SELF forkStep(String id, Class<N> contextType, ContextMapper<C, N> mapper,
                              Action<? super T, N> action) {
        sequenceSink().step(id, contextType, MapperRef.inline(mapper), action, true);
        return sequenceSelf();
    }

    @Override
    default <N> SELF forkStep(String id, Class<N> contextType, Consumer<StepDef<T, N>> configurer) {
        sequenceSink().step(id, contextType, MapperRef.passThrough(), configurer, true);
        return sequenceSelf();
    }

    @Override
    default <N> SELF forkStep(String id, Class<N> contextType, ContextMapper<C, N> mapper,
                              Consumer<StepDef<T, N>> configurer) {
        sequenceSink().step(id, contextType, MapperRef.inline(mapper), configurer, true);
        return sequenceSelf();
    }

    @Override
    default <N> SELF forkChoice(String id, Class<N> contextType, Consumer<ChoiceDef<T, N>> configurer) {
        sequenceSink().choice(id, contextType, MapperRef.passThrough(), configurer, true);
        return sequenceSelf();
    }

    @Override
    default <N> SELF forkChoice(String id, Class<N> contextType, ContextMapper<C, N> mapper,
                                Consumer<ChoiceDef<T, N>> configurer) {
        sequenceSink().choice(id, contextType, MapperRef.inline(mapper), configurer, true);
        return sequenceSelf();
    }

    @Override
    default <N> SELF forkOperation(String id, Class<N> contextType, Consumer<OperationDef<T, N>> configurer) {
        sequenceSink().operation(id, contextType, MapperRef.passThrough(), configurer, true);
        return sequenceSelf();
    }

    @Override
    default <N> SELF forkOperation(String id, Class<N> contextType, ContextMapper<C, N> mapper,
                                   Consumer<OperationDef<T, N>> configurer) {
        sequenceSink().operation(id, contextType, MapperRef.inline(mapper), configurer, true);
        return sequenceSelf();
    }

    @Override
    default <N> SELF step(String id, Class<N> contextType, String mapperId, Action<? super T, N> action) {
        sequenceSink().step(id, contextType, MapperRef.byId(mapperId), action, false);
        return sequenceSelf();
    }

    @Override
    default <N> SELF step(String id, Class<N> contextType, String mapperId, Consumer<StepDef<T, N>> configurer) {
        sequenceSink().step(id, contextType, MapperRef.byId(mapperId), configurer, false);
        return sequenceSelf();
    }

    @Override
    default <N> SELF choice(String id, Class<N> contextType, String mapperId, Consumer<ChoiceDef<T, N>> configurer) {
        sequenceSink().choice(id, contextType, MapperRef.byId(mapperId), configurer, false);
        return sequenceSelf();
    }

    @Override
    default <N> SELF operation(String id, Class<N> contextType, String mapperId,
                               Consumer<OperationDef<T, N>> configurer) {
        sequenceSink().operation(id, contextType, MapperRef.byId(mapperId), configurer, false);
        return sequenceSelf();
    }

    @Override
    default <N> SELF forkStep(String id, Class<N> contextType, String mapperId, Action<? super T, N> action) {
        sequenceSink().step(id, contextType, MapperRef.byId(mapperId), action, true);
        return sequenceSelf();
    }

    @Override
    default <N> SELF forkStep(String id, Class<N> contextType, String mapperId, Consumer<StepDef<T, N>> configurer) {
        sequenceSink().step(id, contextType, MapperRef.byId(mapperId), configurer, true);
        return sequenceSelf();
    }

    @Override
    default <N> SELF forkChoice(String id, Class<N> contextType, String mapperId,
                                Consumer<ChoiceDef<T, N>> configurer) {
        sequenceSink().choice(id, contextType, MapperRef.byId(mapperId), configurer, true);
        return sequenceSelf();
    }

    @Override
    default <N> SELF forkOperation(String id, Class<N> contextType, String mapperId,
                                   Consumer<OperationDef<T, N>> configurer) {
        sequenceSink().operation(id, contextType, MapperRef.byId(mapperId), configurer, true);
        return sequenceSelf();
    }
}
