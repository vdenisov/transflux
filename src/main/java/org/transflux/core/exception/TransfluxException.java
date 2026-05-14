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

package org.transflux.core.exception;

/**
 * Base class for all unchecked exceptions thrown by Transflux.
 * <p>
 * All framework-specific exceptions extend this type, allowing callers to catch
 * Transflux-originated failures in a single {@code catch} block while still
 * being able to handle individual subtypes when finer discrimination is needed.
 *
 * <p>Subtypes:
 * <ul>
 *   <li>{@link TransfluxValidationException} — definition, builder, or lookup errors
 *       raised synchronously when the framework's invariants are violated.</li>
 *   <li>{@link TransfluxReentrancyException} — reentrant transition attempts on the
 *       same state machine instance for the same entity.</li>
 * </ul>
 */
public class TransfluxException extends RuntimeException {

    public TransfluxException(String message) {
        super(message);
    }

    public TransfluxException(String message, Throwable cause) {
        super(message, cause);
    }
}
