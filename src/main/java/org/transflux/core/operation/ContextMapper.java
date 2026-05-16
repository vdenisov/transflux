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

/**
 * Bridges a nested operation's context type {@code N} with its enclosing parent's context
 * type {@code P}.
 * <p>
 * When a nested operation is declared with a narrower (or otherwise different) context type
 * via {@code .usingContext(Class<N>)} on its builder, the runtime crosses the parent-to-child
 * boundary by invoking {@link #mapTo(Object)} to produce the child's context, runs the nested
 * operation with that context, and — when the nested operation returns successfully — invokes
 * {@link #mapFrom(Object, Object)} so any results the child wrote into its own context can
 * flow back into the parent's. The default {@code mapFrom} is a no-op, making the
 * write-back side optional: a nested operation whose results need not propagate to the
 * parent can omit it.
 *
 * <p>Mapper failures are attributed to whichever side of the boundary they conceptually
 * belong to: a {@code mapTo} failure surfaces as a <em>parent</em> member failure at the
 * nested operation's position (the child never started), while a {@code mapFrom} failure
 * surfaces as a <em>child</em> failure (the child completed but its write-back blew up).
 *
 * @param <P> the enclosing parent's context type
 * @param <N> the nested operation's context type
 */
public interface ContextMapper<P, N> {

    /**
     * Maps the parent's context object to a fresh context object for the nested operation.
     * Invoked once per nested-operation execution, before the nested operation starts.
     *
     * @param parentContext the enclosing parent's context; never {@code null}
     *
     * @return the nested operation's context; should not be {@code null}
     */
    N mapTo(P parentContext);

    /**
     * Folds the nested operation's context back into the parent's. Invoked once per
     * nested-operation execution, after the nested operation returns successfully. The
     * default implementation is a no-op; override when results from the child must flow
     * back to the parent.
     *
     * @param parentContext the enclosing parent's context; never {@code null}
     * @param nestedContext the nested operation's context produced by
     *                      {@link #mapTo(Object)}; never {@code null}
     */
    default void mapFrom(P parentContext, N nestedContext) {
        // no-op by default
    }
}
