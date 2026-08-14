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

import org.transflux.core.condition.ConditionDescriptor;
import org.transflux.core.operation.Operation;
import org.transflux.core.operation.Step;

import java.util.Map;

import static org.transflux.core.impl.ReflectionUtils.instantiateNoArg;
import static org.transflux.core.impl.StateMachineDefImpl.claimCanonical;
import static org.transflux.core.impl.StateMachineDefImpl.claimInlineCondition;

/**
 * Visitor sink for the polymorphic {@code collectInlineRegistrations} walk over a composite's
 * action refs and its conditionals' branch refs. Bundles the per-composite locals
 * ({@link RegistryImpl scope}, canonical-payload table, context type, conditions registry) so
 * each variant override can deposit its inline registration with a single call.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type of the enclosing composite
 */
final class InlineRegistrationSink<T, C> {

    private final RegistryImpl<T> scope;
    private final Map<String, Object> canonical;
    private final Class<C> contextType;
    private final Map<String, BoundCondition<T, C>> conditionRegistry;

    InlineRegistrationSink(RegistryImpl<T> scope,
                           Map<String, Object> canonical,
                           Class<C> contextType,
                           Map<String, BoundCondition<T, C>> conditionRegistry) {
        this.scope = scope;
        this.canonical = canonical;
        this.contextType = contextType;
        this.conditionRegistry = conditionRegistry;
    }

    void registerInlineStep(String id, Step<T, C> step) {
        claimCanonical(canonical, id, step, "Step");
        if (scope.get(id).isPresent()) {
            return;
        }
        BoundStep<T, C> bound = BoundStep.of(id, step);
        scope.register(new Component.Step<>(id, contextType, bound));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    void registerInlineStepClass(String id, Class<? extends Step<T, C>> stepClass) {
        claimCanonical(canonical, id, stepClass, "Step");
        if (scope.get(id).isPresent()) {
            return;
        }
        Step<T, C> resolved = (Step<T, C>) instantiateNoArg((Class) stepClass, "Step");
        BoundStep<T, C> bound = BoundStep.of(id, resolved);
        scope.register(new Component.Step<>(id, contextType, bound));
    }

    void registerInlineOperation(String id, Operation<T, C> operation) {
        claimCanonical(canonical, id, operation, "Operation");
        if (scope.get(id).isPresent()) {
            return;
        }
        BoundOperation<T, C> bound = BoundOperation.of(id, operation);
        scope.register(new Component.Operation<>(id, contextType, bound));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    void registerInlineOperationClass(String id, Class<? extends Operation<T, C>> operationClass) {
        claimCanonical(canonical, id, operationClass, "Operation");
        if (scope.get(id).isPresent()) {
            return;
        }
        Operation<T, C> resolved = (Operation<T, C>) instantiateNoArg((Class) operationClass, "Operation");
        BoundOperation<T, C> bound = BoundOperation.of(id, resolved);
        scope.register(new Component.Operation<>(id, contextType, bound));
    }

    /**
     * Claims an inline branch condition's id, so it competes for the id with every other component
     * exactly as an inline step or operation does.
     *
     * @param descriptor the branch's condition descriptor; may be {@code null}
     */
    void registerInlineCondition(ConditionDescriptor descriptor) {
        claimInlineCondition(canonical, descriptor);
    }

    void registerConditional(String id, ConditionalStepDefImpl<T, C> def) {
        claimCanonical(canonical, id, def, "Conditional step");
        if (scope.get(id).isPresent()) {
            return;
        }
        BoundStep<T, C> bound = def.buildBoundStep(conditionRegistry);
        scope.register(new Component.Step<>(id, contextType, bound));
    }
}
