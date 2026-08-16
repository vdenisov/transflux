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

import static org.transflux.core.Preconditions.requireNotBlank;
import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Runtime binder that pairs a pure {@link Action} with framework-owned identity and the form it
 * was authored in.
 *
 * @param id the framework-owned action id; never {@code null} or blank
 * @param action the bound {@link Action} executable; never {@code null}
 * @param kind the authoring form, carried for diagnostics only; never {@code null}
 * @param listeners the action's own listeners, in declaration order; never {@code null}. They ride
 *                  on the bound record rather than on the call site, so an action is observed
 *                  wherever it runs
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
record BoundAction<T, C>(String id, Action<T, C> action, ActionKind kind,
                         BoundActionListeners<T, C> listeners) {

    BoundAction {
        requireNotBlank(id, "Bound action ID");
        requireNotNull(action, "Bound action");
        requireNotNull(kind, "Bound action kind");
        requireNotNull(listeners, "Bound action listeners");
    }

    /**
     * Convenience factory for an action nothing observes - the shape produced wherever no def
     * exists to carry a listener.
     *
     * @param id the action id
     * @param action the action executable
     * @param kind the authoring form
     * @param <T> the entity type
     * @param <C> the context type
     *
     * @return a fresh bound action with no listeners
     */
    static <T, C> BoundAction<T, C> of(String id, Action<T, C> action, ActionKind kind) {
        return new BoundAction<>(id, action, kind, BoundActionListeners.none());
    }

    /**
     * Convenience factory equivalent to the canonical constructor.
     *
     * @param id the action id
     * @param action the action executable
     * @param kind the authoring form
     * @param listeners the action's own listeners
     * @param <T> the entity type
     * @param <C> the context type
     *
     * @return a fresh bound action
     */
    static <T, C> BoundAction<T, C> of(String id, Action<T, C> action, ActionKind kind,
                                       BoundActionListeners<T, C> listeners) {
        return new BoundAction<>(id, action, kind, listeners);
    }
}
