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
import org.transflux.core.StateMachineDef;
import org.transflux.core.Transflux;
import org.transflux.core.exception.TransfluxValidationException;
import org.transflux.yaml.source.DefinitionResource;
import org.transflux.yaml.source.DefinitionSource;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.Tag;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.transflux.core.Preconditions.requireNotBlank;
import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Reads a YAML state-machine definition from a {@link DefinitionSource} into a
 * {@link StateMachineDef}, which the host builds or hands to {@code replaceDefinition}. A loader is
 * immutable, may be shared, and caches nothing between loads: every load reads through the source.
 *
 * <pre>{@code
 * YamlDefinitionLoader loader = YamlDefinitionLoader.builder(new ClasspathDefinitionSource()).build();
 * StateMachine<Subscription> sm = loader.load("subscription.yml", Subscription.class).build();
 * }</pre>
 */
public final class YamlDefinitionLoader {

    /** The only document format version this loader reads. */
    public static final String API_VERSION = "transflux/v1";

    private final DefinitionSource source;
    private final Classes classes;

    private YamlDefinitionLoader(DefinitionSource source, Classes classes) {
        this.source = source;
        this.classes = classes;
    }

    /**
     * @param source where documents are read from
     *
     * @return a builder for a loader reading from {@code source}
     *
     * @throws TransfluxValidationException if {@code source} is null
     */
    public static Builder builder(DefinitionSource source) {
        return new Builder(requireNotNull(source, "Definition source"));
    }

    /**
     * Loads the definition a root document declares, together with every document it imports.
     *
     * @param identifier the root document's identifier, handed to the source as written
     * @param entityType the entity type the document's {@code entityType} must name
     * @param <T> the entity type
     *
     * @return the definition, not yet built
     *
     * @throws DefinitionLoadException when the document or an import is missing or is not valid, the
     *         imports are circular, or the document declares an entity type other than
     *         {@code entityType}
     * @throws TransfluxValidationException if an argument is null or blank
     */
    public <T> StateMachineDef<T> load(String identifier, Class<T> entityType) {
        requireNotBlank(identifier, "Root identifier");
        requireNotNull(entityType, "Entity type");

        DefinitionResource resource = source.open(identifier).orElseThrow(() ->
            new DefinitionLoadException(List.of(), identifier, null, null, null, null, "no such document", null));
        Document document = read(List.of(), resource);
        return bind(NodeMap.of(document, document.root(), null, "a definition document"), identifier, entityType);
    }

