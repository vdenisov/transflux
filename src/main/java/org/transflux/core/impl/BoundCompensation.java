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

import org.transflux.core.operation.*;
import org.transflux.core.transition.StepPath;

import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Runtime binder that pairs a {@link Compensation} with the qualified {@link StepPath} of
 * the step whose effects it rolls back. The path captures both the step's local id and any
 * enclosing nested-operation ids, so compensation entries surface in
 * {@link org.transflux.core.transition.TransitionResult#getCompensatedStepIds()} under the
 * same qualified-path form as executed steps.
 *
 * @param path the qualified step path the compensation was registered against; never
 *             {@code null}
 * @param compensation the rollback callback; never {@code null}
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
record BoundCompensation<T, C>(StepPath path, Compensation<T, C> compensation) {

    public BoundCompensation {
        requireNotNull(path, "Bound compensation step path");
        requireNotNull(compensation, "Bound compensation");
    }
}
