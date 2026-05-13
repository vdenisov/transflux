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

/**
 * Thrown when a transition is invoked from within a listener, operation, step, or
 * condition on the same {@link StateMachine} instance for the same entity.
 * <p>
 * Reentrancy is fail-fast in Transflux: a transition that is already executing
 * cannot trigger another transition on the same entity through the same state
 * machine. Triggering a transition on a different entity is permitted, provided
 * the host is prepared to handle the implications.
 *
 * <p>This exception type is part of the public API contract; the runtime guard
 * that raises it is wired in alongside the operation framework.
 */
public class TransfluxReentrancyException extends TransfluxException {

    public TransfluxReentrancyException(String message) {
        super(message);
    }

    public TransfluxReentrancyException(String message, Throwable cause) {
        super(message, cause);
    }
}
