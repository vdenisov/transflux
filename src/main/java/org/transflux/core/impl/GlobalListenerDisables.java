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

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.transflux.core.Preconditions.requireNotBlank;

/**
 * Shared storage and filtering for the two disable forms every listener owner exposes - a state, a
 * transition, and an action.
 * <p>
 * One sink per owner, declared alongside the listener sink, borrowing the owner's configurer guard
 * rather than carrying one of its own. The deny-list is a set, so declaring an id twice is a no-op,
 * and the blanket form wins whatever order the two were called in.
 *
 * <p>An owner's own listeners are never filtered: attaching a listener to an owner is the consent,
 * and it is also how that owner replaces the observation it just turned off.
 */
final class GlobalListenerDisables {

    /**
     * The declaration made by an action that has no def to declare on - a member written as a bare
     * {@link org.transflux.core.action.Action} instance. Its owner is {@code null} because nothing
     * can reach the mutators: no def holds this instance.
     */
    private static final GlobalListenerDisables NONE = new GlobalListenerDisables(null);

    private final ConfigurableDefImpl owner;

    private final Set<String> ids = new LinkedHashSet<>();
    private boolean all;

    /**
     * Creates a sink for one owner def.
     *
     * @param owner the def whose configurer guard gates both forms
     */
    GlobalListenerDisables(ConfigurableDefImpl owner) {
        this.owner = owner;
    }

    /**
     * Returns the shared declaration that disables nothing.
     *
     * @return the empty declaration
     */
    static GlobalListenerDisables none() {
        return NONE;
    }

    void disable(String listenerId) {
        owner.requireConfigurerActive("disableGlobalListener");
        requireNotBlank(listenerId, "Listener ID");
        ids.add(listenerId);
    }

    void disableAll() {
        owner.requireConfigurerActive("disableAllGlobalListeners");
        all = true;
    }

    /**
     * Returns the ids named by the deny-list, in declaration order. Read by the build, which
     * rejects one that names no global listener of the owner's own category.
     *
     * @return an unmodifiable view of the named ids; possibly empty
     */
    Set<String> ids() {
        return Collections.unmodifiableSet(ids);
    }

    /**
     * Applies this owner's declaration to the state machine's global listeners of the owner's
     * category.
     *
     * @param globals the global listeners, in declaration order
     * @param id how to read a bound listener's id - the three bound listener records carry one but
     *           share no supertype
     * @param <L> the bound listener type of the owner's category
     *
     * @return the listeners that still get notified; the argument itself when nothing is disabled
     */
    <L> List<L> filter(List<L> globals, Function<L, String> id) {
        if (all) {
            return List.of();
        }
        if (ids.isEmpty() || globals.isEmpty()) {
            return globals;
        }
        return globals.stream().filter(l -> !ids.contains(id.apply(l))).toList();
    }
}
