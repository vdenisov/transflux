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

import org.transflux.core.action.Compensation;
import org.transflux.core.transition.ActionPath;

import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Runtime binder that pairs a {@link Compensation} with the qualified {@link ActionPath} of
 * the step whose effects it rolls back. The path captures both the step's local id and any
 * enclosing nested-operation ids, so compensation entries surface in
 * {@link org.transflux.core.transition.TransitionResult#getCompensatedPath()} under the
 * same qualified-path form as executed steps.
 *
 * <p>The context captured alongside the callback is the one the action itself ran against - the
 * mapped child context where the call site maps, the enclosing one otherwise. The drain hands it
 * back at rollback time, which is what makes {@link Compensation#compensate(Object, Object)}'s
 * contract - the same references {@code execute} saw - hold at a mapped call site.
 *
 * @param path the qualified action path the compensation was registered against; never
 *             {@code null}
 * @param compensation the rollback callback; never {@code null}
 * @param context the context the compensated action ran against; may be {@code null}
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
record BoundCompensation<T, C>(ActionPath path, Compensation<T, C> compensation, C context) {

    BoundCompensation {
        requireNotNull(path, "Bound compensation action path");
        requireNotNull(compensation, "Bound compensation");
    }
}
