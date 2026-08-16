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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.transflux.core.exception.TransfluxValidationException;
import org.transflux.core.action.Action;
import org.transflux.core.action.StepDef;

import java.util.Map;
import java.util.Optional;

import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Default {@link StepDef} implementation - the imperative authoring form.
 * <p>
 * Holds either an {@link Action} instance or an {@code Action} class plus the declared context
 * type; the two source forms are mutually exclusive and last-write-wins. The build resolves the
 * held source, reflectively instantiating the class form when needed, into a {@link BoundAction}
 * paired with this def's id.
 *
 * <p>An imperative action binds no children at definition time, so the scope-related hooks
 * declared on {@link ActionDefImpl} are all no-ops here; only {@link OperationDefImpl} carries
 * real bodies for them.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type this action requires
 */
final class StepDefImpl<T, C> extends ActionDefImpl<T, C, StepDefImpl<T, C>> implements StepDef<T, C> {
    private static final Logger log = LoggerFactory.getLogger(StepDefImpl.class);

    private final Class<C> contextType;
    private final InstanceOrClassSource<Action<T, C>> source;

    /**
     * Declares an action with no explicit context type, which defaults to the permissive
     * {@code Object.class} sentinel. Used by the attachment sites that do not take a context
     * token, such as an action declared inline on a transition.
     *
     * @param id the action id
     */
    @SuppressWarnings("unchecked")
    StepDefImpl(String id) {
        this(id, (Class<C>) Object.class);
    }

    StepDefImpl(String id, Class<C> contextType) {
        super(id, "step", "Step ID");
        requireNotNull(contextType, "Step context type");
        this.contextType = contextType;
        this.source = new InstanceOrClassSource<>(log, "Step source", "StepDef '" + id + "'");
    }

    @Override
    public Class<C> contextType() {
        return contextType;
    }

    @Override
    public StepDefImpl<T, C> using(Action<T, C> action) {
        requireConfigurerActive("using");
        requireNotNull(action, "Step");
        source.setInstance(action);
        return this;
    }

    @Override
    public StepDefImpl<T, C> using(Class<? extends Action<T, C>> actionClass) {
        requireConfigurerActive("using");
        requireNotNull(actionClass, "Step class");
        source.setClass(actionClass);
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
        return BoundAction.of(getId(), source.resolve("Step"), ActionKind.STEP, buildBoundListeners());
    }

    @Override
    BoundAction<T, C> buildBound(StateMachineImpl<T> stateMachine) {
        return buildBoundAction();
    }

    @Override
    void checkRefs(Class<?> scopeContext, String scopeLabel, StateMachineDefImpl<T> smDef) {
        // An imperative action declares no members, so there is nothing to check.
    }

    @Override
    void checkBranchRefs() {
        // An imperative action declares no conditionals, so it owns no branch members.
    }

    @Override
    void bindScope(RegistryImpl<T> rootRegistry,
                   Map<String, Object> canonical,
                   Map<String, BoundCondition<T, ?>> conditionRegistry) {
        // An imperative action owns no lexical scope.
    }

    @Override
    void flattenScope() {
        // No scope registry to flatten.
    }

    @Override
    Optional<String> scanScopeFor(String id, String excludingId) {
        return Optional.empty();
    }

    @Override
    Registry<T> getScopeRegistry() {
        return null;
    }
}
