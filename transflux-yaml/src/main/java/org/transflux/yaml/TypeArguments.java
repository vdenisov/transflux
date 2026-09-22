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

package org.transflux.yaml;

import org.apache.commons.lang3.reflect.TypeUtils;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.Map;

/**
 * Checks a class named by a document against the type arguments javac would have checked for a
 * Java host. Only what the class declares is visible: a type argument left as a type variable, a
 * generic class named raw, is not checked here and falls to the framework's runtime checks.
 */
final class TypeArguments {

    /**
     * What one type parameter of the position's interface must resolve to.
     *
     * @param type the expected class
     * @param contravariant whether a supertype of {@code type} is accepted, as for an entity
     *        argument, rather than {@code type} alone, as for a context
     */
    record Expected(Class<?> type, boolean contravariant) {

        static Expected superOf(Class<?> type) {
            return new Expected(type, true);
        }

        static Expected exactly(Class<?> type) {
            return new Expected(type, false);
        }
    }

    private TypeArguments() {
    }

    /**
     * Finds the first type argument that contradicts the position.
     *
     * @param type the class a document named, already known to implement {@code position}
     * @param position the generic interface the position takes
     * @param expected one entry per type parameter of {@code position}, in declaration order;
     *        {@code null} leaves that parameter unchecked, as a wildcard would in Java
     *
     * @return what is wrong, or {@code null} when nothing visible is
     */
    static String mismatch(Class<?> type, Class<?> position, Expected... expected) {
        TypeVariable<?>[] parameters = position.getTypeParameters();
        if (parameters.length != expected.length) {
            throw new IllegalArgumentException(position.getName() + " takes " + parameters.length
                + " type arguments, " + expected.length + " expected");
        }
        Map<TypeVariable<?>, Type> arguments = TypeUtils.getTypeArguments(type, position);
        for (int i = 0; i < parameters.length; i++) {
            if (expected[i] == null) {
                continue;
            }
            Class<?> declared = declaredClass(arguments == null ? null : arguments.get(parameters[i]));
            if (declared == null) {
                Loggers.YAML_BINDING.trace("Type argument left to the runtime check, class={}, parameter={}",
                    type.getName(), parameters[i].getName());
                continue;
            }
            Expected want = expected[i];
            boolean fits = want.contravariant() ? declared.isAssignableFrom(want.type()) : declared == want.type();
            if (!fits) {
                return "class " + type.getName() + " declares " + position.getSimpleName() + "'s "
                    + parameters[i].getName() + " as " + declared.getName() + ", where this position needs "
                    + want.type().getName() + (want.contravariant() ? " or a supertype of it" : "");
            }
        }
        return null;
    }

    private static Class<?> declaredClass(Type argument) {
        if (argument instanceof Class<?> declared) {
            return declared;
        }
        if (argument instanceof ParameterizedType parameterized) {
            return (Class<?>) parameterized.getRawType();
        }
        return null;
    }
}
