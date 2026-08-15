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

import org.transflux.core.action.Action;
import org.transflux.core.action.ActionKind;
import org.transflux.core.action.OperationDef;
import org.transflux.core.exception.TransfluxValidationException;

import java.util.Optional;

import static org.transflux.core.Preconditions.requireNotBlank;
import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Package-private reference to an action inside a composite operation's declaration-time member
 * list.
 * <p>
 * A member is either named by id - the callee is registered elsewhere, and which form it was
 * authored in is a property of that registration rather than of this call site - or declared
 * inline, in which case the declaring form is captured as an {@link ActionKind} and travels onto
 * the bound record. Either way there is a single {@linkplain #resolve(StateMachineImpl, Registry,
 * String) resolve} that hands back a {@link BoundAction}; the composite executor never has to ask
 * what kind of member it is holding.
 * <p>
 * By-id references carry a {@link MapperRef} capturing the call-site mapper choice (pass-through,
 * registered by id, inline function, or inline mapper instance). Inline declarations always carry
 * {@link MapperRef#passThrough()} - they declare an action against the enclosing composite's own
 * context type and therefore need no boundary mapping.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
sealed interface ActionRef<T, C>
    permits ActionRef.ById, ActionRef.InlineInstance, ActionRef.InlineClass, ActionRef.Conditional {

    String id();

    /**
     * Returns the call-site mapper reference for this member. By-id references override; inline
     * declarations default to {@link MapperRef#passThrough()}.
     *
     * @return the mapper reference; never {@code null}
     */
    default MapperRef mapperRef() {
        return MapperRef.passThrough();
    }

    /**
     * Resolves this reference against the enclosing composite's lexical-scope {@link Registry}
     * and returns the matching {@link BoundAction}.
     *
     * @param stateMachine the enclosing state machine, retained for error reporting
     * @param scopeRegistry the enclosing composite's scope registry; resolution walks the
     *                      parent chain up to the state-machine root
     * @param enclosingCompositeId the id of the composite that declared this reference, surfaced
     *                             in the error message when it does not resolve
     *
     * @return the bound action; never {@code null}
     *
     * @throws TransfluxValidationException if no entry is registered under {@link #id()} in
     *         the scope chain, or the matched entry is not an action
     */
    @SuppressWarnings("unchecked")
    default BoundAction<T, C> resolve(StateMachineImpl<T> stateMachine, Registry<T> scopeRegistry,
                                      String enclosingCompositeId) {
        Optional<Component<T>> resolved = scopeRegistry.resolve(id());
        if (resolved.isEmpty()) {
            throw new TransfluxValidationException(
                unknownIdMessage(id(), stateMachine, enclosingCompositeId));
        }

        Component<T> component = resolved.get();
        if (!(component instanceof Component.Action<T, ?> action)) {
            throw new TransfluxValidationException(
                "OperationDef '" + enclosingCompositeId
                    + "' references id '" + id() + "' which is registered as a "
                    + component.getClass().getSimpleName().toLowerCase()
                    + ", not an action");
        }

        return (BoundAction<T, C>) action.bound();
    }

    /**
     * Deposits any inline-declaration payloads this reference carries into the supplied sink.
     * By-id references no-op (they contribute nothing to the enclosing composite's local scope);
     * inline declarations push themselves into the sink; the {@link Conditional} variant recurses
     * into its branches' inline members and then registers the conditional's own bound action.
     * Drives the scope-binding pass on {@link OperationDefImpl}.
     */
    default void collectInlineRegistrations(InlineRegistrationSink<T, C> sink) {
        // by-id references contribute no inline registration; overridden in the inline variants
    }

    /**
     * Builds the "unknown id" diagnostic for a failing resolution, enriching it with
     * sibling-scope information when an inline declaration of the same id exists in another
     * composite under the SM.
     */
    static String unknownIdMessage(String id, StateMachineImpl<?> stateMachine,
                                   String enclosingCompositeId) {
        String base = "OperationDef '" + enclosingCompositeId
            + "' references unknown action id '" + id + "' in its scope";
        return stateMachine.findInlineSiblingScope(id, enclosingCompositeId)
            .map(siblingId -> base + ". An inline action with this id is registered in sibling composite '"
                + siblingId + "' — inline registrations are only visible inside their own composite's subtree."
                + " Move to SM root if shared use is intended.")
            .orElse(base);
    }

    static <T, C> ActionRef<T, C> byId(String id) {
        return new ById<>(id, MapperRef.passThrough());
    }

    static <T, C> ActionRef<T, C> byId(String id, MapperRef mapperRef) {
        return new ById<>(id, mapperRef);
    }

    static <T, C> ActionRef<T, C> inline(String id, Action<T, C> action, ActionKind kind) {
        return new InlineInstance<>(id, action, kind);
    }

    static <T, C> ActionRef<T, C> inline(String id, Class<? extends Action<T, C>> actionClass,
                                         ActionKind kind) {
        return new InlineClass<>(id, actionClass, kind);
    }

    static <T, C> ActionRef<T, C> conditional(String id, ConditionalOperationDefImpl<T, C> def) {
        return new Conditional<>(id, def);
    }

    record ById<T, C>(String id, MapperRef mapperRef) implements ActionRef<T, C> {
        public ById {
            requireNotBlank(id, "Action reference ID");
            requireNotNull(mapperRef, "Mapper reference");
        }
    }

    @SuppressWarnings("ClassEscapesDefinedScope")
    record InlineInstance<T, C>(String id, Action<T, C> action, ActionKind kind)
        implements ActionRef<T, C> {

        public InlineInstance {
            requireNotBlank(id, "Action reference ID");
            requireNotNull(action, "Inline action instance");
            requireNotNull(kind, "Inline action kind");
        }

        @Override
        public void collectInlineRegistrations(InlineRegistrationSink<T, C> sink) {
            sink.registerInlineAction(id, action, kind);
        }
    }

    @SuppressWarnings("ClassEscapesDefinedScope")
    record InlineClass<T, C>(String id, Class<? extends Action<T, C>> actionClass, ActionKind kind)
        implements ActionRef<T, C> {

        public InlineClass {
            requireNotBlank(id, "Action reference ID");
            requireNotNull(actionClass, "Inline action class");
            requireNotNull(kind, "Inline action kind");
        }

        @Override
        public void collectInlineRegistrations(InlineRegistrationSink<T, C> sink) {
            sink.registerInlineActionClass(id, actionClass, kind);
        }
    }

    @SuppressWarnings("ClassEscapesDefinedScope")
    record Conditional<T, C>(String id, ConditionalOperationDefImpl<T, C> def) implements ActionRef<T, C> {
        public Conditional {
            requireNotBlank(id, "Action reference ID");
            requireNotNull(def, "Conditional operation def");
        }

        @Override
        public void collectInlineRegistrations(InlineRegistrationSink<T, C> sink) {
            def.collectInlineRegistrations(sink);
            sink.registerConditional(id, def);
        }
    }
}
