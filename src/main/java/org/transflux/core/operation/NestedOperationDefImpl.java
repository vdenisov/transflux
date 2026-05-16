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

package org.transflux.core.operation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.transflux.core.exception.TransfluxValidationException;

import java.util.function.BiConsumer;
import java.util.function.Function;

import static org.transflux.core.ReflectionUtils.instantiateNoArg;
import static org.transflux.core.ValidationUtils.requireNotBlank;
import static org.transflux.core.ValidationUtils.requireNotNull;
import static org.transflux.core.ValidationUtils.warnIfSet;

/**
 * Implementation of {@link NestedOperationDef}.
 *
 * <p>Holds the four orthogonal configuration choices a nested-operation declaration may
 * carry — child context type (via {@code .usingContext(...)}), a class- or instance-based
 * {@link ContextMapper}, inline {@code .mapTo(...)} / {@code .mapFrom(...)} lambdas, and
 * metadata — and resolves them at build time into a {@link ResolvedContextMapping} that the
 * runtime executor invokes at the parent-to-child boundary.
 *
 * <p><b>Mode discipline.</b> Validation rules enforced on the
 * {@linkplain #toResolvedMapping() build-time resolution}:
 * <ul>
 *   <li>Class- or instance-form {@link ContextMapper} cannot be combined with inline
 *       {@code .mapTo(...)} / {@code .mapFrom(...)} lambdas on the same declaration.</li>
 *   <li>When {@code .usingContext(...)} has been called, either a {@link ContextMapper} or
 *       at least a {@code .mapTo(...)} must also be supplied; {@code .mapFrom(...)} stays
 *       optional.</li>
 *   <li>When neither {@code .usingContext(...)} nor any mapping call has been made, the
 *       nested operation runs pass-through — the parent's context object is reused verbatim
 *       and {@code N} equals {@code P}.</li>
 * </ul>
 *
 * <p>This is framework-internal infrastructure; user code constructs nested-operation defs
 * through {@link CompositeOperationDef}'s lambda-configurer overloads.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <P> the enclosing parent's context type
 * @param <N> the nested operation's context type; equals {@code P} until
 *            {@link #usingContext(Class)} narrows it
 */
public final class NestedOperationDefImpl<T, P, N> implements NestedOperationDef<T, P, N> {
    private static final Logger log = LoggerFactory.getLogger(NestedOperationDefImpl.class);

    private final String id;
    private String name;
    private String description;

    private Class<?> childContextType;     // set by usingContext(...); null = pass-through

    private Class<? extends ContextMapper<?, ?>> mapperClass;
    private ContextMapper<?, ?> mapperInstance;

    private Function<?, ?> mapToFn;
    private BiConsumer<?, ?> mapFromFn;

