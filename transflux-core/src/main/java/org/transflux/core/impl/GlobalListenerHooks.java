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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The eight state-machine-wide listener hooks, each in declaration order, behind which the
 * per-owner listeners of whichever state, transition or action is involved run first. A pass over
 * the hooks iterates {@link #all()} or a category's map rather than naming lists, so none is missed.
 * Transition and action hooks span owners of differing contexts and so are typed against
 * {@link Object}.
 *
 * @param <T> the entity type the surrounding state machine manages
 */
final class GlobalListenerHooks<T> {

    final List<ListenerEntry<StateListenerDefImpl<T>>> stateEntry = new ArrayList<>();
    final List<ListenerEntry<StateListenerDefImpl<T>>> stateExit = new ArrayList<>();
    final List<ListenerEntry<TransitionListenerDefImpl<T, Object>>> transitionStart = new ArrayList<>();
    final List<ListenerEntry<TransitionListenerDefImpl<T, Object>>> transitionComplete = new ArrayList<>();
    final List<ListenerEntry<TransitionListenerDefImpl<T, Object>>> transitionError = new ArrayList<>();
    final List<ListenerEntry<ActionListenerDefImpl<T, Object>>> actionStart = new ArrayList<>();
    final List<ListenerEntry<ActionListenerDefImpl<T, Object>>> actionComplete = new ArrayList<>();
    final List<ListenerEntry<ActionListenerDefImpl<T, Object>>> actionError = new ArrayList<>();

    /** The state hooks by DSL method, in a fixed order. */
    final Map<String, List<ListenerEntry<StateListenerDefImpl<T>>>> state = new LinkedHashMap<>();

    /** The transition hooks by DSL method, in a fixed order. */
    final Map<String, List<ListenerEntry<TransitionListenerDefImpl<T, Object>>>> transition = new LinkedHashMap<>();

    /** The action hooks by DSL method, in a fixed order. */
    final Map<String, List<ListenerEntry<ActionListenerDefImpl<T, Object>>>> action = new LinkedHashMap<>();

    private final List<List<? extends ListenerEntry<? extends ListenerDefImpl<?>>>> all;

    GlobalListenerHooks() {
        state.put("onAnyStateEntry", stateEntry);
        state.put("onAnyStateExit", stateExit);
        transition.put("onAnyTransitionStart", transitionStart);
        transition.put("onAnyTransitionComplete", transitionComplete);
        transition.put("onAnyTransitionError", transitionError);
        action.put("onAnyActionStart", actionStart);
        action.put("onAnyActionComplete", actionComplete);
        action.put("onAnyActionError", actionError);

        List<List<? extends ListenerEntry<? extends ListenerDefImpl<?>>>> hooks = new ArrayList<>(8);
        hooks.addAll(state.values());
        hooks.addAll(transition.values());
        hooks.addAll(action.values());
        this.all = List.copyOf(hooks);
    }

    /**
     * Returns all eight hooks, for a pass that treats every category alike.
     *
     * @return the hook lists, state hooks first, then transition, then action
     */
    List<List<? extends ListenerEntry<? extends ListenerDefImpl<?>>>> all() {
        return all;
    }
}
