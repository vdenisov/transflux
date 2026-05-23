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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.transflux.core.Preconditions.requireNotBlank;
import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Default {@link Registry} implementation. Maintains an insertion-ordered map of
 * {@link Component}s and exposes the parent-chain walk via {@link #resolve(String)}.
 *
 * @param <T> the entity type the surrounding state machine manages
 */
final class RegistryImpl<T> implements Registry<T> {

    private final Map<String, Component<T>> components = new LinkedHashMap<>();
    private final Registry<T> parent;

    /**
     * Creates a parentless root registry. Equivalent to {@code new RegistryImpl<>(null)}.
     */
    RegistryImpl() {
        this(null);
    }

    /**
     * Creates a registry with the supplied parent. {@code null} means parentless (root).
     *
     * @param parent the parent registry, or {@code null}
     */
    RegistryImpl(Registry<T> parent) {
        this.parent = parent;
    }

    /**
     * Registers {@code component} under its id. The component is validated via
     * {@link Component#validate()} before insertion. Re-registering the same component
     * instance under the same id is a no-op; a different component under an already-taken
     * id raises {@link TransfluxValidationException}.
     *
     * @param component the component to register; never {@code null}
     *
     * @throws TransfluxValidationException if the id is already taken by a different
     *         component, or if {@link Component#validate()} fails
     */
    void register(Component<T> component) {
        requireNotNull(component, "Component");
        requireNotBlank(component.id(), "Component id");
        component.validate();

        Component<T> existing = components.get(component.id());
        if (existing != null) {
            if (existing == component) {
                return;
            }
            throw new TransfluxValidationException(
                "Component id '" + component.id() + "' is already registered");
        }

        components.put(component.id(), component);
    }

    @Override
    public Optional<Component<T>> get(String id) {
        return Optional.ofNullable(components.get(id));
    }

    @Override
    public Optional<Component<T>> resolve(String id) {
        Component<T> local = components.get(id);
        if (local != null) {
            return Optional.of(local);
        }
        if (parent != null) {
            return parent.resolve(id);
        }
        return Optional.empty();
    }

    @Override
    public Set<String> ids() {
        return Collections.unmodifiableSet(components.keySet());
    }

    @Override
    public Registry<T> parent() {
        return parent;
    }

    /**
     * Copies every ancestor entry that is visible through {@link #resolve(String)} but not held
     * locally into the local map. After this call, {@link #resolve(String)} is a single local-map
     * lookup with no parent-chain traversal. {@link #parent()} is left in place as a public
     * introspection accessor.
     *
     * <p>Safe to call once per registry, at the end of the state-machine build pipeline, after
     * every ancestor's local entries are settled.
     */
    void flatten() {
        Registry<T> ancestor = parent;
        while (ancestor != null) {
            for (String id : ancestor.ids()) {
                if (!components.containsKey(id)) {
                    ancestor.get(id).ifPresent(c -> components.put(c.id(), c));
                }
            }
            ancestor = ancestor.parent();
        }
    }
}
