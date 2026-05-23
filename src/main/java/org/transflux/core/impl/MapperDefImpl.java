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

import org.transflux.core.operation.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.transflux.core.exception.TransfluxValidationException;

import java.util.function.Function;

import static org.transflux.core.impl.ReflectionUtils.instantiateNoArg;
import static org.transflux.core.impl.ValidationUtils.requireNotBlank;
import static org.transflux.core.impl.ValidationUtils.requireNotNull;

/**
 * Default {@link MapperDef} implementation.
 * <p>
 * Holds one of three mutually exclusive source forms — a {@link ContextMapper} instance, a
 * {@code ContextMapper} class, or an inline parent-to-child {@link Function} — plus the parent
 * and child type tokens used by the build-time call-site type-compatibility check. The three
 * source forms are last-write-wins.
 *
 * <p>This is framework-internal infrastructure used by Transflux's own runtime; user code
 * constructs mapper defs through the public {@link MapperDef} surface and the
 * {@link org.transflux.core.StateMachineDef} registration overloads.
 *
 * @param <P> the parent context type
 * @param <N> the child context type
 */
public final class MapperDefImpl<P, N> implements MapperDef<P, N> {
    private static final Logger log = LoggerFactory.getLogger(MapperDefImpl.class);

    private final String id;
    private final Class<P> parentType;
    private final Class<N> childType;

    private String name;
    private String description;

    private ContextMapper<P, N> mapperInstance;
    private Class<? extends ContextMapper<P, N>> mapperClass;
    private Function<P, N> mapToFn;

    public MapperDefImpl(String id, Class<P> parentType, Class<N> childType) {
        requireNotBlank(id, "Mapper ID");
        requireNotNull(parentType, "Mapper parent type");
        requireNotNull(childType, "Mapper child type");
        this.id = id;
        this.parentType = parentType;
        this.childType = childType;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public Class<P> parentType() {
        return parentType;
    }

    @Override
    public Class<N> childType() {
        return childType;
    }

    @Override
    public MapperDefImpl<P, N> using(ContextMapper<P, N> mapper) {
        requireNotNull(mapper, "Context mapper");
        warnIfSourceSet();
        this.mapperInstance = mapper;
        this.mapperClass = null;
        this.mapToFn = null;
        return this;
    }

    @Override
    public MapperDefImpl<P, N> using(Class<? extends ContextMapper<P, N>> mapperClass) {
        requireNotNull(mapperClass, "Context mapper class");
        warnIfSourceSet();
        this.mapperClass = mapperClass;
        this.mapperInstance = null;
        this.mapToFn = null;
        return this;
    }

    @Override
    public MapperDefImpl<P, N> using(Function<P, N> mapTo) {
        requireNotNull(mapTo, "mapTo function");
        warnIfSourceSet();
        this.mapToFn = mapTo;
        this.mapperInstance = null;
        this.mapperClass = null;
        return this;
    }

    @Override
    public MapperDefImpl<P, N> withName(String name) {
        this.name = name;
        return this;
    }

    @Override
    public MapperDefImpl<P, N> withDescription(String description) {
        this.description = description;
        return this;
    }

    /**
     * Resolves this def into a runtime {@link ContextMapper}. The class form is instantiated
     * reflectively; the inline-function form is wrapped in a mapper whose
     * {@link ContextMapper#mapFrom(Object, Object) mapFrom} is the default no-op.
     *
     * @return the resolved mapper
     *
     * @throws TransfluxValidationException if no source has been set
     */
    public ContextMapper<P, N> buildMapper() {
        if (mapperInstance != null) {
            return mapperInstance;
        }
        if (mapperClass != null) {
            return instantiateNoArg(mapperClass, "ContextMapper");
        }
        if (mapToFn != null) {
            Function<P, N> fn = mapToFn;
            return fn::apply;
        }
        throw new TransfluxValidationException(
            "MapperDef '" + id + "' has no source set; call using(...) before build");
    }

    private void warnIfSourceSet() {
        if (mapperInstance != null || mapperClass != null || mapToFn != null) {
            log.warn("Mapper source already defined for MapperDef '{}'; overriding previous value", id);
        }
    }
}
