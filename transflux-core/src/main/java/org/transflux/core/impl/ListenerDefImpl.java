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

import org.transflux.core.action.AsyncRejectionPolicy;
import org.transflux.core.exception.TransfluxValidationException;

import static org.transflux.core.Preconditions.requireNotNull;
import static org.transflux.core.impl.ValidationUtils.warnIfSet;

/**
 * Base for the three listener def impls, carrying what they share beyond identity: whether the
 * listener runs on the executor, and what a refused submission does.
 *
 * @param <SELF> the concrete subclass type, used for covariant fluent returns
 */
abstract class ListenerDefImpl<SELF extends ListenerDefImpl<SELF>> extends IdentifiedDefImpl<SELF> {

    private final Class<?> contextType;
    private AsyncRejectionPolicy async;

    protected ListenerDefImpl(String id, String kind, String idLabel, Class<?> contextType) {
        super(id, kind, idLabel);
        this.contextType = requireNotNull(contextType, "Listener context type");
    }

    /**
     * Reports whether a listener instance has been supplied through {@code using(...)}.
     *
     * @return {@code true} once one has been set
     */
    abstract boolean hasListener();

    /**
     * Names this listener's category, as rejections name it.
     *
     * @return {@code state}, {@code transition} or {@code action}
     */
    abstract String category();

    /**
     * Rejects a def that declared no listener, at the point the def is filed rather than when
     * something eventually binds it.
     *
     * @throws TransfluxValidationException if no listener was supplied
     */
    final void requireListenerDeclared() {
        if (!hasListener()) {
            throw new TransfluxValidationException(
                Character.toUpperCase(defLabel().charAt(0)) + defLabel().substring(1)
                    + " declares no listener; call using(...) in its configurer");
        }
    }

    /**
     * Returns the context class this listener was declared against.
     *
     * @return the context type; never {@code null}
     */
    public Class<?> getContextType() {
        return contextType;
    }

    /**
     * Marks the listener async, losing a refused notification with a warning.
     *
     * @return this def, for chaining
     */
    public SELF withAsync() {
        return withAsync(AsyncRejectionPolicy.DROP);
    }

    /**
     * Marks the listener async with the given answer to a refused submission.
     *
     * @param policy what a refused submission does; never {@code null} or {@code FAIL}
     *
     * @return this def, for chaining
     *
     * @throws TransfluxValidationException if {@code policy} is {@code null} or {@code FAIL}
     */
    public SELF withAsync(AsyncRejectionPolicy policy) {
        requireConfigurerActive("withAsync");
        requireNotNull(policy, "Async rejection policy");
        // At a terminal hook the applier has committed, so there is no transition left to fail.
        if (policy == AsyncRejectionPolicy.FAIL) {
            throw new TransfluxValidationException(
                "Async rejection policy FAIL is not available on " + defLabel()
                    + "; a listener cannot fail the transition it observes, so choose DROP, BLOCK"
                    + " or CALLER_RUNS");
        }
        warnIfSet(this.async != null, "Async listener policy", defLabel(), Loggers.BUILD_VALIDATION);

        this.async = policy;
        return self();
    }

    /**
     * Returns the policy the listener was made async with.
     *
     * @return the policy, or {@code null} when the listener runs synchronously
     */
    final AsyncRejectionPolicy getAsync() {
        return async;
    }
}
