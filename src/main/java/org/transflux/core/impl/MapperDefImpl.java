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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.transflux.core.exception.TransfluxValidationException;
import org.transflux.core.operation.ContextMapper;
import org.transflux.core.operation.MapperDef;

import java.util.function.Function;

import static org.transflux.core.Preconditions.requireNotBlank;
import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Default {@link MapperDef} implementation.
 * <p>
 * Holds one of three mutually exclusive source forms — a {@link ContextMapper} instance, a
 * {@code ContextMapper} class, or an inline parent-to-child {@link Function} — plus the parent
 * and child type tokens used by the build-time call-site type-compatibility check. The three
 * source forms are last-write-wins.
 *
 * @param <P> the parent context type
 * @param <N> the child context type
 */
final class MapperDefImpl<P, N> implements MapperDef<P, N> {
    private static final Logger log = LoggerFactory.getLogger(MapperDefImpl.class);

    private final String id;
    private final Class<P> parentType;
    private final Class<N> childType;

    private final InstanceOrClassSource<ContextMapper<P, N>> source;

    private String name;
    private String description;
    private Function<P, N> mapToFn;

    MapperDefImpl(String id, Class<P> parentType, Class<N> childType) {
        requireNotBlank(id, "Mapper ID");
        requireNotNull(parentType, "Mapper parent type");
        requireNotNull(childType, "Mapper child type");
        this.id = id;
        this.parentType = parentType;
        this.childType = childType;
        this.source = new InstanceOrClassSource<>(log, "Mapper source", "MapperDef '" + id + "'");
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
        warnIfFunctionSet();
        source.setInstance(mapper);
        this.mapToFn = null;
        return this;
    }

    @Override
    public MapperDefImpl<P, N> using(Class<? extends ContextMapper<P, N>> mapperClass) {
        requireNotNull(mapperClass, "Context mapper class");
        warnIfFunctionSet();
        source.setClass(mapperClass);
        this.mapToFn = null;
        return this;
    }

    @Override
    public MapperDefImpl<P, N> using(Function<P, N> mapTo) {
        requireNotNull(mapTo, "mapTo function");
        if (source.isSet() || mapToFn != null) {
            log.warn("Mapper source already defined for MapperDef '{}'; overriding previous value", id);
        }
        this.mapToFn = mapTo;
        source.clear();
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
    ContextMapper<P, N> buildMapper() {
        if (mapToFn != null) {
            Function<P, N> fn = mapToFn;
            return fn::apply;
        }
        if (source.isSet()) {
            return source.resolve("ContextMapper");
        }
        throw new TransfluxValidationException(
            "MapperDef '" + id + "' has no source set; call using(...) before build");
    }

    private void warnIfFunctionSet() {
        if (mapToFn != null) {
            log.warn("Mapper source already defined for MapperDef '{}'; overriding previous value", id);
        }
    }
}
