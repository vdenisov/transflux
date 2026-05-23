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

import org.slf4j.Logger;

/**
 * Internal helpers that complement {@link org.transflux.core.Preconditions} with the
 * impl-only "last writer wins with a warning" setter pattern used across the {@code *DefImpl}
 * classes. Argument-precondition checks ({@code requireNotNull}, {@code requireNotBlank})
 * live on the public {@code Preconditions} class so they're reachable from public-API types
 * such as {@link org.transflux.core.condition.ConditionDescriptor}.
 */
final class ValidationUtils {

    private ValidationUtils() {
        // utility class — no instances
    }

    /**
     * Logs a warning if {@code current} is already set (non-null) so callers can apply the
     * "last writer wins with a warning" pattern in one line. Does nothing when {@code current}
     * is {@code null}.
     *
     * @param current the current field value
     * @param incoming the incoming value that will replace it
     * @param fieldName the human-readable field name, used in the log message
     * @param log the logger to emit the warning on
     * @param <T> the value type
     */
    static <T> void warnIfSet(T current, T incoming, String fieldName, Logger log) {
        if (current != null) {
            log.warn("{} is already defined: {}. Overriding previous value with {}",
                    fieldName, current, incoming);
        }
    }
}
