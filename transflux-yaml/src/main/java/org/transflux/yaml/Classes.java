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
        Class<?> type;

        try {
            type = Class.forName(name, false, classLoader);
        } catch (ClassNotFoundException | LinkageError e) {
            throw map.error(at, "class " + name + " cannot be loaded", e);
        }

        if (position != null && !position.isAssignableFrom(type)) {
            throw map.error(at, "class " + name + " is not a " + position.getName());
        }

        return type;
    }

    /**
     * Creates an instance of the class a key names.
     *
     * @param map the mapping holding the key
     * @param key the key whose value is a class name
     * @param position what the class must be assignable to at this position
     * @param <X> the position's type
     *
     * @return the instance the component factory created
     *
     * @throws DefinitionLoadException when the class cannot be loaded, is not a {@code position}, or
     *         the factory fails or returns something else
     */
    <X> X instantiate(NodeMap map, String key, Class<X> position) {
        Class<?> type = requiredClass(map, key, position);
        Node at = map.requiredNode(key);
        Object instance;

        try {
            instance = componentFactory.create(type);
        } catch (TransfluxValidationException e) {
            throw map.error(at, e.getMessage(), e);
        } catch (RuntimeException e) {
            throw map.error(at, "the component factory failed for class " + type.getName() + ": "
                + e.getClass().getName(), e);
        }

        if (!type.isInstance(instance)) {
            throw map.error(at, "the component factory returned "
                + (instance == null ? "null" : "a " + instance.getClass().getName()) + " for class " + type.getName());
        }

        Loggers.YAML_BINDING.trace("Component instantiated, class={}", type.getName());

        return position.cast(instance);
    }
}
