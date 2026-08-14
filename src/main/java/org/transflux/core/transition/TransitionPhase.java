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

/**
 * Identifies which point of a transition's execution a {@link TransitionListener} was notified
 * for.
 * <p>
 * A listener attached through a single hook already knows its own phase; the discriminator
 * matters to listeners registered against every transition, which observe all three.
 *
 * <p>{@link #COMPLETE} and {@link #ERROR} partition the outcomes: exactly one of them follows
 * every {@link #START}, and neither occurs without one.
 */
public enum TransitionPhase {

    /** The transition passed its pre-conditions and is about to run. */
    START,

    /** The transition succeeded and its new state has been committed. */
    COMPLETE,

    /**
     * The transition failed and its new state was not committed. Rollback has already finished by
     * the time the notification arrives; {@link TransitionResult#getCompensatedPath()} reports
     * which compensations ran, and is empty when none did.
     */
    ERROR
}
