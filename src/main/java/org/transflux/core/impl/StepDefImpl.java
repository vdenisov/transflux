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

import org.transflux.core.action.ActionKind;
import org.transflux.core.exception.TransfluxValidationException;
import org.transflux.core.action.Action;
import org.transflux.core.action.StepDef;

import java.util.Map;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Default {@link StepDef} implementation - the imperative authoring form.
 * <p>
 * Holds the {@link Action} instance plus the declared context type, last-write-wins. The build
 * resolves the held source into a {@link BoundAction} paired with this def's id.
 *
 * <p>An imperative action binds no children at definition time, so the scope-related hooks
 * declared on {@link ActionDefImpl} are all no-ops here; only {@link OperationDefImpl} carries
 * real bodies for them.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type this action requires
 */
final class StepDefImpl<T, C> extends ActionDefImpl<T, C, StepDefImpl<T, C>> implements StepDef<T, C> {
    private final InstanceSource<Action<T, C>> source;

    /**
     * Declares an action that names no context of its own, so it takes the enclosing position's.
     * Used by the attachment sites that pass no context token, such as an action declared inline
     * on a transition.
     *
     * @param id the action id
     */
    StepDefImpl(String id) {
        super(id, "step", "Step ID", null);
        this.source = new InstanceSource<>(Loggers.BUILD_VALIDATION, "Step source",
                                           "StepDef '" + id + "'");
    }

    StepDefImpl(String id, Class<C> contextType) {
        super(id, "step", "Step ID", requireNotNull(contextType, "Step context type"));
        this.source = new InstanceSource<>(Loggers.BUILD_VALIDATION, "Step source",
                                           "StepDef '" + id + "'");
    }

    @Override
    public StepDefImpl<T, C> using(Action<T, C> action) {
        requireConfigurerActive("using");
        requireNotNull(action, "Step");
        source.setInstance(action);
        return this;
    }

    /**
     * Resolves this def into a {@link BoundAction} pairing the executable with this def's id.
     *
     * @return the bound action
     *
     * @throws TransfluxValidationException if no source has been set
     */
    BoundAction<T, C> buildBoundAction() {
        return BoundAction.of(getId(), source.resolve("Step"), ActionKind.STEP, buildBoundListeners(),
                              buildCompensationRouter());
    }

    @Override
    BoundAction<T, C> buildBound() {
        return buildBoundAction();
    }

    @Override
    void checkRefs(Class<?> scopeContext, String scopeLabel, String contextOwner,
                   List<String> visibleScopes, StateMachineDefImpl<T> smDef) {
        // An imperative action declares no members, so there is nothing to check.
    }

    @Override
    void bindMembers(StateMachineImpl<T> stateMachine, String positionLabel) {
        // An imperative action declares no members.
    }

    @Override
    void collectMemberContexts(Class<?> scopeContext, InlineContextSink sink) {
        // An imperative action declares no members.
    }

    @Override
    void bindScope(RegistryImpl<T> rootRegistry,
                   Map<String, Object> canonical,
                   Map<String, BoundCondition<T, ?>> conditionRegistry,
                   Class<?> inheritedContext) {
        // An imperative action owns no lexical scope.
    }

    @Override
    void visitScopeOwners(Consumer<ActionDefImpl<T, C, ?>> visitor) {
        // An imperative action declares no members, so nothing beneath it owns a scope.
    }

    @Override
    boolean declaresFork() {
        return false;
    }

    @Override
    List<String> ownByIdReferenceIds() {
        return List.of();
    }

    @Override
    void collectNestedCycleNodes(BiConsumer<String, List<String>> sink) {
        // An imperative action declares no members, so it contributes no node.
    }

}
