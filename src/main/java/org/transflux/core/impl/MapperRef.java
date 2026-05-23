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

import java.util.function.Function;

import static org.transflux.core.Preconditions.requireNotBlank;
import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Call-site declaration of a {@link ContextMapper} reference. Captures the unresolved choice
 * the user made at a composite-member or {@code TransitionView.step(...)} / {@code .operation(...)}
 * call site; the build pipeline resolves it against the enclosing state machine's mapper
 * registry to produce a runtime {@link ResolvedContextMapping}.
 *
 * <p>Four forms are supported:
 * <ul>
 *   <li>{@link PassThrough} — no mapper; the parent's context flows to the called step or
 *       operation unchanged (subject to type compatibility at build time).</li>
 *   <li>{@link ById} — references a {@link MapperDef} registered on the state machine.</li>
 *   <li>{@link InlineFunction} — an inline read-only parent-to-child projection; equivalent to
 *       a {@link ContextMapper} whose {@link ContextMapper#mapFrom(Object, Object) mapFrom} is
 *       the default no-op.</li>
 *   <li>{@link InlineMapper} — a fully-supplied {@link ContextMapper} instance.</li>
 * </ul>
 */
sealed interface MapperRef
    permits MapperRef.PassThrough, MapperRef.ById, MapperRef.InlineFunction, MapperRef.InlineMapper {

    /**
     * Returns the singleton {@link PassThrough} reference.
     *
     * @return the pass-through reference
     */
    static MapperRef passThrough() {
        return PassThrough.INSTANCE;
    }

    /**
     * Returns a reference to a registered {@link MapperDef} by id.
     *
     * @param mapperId the mapper id; must be non-blank
     *
     * @return the by-id reference
     */
    static MapperRef byId(String mapperId) {
        return new ById(mapperId);
    }

    /**
     * Returns an inline read-only mapper reference wrapping the supplied function.
     *
     * @param fn the parent-to-child projection; must not be {@code null}
     *
     * @return the inline-function reference
     */
    static MapperRef inline(Function<?, ?> fn) {
        return new InlineFunction(fn);
    }

    /**
     * Returns an inline mapper reference wrapping the supplied {@link ContextMapper} instance.
     *
     * @param mapper the mapper; must not be {@code null}
     *
     * @return the inline-mapper reference
     */
    static MapperRef inline(ContextMapper<?, ?> mapper) {
        return new InlineMapper(mapper);
    }

    /**
     * Marker variant indicating "no mapper" — the parent's context is passed through unchanged.
     * The build-time check verifies that the parent's context class is assignable to the called
     * step or operation's required context class.
     */
    record PassThrough() implements MapperRef {
        static final PassThrough INSTANCE = new PassThrough();
    }

    /**
     * Variant referencing a {@link MapperDef} registered on the enclosing state machine. The
     * build-time check verifies the mapper exists and its parent / child type tokens align with
     * the call site's parent context and the called step or operation's required context.
     *
     * @param mapperId the mapper id; never {@code null} or blank
     */
    record ById(String mapperId) implements MapperRef {
        public ById {
            requireNotBlank(mapperId, "Mapper reference ID");
        }
    }

    /**
     * Variant carrying an inline read-only parent-to-child function. The function is wrapped at
     * build time in a {@link ContextMapper} whose {@code mapFrom} is the default no-op.
     *
     * @param fn the parent-to-child projection; never {@code null}
     */
    record InlineFunction(Function<?, ?> fn) implements MapperRef {
        public InlineFunction {
            requireNotNull(fn, "Inline mapper function");
        }
    }

    /**
     * Variant carrying an inline fully-supplied {@link ContextMapper}.
     *
     * @param mapper the mapper; never {@code null}
     */
    record InlineMapper(ContextMapper<?, ?> mapper) implements MapperRef {
        public InlineMapper {
            requireNotNull(mapper, "Inline mapper instance");
        }
    }
}
