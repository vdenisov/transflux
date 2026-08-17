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
import org.transflux.core.action.ContextMapper;
import org.transflux.core.action.MapperDef;

import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Default {@link MapperDef} implementation.
 * <p>
 * Holds one of two mutually exclusive source forms — a {@link ContextMapper} instance or a
 * {@code ContextMapper} class — plus the parent and child type tokens used by the build-time
 * call-site type-compatibility check. The two source forms are last-write-wins.
 *
 * @param <P> the parent context type
 * @param <N> the child context type
 */
final class MapperDefImpl<P, N> extends IdentifiedDefImpl<MapperDefImpl<P, N>>
        implements MapperDef<P, N> {

    private final Class<P> parentType;
    private final Class<N> childType;

    private final InstanceOrClassSource<ContextMapper<P, N>> source;

    MapperDefImpl(String id, Class<P> parentType, Class<N> childType) {
        super(id, "mapper", "Mapper ID");
        requireNotNull(parentType, "Mapper parent type");
        requireNotNull(childType, "Mapper child type");
        this.parentType = parentType;
        this.childType = childType;
        this.source = new InstanceOrClassSource<>(Loggers.BUILD_VALIDATION, "Mapper source",
                                                  "MapperDef '" + id + "'");
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
        requireConfigurerActive("using");
        requireNotNull(mapper, "Context mapper");
        source.setInstance(mapper);
        return this;
    }

    @Override
    public MapperDefImpl<P, N> using(Class<? extends ContextMapper<P, N>> mapperClass) {
        requireConfigurerActive("using");
        requireNotNull(mapperClass, "Context mapper class");
        source.setClass(mapperClass);
        return this;
    }

    /**
     * Resolves this def into a runtime {@link ContextMapper}, instantiating the class form
     * reflectively.
     *
     * @return the resolved mapper
     *
     * @throws TransfluxValidationException if no source has been set
     */
    ContextMapper<P, N> buildMapper() {
        if (!source.isSet()) {
            throw new TransfluxValidationException(
                "MapperDef '" + getId() + "' has no source set; call using(...) before build");
        }
        return source.resolve("ContextMapper");
    }
}
