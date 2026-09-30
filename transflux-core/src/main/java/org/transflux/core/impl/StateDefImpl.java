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

import org.transflux.core.exception.TransfluxValidationException;
import org.transflux.core.state.StateDef;
import org.transflux.core.state.StateListener;
import org.transflux.core.state.StateListenerDef;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private final List<ListenerEntry<StateListenerDefImpl<T>>> entryListeners = new ArrayList<>();
    private final List<ListenerEntry<StateListenerDefImpl<T>>> exitListeners = new ArrayList<>();

    private final GlobalListenerDisables disabledGlobals = new GlobalListenerDisables(this);

    /** Where each listener declared in place here is declared, in declaration order. */
    private final Map<String, String> listenerSites = new LinkedHashMap<>();

    StateDefImpl(StateMachineDefImpl<T> smd, String id) {
        super(id, "state", "State ID");
        requireNotNull(smd, "State machine definition");

        this.stateMachineDef = smd;
    }

    @Override
    public StateDefImpl<T> onEntry(String listenerId, StateListener<? super T> listener) {
        requireConfigurerActive("onEntry");
        requireNotBlank(listenerId, "State listener ID");
        requireNotNull(listener, "State listener");
        entryListeners.add(declare(listenerId, "onEntry", l -> l.using(listener)));
        return this;
    }

    @Override
    public StateDefImpl<T> onEntry(String listenerId, Consumer<StateListenerDef<T>> configurer) {
        requireConfigurerActive("onEntry");
        requireNotBlank(listenerId, "State listener ID");
        requireNotNull(configurer, "State listener configurer");
        entryListeners.add(declare(listenerId, "onEntry", configurer));
        return this;
    }

    @Override
    public StateDefImpl<T> onEntry(String listenerId) {
        requireConfigurerActive("onEntry");
        requireNotBlank(listenerId, "State listener ID");
        entryListeners.add(ListenerEntry.reference(listenerId));
        return this;
    }

    @Override
    public StateDefImpl<T> onExit(String listenerId) {
        requireConfigurerActive("onExit");
        requireNotBlank(listenerId, "State listener ID");
        exitListeners.add(ListenerEntry.reference(listenerId));
        return this;
    }

    @Override
    public StateDefImpl<T> onExit(String listenerId, StateListener<? super T> listener) {
        requireConfigurerActive("onExit");
        requireNotBlank(listenerId, "State listener ID");
        requireNotNull(listener, "State listener");
        exitListeners.add(declare(listenerId, "onExit", l -> l.using(listener)));
        return this;
    }

    @Override
    public StateDefImpl<T> onExit(String listenerId, Consumer<StateListenerDef<T>> configurer) {
        requireConfigurerActive("onExit");
        requireNotBlank(listenerId, "State listener ID");
        requireNotNull(configurer, "State listener configurer");
        exitListeners.add(declare(listenerId, "onExit", configurer));
        return this;
    }

    @Override
    public StateDefImpl<T> disableGlobalListener(String listenerId) {
        disabledGlobals.disable(listenerId);
        return this;
    }

    @Override
    public StateDefImpl<T> disableGlobalListeners(String... listenerIds) {
        disabledGlobals.disable(listenerIds);
        return this;
    }

    @Override
    public StateDefImpl<T> disableAllGlobalListeners() {
        disabledGlobals.disableAll();
        return this;
    }

    /**
     * Returns what this state turned off among the state-machine-wide state listeners.
     *
     * @return this state's declaration; never {@code null}
     */
    GlobalListenerDisables getDisabledGlobals() {
        return disabledGlobals;
    }

    /**
     * Returns this state's entry listeners in declaration order.
     *
     * @return the live entry-listener list
     */
    List<ListenerEntry<StateListenerDefImpl<T>>> getEntryListeners() {
        return entryListeners;
    }

    /**
     * Returns this state's exit listeners in declaration order.
     *
     * @return the live exit-listener list
     */
    List<ListenerEntry<StateListenerDefImpl<T>>> getExitListeners() {
        return exitListeners;
    }

    /**
     * Returns where each listener this state declares in place is declared, for the state machine
     * to claim when the state registers.
     *
     * @return listener id to {@code state 's' via onEntry}, in declaration order
     */
    Map<String, String> declaredListenerSites() {
        return Collections.unmodifiableMap(listenerSites);
    }

    private ListenerEntry<StateListenerDefImpl<T>> declare(String listenerId, String hook,
                                                           Consumer<StateListenerDef<T>> configurer) {
        StateListenerDefImpl<T> listenerDef = new StateListenerDefImpl<>(listenerId);
        ConfigurableDefImpl.runConfigurer(listenerDef, configurer);
        // Checked here so the refusal points at this call; claimed when the state registers.
        String site = site(hook);
        stateMachineDef.requireListenerIdFree(listenerId, site);
        String first = listenerSites.get(listenerId);
        if (first != null) {
            throw new TransfluxValidationException(StateMachineDefImpl.duplicateListener(listenerId, first, site));
        }
        listenerSites.put(listenerId, site);
        return ListenerEntry.declared(listenerId, listenerDef);
    }

    private String site(String hook) {
        return "state '" + getId() + "' via " + hook;
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
