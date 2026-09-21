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

package org.transflux.core.transition;

import org.transflux.core.exception.TransfluxValidationException;

import java.util.Optional;

import static org.transflux.core.Preconditions.requireNotBlank;
import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Outcome of a host-driven trigger evaluation — the result of {@code processEvent(...)} or
 * {@code processDataChange(...)}.
 * <p>
 * Unlike {@code transitionTo(...)} / {@code fire(...)}, which always attempt a transition, these
 * entry points evaluate the eligible triggers and may fire nothing at all. The two outcomes are
 * kept distinct:
 * <ul>
 * <li><b>Nothing matched</b> — {@link #fired()} is {@code false}, {@link #result()} is empty, and
 *     {@link #firedTriggerId()} is {@code null}. No transition ran.</li>
 * <li><b>A trigger fired</b> — {@link #fired()} is {@code true}, {@link #firedTriggerId()} names the
 *     trigger, and {@link #result()} carries the {@link TransitionResult} of the transition it
 *     drove.</li>
 * </ul>
 *
 * <p><b>{@link #fired()} does not imply success.</b> A trigger can match and fire while the
 * transition it drives still fails (for example, a transition pre-condition or post-condition not
 * holding). Inspect {@code result().get().isSuccess()} for the fired transition's outcome.
 *
 * @param <T> the type of entity involved in the transition
 */
public final class ProcessResult<T> {

    private final String firedTriggerId;
    private final TransitionResult<T> firedResult;

    private ProcessResult(String firedTriggerId, TransitionResult<T> firedResult) {
        this.firedTriggerId = firedTriggerId;
        this.firedResult = firedResult;
    }

    /**
     * Creates a result for the case where a trigger matched and its transition ran.
     *
     * @param firedTriggerId the id of the trigger that fired; never {@code null} or blank
     * @param firedResult the result of the transition the trigger drove; never {@code null}
     * @param <T> the entity type
     *
     * @return a fired result
     *
     * @throws TransfluxValidationException if {@code firedTriggerId} is {@code null}/blank or
     *         {@code firedResult} is {@code null}
     */
    public static <T> ProcessResult<T> fired(String firedTriggerId, TransitionResult<T> firedResult) {
        requireNotBlank(firedTriggerId, "Fired trigger ID");
        requireNotNull(firedResult, "Fired transition result");
        return new ProcessResult<>(firedTriggerId, firedResult);
    }

    /**
     * Creates a result for the case where no eligible trigger matched and nothing ran.
     *
     * @param <T> the entity type
     *
     * @return a not-fired result
     */
    public static <T> ProcessResult<T> notFired() {
        return new ProcessResult<>(null, null);
    }

    /**
     * Indicates whether a trigger matched and drove a transition.
     *
     * @return {@code true} if a trigger fired, {@code false} if nothing matched
     */
    public boolean fired() {
        return firedResult != null;
    }

    /**
     * Returns the result of the transition the fired trigger drove, if any.
     *
     * @return the transition result when a trigger fired; empty otherwise
     */
    public Optional<TransitionResult<T>> result() {
        return Optional.ofNullable(firedResult);
    }

    /**
     * Returns the id of the trigger that fired, or {@code null} when nothing matched.
     *
     * @return the fired trigger id, or {@code null}
     */
    public String firedTriggerId() {
        return firedTriggerId;
    }
}
