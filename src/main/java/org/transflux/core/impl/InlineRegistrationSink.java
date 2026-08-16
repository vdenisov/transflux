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
import org.transflux.core.action.ActionKind;
import org.transflux.core.condition.ConditionDescriptor;

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

    void registerInlineAction(String id, Action<T, C> action, ActionKind kind) {
        claimCanonical(canonical, id, action, label(kind));
        if (scope.get(id).isPresent()) {
            return;
        }
        BoundAction<T, C> bound = BoundAction.of(id, action, kind);
        scope.register(new Component.Action<>(id, contextType, bound));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    void registerInlineActionClass(String id, Class<? extends Action<T, C>> actionClass,
                                   ActionKind kind) {
        claimCanonical(canonical, id, actionClass, label(kind));
        if (scope.get(id).isPresent()) {
            return;
        }
        Action<T, C> resolved = (Action<T, C>) instantiateNoArg((Class) actionClass, label(kind));
        BoundAction<T, C> bound = BoundAction.of(id, resolved, kind);
        scope.register(new Component.Action<>(id, contextType, bound));
    }

    void registerInlineStepDef(String id, StepDefImpl<T, C> def) {
        claimCanonical(canonical, id, def, "Step");
        if (scope.get(id).isPresent()) {
            return;
        }
        scope.register(new Component.Action<>(id, contextType, def.buildBoundAction()));
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

    void registerConditional(String id, ConditionalOperationDefImpl<T, C> def) {
        claimCanonical(canonical, id, def, "Conditional operation");
        if (scope.get(id).isPresent()) {
            return;
        }
        BoundAction<T, C> bound = def.buildBoundAction(conditionRegistry);
        scope.register(new Component.Action<>(id, contextType, bound));
    }

    /**
     * The label used when claiming an id and when reporting an instantiation failure. It names the
     * form the member was declared in, so the diagnostic matches the DSL the user wrote.
     */
    private static String label(ActionKind kind) {
        return kind == ActionKind.STEP ? "Step" : "Operation";
    }
}
