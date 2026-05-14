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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import static org.transflux.core.ValidationUtils.requireNotBlank;
import static org.transflux.core.ValidationUtils.requireNotNull;

/**
 * Derives stable, deterministic ids for inline expression-based conditions whose authoring
 * form omits an explicit id.
 * <p>
 * The derivation rule is:
 * <ol>
 *     <li>Concatenate {@code path + "::" + expression}.</li>
 *     <li>Hash the concatenation with SHA-256.</li>
 *     <li>Encode the digest as base64-url without padding.</li>
 *     <li>Truncate to twelve characters.</li>
 *     <li>Prefix with {@code "expr-"}.</li>
 * </ol>
 * The same {@code (expression, path)} pair always yields the same id across JVM runs;
 * varying either input produces a different id.
 */
final class ExpressionIdDerivation {

    private static final String PREFIX = "expr-";
    private static final int TRUNCATED_LENGTH = 12;

    private ExpressionIdDerivation() {
        // utility class — no instances
    }

    /**
     * Returns a deterministic id for the given expression at the given location.
     *
     * @param expression the SpEL expression text; never {@code null} or blank
     * @param path slash-separated location of the descriptor within the enclosing state
     *             machine (for example {@code "transition[order->shipped]/preCondition[0]"});
     *             never {@code null}
     *
     * @return an id of the form {@code "expr-<12-char-hash>"}
     *
     * @throws TransfluxValidationException if {@code expression} is {@code null} or blank
     */
    static String deriveId(String expression, String path) {
        requireNotBlank(expression, "Expression");
        requireNotNull(path, "Path");

        byte[] input = (path + "::" + expression).getBytes(StandardCharsets.UTF_8);
        byte[] digest = ThrowingUtils.sneakyGet(() -> MessageDigest.getInstance("SHA-256").digest(input),
                                                "SHA-256 algorithm unavailable");

        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        return PREFIX + encoded.substring(0, TRUNCATED_LENGTH);
    }
}
