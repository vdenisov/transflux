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
final class ThrowingUtils {

    private ThrowingUtils() {
        // utility class — no instances
    }

    /**
     * Reports whether a failure means the JVM itself can no longer be trusted - out of memory, an
     * internal error - as opposed to an {@code Error} raised on a healthy one, such as an
     * {@code AssertionError} or a {@code LinkageError}. Rollback is abandoned for the former and
     * runs for everything else.
     *
     * @param failure the failure to classify
     *
     * @return {@code true} if rollback handlers must not be driven after it
     */
    static boolean isFatal(Throwable failure) {
        return failure instanceof VirtualMachineError;
    }

    /**
     * Rethrows a failure the framework must not swallow. Every seam that catches and warns - an
     * observer, a compensation mid-drain, a route guard - calls this first, so a healthy-JVM
     * {@code Error} is contained exactly as an exception is while a fatal one still propagates.
     *
     * @param failure the failure just caught
     */
    static void rethrowIfFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) {
            throw fatal;
        }
    }

    /**
     * A {@link java.util.function.Supplier}-shaped lambda type that may throw any exception.
     *
     * @param <T> the supplied value type
     */
    @FunctionalInterface
    interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    /**
     * A {@link Runnable}-shaped lambda type that may throw any exception.
     */
    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }

    /**
     * Invokes {@code supplier} and returns its result; any thrown {@link Exception} is wrapped
     * in a {@link TransfluxValidationException} whose message is {@code errorMessage} plus the
     * failure's type, and whose cause is the original exception. The original's message is left on
     * the cause and kept out of this one: it is host text and may carry anything.
     *
     * @param supplier the lambda to invoke
     * @param errorMessage the prefix of the wrapping exception's message
     * @param <T> the supplied value type
     *
     * @return whatever {@code supplier} returns
     *
     * @throws TransfluxValidationException if {@code supplier} throws any exception
     */
    static <T> T sneakyGet(ThrowingSupplier<T> supplier, String errorMessage) {
        try {
            return supplier.get();
        } catch (Exception e) {
            throw new TransfluxValidationException(errorMessage + ": " + e.getClass().getName(), e);
        }
    }

    /**
     * Invokes {@code runnable}; any thrown {@link Exception} is wrapped in a
     * {@link TransfluxValidationException} whose message is {@code errorMessage} plus the
     * failure's type, and whose cause is the original exception.
     *
     * @param runnable the lambda to invoke
     * @param errorMessage the prefix of the wrapping exception's message
     *
     * @throws TransfluxValidationException if {@code runnable} throws any exception
     */
    static void sneakyRun(ThrowingRunnable runnable, String errorMessage) {
        try {
            runnable.run();
        } catch (Exception e) {
            throw new TransfluxValidationException(errorMessage + ": " + e.getClass().getName(), e);
        }
    }
}
