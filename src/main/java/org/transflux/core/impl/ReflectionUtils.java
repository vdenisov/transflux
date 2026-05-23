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

import java.lang.reflect.InvocationTargetException;

/**
 * Static helpers for the reflective patterns repeated across the def builders.
 * <p>
 * Designed for use via {@code import static org.transflux.core.impl.ReflectionUtils.*;}. Reflective
 * failures surface as {@link TransfluxValidationException} with a message that names the
 * failing class and distinguishes "no no-arg constructor" from "constructor exists but
 * instantiation failed".
 *
 * <p>This is framework-internal infrastructure used by Transflux's own def builders; user
 * code should not invoke it directly.
 */
final class ReflectionUtils {

    private ReflectionUtils() {
        // utility class — no instances
    }

    /**
     * Instantiates {@code type} through its public no-arg constructor.
     * <p>
     * The {@code typeName} argument is used in the failure message in two forms: capitalized
     * for the "no no-arg constructor" message ("Step class 'X' has no accessible no-arg
     * constructor") and lower-cased for the "instantiation failed" message ("Failed to
     * instantiate step class 'X'"). Supply the capitalized form (e.g. {@code "Step"},
     * {@code "Operation"}, {@code "Condition"}).
     *
     * @param type the class to instantiate
     * @param typeName the human-readable component type name (e.g. {@code "Step"}), used in
     *                 the failure message
     * @param <T> the instantiated type
     *
     * @return a new instance of {@code type}
     *
     * @throws TransfluxValidationException if {@code type} has no accessible no-arg
     *         constructor, or if instantiation fails for any other reason
     */
    public static <T> T instantiateNoArg(Class<? extends T> type, String typeName) {
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (NoSuchMethodException e) {
            throw new TransfluxValidationException(
                typeName + " class '" + type.getName() + "' has no accessible no-arg constructor", e);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new TransfluxValidationException(
                "Failed to instantiate " + typeName.toLowerCase() + " class '" + type.getName() + "'", e);
        }
    }
}
