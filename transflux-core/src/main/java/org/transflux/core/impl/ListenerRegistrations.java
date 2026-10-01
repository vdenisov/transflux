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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Resolves a hook's listener entries - each one declared at that hook, or a reference to one
 * declared elsewhere - into bound listeners, for one build.
 *
 * @param <T> the entity type the surrounding state machine manages
 */
final class ListenerRegistrations<T> {

    private final StateMachineDefImpl<T> def;
    private final Map<String, BoundStateListener<T>> boundStates = new HashMap<>();
    private final Map<String, BoundTransitionListener<T, ?>> boundTransitions = new HashMap<>();
    private final Map<String, BoundActionListener<T, ?>> boundActions = new HashMap<>();

    ListenerRegistrations(StateMachineDefImpl<T> def) {
        this.def = def;
    }

    /**
     * A resolver for a def built outside a state machine, which is what a unit test does: there are
     * no registrations to reach, so a reference resolves against the owner's own declarations or
     * fails.
     *
     * @param <T> the entity type
     *
     * @return a resolver with no registrations
     */
    static <T> ListenerRegistrations<T> standalone() {
        return new ListenerRegistrations<>(null);
    }

    private Map<String, StateListenerDefImpl<T>> stateRegistrations() {
        return def == null ? Map.of() : def.getStateListenerRegistrations();
    }

    private Map<String, TransitionListenerDefImpl<T, ?>> transitionRegistrations() {
        return def == null ? Map.of() : def.getTransitionListenerRegistrations();
    }

    private Map<String, ActionListenerDefImpl<T, ?>> actionRegistrations() {
        return def == null ? Map.of() : def.getActionListenerRegistrations();
    }

    List<BoundStateListener<T>> bindStates(List<ListenerEntry<StateListenerDefImpl<T>>> entries,
                                           Map<String, StateListenerDefImpl<T>> ownScope) {
        return bind(entries, ownScope, stateRegistrations(), boundStates,
                    StateListenerDefImpl::buildBoundListener);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    <C> List<BoundTransitionListener<T, C>> bindTransitions(
            List<ListenerEntry<TransitionListenerDefImpl<T, C>>> entries,
            Map<String, TransitionListenerDefImpl<T, C>> ownScope) {
        return (List) bind(entries, ownScope, (Map) transitionRegistrations(),
                           (Map) boundTransitions, TransitionListenerDefImpl::buildBoundListener);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    <C> List<BoundActionListener<T, C>> bindActions(
            List<ListenerEntry<ActionListenerDefImpl<T, C>>> entries,
            Map<String, ActionListenerDefImpl<T, C>> ownScope) {
        return (List) bind(entries, ownScope, (Map) actionRegistrations(),
                           (Map) boundActions, ActionListenerDefImpl::buildBoundListener);
    }

    /**
     * Collects the listeners an owner declared across its own hooks, which is the scope a
     * reference from any of them resolves against first.
     *
     * @param hooks the owner's hook lists
     * @param <D> the category's def type
     *
     * @return the owner's declarations keyed by id
     */
    @SafeVarargs
    static <D> Map<String, D> ownScope(List<ListenerEntry<D>>... hooks) {
        Map<String, D> scope = new HashMap<>();
        for (List<ListenerEntry<D>> hook : hooks) {
            for (ListenerEntry<D> entry : hook) {
                if (!entry.isReference()) {
                    scope.putIfAbsent(entry.id(), entry.declared());
                }
            }
        }

        return scope;
    }

    /**
     * Returns the declarations among a hook's entries, for the passes that only look at what is new.
     *
     * @param entries the hook's entries
     * @param <D> the category's def type
     *
     * @return the declared defs, in declaration order
     */
    static <D> List<D> declaredOf(List<? extends ListenerEntry<? extends D>> entries) {
        List<D> declared = new ArrayList<>(entries.size());
        for (ListenerEntry<? extends D> entry : entries) {
            if (!entry.isReference()) {
                declared.add(entry.declared());
            }
        }

        return declared;
    }

    private static <D, B> List<B> bind(List<ListenerEntry<D>> entries, Map<String, D> ownScope,
                                       Map<String, D> registrations, Map<String, B> boundCache,
                                       Function<D, B> bind) {
        if (entries.isEmpty()) {
            return List.of();
        }

        List<B> bound = new ArrayList<>(entries.size());
        for (ListenerEntry<D> entry : entries) {
            if (!entry.isReference()) {
                bound.add(bind.apply(entry.declared()));
                continue;
            }

            // The owner's own declarations first, from any of its hooks, so one listener serves several.
            D own = ownScope.get(entry.id());
            if (own != null) {
                bound.add(bind.apply(own));
                continue;
            }

            D registered = registrations.get(entry.id());
            if (registered == null) {
                // The build rejected every reference that resolves to nothing, so this is a broken invariant.
                throw new TransfluxValidationException(
                    "Listener '" + entry.id() + "' resolved to nothing at bind time");
            }

            // Bound once, the same record at every attachment, which is what makes withAsync hold everywhere.
            bound.add(boundCache.computeIfAbsent(entry.id(), id -> bind.apply(registered)));
        }

        // Frozen, because a global hook's list is shared by every owner that does not disable it.
        return List.copyOf(bound);
    }
}
