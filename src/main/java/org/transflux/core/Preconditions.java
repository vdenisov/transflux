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

import org.transflux.core.exception.TransfluxValidationException;

/**
 * Argument-precondition helpers that turn the repeated null / blank check patterns into
 * one-liners.
 * <p>
 * Designed for use via {@code import static org.transflux.core.Preconditions.*;}. Failed
 * checks raise {@link TransfluxValidationException} so callers don't need to import a
 * separate exception type.
 *
 * <p><b>Examples:</b>
 * <pre>{@code
 * import static org.transflux.core.Preconditions.*;
 *
 * void registerStep(String id, Step<T, C> step) {
 *     requireNotBlank(id, "Step ID");
 *     requireNotNull(step, "Step");
 *     // ...
 * }
 * }</pre>
 */
public final class Preconditions {

    private Preconditions() {
        // utility class — no instances
    }

    /**
     * Throws {@link TransfluxValidationException} if {@code value} is {@code null}; otherwise
     * returns it unchanged so the helper can be inlined in field assignments.
     *
     * @param value the value to check
     * @param fieldName the human-readable field name, used in the exception message
     * @param <T> the value type
     *
     * @return {@code value}, guaranteed non-null
     *
     * @throws TransfluxValidationException if {@code value} is {@code null}
     */
    public static <T> T requireNotNull(T value, String fieldName) {
        if (value == null) {
            throw new TransfluxValidationException(fieldName + " cannot be null");
        }
        return value;
    }

    /**
     * Throws {@link TransfluxValidationException} if {@code value} is {@code null} or blank;
     * otherwise returns it unchanged.
     *
     * @param value the string to check
     * @param fieldName the human-readable field name, used in the exception message
     *
     * @return {@code value}, guaranteed non-null and non-blank
     *
     * @throws TransfluxValidationException if {@code value} is {@code null} or blank
     */
    public static String requireNotBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new TransfluxValidationException(fieldName + " cannot be null or blank");
        }
        return value;
    }
}
