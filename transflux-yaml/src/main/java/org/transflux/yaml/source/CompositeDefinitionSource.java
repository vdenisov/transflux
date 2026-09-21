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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.transflux.core.exception.TransfluxValidationException;

/**
 * An ordered list of sources, each answering to the {@link DefinitionSource#prefixes() prefixes} it
 * declares. An identifier starting with a declared prefix is asked of the sources declaring it, with
 * the prefix stripped; any other identifier is asked of every source, verbatim. Either way the sources
 * are asked in list order and the first to find the document wins.
 *
 * <p>The order is a trust decision: an earlier source shadows a later one, so a writable directory
 * listed ahead of the classpath overrides what the application ships.
 */
public final class CompositeDefinitionSource implements DefinitionSource {

    private final List<Entry> entries;

    private CompositeDefinitionSource(List<Entry> entries) {
        this.entries = entries;
    }

    /**
     * Combines sources in the order they are asked. Each source's prefixes are read once, here.
     *
     * @param sources the sources, in the order they are asked
     *
     * @return the combined source
     *
     * @throws TransfluxValidationException if no source is given, one is {@code null}, or one declares
     *         a {@code null} or blank prefix
     */
    public static CompositeDefinitionSource of(DefinitionSource... sources) {
        if (requireNotNull(sources, "sources").length == 0) {
            throw new TransfluxValidationException("A composite definition source needs at least one source");
        }
        List<Entry> entries = new ArrayList<>();
        for (DefinitionSource source : sources) {
            requireNotNull(source, "source");
            entries.add(new Entry(source, checkedPrefixes(source.prefixes().toArray(String[]::new))));
        }
        return new CompositeDefinitionSource(List.copyOf(entries));
    }

    /**
     * Opens the identifier through the sources its prefix selects, or through every source when it
     * carries no declared prefix. A matched prefix whose sources all miss is a miss: the other sources
     * are not asked. A source that throws ends the search, since a failure is not a miss.
     *
     * @param identifier the identifier, prefix included
     *
     * @return the first resource found, reporting {@code identifier} and the answering source's location
     *
     * @throws TransfluxValidationException if {@code identifier} is blank
     */
    @Override
    public Optional<DefinitionResource> open(String identifier) {
        requireNotBlank(identifier, "identifier");
        boolean prefixed = entries.stream().anyMatch(entry -> entry.longestMatch(identifier) != null);
        for (Entry entry : entries) {
            String prefix = entry.longestMatch(identifier);
            if (prefixed && prefix == null) {
                continue;
            }
            String delegated = prefixed ? identifier.substring(prefix.length()) : identifier;
            Optional<DefinitionResource> resource = entry.source().open(delegated);
            if (resource.isPresent()) {
                DefinitionResource found = resource.get();
                Loggers.YAML_SOURCE.debug("Definition found, identifier={}, location={}", identifier, found.location());
                return Optional.of(new DefinitionResource(identifier, found.bytes(), found.location(),
                    found.lastModified(), found.etag()));
            }
        }
        Loggers.YAML_SOURCE.debug("Definition not found in any source, identifier={}, prefixed={}", identifier, prefixed);
        return Optional.empty();
    }

    static Set<String> checkedPrefixes(String... prefixes) {
        for (String prefix : requireNotNull(prefixes, "prefixes")) {
            requireNotBlank(prefix, "prefix");
        }
        return Set.copyOf(List.of(prefixes));
    }

    private record Entry(DefinitionSource source, Set<String> prefixes) {

        /** The longest of this source's prefixes the identifier starts with, or {@code null}. */
        String longestMatch(String identifier) {
            String longest = null;
            for (String prefix : prefixes) {
                if (identifier.startsWith(prefix) && (longest == null || prefix.length() > longest.length())) {
                    longest = prefix;
                }
            }
            return longest;
        }
    }
}
