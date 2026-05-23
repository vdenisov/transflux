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

import java.util.Optional;
import java.util.Set;

/**
 * Unified id-keyed lookup over the framework's reusable building blocks — steps, operations,
 * and conditions — held as {@link Component} variants.
 * <p>
 * Lookup is scope-aware. Every {@link StateMachineImpl} owns one root {@code Registry} that
 * holds SM-level registrations; every {@code CompositeOperationDefImpl} owns its own
 * {@code Registry} whose {@link #parent()} is the enclosing scope's registry (root in 2.6.6;
 * a process-wide registry once Phase 6.2 lands). Inline composite members live in the
 * composite's own registry only — visibility is lexical. {@link #resolve(String)} walks the
 * parent chain on a local miss; {@link #get(String)} is local-only.
 *
 * <p>After state-machine construction the registry chain is flattened
 * ({@link RegistryImpl#flatten()}), so {@code resolve(id)} becomes a single local-map lookup
 * at runtime. {@link #parent()} stays exposed as an introspection accessor for tooling and
 * diagnostics.
 *
 * <p>This is framework-internal infrastructure used by Transflux's own runtime; user code
 * should not reference it directly.
 *
 * @param <T> the entity type the surrounding state machine manages
 */
interface Registry<T> {

    /**
     * Returns the component registered under {@code id} in this registry only — no
     * parent-chain walk.
     *
     * @param id the component id
     *
     * @return the component if present in this registry; {@link Optional#empty()} otherwise
     */
    Optional<Component<T>> get(String id);

    /**
     * Returns the component registered under {@code id}, walking the parent chain when the
     * local registry has no entry. After {@link RegistryImpl#flatten()} every visible
     * ancestor entry has been copied into the local map and {@code resolve} no longer
     * traverses the parent chain.
     *
     * @param id the component id
     *
     * @return the component if present in this registry or any ancestor; {@link Optional#empty()}
     *         otherwise
     */
    Optional<Component<T>> resolve(String id);

    /**
     * Returns the ids registered locally in this registry. Parent-chain ids are not included.
     *
     * @return the set of locally registered ids; never {@code null}
     */
    Set<String> ids();

    /**
     * Returns this registry's parent, or {@code null} when this is a root registry.
     *
     * @return the parent registry, or {@code null}
     */
    Registry<T> parent();
}
