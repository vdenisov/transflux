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

import org.transflux.core.operation.ContextMapper;

import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Build-time-resolved description of how to bridge a nested operation's parent-to-child
 * context boundary at execution time. Either {@linkplain #passThrough() pass-through} (the
 * parent's context object flows through unchanged) or {@linkplain #mapped(ContextMapper)
 * mapped} (a {@link ContextMapper} produces a separate child context on the way in and
 * optionally folds it back on the way out).
 */
final class ResolvedContextMapping {
    private static final ResolvedContextMapping PASS_THROUGH = new ResolvedContextMapping(null);

    private final ContextMapper<Object, Object> mapper;

    private ResolvedContextMapping(ContextMapper<Object, Object> mapper) {
        this.mapper = mapper;
    }

    /**
     * Returns the singleton pass-through mapping; the child reuses the parent's context.
     *
     * @return the pass-through mapping
     */
    static ResolvedContextMapping passThrough() {
        return PASS_THROUGH;
    }

    /**
     * Returns a mapping that bridges the parent and child contexts via the supplied
     * {@link ContextMapper}.
     *
     * @param mapper the mapper to invoke at the parent-to-child boundary; never {@code null}
     *
     * @return the mapped mapping
     */
    static ResolvedContextMapping mapped(ContextMapper<Object, Object> mapper) {
        requireNotNull(mapper, "Context mapper");
        return new ResolvedContextMapping(mapper);
    }

    /**
     * Indicates whether this mapping is pass-through (no mapper) or carries a mapper.
     *
     * @return {@code true} if pass-through; {@code false} if mapped
     */
    boolean isPassThrough() {
        return mapper == null;
    }

    /**
     * Returns the mapper carried by this mapping, or {@code null} if pass-through.
     *
     * @return the mapper or {@code null}
     */
    ContextMapper<Object, Object> mapper() {
        return mapper;
    }
}
