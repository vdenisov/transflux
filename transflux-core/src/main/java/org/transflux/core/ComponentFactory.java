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

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;

/**
 * Turns a class a definition names into an instance of it. Every component a definition names by
 * class, rather than supplying as an object, is created through one of these; a host supplies its
 * own to hand out instances its container manages.
 */
@FunctionalInterface
public interface ComponentFactory {

    /**
     * Creates an instance of a component class. The caller checks the result against the position
     * the class was named at, so a factory need not.
     *
     * @param type the class a definition named
     *
     * @return an instance of {@code type}, never {@code null}
     *
     * @throws TransfluxValidationException when no instance can be created
     */
    Object create(Class<?> type);

    /**
     * The default factory: invokes the class's accessible no-argument constructor.
     *
     * @return the reflective factory
     */
    static ComponentFactory reflective() {
        return type -> {
            if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
                throw new TransfluxValidationException(
                    "Cannot instantiate " + type.getName() + ": it is abstract; name a concrete class");
            }

            Constructor<?> constructor;
            try {
                constructor = type.getDeclaredConstructor();
            } catch (NoSuchMethodException e) {
                throw new TransfluxValidationException(
                    "Cannot instantiate " + type.getName() + ": it declares no no-argument constructor", e);
            }

            try {
                return constructor.newInstance();
            } catch (IllegalAccessException e) {
                throw new TransfluxValidationException(
                    "Cannot instantiate " + type.getName()
                        + ": its no-argument constructor is not accessible; make the class and the constructor public",
                    e);
            } catch (InvocationTargetException e) {
                throw new TransfluxValidationException(
                    "Cannot instantiate " + type.getName() + ": its constructor threw " + e.getCause().getClass().getName(),
                    e.getCause());
            } catch (LinkageError e) {
                // A retry's NoClassDefFoundError wraps a record of the first failure, not the failure itself.
                Throwable failure = e;
                while (failure.getCause() != null) {
                    failure = failure.getCause();
                }
                throw new TransfluxValidationException(
                    "Cannot instantiate " + type.getName() + ": its static initialisation failed with "
                        + failure.getClass().getName(),
                    failure);
            } catch (InstantiationException e) {
                throw new TransfluxValidationException("Cannot instantiate " + type.getName(), e);
            }
        };
    }
}
