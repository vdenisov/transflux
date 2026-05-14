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

package org.transflux.core.operation;

import static org.transflux.core.ValidationUtils.requireNotBlank;
import static org.transflux.core.ValidationUtils.requireNotNull;

/**
 * Runtime binder that pairs a {@link Compensation} with the id of the step whose effects it
 * rolls back.
 *
 * <p>This is framework-internal infrastructure; user code should not construct or inspect
 * bound compensations directly.
 *
 * @param stepId the id of the step the compensation was registered against; never {@code null}
 *               or blank
 * @param compensation the rollback callback; never {@code null}
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
public record BoundCompensation<T, C>(String stepId, Compensation<T, C> compensation) {

    public BoundCompensation {
        requireNotBlank(stepId, "Bound compensation step ID");
        requireNotNull(compensation, "Bound compensation");
    }
}
