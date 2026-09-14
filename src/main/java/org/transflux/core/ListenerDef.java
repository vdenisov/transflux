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

package org.transflux.core;

import org.transflux.core.action.AsyncRejectionPolicy;
import org.transflux.core.action.ForkableContext;
import org.transflux.core.exception.TransfluxValidationException;

/**
 * What every listener definition declares, whichever category it observes: an identity, the
 * listener itself, and whether it runs on the executor.
 * <p>
 * Self-typed so a chain keeps the concrete def type, including in a generic helper that configures
 * a listener of any category.
 *
 * @param <L> the listener contract this def attaches
 * @param <SELF> the concrete def type, returned from every setter
 */
public interface ListenerDef<L, SELF extends ListenerDef<L, SELF>> extends Identifiable {

    /**
     * Returns this listener's identifier.
     *
     * @return the listener id; never {@code null} or blank
     */
    @Override
    String getId();

    /**
     * Returns this listener's optional human-readable name.
     *
     * @return the name, or {@code null} if none was set
     */
    String getName();

    /**
     * Returns this listener's optional description.
     *
     * @return the description, or {@code null} if none was set
     */
    String getDescription();

    /**
     * Sets the human-readable name for this listener.
     *
     * @param name the human-readable name
     *
     * @return this listener def for chaining
     */
    SELF withName(String name);

    /**
     * Sets the description for this listener.
     *
     * @param description the description
     *
     * @return this listener def for chaining
     */
    SELF withDescription(String description);

    /**
     * Attaches a pre-built listener instance; re-declaring replaces the previous one.
     *
     * @param listener the listener instance; never {@code null}
     *
     * @return this listener def for chaining
     *
     * @throws TransfluxValidationException if {@code listener} is {@code null}
     */
    SELF using(L listener);

    /**
     * Runs this listener on the state machine's executor rather than in line, so the transition
     * does not wait for it; a notification the executor refuses is lost with a warning.
     * <p>
     * Everything else a listener promises still holds: it observes, and whatever it throws is logged
     * and swallowed. Three things change. Notifications are submitted in declaration order but run
     * in no guaranteed order - an {@code onComplete} may be handled before the same transition's
     * {@code onStart}. The context is {@link ForkableContext#fork() forked} for each notification
     * when it implements {@link ForkableContext}, and shared otherwise; the entity is always shared.
     * And the listener may not drive the state machine that notified it, for any entity. A
     * definition declaring an async listener builds its own pool.
     *
     * @return this listener def for chaining
     */
    SELF withAsync();

    /**
     * {@link #withAsync()} with a chosen answer to a refused submission: {@code DROP} loses it with a
     * warning, {@code BLOCK} waits for capacity, {@code CALLER_RUNS} handles it on the notifying
     * thread. {@code BLOCK} needs the pool the framework builds and fails the build against a
     * host-supplied executor. After the state machine is closed it loses the notification, unless
     * the notifying thread is itself running a branch of this state machine - an action listener on
     * a branch still finishing - where it runs inline instead.
     *
     * @param policy what a refused submission does; never {@code null}
     *
     * @return this listener def for chaining
     *
     * @throws TransfluxValidationException if {@code policy} is {@code null} or {@code FAIL}, which
     *         has no meaning for an observer
     */
    SELF withAsync(AsyncRejectionPolicy policy);
}
