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

import java.util.List;

import static org.transflux.core.Preconditions.requireNotBlank;
import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Framework-internal bound record of a transition's resolved runtime data.
 * <p>
 * One {@code BoundTransition} is built per declared transition during state machine
 * construction. It carries the topology (id, source/target state ids, declared context type)
 * plus the resolved {@link BoundAction} and pre/post {@link BoundCondition} lists, all
 * computed once against the live registry.
 *
 * <p>This type does not participate in dispatch. The per-execution
 * {@link org.transflux.core.transition.Transition} surface (topology accessors plus the
 * {@code step(...)} / {@code operation(...)} entry points) lives on {@link TransitionView},
 * which holds a {@code BoundTransition} in its {@code bound} field and runs against the
 * captured execution scope. Members of the {@code Bound*} family are framework-internal
 * data carriers; user code should not reference this type directly.
 *
 * @param id the transition id; never {@code null} or blank
 * @param sourceStateId the source state id; never {@code null} or blank
 * @param targetStateId the target state id; never {@code null} or blank
 * @param contextType the declared firing-context type; never {@code null}
 *                    ({@code Object.class} when untyped, {@code Void.class} when null-only)
 * @param boundAction the resolved operation to run; may be {@code null} for transitions
 *                       without an operation
 * @param boundPreConditions the resolved pre-conditions, in declaration order
 * @param boundPostConditions the resolved post-conditions, in declaration order
 * @param boundListeners the resolved listeners for each hook, already in notification order
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
record BoundTransition<T, C>(String id,
                             String sourceStateId,
                             String targetStateId,
                             Class<C> contextType,
                             BoundAction<T, C> boundAction,
                             List<BoundCondition<T, C>> boundPreConditions,
                             List<BoundCondition<T, C>> boundPostConditions,
                             BoundTransitionListeners<T, C> boundListeners) {

    BoundTransition {
        requireNotBlank(id, "Bound transition ID");
        requireNotBlank(sourceStateId, "Source state ID");
        requireNotBlank(targetStateId, "Target state ID");
        requireNotNull(contextType, "Context type");
        requireNotNull(boundPreConditions, "Bound pre-conditions");
        requireNotNull(boundPostConditions, "Bound post-conditions");
        requireNotNull(boundListeners, "Bound transition listeners");
        boundPreConditions = List.copyOf(boundPreConditions);
        boundPostConditions = List.copyOf(boundPostConditions);
    }

    /**
     * Builds a {@code BoundTransition} by resolving the supplied definition against the
     * enclosing state machine and condition registry. Equivalent to invoking the canonical
     * constructor with the def's resolved bindings.
     *
     * @param def the transition definition to resolve
     * @param stateMachine the enclosing state machine; needed by composite operations to
     *                     resolve step references
     * @param conditionRegistry the resolved state-machine condition registry
     * @param listeners the transition's listeners merged with the state machine's global ones,
     *                  already in notification order
     * @param <T> the entity type
     * @param <C> the context type
     *
     * @return the resolved bound transition
     */
    static <T, C> BoundTransition<T, C> from(TransitionDefImpl<T, C> def,
                                             StateMachineImpl<T> stateMachine,
                                             java.util.Map<String, BoundCondition<T, C>> conditionRegistry,
                                             BoundTransitionListeners<T, C> listeners) {
        requireNotNull(def, "Transition definition");
        requireNotNull(conditionRegistry, "Condition registry");
        return new BoundTransition<>(
            def.getId(),
            def.getSourceStateId(),
            def.getTargetStateId(),
            def.getContextType(),
            def.buildBoundAction(stateMachine),
            def.buildBoundPreConditions(conditionRegistry),
            def.buildBoundPostConditions(conditionRegistry),
            listeners);
    }
}
