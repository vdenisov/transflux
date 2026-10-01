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

import org.transflux.core.action.AsyncRejectionPolicy;
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
 * @param compensationRouter the compensation table declared on the action's def, or {@code null}
 *                           when the def declared nothing. What the action's own
 *                           {@link Action#getCompensation(Object, Object)} returns is folded in as
 *                           the fallback at push time, unless the def declared one of its own
 * @param asyncRejectionPolicy what a fork of this action does when the executor cannot take it,
 *                             or {@code null} to take the state machine's own default
 * @param disabledGlobals which of the state-machine-wide action listeners this action turned off;
 *                        never {@code null}. It rides here rather than being folded into
 *                        {@code listeners} because the globals are appended at notification time
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
record BoundAction<T, C>(String id, Action<? super T, C> action, ActionKind kind,
                         BoundActionListeners<T, C> listeners,
                         BoundCompensationRouter<T, C> compensationRouter,
                         AsyncRejectionPolicy asyncRejectionPolicy,
                         GlobalListenerDisables disabledGlobals) {

    BoundAction {
        requireNotBlank(id, "Bound action ID");
        requireNotNull(action, "Bound action");
        requireNotNull(kind, "Bound action kind");
        requireNotNull(listeners, "Bound action listeners");
        requireNotNull(disabledGlobals, "Bound action global-listener disables");
        // The declaration's frozen copy, which holds no reference back to the def that made it.
        disabledGlobals = disabledGlobals.frozen();
    }

    /**
     * Convenience factory for an action nothing observes and nothing declared a compensation for -
     * the shape produced wherever no def exists to carry either. It disables no global listener
     * for the same reason: there is no def the declaration could have been written on.
     *
     * @param id the action id
     * @param action the action executable
     * @param kind the authoring form
     * @param <T> the entity type
     * @param <C> the context type
     *
     * @return a fresh bound action with no listeners and no declared compensation
     */
    static <T, C> BoundAction<T, C> of(String id, Action<? super T, C> action, ActionKind kind) {
        return new BoundAction<>(id, action, kind, BoundActionListeners.none(), null, null,
                                 GlobalListenerDisables.none());
    }

    /**
     * Convenience factory for a def-backed action.
     *
     * @param id the action id
     * @param action the action executable
     * @param kind the authoring form
     * @param listeners the action's own listeners
     * @param compensationRouter the compensation table declared on the def, or {@code null}
     * @param asyncRejectionPolicy the policy declared on the def, or {@code null}
     * @param disabledGlobals what the def turned off among the global action listeners
     * @param <T> the entity type
     * @param <C> the context type
     *
     * @return a fresh bound action
     */
    static <T, C> BoundAction<T, C> of(String id, Action<? super T, C> action, ActionKind kind,
                                       BoundActionListeners<T, C> listeners,
                                       BoundCompensationRouter<T, C> compensationRouter,
                                       AsyncRejectionPolicy asyncRejectionPolicy,
                                       GlobalListenerDisables disabledGlobals) {
        return new BoundAction<>(id, action, kind, listeners, compensationRouter,
                                 asyncRejectionPolicy, disabledGlobals);
    }
}
