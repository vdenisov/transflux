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

import org.transflux.core.*;

import org.transflux.core.exception.TransfluxValidationException;

/**
 * Static helpers that wrap a checked- or unchecked-exception-throwing call in a
 * {@link TransfluxValidationException}.
 * <p>
 * Designed for use via {@code import static org.transflux.core.impl.ThrowingUtils.*;}. Any
 * {@link Exception} thrown by the supplied lambda is rethrown as
 * {@code TransfluxValidationException} with the supplied {@code errorMessage} prefix and the
 * original exception as the cause.
 *
 * <p><b>Examples:</b>
 * <pre>{@code
 * import static org.transflux.core.impl.ThrowingUtils.*;
 *
 * byte[] digest = sneakyGet(
 *     () -> MessageDigest.getInstance("SHA-256").digest(input),
 *     "SHA-256 algorithm unavailable");
 *
 * Expression parsed = sneakyGet(
 *     () -> parser.parseExpression(expression),
 *     "Invalid SpEL expression '" + expression + "'");
 * }</pre>
 */
public final class ThrowingUtils {

    private ThrowingUtils() {
        // utility class — no instances
    }

    /**
     * A {@link java.util.function.Supplier}-shaped lambda type that may throw any exception.
     *
     * @param <T> the supplied value type
     */
    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    /**
     * A {@link Runnable}-shaped lambda type that may throw any exception.
     */
    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    /**
     * Invokes {@code supplier} and returns its result; any thrown {@link Exception} is wrapped
     * in a {@link TransfluxValidationException} whose message starts with {@code errorMessage}
     * and whose cause is the original exception.
     *
     * @param supplier the lambda to invoke
     * @param errorMessage the prefix of the wrapping exception's message
     * @param <T> the supplied value type
     *
     * @return whatever {@code supplier} returns
     *
     * @throws TransfluxValidationException if {@code supplier} throws any exception
     */
    public static <T> T sneakyGet(ThrowingSupplier<T> supplier, String errorMessage) {
        try {
            return supplier.get();
        } catch (Exception e) {
            throw new TransfluxValidationException(errorMessage + ": " + e.getMessage(), e);
        }
    }

    /**
     * Invokes {@code runnable}; any thrown {@link Exception} is wrapped in a
     * {@link TransfluxValidationException} whose message starts with {@code errorMessage} and
     * whose cause is the original exception.
     *
     * @param runnable the lambda to invoke
     * @param errorMessage the prefix of the wrapping exception's message
     *
     * @throws TransfluxValidationException if {@code runnable} throws any exception
     */
    public static void sneakyRun(ThrowingRunnable runnable, String errorMessage) {
        try {
            runnable.run();
        } catch (Exception e) {
            throw new TransfluxValidationException(errorMessage + ": " + e.getMessage(), e);
        }
    }
}
