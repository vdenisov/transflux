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

import org.transflux.core.Identifiable;
import org.transflux.core.state.StateDef;
import org.transflux.core.state.StateListener;
import org.transflux.core.state.StateListenerDef;
import org.transflux.core.transition.TransitionDef;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.transflux.core.Preconditions.requireNotBlank;
import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Builder implementation class for defining states within a state machine definition.
 *
 * @param <T> the type of entity managed by the state machine
 */
class StateDefImpl<T> extends IdentifiedDefImpl<StateDefImpl<T>> implements StateDef<T> {

    private final StateMachineDefImpl<T> stateMachineDef;

    private final List<StateListenerDefImpl<T>> entryListeners = new ArrayList<>();
    private final List<StateListenerDefImpl<T>> exitListeners = new ArrayList<>();

    StateDefImpl(StateMachineDefImpl<T> smd, String id) {
        super(id, "state", "State ID");
        requireNotNull(smd, "State machine definition");

        this.stateMachineDef = smd;
    }

    StateDefImpl(StateMachineDefImpl<T> smd, Identifiable identifiable) {
        super(requireNotNullThenGetId(identifiable), "state", "State ID");
        requireNotNull(smd, "State machine definition");

        this.stateMachineDef = smd;
    }

    @Override
    public StateDefImpl<T> onEntry(String listenerId, StateListener<T> listener) {
        requireConfigurerActive("onEntry");
        requireNotBlank(listenerId, "State listener ID");
        requireNotNull(listener, "State listener");
        entryListeners.add(declareListener(listenerId, l -> l.using(listener)));
        return this;
    }

    @Override
    public StateDefImpl<T> onEntry(Identifiable listenerIdentifiable, StateListener<T> listener) {
        requireNotNull(listenerIdentifiable, "State listener identifiable");
        return onEntry(listenerIdentifiable.getId(), listener);
    }

    @Override
    public StateDefImpl<T> onEntry(String listenerId, Class<? extends StateListener<T>> listenerClass) {
        requireConfigurerActive("onEntry");
        requireNotBlank(listenerId, "State listener ID");
        requireNotNull(listenerClass, "State listener class");
        entryListeners.add(declareListener(listenerId, l -> l.using(listenerClass)));
        return this;
    }

    @Override
    public StateDefImpl<T> onEntry(Identifiable listenerIdentifiable,
                                   Class<? extends StateListener<T>> listenerClass) {
        requireNotNull(listenerIdentifiable, "State listener identifiable");
        return onEntry(listenerIdentifiable.getId(), listenerClass);
    }

    @Override
    public StateDefImpl<T> onEntry(String listenerId, Consumer<StateListenerDef<T>> configurer) {
        requireConfigurerActive("onEntry");
        requireNotBlank(listenerId, "State listener ID");
        requireNotNull(configurer, "State listener configurer");
        entryListeners.add(declareListener(listenerId, configurer));
        return this;
    }

    @Override
    public StateDefImpl<T> onEntry(Identifiable listenerIdentifiable, Consumer<StateListenerDef<T>> configurer) {
        requireNotNull(listenerIdentifiable, "State listener identifiable");
        return onEntry(listenerIdentifiable.getId(), configurer);
    }

    @Override
    public StateDefImpl<T> onExit(String listenerId, StateListener<T> listener) {
        requireConfigurerActive("onExit");
        requireNotBlank(listenerId, "State listener ID");
        requireNotNull(listener, "State listener");
        exitListeners.add(declareListener(listenerId, l -> l.using(listener)));
        return this;
    }

    @Override
    public StateDefImpl<T> onExit(Identifiable listenerIdentifiable, StateListener<T> listener) {
        requireNotNull(listenerIdentifiable, "State listener identifiable");
        return onExit(listenerIdentifiable.getId(), listener);
    }

    @Override
    public StateDefImpl<T> onExit(String listenerId, Class<? extends StateListener<T>> listenerClass) {
        requireConfigurerActive("onExit");
        requireNotBlank(listenerId, "State listener ID");
        requireNotNull(listenerClass, "State listener class");
        exitListeners.add(declareListener(listenerId, l -> l.using(listenerClass)));
        return this;
    }

    @Override
    public StateDefImpl<T> onExit(Identifiable listenerIdentifiable,
                                  Class<? extends StateListener<T>> listenerClass) {
        requireNotNull(listenerIdentifiable, "State listener identifiable");
        return onExit(listenerIdentifiable.getId(), listenerClass);
    }

    @Override
    public StateDefImpl<T> onExit(String listenerId, Consumer<StateListenerDef<T>> configurer) {
        requireConfigurerActive("onExit");
        requireNotBlank(listenerId, "State listener ID");
        requireNotNull(configurer, "State listener configurer");
        exitListeners.add(declareListener(listenerId, configurer));
        return this;
    }

    @Override
    public StateDefImpl<T> onExit(Identifiable listenerIdentifiable, Consumer<StateListenerDef<T>> configurer) {
        requireNotNull(listenerIdentifiable, "State listener identifiable");
        return onExit(listenerIdentifiable.getId(), configurer);
    }

