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
import org.transflux.core.Identifiable;
import org.transflux.core.action.DefaultBranchDef;
import org.transflux.core.action.Action;
import org.transflux.core.action.StepDef;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.transflux.core.Preconditions.requireNotBlank;
import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Implementation of {@link DefaultBranchDef} used by {@link ConditionalOperationDefImpl}.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
final class DefaultBranchDefImpl<T, C> extends ConfigurableDefImpl implements DefaultBranchDef<T, C> {

    private final List<ActionRef<T, C>> actionRefs = new ArrayList<>();

    DefaultBranchDefImpl() {
    }

    @Override
    protected String defLabel() {
        return "default branch";
    }

    List<ActionRef<T, C>> getActionRefs() {
        return Collections.unmodifiableList(actionRefs);
    }

    void collectInlineRegistrations(InlineRegistrationSink<T, C> sink) {
        for (ActionRef<T, C> ref : actionRefs) {
            ref.collectInlineRegistrations(sink);
        }
    }

    void collectListenerIds(BiConsumer<String, String> sink) {
        for (ActionRef<T, C> ref : actionRefs) {
            ref.collectListenerIds(sink);
        }
    }

    @Override
    public DefaultBranchDef<T, C> run(String id) {
        requireConfigurerActive("run");
        actionRefs.add(ActionRef.byId(id));
        return this;
    }

    @Override
    public DefaultBranchDef<T, C> run(Identifiable registeredAction) {
        requireNotNull(registeredAction, "Action identifiable");
        return run(registeredAction.getId());
    }

    @Override
    public DefaultBranchDef<T, C> step(String id, Action<T, C> step) {
        requireConfigurerActive("step");
        actionRefs.add(ActionRef.inline(id, step, ActionKind.STEP));
        return this;
    }

    @Override
    public DefaultBranchDef<T, C> step(Identifiable stepIdentifiable, Action<T, C> step) {
        requireNotNull(stepIdentifiable, "Step identifiable");
        return step(stepIdentifiable.getId(), step);
    }

    @Override
    public DefaultBranchDef<T, C> step(String id, Class<? extends Action<T, C>> stepClass) {
        requireConfigurerActive("step");
        actionRefs.add(ActionRef.inline(id, stepClass, ActionKind.STEP));
        return this;
    }

    @Override
    public DefaultBranchDef<T, C> step(Identifiable stepIdentifiable, Class<? extends Action<T, C>> stepClass) {
        requireNotNull(stepIdentifiable, "Step identifiable");
        return step(stepIdentifiable.getId(), stepClass);
    }

    @Override
    public DefaultBranchDef<T, C> step(String id, Consumer<StepDef<T, C>> configurer) {
        requireConfigurerActive("step");
        requireNotBlank(id, "Step ID");
        requireNotNull(configurer, "Step configurer");
        StepDefImpl<T, C> def = new StepDefImpl<>(id);
        ConfigurableDefImpl.runConfigurer(def, configurer);
        actionRefs.add(ActionRef.inline(id, def));
        return this;
    }

    @Override
    public DefaultBranchDef<T, C> step(Identifiable stepIdentifiable, Consumer<StepDef<T, C>> configurer) {
        requireNotNull(stepIdentifiable, "Step identifiable");
        return step(stepIdentifiable.getId(), configurer);
    }
}
