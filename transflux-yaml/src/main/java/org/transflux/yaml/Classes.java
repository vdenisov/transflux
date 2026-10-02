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

import org.transflux.core.ComponentFactory;
import org.transflux.core.exception.TransfluxValidationException;
import org.yaml.snakeyaml.nodes.Node;

import java.lang.reflect.MalformedParameterizedTypeException;

/**
 * The one way a document's class names become classes and instances: through the host's class
 * loader and component factory, with every failure reported at the line that named the class.
 */
final class Classes {

    private final ClassLoader classLoader;
    private final ComponentFactory componentFactory;

    Classes(ClassLoader classLoader, ComponentFactory componentFactory) {
        this.classLoader = classLoader;
        this.componentFactory = componentFactory;
    }

    /**
     * @return the class loader class names are loaded through
     */
    ClassLoader classLoader() {
        return classLoader;
    }

    /**
     * Loads the class a key names.
     *
     * @param map the mapping holding the key
     * @param key the key whose value is a class name
     * @param position what the class must be assignable to at this position; {@code null} for any
     *
     * @return the class, not initialised
     *
     * @throws DefinitionLoadException when the key is absent, the class cannot be loaded, or it is
     *         not a {@code position}
     */
    Class<?> requiredClass(NodeMap map, String key, Class<?> position) {
        String name = map.requiredString(key);
        Node at = map.requiredNode(key);
        String candidate = name;
        ClassNotFoundException miss = null;
        Class<?> type = null;
        while (type == null) {
            try {
                type = Class.forName(candidate, false, classLoader);
            } catch (ClassNotFoundException e) {
                miss = miss == null ? e : miss;
                // Java source spells a nested class with dots, where its binary name has $; turn them from the right.
                int dot = candidate.lastIndexOf('.');
                if (dot < 0) {
                    throw map.error(at, "class " + name + " cannot be loaded", miss);
                }
                candidate = candidate.substring(0, dot) + '$' + candidate.substring(dot + 1);
            } catch (LinkageError e) {
                throw map.error(at, "class " + name + " cannot be loaded", e);
            }
        }
        if (!candidate.equals(name)) {
            Loggers.YAML_BINDING.debug("Class resolved as nested, name={}, binaryName={}", name, candidate);
        }

        if (position != null && !position.isAssignableFrom(type)) {
            throw map.error(at, "class " + name + " is not a subtype of " + position.getName());
        }

        return type;
    }

    /**
     * Loads the class a key names, when the key is present.
     *
     * @param map the mapping holding the key
     * @param key the key whose value is a class name
     * @param position what the class must be assignable to at this position; {@code null} for any
     *
     * @return the class, or {@code null} when the key is absent
     *
     * @throws DefinitionLoadException when the class cannot be loaded or is not a {@code position}
     */
    Class<?> optionalClass(NodeMap map, String key, Class<?> position) {
        return map.optionalNode(key) == null ? null : requiredClass(map, key, position);
    }

    /**
     * Creates an instance of the class a key names.
     *
     * @param map the mapping holding the key
     * @param key the key whose value is a class name
     * @param position what the class must be assignable to at this position
     * @param expected what the class's type arguments on {@code position} must be, one per type
     *        parameter; none to leave them unchecked
     * @param <X> the position's type
     *
     * @return the instance the component factory created
     *
     * @throws DefinitionLoadException when the class cannot be loaded, is not a {@code position},
     *         declares type arguments the position refuses, refers to a class that cannot be
     *         loaded, or the factory fails or returns something unusable at the position
     */
    <X> X instantiate(NodeMap map, String key, Class<X> position, TypeArguments.Expected... expected) {
        return instantiate(map, key, requiredClass(map, key, position), position, expected);
    }

    /**
     * Creates an instance of a class already loaded from a key.
     *
     * @param map the mapping holding the key
     * @param key the key that named {@code type}
     * @param type the class, already known to be a {@code position}
     * @param position the interface the instance is used as
     * @param expected what the class's type arguments on {@code position} must be, one per type
     *        parameter; none to leave them unchecked
     * @param <X> the position's type
     *
     * @return the instance the component factory created
     *
     * @throws DefinitionLoadException when the class declares type arguments the position refuses,
     *         refers to a class that cannot be loaded, or the factory fails or returns something
     *         unusable at the position
     */
    <X> X instantiate(NodeMap map, String key, Class<?> type, Class<X> position, TypeArguments.Expected... expected) {
        Node at = map.requiredNode(key);
        if (expected.length > 0) {
            String mismatch;
            try {
                mismatch = TypeArguments.mismatch(type, position, expected);
            } catch (TypeNotPresentException | MalformedParameterizedTypeException | LinkageError e) {
                throw unloadable(map, at, type, e);
            }
            if (mismatch != null) {
                throw map.error(at, mismatch);
            }
        }

        Object instance;
        try {
            instance = componentFactory.create(type);
        } catch (TransfluxValidationException e) {
            throw map.error(at, e.getMessage(), e);
        } catch (RuntimeException e) {
            throw map.error(at, "the component factory failed for class " + type.getName() + ": "
                + e.getClass().getName(), e);
        }

        // The position, not the class: a container may hand back a proxy implementing the same interfaces.
        if (!position.isInstance(instance)) {
            throw map.error(at, "the component factory returned "
                + (instance == null ? "null" : "a " + instance.getClass().getName()) + " for class " + type.getName());
        }

        Loggers.YAML_BINDING.trace("Component instantiated, class={}", type.getName());

        return position.cast(instance);
    }

    /**
     * @param map the mapping holding the key
     * @param at the key's value
     * @param type the class it named
     * @param failure what reflection threw resolving a type argument the class declares
     *
     * @return the failure, at the line that named the class
     */
    private static DefinitionLoadException unloadable(NodeMap map, Node at, Class<?> type, Throwable failure) {
        // Reflection resolves a type argument lazily, so an absent optional dependency surfaces here.
        return map.error(at, "class " + type.getName() + " refers to a class that cannot be loaded (" + failure + ")",
            failure);
    }
}
