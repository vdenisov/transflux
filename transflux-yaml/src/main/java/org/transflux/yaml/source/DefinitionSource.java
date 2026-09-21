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

package org.transflux.yaml.source;

import java.util.Optional;
import java.util.Set;

/**
 * Where definition documents come from. The framework parses and validates; a source only retrieves
 * bytes for an identifier, whose meaning is entirely the source's own.
 *
 * <p>Every call is a fresh request: the framework caches nothing a source returns, so a source that
 * wants caching does it itself.
 */
@FunctionalInterface
public interface DefinitionSource {

    /**
     * Opens the document an identifier names.
     *
     * @param identifier an opaque, source-defined string, passed exactly as the host or an import wrote it
     *
     * @return the opened resource, which the caller closes; empty when this source has no such document
     *
     * @throws RuntimeException when the document exists but cannot be read, or the identifier is one
     *         this source refuses - a failure, never a miss
     */
    Optional<DefinitionResource> open(String identifier);

    /**
     * The identifier prefixes this source answers to inside a {@link CompositeDefinitionSource},
     * which strips the one it matched before calling {@link #open(String)}. Used on its own, a source
     * receives identifiers exactly as given, prefix included.
     *
     * @return the prefixes, such as {@code "cp:"}; empty by default, which leaves the source reachable
     *         by an identifier that carries no known prefix only
     */
    default Set<String> prefixes() {
        return Set.of();
    }
}
