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

import org.transflux.core.Identifiable;
import org.transflux.core.exception.TransfluxValidationException;

import java.util.function.Function;

/**
 * Def-side anchor that pairs a {@link ContextMapper} with a framework-owned id and explicit
 * parent / child type tokens.
 * <p>
 * Mappers are a first-class reusable component: a single {@code MapperDef} can be referenced by
 * id from any number of call sites — a composite member, a {@code TransitionView.operation(...)}
 * dispatch, an {@code async} block — without each call site having to inline the mapping. The
 * mandatory {@code parentType} / {@code childType} tokens let the build pipeline verify that the
 * mapper's {@code P} aligns with each call site's parent context and the mapper's {@code N}
 * matches the called step or operation's required context.
 *
 * <p>Three source forms are supported and mutually exclusive: a pre-constructed
 * {@link ContextMapper} instance, a {@code ContextMapper} class instantiated reflectively at
 * build time, or an inline {@link Function} (parent → child) which the framework wraps in a
 * {@code ContextMapper} whose {@link ContextMapper#mapFrom(Object, Object) mapFrom} is the
 * default no-op.
 *
 * @param <P> the enclosing parent's context type at the call site
 * @param <N> the called step or operation's required context type
 */
public interface MapperDef<P, N> extends Identifiable {

    /**
     * Returns the unique identifier of this mapper def.
     *
     * @return the mapper id; never {@code null} or blank
     */
    @Override
    String getId();

    /**
     * Returns the human-readable name of this mapper, or {@code null} when unset.
     *
     * @return the optional mapper name
     */
    String getName();

    /**
     * Returns the description of this mapper, or {@code null} when unset.
     *
     * @return the optional mapper description
     */
    String getDescription();

    /**
     * Returns the parent context class this mapper consumes.
     *
     * @return the parent class; never {@code null}
     */
    Class<P> parentType();

    /**
     * Returns the child context class this mapper produces.
     *
     * @return the child class; never {@code null}
     */
    Class<N> childType();

    /**
     * Wires this def to a pre-constructed {@link ContextMapper} instance.
     *
     * @param mapper the mapper to invoke at the parent-to-child boundary; never {@code null}
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code mapper} is {@code null}
     */
    MapperDef<P, N> using(ContextMapper<P, N> mapper);

    /**
     * Wires this def to a {@link ContextMapper} class. The framework instantiates it via its
     * public no-arg constructor at build time.
     *
     * @param mapperClass the mapper class; never {@code null}
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code mapperClass} is {@code null}
     */
    MapperDef<P, N> using(Class<? extends ContextMapper<P, N>> mapperClass);

    /**
     * Wires this def to a read-only parent-to-child function. The framework wraps it in a
     * {@link ContextMapper} whose {@link ContextMapper#mapFrom(Object, Object) mapFrom} is the
     * default no-op.
     *
     * @param mapTo the parent-to-child projection; never {@code null}
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code mapTo} is {@code null}
     */
    MapperDef<P, N> using(Function<P, N> mapTo);

    /**
     * Sets the human-readable name of this mapper.
     *
     * @param name the name; may be {@code null} to clear
     *
     * @return this def for chaining
     */
    MapperDef<P, N> withName(String name);

    /**
     * Sets the description of this mapper.
     *
     * @param description the description; may be {@code null} to clear
     *
     * @return this def for chaining
     */
    MapperDef<P, N> withDescription(String description);
}
