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
import org.transflux.yaml.source.DefinitionResource;
import org.transflux.yaml.source.DefinitionSource;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

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
     * @throws org.transflux.core.exception.TransfluxValidationException if {@code source} is null
     */
    public static Builder builder(DefinitionSource source) {
        return new Builder(requireNotNull(source, "Definition source"));
    }

    /**
     * Loads the definition a root document declares.
     *
     * @param identifier the root document's identifier, handed to the source as written
     * @param entityType the entity type the document's {@code entityType} must name
     * @param <T> the entity type
     *
     * @return the definition, not yet built
     *
     * @throws DefinitionLoadException when the document is missing, is not valid, or declares an
     *         entity type other than {@code entityType}
     * @throws org.transflux.core.exception.TransfluxValidationException if an argument is null or blank
     */
    public <T> StateMachineDef<T> load(String identifier, Class<T> entityType) {
        requireNotBlank(identifier, "Root identifier");
        requireNotNull(entityType, "Entity type");

        Document document = read(identifier);
        return bind(NodeMap.of(document, document.root(), null, "a definition document"), entityType);
    }

    private Document read(String identifier) {
        DefinitionResource resource = source.open(identifier).orElseThrow(() ->
            new DefinitionLoadException(identifier, null, null, null, null, "no such document", null));
        Document document;
        try {
            document = Document.parse(resource.identifier(), resource.location(),
                new InputStreamReader(resource.bytes(), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            closeQuietly(resource);
            throw e;
        }
        closeQuietly(resource);
        Loggers.YAML_PARSE.debug("Document parsed, identifier={}, location={}",
            resource.identifier(), resource.location());
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

    private <T> StateMachineDef<T> bind(NodeMap root, Class<T> entityType) {
        String apiVersion = root.requiredString("apiVersion");
        if (!API_VERSION.equals(apiVersion)) {
            throw root.error(root.requiredNode("apiVersion"),
                "apiVersion must be '" + API_VERSION + "', not '" + apiVersion + "'");
        }
        NodeMap stateMachine = root.requiredMap("stateMachine").within("state machine");

        Class<?> declared = classes.requiredClass(stateMachine, "entityType", null);
        if (declared != entityType) {
            throw stateMachine.error(stateMachine.requiredNode("entityType"), "entityType is "
                + declared.getName() + ", but " + entityType.getName() + " was asked for"
                + (declared.getName().equals(entityType.getName()) ? " (loaded by another class loader)" : ""));
        }
        stateMachine.rejectUnknownKeys();

        StateMachineDef<T> def = Transflux.defineStateMachine(entityType);
        Loggers.YAML_BINDING.debug("State machine definition created, identifier={}, entityType={}",
            root.document().identifier(), entityType.getName());
        ComponentSections.read(root, def, entityType, classes);
        root.rejectUnknownKeys();
        return def;
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
         * @param classLoader the class loader class names in documents are loaded through; the
         *        thread context class loader current at {@link #build()} when not set, or this
         *        class's own loader when that is {@code null}
         *
         * @return this builder
         *
         * @throws org.transflux.core.exception.TransfluxValidationException if {@code classLoader} is null
         */
        public Builder withClassLoader(ClassLoader classLoader) {
            this.classLoader = requireNotNull(classLoader, "Class loader");
            return this;
        }

        /**
         * @param componentFactory what turns a class a document names into an instance;
         *        {@link ComponentFactory#reflective()} when not set
         *
         * @return this builder
         *
         * @throws org.transflux.core.exception.TransfluxValidationException if {@code componentFactory} is null
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
