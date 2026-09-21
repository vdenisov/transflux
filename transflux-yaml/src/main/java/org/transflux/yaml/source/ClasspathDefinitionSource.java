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

import static org.transflux.core.Preconditions.requireNotBlank;
import static org.transflux.core.Preconditions.requireNotNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Optional;
import java.util.Set;

/**
 * Reads definitions as classpath resources, the identifier being the resource name
 * ({@code components/shared.yml}, no leading slash). Answers to {@code cp:} in a composite unless
 * given other prefixes. Reports the resource's URL as its location, and no change metadata.
 */
public final class ClasspathDefinitionSource implements DefinitionSource {

    private static final Set<String> DEFAULT_PREFIXES = Set.of("cp:");

    private final ClassLoader classLoader;
    private final Set<String> prefixes;

    /**
     * Creates a source reading through the thread context class loader current at construction, or
     * through this class's own loader when there is none.
     */
    public ClasspathDefinitionSource() {
        this(contextOrOwnLoader(), DEFAULT_PREFIXES);
    }

    /**
     * Creates a source reading through the given class loader.
     *
     * @param classLoader the loader resources are looked up in
     *
     * @throws org.transflux.core.exception.TransfluxValidationException if {@code classLoader} is {@code null}
     */
    public ClasspathDefinitionSource(ClassLoader classLoader) {
        this(requireNotNull(classLoader, "classLoader"), DEFAULT_PREFIXES);
    }

    private ClasspathDefinitionSource(ClassLoader classLoader, Set<String> prefixes) {
        this.classLoader = classLoader;
        this.prefixes = prefixes;
    }

    /**
     * Returns a copy of this source answering to the given prefixes in place of its current ones.
     *
     * @param prefixes the prefixes; none leaves the source reachable by an unprefixed identifier only
     *
     * @return the copy
     *
     * @throws org.transflux.core.exception.TransfluxValidationException if a prefix is {@code null} or blank
     */
    public ClasspathDefinitionSource withPrefixes(String... prefixes) {
        return new ClasspathDefinitionSource(classLoader, CompositeDefinitionSource.checkedPrefixes(prefixes));
    }

    @Override
    public Set<String> prefixes() {
        return prefixes;
    }

    /**
     * Opens the classpath resource the identifier names.
     *
     * @param identifier the resource name
     *
     * @return the resource, or empty when the class loader has none by that name
     *
     * @throws org.transflux.core.exception.TransfluxValidationException if {@code identifier} is blank
     * @throws UncheckedIOException if the resource exists but cannot be opened
     */
    @Override
    public Optional<DefinitionResource> open(String identifier) {
        requireNotBlank(identifier, "identifier");
        // not getResourceAsStream, which answers an unreadable resource with null, as though it were missing
        URL url = classLoader.getResource(identifier);
        if (url == null) {
            Loggers.YAML_SOURCE.debug("Classpath definition not found, identifier={}", identifier);
            return Optional.empty();
        }
        try {
            return Optional.of(new DefinitionResource(identifier, url.openStream(), url.toString(), null, null));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read definition '" + identifier + "' at " + url, e);
        }
    }

    private static ClassLoader contextOrOwnLoader() {
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        return contextLoader != null ? contextLoader : ClasspathDefinitionSource.class.getClassLoader();
    }
}
