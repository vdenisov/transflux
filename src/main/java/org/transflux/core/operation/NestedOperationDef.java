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

import org.transflux.core.exception.TransfluxValidationException;

import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Configurer surface for a nested operation declared inside a {@link CompositeOperationDef}'s
 * lambda-configurer {@code .operation(...)} overload.
 * <p>
 * A nested-operation declaration carries optional metadata ({@link #withName(String)},
 * {@link #withDescription(String)}) and the context-mapping configuration that bridges the
 * enclosing parent's context type {@code P} with the nested operation's own context type
 * {@code N}. When no mapping is declared the nested operation runs in <b>pass-through</b>
 * mode — the parent's context object is reused verbatim and {@code N} equals {@code P}.
 *
 * <p><b>Re-generification through {@link #usingContext(Class)}.</b> The builder starts as
 * {@code NestedOperationDef<T, P, P>} (pass-through). Calling {@code .usingContext(Class<N2>)}
 * returns a builder typed {@code NestedOperationDef<T, P, N2>}, so subsequent
 * {@link #withContextMapping(ContextMapper) withContextMapping}, {@link #mapTo(Function)},
 * and {@link #mapFrom(BiConsumer)} calls are checked at compile time against the new child
 * context type — a mapper whose generics do not align with the parent's {@code P} and the
 * child's declared {@code N2} fails to compile.
 *
 * <p><b>Mapping forms.</b> Three are accepted, but they may not be mixed: either supply a
 * {@link ContextMapper} (class or instance form via {@link #withContextMapping}) or supply
 * inline {@link #mapTo(Function)} / {@link #mapFrom(BiConsumer)} lambdas — never both on the
 * same nested-operation declaration. When {@link #usingContext(Class)} has been called,
 * either a {@code ContextMapper} or a {@code mapTo} lambda must be supplied; {@code mapFrom}
 * remains optional.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <P> the enclosing parent's context type
 * @param <N> the nested operation's context type; equals {@code P} until
 *            {@link #usingContext(Class)} narrows it
 */
public interface NestedOperationDef<T, P, N> extends OperationDef<T, N> {

    /**
     * Sets the human-readable name for this nested operation.
     *
     * @param name the name; may be {@code null} to clear
     *
     * @return this def for chaining
     */
    @Override
    NestedOperationDef<T, P, N> withName(String name);

    /**
     * Sets the description for this nested operation.
     *
     * @param description the description; may be {@code null} to clear
     *
     * @return this def for chaining
     */
    @Override
    NestedOperationDef<T, P, N> withDescription(String description);

    /**
     * Narrows the nested operation's context type. Calling this method re-generifies the
     * builder so that subsequent mapping calls are checked at compile time against the new
     * child context type.
     *
     * @param contextType the nested operation's context class; never {@code null}
     * @param <N2> the new child context type
     *
     * @return this def, re-typed with the new child context type
     *
     * @throws TransfluxValidationException if {@code contextType} is {@code null}
     */
    <N2> NestedOperationDef<T, P, N2> usingContext(Class<N2> contextType);

    /**
     * Configures a class-based {@link ContextMapper} for this nested operation. The framework
     * reflectively instantiates the mapper class through its public no-arg constructor when
     * the nested operation first runs.
     *
     * @param mapperClass the mapper class; never {@code null}
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code mapperClass} is {@code null}
     */
    NestedOperationDef<T, P, N> withContextMapping(Class<? extends ContextMapper<P, N>> mapperClass);

    /**
     * Configures an instance-based {@link ContextMapper} for this nested operation.
     *
     * @param mapper the mapper instance; never {@code null}
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code mapper} is {@code null}
     */
    NestedOperationDef<T, P, N> withContextMapping(ContextMapper<P, N> mapper);

    /**
     * Configures the parent-to-child mapping inline as a lambda. The supplied function is
     * invoked once per nested-operation execution, before the nested operation starts.
     *
     * @param mapper the parent-to-child mapping function; never {@code null}
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code mapper} is {@code null}
     */
    NestedOperationDef<T, P, N> mapTo(Function<P, N> mapper);

    /**
     * Configures the child-to-parent merge-back inline as a lambda. Optional even when
     * {@link #usingContext(Class)} is set — a nested operation whose results need not flow
     * back to the parent may omit this call.
     *
     * @param mapper the child-to-parent merge-back; never {@code null}
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code mapper} is {@code null}
     */
    NestedOperationDef<T, P, N> mapFrom(BiConsumer<P, N> mapper);
}