    /**
     * Returns this state's entry listeners in declaration order.
     *
     * @return the live entry-listener list
     */
    List<StateListenerDefImpl<T>> getEntryListeners() {
        return entryListeners;
    }

    /**
     * Returns this state's exit listeners in declaration order.
     *
     * @return the live exit-listener list
     */
    List<StateListenerDefImpl<T>> getExitListeners() {
        return exitListeners;
    }

    @Override
    public StateDefImpl<T> transitionsTo(String targetStateId, String transitionId,
                                         Consumer<TransitionDef<T, Object>> configurer) {
        requireConfigurerActive("transitionsTo");
        requireNotBlank(targetStateId, "Target state ID");
        requireNotBlank(transitionId, "Transition ID");
        requireNotNull(configurer, "Transition configurer");
        TransitionDefImpl<T, Object> td = stateMachineDef.<Object>registerTransition(
            getId(), targetStateId, transitionId, Object.class);
        ConfigurableDefImpl.runConfigurer(td, configurer);
        return this;
    }

    @Override
    public <C> StateDefImpl<T> transitionsTo(String targetStateId, String transitionId,
                                             Class<C> contextType,
                                             Consumer<TransitionDef<T, C>> configurer) {
        requireConfigurerActive("transitionsTo");
        requireNotBlank(targetStateId, "Target state ID");
        requireNotBlank(transitionId, "Transition ID");
        requireNotNull(contextType, "Context type");
        requireNotNull(configurer, "Transition configurer");
        TransitionDefImpl<T, C> td = stateMachineDef.registerTransition(
            getId(), targetStateId, transitionId, contextType);
        ConfigurableDefImpl.runConfigurer(td, configurer);
        return this;
    }

    @Override
    public StateDefImpl<T> transitionsTo(Identifiable targetStateIdentifiable, String transitionId,
                                         Consumer<TransitionDef<T, Object>> configurer) {
        requireNotNull(targetStateIdentifiable, "Target state identifiable");
        return transitionsTo(targetStateIdentifiable.getId(), transitionId, configurer);
    }

    @Override
    public <C> StateDefImpl<T> transitionsTo(Identifiable targetStateIdentifiable, String transitionId,
                                             Class<C> contextType,
                                             Consumer<TransitionDef<T, C>> configurer) {
        requireNotNull(targetStateIdentifiable, "Target state identifiable");
        return transitionsTo(targetStateIdentifiable.getId(), transitionId, contextType, configurer);
    }

    @Override
    public StateDefImpl<T> transitionsTo(String targetStateId, Identifiable transitionIdentifiable,
                                         Consumer<TransitionDef<T, Object>> configurer) {
        requireNotNull(transitionIdentifiable, "Transition identifiable");
        return transitionsTo(targetStateId, transitionIdentifiable.getId(), configurer);
    }

    @Override
    public <C> StateDefImpl<T> transitionsTo(String targetStateId, Identifiable transitionIdentifiable,
                                             Class<C> contextType,
                                             Consumer<TransitionDef<T, C>> configurer) {
        requireNotNull(transitionIdentifiable, "Transition identifiable");
        return transitionsTo(targetStateId, transitionIdentifiable.getId(), contextType, configurer);
    }

    @Override
    public StateDefImpl<T> transitionsTo(Identifiable targetStateIdentifiable, Identifiable transitionIdentifiable,
                                         Consumer<TransitionDef<T, Object>> configurer) {
        requireNotNull(targetStateIdentifiable, "Target state identifiable");
        requireNotNull(transitionIdentifiable, "Transition identifiable");
        return transitionsTo(targetStateIdentifiable.getId(), transitionIdentifiable.getId(), configurer);
    }

    @Override
    public <C> StateDefImpl<T> transitionsTo(Identifiable targetStateIdentifiable, Identifiable transitionIdentifiable,
                                             Class<C> contextType,
                                             Consumer<TransitionDef<T, C>> configurer) {
        requireNotNull(targetStateIdentifiable, "Target state identifiable");
        requireNotNull(transitionIdentifiable, "Transition identifiable");
        return transitionsTo(targetStateIdentifiable.getId(), transitionIdentifiable.getId(), contextType, configurer);
    }

    private StateListenerDefImpl<T> declareListener(String listenerId,
                                                    Consumer<StateListenerDef<T>> configurer) {
        StateListenerDefImpl<T> listenerDef = new StateListenerDefImpl<>(listenerId);
        // Claimed only once the configurer has returned, so a configurer that throws leaves the id
        // free for the caller's corrected retry.
        ConfigurableDefImpl.runConfigurer(listenerDef, configurer);
        stateMachineDef.claimListenerId(listenerId);
        return listenerDef;
    }

    private static String requireNotNullThenGetId(Identifiable identifiable) {
        requireNotNull(identifiable, "Identifiable for state ID");
        return identifiable.getId();
    }

    @Override
    public String toString() {
        return "StateDef{" +
            "id='" + getId() + '\'' +
            ", name='" + getName() + '\'' +
            ", description='" + getDescription() + '\'' +
            ", stateMachineDef=" + stateMachineDef +
            '}';
    }
}