    private Document read(List<String> importChain, DefinitionResource resource) {
        Document document;
        try {
            document = Document.parse(importChain, resource.identifier(), resource.location(),
                new InputStreamReader(resource.bytes(), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            closeQuietly(resource);
            throw e;
        }
        closeQuietly(resource);
        String importedBy = importChain.isEmpty() ? null : importChain.get(importChain.size() - 1);
        Loggers.YAML_PARSE.debug("Document parsed, identifier={}, location={}, importedBy={}",
            resource.identifier(), resource.location(), importedBy);
        return document;
    }

    private static void closeQuietly(DefinitionResource resource) {
        try {
            resource.close();
        } catch (RuntimeException e) {
            // The bytes are already read, so a stream that fails to close costs the source a handle, not the load.
            Loggers.YAML_PARSE.warn("Definition resource failed to close, identifier={}, error={}",
                resource.identifier(), e.getClass().getName());
        }
    }

    private <T> StateMachineDef<T> bind(NodeMap root, String identifier, Class<T> entityType) {
        requireApiVersion(root);
        NodeMap stateMachine = root.requiredMap("stateMachine").within("state machine");

        Class<?> declared = classes.requiredClass(stateMachine, "entityType", null);
        if (declared != entityType) {
            throw stateMachine.error(stateMachine.requiredNode("entityType"), "entityType is "
                + declared.getName() + ", but " + entityType.getName() + " was asked for"
                + (declared.getName().equals(entityType.getName()) ? " (loaded by another class loader)" : ""));
        }
        StateMachineDef<T> def = Transflux.defineStateMachine(entityType);
        Loggers.YAML_BINDING.debug("State machine definition created, identifier={}, entityType={}",
            root.document().identifier(), entityType.getName());
        // A library names no entity type, so its classes can only be checked once the root's is known.
        Readers readers = Readers.of(classes, entityType, new DeclarationSites());
        imports(root, List.of(identifier), new HashSet<>(Set.of(identifier)), def, readers);
        ComponentSections.read(root, def, readers);
        StateMachineSection.read(stateMachine, def, readers);
        root.rejectUnknownKeys();
        return def;
    }

    /**
     * Reads what a document imports, depth first, each import's own imports before its sections.
     *
     * @param importer the importing document's root mapping
     * @param asked the identifiers the documents from the root to {@code importer} were asked for
     * @param seen the identifiers already asked for in this load, which a diamond import skips
     * @param def the definition the imported components are registered on
     * @param readers the readers this load shares
     */
    private void imports(NodeMap importer, List<String> asked, Set<String> seen, StateMachineDef<?> def,
                         Readers readers) {
        List<Node> entries = importer.optionalList("imports");
        if (entries == null) {
            return;
        }
        String importedBy = importer.document().identifier();
        // Errors name each document by what its source reported; deduplication uses what was asked.
        List<String> importChain = new ArrayList<>(importer.document().importChain());
        importChain.add(importedBy);
        for (Node entry : entries) {
            if (!(entry instanceof ScalarNode scalar) || Tag.NULL.equals(scalar.getTag())
                || scalar.getValue().isBlank()) {
                throw importer.error(entry, "an import names a document by its identifier; this entry names none");
            }
            String identifier = scalar.getValue();
            // Every document on the chain is also in seen, so the cycle has to be told apart first.
            int cycle = asked.indexOf(identifier);
            if (cycle >= 0) {
                throw importer.error(entry, "circular import: "
                    + String.join(" -> ", asked.subList(cycle, asked.size())) + " -> " + identifier);
            }
            if (!seen.add(identifier)) {
                Loggers.YAML_PARSE.debug("Import already read, identifier={}, importedBy={}", identifier, importedBy);
                continue;
            }
            Document document = read(List.copyOf(importChain), open(importer, entry, identifier));
            NodeMap library = NodeMap.of(document, document.root(), null, "a definition document");
            requireApiVersion(library);
            if (library.holds("stateMachine")) {
                throw library.error(library.keyNode("stateMachine"),
                    "only the root document declares a state machine; this one is imported");
            }
            List<String> nested = new ArrayList<>(asked);
            nested.add(identifier);
            imports(library, nested, seen, def, readers);
            ComponentSections.read(library, def, readers);
            library.rejectUnknownKeys();
        }
    }

    /**
     * Opens an import, attributing a miss and a refusal alike to the entry that named it.
     *
     * @param importer the importing document's root mapping
     * @param entry the {@code imports:} entry naming the document
     * @param identifier the identifier the entry names
     *
     * @return the opened resource
     *
     * @throws DefinitionLoadException when the source misses or refuses the identifier
     */
    private DefinitionResource open(NodeMap importer, Node entry, String identifier) {
        Optional<DefinitionResource> resource;
        try {
            resource = source.open(identifier);
        } catch (RuntimeException e) {
            throw importer.error(entry, "import '" + identifier + "': "
                + (e.getMessage() == null ? e.getClass().getName() : e.getMessage()), e);
        }
        return resource.orElseThrow(() -> importer.error(entry, "import '" + identifier + "': no such document"));
    }

    private static void requireApiVersion(NodeMap document) {
        String apiVersion = document.requiredString("apiVersion");
        if (!API_VERSION.equals(apiVersion)) {
            throw document.error(document.requiredNode("apiVersion"),
                "'apiVersion' must be '" + API_VERSION + "', not '" + apiVersion + "'");
        }
    }

    /** Configures a {@link YamlDefinitionLoader}. */
    public static final class Builder {

        private final DefinitionSource source;
        private ClassLoader classLoader;
        private ComponentFactory componentFactory = ComponentFactory.reflective();

        private Builder(DefinitionSource source) {
            this.source = source;
        }

        /**
         * Sets the class loader class names in documents are loaded through. When it is not set,
         * the thread context class loader current at {@link #build()} is used, or this class's own
         * loader when that is {@code null}.
         *
         * @param classLoader the class loader
         *
         * @return this builder
         *
         * @throws TransfluxValidationException if {@code classLoader} is null
         */
        public Builder withClassLoader(ClassLoader classLoader) {
            this.classLoader = requireNotNull(classLoader, "Class loader");
            return this;
        }

        /**
         * Sets what turns a class a document names into an instance; {@link ComponentFactory#reflective()}
         * when not set.
         *
         * @param componentFactory the component factory
         *
         * @return this builder
         *
         * @throws TransfluxValidationException if {@code componentFactory} is null
         */
        public Builder withComponentFactory(ComponentFactory componentFactory) {
            this.componentFactory = requireNotNull(componentFactory, "Component factory");
            return this;
        }

        /**
         * @return the loader
         */
        public YamlDefinitionLoader build() {
            ClassLoader loader = classLoader;
            if (loader == null) {
                loader = Thread.currentThread().getContextClassLoader();
            }
            if (loader == null) {
                loader = YamlDefinitionLoader.class.getClassLoader();
            }
            return new YamlDefinitionLoader(source, new Classes(loader, componentFactory));
        }
    }
}
