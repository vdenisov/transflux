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
 * A running transition was refused by the framework itself, as opposed to failing because host
 * code threw. It normally reaches the caller as {@code TransitionResult.getError()}, and is what a
 * compensation route declared for it matches; one raised where nothing may fail the transition -
 * notifying a listener - is logged instead.
 * <p>
 * A broken definition or a misdirected call is a {@link TransfluxValidationException} instead.
 */
public class TransfluxExecutionException extends TransfluxException {

    /**
     * @param message the detail message
     */
    public TransfluxExecutionException(String message) {
        super(message);
    }
}
