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
    private final Map<String, String> declaringScopes = new LinkedHashMap<>();
    private final Registry<T> parent;
    private final String label;

    /**
     * Creates a parentless root registry.
     */
    RegistryImpl() {
        this(null, "root");
    }

    /**
     * Creates a child registry under the supplied parent. There is deliberately no parent-only
     * overload: the label is what {@link #label()} reports as the claiming scope, so a child that
     * did not name itself would be indistinguishable from the root in diagnostics.
     *
     * @param parent the parent registry, or {@code null} for a root
     * @param label the owning container's id, used in diagnostics
     */
    RegistryImpl(Registry<T> parent, String label) {
        this.parent = parent;
        this.label = label;
    }

    /**
     * Registers {@code component} under its id. Re-registering the same component instance under
     * the same id is a no-op; a different component under an already-taken id raises
     * {@link TransfluxValidationException}.
     *
     * <p>{@link Component#validate()} is not called here — it runs once over the whole registry
     * after the build settles, so a component's rules may depend on the rest of the definition.
     *
     * @param component the component to register; never {@code null}
     *
     * @throws TransfluxValidationException if the id is already taken by a different component
     */
    void register(Component<T> component) {
        requireNotNull(component, "Component");
        requireNotBlank(component.id(), "Component id");

        Component<T> existing = components.get(component.id());
        if (existing != null) {
            if (existing == component) {
                return;
            }
            throw new TransfluxValidationException(
                "Component id '" + component.id() + "' is already registered");
        }

        components.put(component.id(), component);
        declaringScopes.put(component.id(), label);
        logBound(component);
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
    public String label() {
        return label;
    }

    @Override
    public Optional<String> declaringScope(String id) {
        String local = declaringScopes.get(id);
        if (local != null) {
            return Optional.of(local);
        }
        if (parent != null) {
            return parent.declaringScope(id);
        }
        return Optional.empty();
    }

    @Override
    public Registry<T> parent() {
        return parent;
    }

    /**
     * Copies every ancestor entry that is visible through {@link #resolve(String)} but not held
     * locally into the local map. After this call, {@link #resolve(String)} is a single local-map
     * lookup with no parent-chain traversal. {@link #parent()} is left in place as a public
     * introspection accessor, and each copied entry carries its declaring scope's label across, so
     * {@link #declaringScope(String)} keeps naming the registry that claimed the id rather than the
     * one that inherited it.
     *
     * <p>Safe to call once per registry, at the end of the state-machine build pipeline, after
     * every ancestor's local entries are settled.
     */
    void flatten() {
        Registry<T> ancestor = parent;
        while (ancestor != null) {
            Registry<T> current = ancestor;
            for (String id : current.ids()) {
                if (!components.containsKey(id)) {
                    current.get(id).ifPresent(c -> {
                        components.put(c.id(), c);
                        current.declaringScope(c.id()).ifPresent(s -> declaringScopes.put(c.id(), s));
                    });
                }
            }
            ancestor = current.parent();
        }
    }

    /**
     * Reports what an id resolved to. Registration is the one seam every component passes through,
     * so a container's inline members — whose ids are reachable from nowhere else and are therefore
     * the ids most likely to be misread — are covered on the same terms as the root's.
     *
     * <p>The context type is the half a reader cannot infer from the definition: an untyped
     * registration is tagged {@code Object}, which is what a pass-through check will later admit
     * unconditionally. The scope says which registry claimed the id, and so which subtree can see
     * it. {@link #flatten()} copies ancestor entries in directly, so a flattened id is not reported
     * a second time under the scope that merely inherited it.
     */
    private void logBound(Component<T> component) {
        if (Loggers.BUILD_BINDING.isDebugEnabled()) {
            Class<?> contextType = component.contextType();
            Loggers.BUILD_BINDING.debug("Component bound, id={}, kind={}, contextType={}, scope={}",
                                        component.id(), component.kind(),
                                        contextType == null ? null : contextType.getName(), label);
        }
    }
}
