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
import org.transflux.core.operation.ContextMapper;
import org.transflux.core.operation.MapperDef;

import java.util.Map;
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
     * Resolves this reference into a runtime {@link ResolvedContextMapping} suitable for
     * dispatch. The by-id form looks up the registered {@link MapperDef} on the state machine;
     * the two inline forms wrap their captured value directly.
     *
     * @param stateMachine the state machine whose mapper registry the by-id form consults
     * @param enclosingId the id of the composite that declared this reference, surfaced in the
     *                    error message when a by-id reference does not resolve
     *
     * @return the resolved mapping
     *
     * @throws TransfluxValidationException if the by-id form references an unknown mapper id
     */
    ResolvedContextMapping resolve(StateMachineImpl<?> stateMachine, String enclosingId);

    /**
     * Validates this reference at build time against the call-site's scope context and the
     * referenced component's required context. Pass-through and by-id forms enforce assignment
     * compatibility; the two inline forms defer to first-dispatch (erasure prevents reliable
     * build-time introspection).
     *
     * @param scopeContext the call site's enclosing context type
     * @param scopeLabel a human-readable label for the call site, used in error messages
     *                   (e.g. {@code "transition 'submit'"})
     * @param kind the component kind, used in error messages (e.g. {@code "step"} or
     *             {@code "operation"})
     * @param memberId the referenced component's id, used in error messages
     * @param componentContext the referenced component's required context type
     * @param mapperRegistry the SM-level mapper registry, consulted by the by-id form
     *
     * @throws TransfluxValidationException on a context-compatibility violation
     */
    void validateAgainst(Class<?> scopeContext, String scopeLabel, String kind,
                         String memberId, Class<?> componentContext,
                         Map<String, MapperDefImpl<?, ?>> mapperRegistry);

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
    @SuppressWarnings("ClassEscapesDefinedScope")
    record PassThrough() implements MapperRef {
        static final PassThrough INSTANCE = new PassThrough();

        @Override
        public ResolvedContextMapping resolve(StateMachineImpl<?> stateMachine, String enclosingId) {
            return ResolvedContextMapping.passThrough();
        }

        @Override
        public void validateAgainst(Class<?> scopeContext, String scopeLabel, String kind,
                                    String memberId, Class<?> componentContext,
                                    Map<String, MapperDefImpl<?, ?>> mapperRegistry) {
            if (componentContext == Object.class || componentContext.isAssignableFrom(scopeContext)) {
                return;
            }
            throw new TransfluxValidationException(
                "Context type mismatch: " + scopeLabel + " (context " + scopeContext.getName()
                    + ") references " + kind + " '" + memberId
                    + "' declared for context " + componentContext.getName()
                    + " without a mapper; supply a mapper to bridge the boundary");
        }
    }

    /**
     * Variant referencing a {@link MapperDef} registered on the enclosing state machine. The
     * build-time check verifies the mapper exists and its parent / child type tokens align with
     * the call site's parent context and the called step or operation's required context.
     *
     * @param mapperId the mapper id; never {@code null} or blank
     */
    @SuppressWarnings("ClassEscapesDefinedScope")
    record ById(String mapperId) implements MapperRef {
        public ById {
            requireNotBlank(mapperId, "Mapper reference ID");
        }

        @Override
        public ResolvedContextMapping resolve(StateMachineImpl<?> stateMachine, String enclosingId) {
            MapperDef<?, ?> mapperDef = stateMachine.getDef().getMapperDef(mapperId);
            if (mapperDef == null) {
                throw new TransfluxValidationException(
                    "CompositeOperationDef '" + enclosingId + "' references unknown mapper id '"
                        + mapperId + "'");
            }
            @SuppressWarnings("unchecked")
            MapperDefImpl<Object, Object> impl = (MapperDefImpl<Object, Object>) mapperDef;
            return ResolvedContextMapping.mapped(impl.buildMapper());
        }

        @Override
        public void validateAgainst(Class<?> scopeContext, String scopeLabel, String kind,
                                    String memberId, Class<?> componentContext,
                                    Map<String, MapperDefImpl<?, ?>> mapperRegistry) {
            MapperDefImpl<?, ?> mapperDef = mapperRegistry.get(mapperId);
            if (mapperDef == null) {
                throw new TransfluxValidationException(
                    scopeLabel + " references unknown mapper '" + mapperId + "' at " + kind
                        + " '" + memberId + "'");
            }
            Class<?> mapperParent = mapperDef.parentType();
            Class<?> mapperChild = mapperDef.childType();
            if (!mapperParent.isAssignableFrom(scopeContext)) {
                throw new TransfluxValidationException(
                    "Mapper '" + mapperId + "' parent type " + mapperParent.getName()
                        + " is not assignable from " + scopeLabel + " context " + scopeContext.getName());
            }
            if (componentContext != Object.class && !componentContext.isAssignableFrom(mapperChild)) {
                throw new TransfluxValidationException(
                    "Mapper '" + mapperId + "' child type " + mapperChild.getName()
                        + " is not assignable to " + kind + " '" + memberId + "' context "
                        + componentContext.getName());
            }
        }
    }

    /**
     * Variant carrying an inline read-only parent-to-child function. The function is wrapped at
     * build time in a {@link ContextMapper} whose {@code mapFrom} is the default no-op.
     *
     * @param fn the parent-to-child projection; never {@code null}
     */
    @SuppressWarnings("ClassEscapesDefinedScope")
    record InlineFunction(Function<?, ?> fn) implements MapperRef {
        public InlineFunction {
            requireNotNull(fn, "Inline mapper function");
        }

        @Override
        @SuppressWarnings("unchecked")
        public ResolvedContextMapping resolve(StateMachineImpl<?> stateMachine, String enclosingId) {
            Function<Object, Object> typed = (Function<Object, Object>) fn;
            ContextMapper<Object, Object> wrapper = typed::apply;
            return ResolvedContextMapping.mapped(wrapper);
        }

        @Override
        public void validateAgainst(Class<?> scopeContext, String scopeLabel, String kind,
                                    String memberId, Class<?> componentContext,
                                    Map<String, MapperDefImpl<?, ?>> mapperRegistry) {
            // Generic-parameter erasure prevents reliable build-time introspection of the
            // function's parent / child types; alignment is checked at first dispatch when the
            // user-supplied value is invoked.
        }
    }

    /**
     * Variant carrying an inline fully-supplied {@link ContextMapper}.
     *
     * @param mapper the mapper; never {@code null}
     */
    @SuppressWarnings("ClassEscapesDefinedScope")
    record InlineMapper(ContextMapper<?, ?> mapper) implements MapperRef {
        public InlineMapper {
            requireNotNull(mapper, "Inline mapper instance");
        }

        @Override
        @SuppressWarnings("unchecked")
        public ResolvedContextMapping resolve(StateMachineImpl<?> stateMachine, String enclosingId) {
            return ResolvedContextMapping.mapped((ContextMapper<Object, Object>) mapper);
        }

        @Override
        public void validateAgainst(Class<?> scopeContext, String scopeLabel, String kind,
                                    String memberId, Class<?> componentContext,
                                    Map<String, MapperDefImpl<?, ?>> mapperRegistry) {
            // Same erasure constraint as InlineFunction — deferred to first dispatch.
        }
    }
}