    public NestedOperationDefImpl(String id) {
        requireNotBlank(id, "Nested operation ID");
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    @Override
    public NestedOperationDefImpl<T, P, N> withName(String name) {
        warnIfSet(this.name, name, "Name", log);
        this.name = name;
        return this;
    }

    @Override
    public NestedOperationDefImpl<T, P, N> withDescription(String description) {
        warnIfSet(this.description, description, "Description", log);
        this.description = description;
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <N2> NestedOperationDefImpl<T, P, N2> usingContext(Class<N2> contextType) {
        requireNotNull(contextType, "Child context type");

        if (this.childContextType != null && this.childContextType != contextType) {
            log.warn("Nested operation '{}' child context type already declared as {}; overriding with {}",
                id, this.childContextType.getName(), contextType.getName());
        }

        this.childContextType = contextType;
        return (NestedOperationDefImpl<T, P, N2>) this;
    }

    @Override
    public NestedOperationDefImpl<T, P, N> withContextMapping(Class<? extends ContextMapper<P, N>> mapperClass) {
        requireNotNull(mapperClass, "Context mapper class");
        if (this.mapperInstance != null) {
            throw new TransfluxValidationException(
                "Nested operation '" + id + "' already declares a ContextMapper instance; cannot also declare a class");
        }
        if (this.mapperClass != null && this.mapperClass != mapperClass) {
            log.warn("Nested operation '{}' context mapper class already set to {}; overriding with {}",
                id, this.mapperClass.getName(), mapperClass.getName());
        }
        this.mapperClass = mapperClass;
        return this;
    }

    @Override
    public NestedOperationDefImpl<T, P, N> withContextMapping(ContextMapper<P, N> mapper) {
        requireNotNull(mapper, "Context mapper");
        if (this.mapperClass != null) {
            throw new TransfluxValidationException(
                "Nested operation '" + id + "' already declares a ContextMapper class; cannot also declare an instance");
        }
        if (this.mapperInstance != null && this.mapperInstance != mapper) {
            log.warn("Nested operation '{}' context mapper instance already set; overriding", id);
        }
        this.mapperInstance = mapper;
        return this;
    }

    @Override
    public NestedOperationDefImpl<T, P, N> mapTo(Function<P, N> mapper) {
        requireNotNull(mapper, "mapTo function");
        if (this.mapToFn != null && this.mapToFn != mapper) {
            log.warn("Nested operation '{}' mapTo function already set; overriding", id);
        }
        this.mapToFn = mapper;
        return this;
    }

    @Override
    public NestedOperationDefImpl<T, P, N> mapFrom(BiConsumer<P, N> mapper) {
        requireNotNull(mapper, "mapFrom function");
        if (this.mapFromFn != null && this.mapFromFn != mapper) {
            log.warn("Nested operation '{}' mapFrom function already set; overriding", id);
        }
        this.mapFromFn = mapper;
        return this;
    }

    /**
     * Validates the declared configuration and produces a {@link ResolvedContextMapping}
     * describing how the runtime should bridge the parent-to-child context boundary. Called
     * once per nested-operation declaration when the enclosing state machine is being built.
     *
     * <p>This is framework-internal infrastructure used by Transflux's own runtime; user code
     * should not invoke it directly.
     *
     * @return the resolved mapping; never {@code null}
     *
     * @throws TransfluxValidationException if the declared configuration is inconsistent
     */
    public ResolvedContextMapping toResolvedMapping() {
        boolean hasClassMapper = mapperClass != null;
        boolean hasInstanceMapper = mapperInstance != null;
        boolean hasInlineMapTo = mapToFn != null;
        boolean hasInlineMapFrom = mapFromFn != null;
        boolean hasInline = hasInlineMapTo || hasInlineMapFrom;
        boolean hasContextMapper = hasClassMapper || hasInstanceMapper;

        if (hasContextMapper && hasInline) {
            throw new TransfluxValidationException(
                "Nested operation '" + id
                    + "' mixes withContextMapping(...) with inline mapTo/mapFrom; pick one form");
        }

        if (childContextType == null && !hasContextMapper && !hasInline) {
            // Pass-through: parent context flows through unchanged.
            return ResolvedContextMapping.passThrough();
        }

        if (childContextType == null) {
            throw new TransfluxValidationException(
                "Nested operation '" + id
                    + "' declares context mapping but never called usingContext(...) to declare the child context type");
        }

        if (!hasContextMapper && !hasInlineMapTo) {
            throw new TransfluxValidationException(
                "Nested operation '" + id
                    + "' declares usingContext(...) but no mapTo or withContextMapping; one is required");
        }

        ContextMapper<Object, Object> mapper;
        if (hasClassMapper) {
            @SuppressWarnings("unchecked")
            Class<? extends ContextMapper<Object, Object>> raw =
                (Class<? extends ContextMapper<Object, Object>>) mapperClass;
            mapper = instantiateNoArg(raw, "ContextMapper");
        } else if (hasInstanceMapper) {
            @SuppressWarnings("unchecked")
            ContextMapper<Object, Object> raw = (ContextMapper<Object, Object>) mapperInstance;
            mapper = raw;
        } else {
            @SuppressWarnings("unchecked")
            Function<Object, Object> mapTo = (Function<Object, Object>) mapToFn;
            @SuppressWarnings("unchecked")
            BiConsumer<Object, Object> mapFrom = (BiConsumer<Object, Object>) mapFromFn;
            mapper = new InlineMapper(mapTo, mapFrom);
        }

        return ResolvedContextMapping.mapped(mapper);
    }

    /**
     * Adapter that exposes a pair of inline mapping lambdas as a {@link ContextMapper}.
     */
    private static final class InlineMapper implements ContextMapper<Object, Object> {
        private final Function<Object, Object> mapTo;
        private final BiConsumer<Object, Object> mapFrom;

        InlineMapper(Function<Object, Object> mapTo, BiConsumer<Object, Object> mapFrom) {
            this.mapTo = mapTo;
            this.mapFrom = mapFrom;
        }

        @Override
        public Object mapTo(Object parentContext) {
            if (mapTo == null) {
                throw new TransfluxValidationException(
                    "Inline mapper invoked without a mapTo function — should have failed earlier in validation");
            }
            return mapTo.apply(parentContext);
        }

        @Override
        public void mapFrom(Object parentContext, Object nestedContext) {
            if (mapFrom != null) {
                mapFrom.accept(parentContext, nestedContext);
            }
        }
    }
}
