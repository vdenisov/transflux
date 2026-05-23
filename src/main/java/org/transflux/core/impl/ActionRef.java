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

import org.transflux.core.impl.Component;
import org.transflux.core.impl.Registry;
import org.transflux.core.impl.StateMachineImpl;
import org.transflux.core.exception.TransfluxValidationException;

import java.util.Optional;

import static org.transflux.core.Preconditions.requireNotBlank;
import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Package-private discriminated reference to an action inside a composite operation's
 * declaration-time action list.
 * <p>
 * An action is either a step (recorded via the {@code step(...)} overloads on
 * {@link CompositeOperationDef}) or a nested operation (recorded via the {@code operation(...)}
 * overloads). The partitioning is expressed through two sealed sub-interfaces —
 * {@link StepRef} and {@link OperationRef} — each of which knows how to
 * {@linkplain #resolve(StateMachineImpl, org.transflux.core.Registry, String) resolve} itself
 * against the enclosing composite's lexical scope registry. The composite executor never has
 * to ask which kind a ref is; it just calls {@code resolve}.
 * <p>
 * By-id variants carry a {@link MapperRef} capturing the call-site mapper choice (pass-through,
 * registered by id, inline function, or inline mapper instance). Inline-registration variants
 * always carry {@link MapperRef#passThrough()} — they declare a step or operation against the
 * enclosing composite's context type and therefore need no boundary mapping.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
sealed interface ActionRef<T, C> permits ActionRef.StepRef, ActionRef.OperationRef {

    String id();

    /**
     * Returns the call-site mapper reference for this action. By-id variants override; all
     * inline-registration variants default to {@link MapperRef#passThrough()}.
     *
     * @return the mapper reference; never {@code null}
     */
    default MapperRef mapperRef() {
        return MapperRef.passThrough();
    }

    /**
     * Resolves this ref against the enclosing composite's lexical-scope {@link Registry} and
     * returns the matching {@link BoundAction}. The two sealed sub-interfaces each pick the
     * correct {@link Component} variant — {@link StepRef} expects a {@link Component.Step},
     * {@link OperationRef} expects a {@link Component.Operation}.
     *
     * @param stateMachine the enclosing state machine, retained for error reporting
     * @param scopeRegistry the enclosing composite's scope registry; resolution walks the
     *                      parent chain up to the state-machine root
     * @param enclosingCompositeId the id of the composite that declared this ref, surfaced
     *                             in the error message when the ref does not resolve
     *
     * @return the bound action; never {@code null}
     *
     * @throws TransfluxValidationException if no entry is registered under {@link #id()} in
     *         the scope chain, or the matched entry is of the wrong kind
     */
    BoundAction<T, C> resolve(StateMachineImpl<T> stateMachine, Registry<T> scopeRegistry,
                              String enclosingCompositeId);

    static <T, C> ActionRef<T, C> byId(String id) {
        return new ById<>(id, MapperRef.passThrough());
    }

    static <T, C> ActionRef<T, C> byId(String id, MapperRef mapperRef) {
        return new ById<>(id, mapperRef);
    }

    static <T, C> ActionRef<T, C> inline(String id, Step<T, C> step) {
        return new InlineInstance<>(id, step);
    }

    static <T, C> ActionRef<T, C> inline(String id, Class<? extends Step<T, C>> stepClass) {
        return new InlineClass<>(id, stepClass);
    }

    static <T, C> ActionRef<T, C> conditional(String id, ConditionalStepDefImpl<T, C> def) {
        return new Conditional<>(id, def);
    }

    static <T, C> ActionRef<T, C> operationById(String id) {
        return new OperationById<>(id, MapperRef.passThrough());
    }

    static <T, C> ActionRef<T, C> operationById(String id, MapperRef mapperRef) {
        return new OperationById<>(id, mapperRef);
    }

    static <T, C> ActionRef<T, C> operationInline(String id, Operation<T, C> operation) {
        return new OperationInlineInstance<>(id, operation);
    }

    static <T, C> ActionRef<T, C> operationInline(String id, Class<? extends Operation<T, C>> operationClass) {
        return new OperationInlineClass<>(id, operationClass);
    }

    /**
     * Marker sub-interface for refs that resolve to a {@link Component.Step} entry in the
     * composite's lexical scope.
     *
     * @param <T> the entity type the surrounding state machine manages
     * @param <C> the host-supplied context type carried through transition execution
     */
    sealed interface StepRef<T, C> extends ActionRef<T, C>
        permits ActionRef.ById, ActionRef.InlineInstance, ActionRef.InlineClass, ActionRef.Conditional {

        @Override
        @SuppressWarnings({"unchecked"})
        default BoundAction<T, C> resolve(StateMachineImpl<T> stateMachine, Registry<T> scopeRegistry,
                                          String enclosingCompositeId) {
            Optional<Component<T>> resolved = scopeRegistry.resolve(id());
            if (resolved.isEmpty()) {
                throw new TransfluxValidationException(
                    "CompositeOperationDef '" + enclosingCompositeId
                        + "' references unknown step id '" + id() + "' in its scope");
            }

            Component<T> component = resolved.get();
            if (!(component instanceof Component.Step<T, ?> step)) {
                throw new TransfluxValidationException(
                    "CompositeOperationDef '" + enclosingCompositeId
                        + "' references id '" + id() + "' which is registered as a "
                        + component.getClass().getSimpleName().toLowerCase()
                        + ", not a step");
            }

            return (BoundAction<T, C>) step.bound();
        }
    }

    /**
     * Marker sub-interface for refs that resolve to a {@link Component.Operation} entry in the
     * composite's lexical scope.
     *
     * @param <T> the entity type the surrounding state machine manages
     * @param <C> the host-supplied context type carried through transition execution
     */
    sealed interface OperationRef<T, C> extends ActionRef<T, C>
        permits ActionRef.OperationById,
                ActionRef.OperationInlineInstance,
                ActionRef.OperationInlineClass {

        @Override
        @SuppressWarnings({"unchecked"})
        default BoundAction<T, C> resolve(StateMachineImpl<T> stateMachine, Registry<T> scopeRegistry,
                                          String enclosingCompositeId) {
            Optional<Component<T>> resolved = scopeRegistry.resolve(id());
            if (resolved.isEmpty()) {
                throw new TransfluxValidationException(
                    "CompositeOperationDef '" + enclosingCompositeId
                        + "' references unknown operation id '" + id() + "' in its scope");
            }

            Component<T> component = resolved.get();
            if (!(component instanceof Component.Operation<T, ?> op)) {
                throw new TransfluxValidationException(
                    "CompositeOperationDef '" + enclosingCompositeId
                        + "' references id '" + id() + "' which is registered as a "
                        + component.getClass().getSimpleName().toLowerCase()
                        + ", not an operation");
            }

            return (BoundAction<T, C>) op.bound();
        }
    }

    record ById<T, C>(String id, MapperRef mapperRef) implements StepRef<T, C> {
        public ById {
            requireNotBlank(id, "Step reference ID");
            requireNotNull(mapperRef, "Mapper reference");
        }
    }

    record InlineInstance<T, C>(String id, Step<T, C> step) implements StepRef<T, C> {
        public InlineInstance {
            requireNotBlank(id, "Step reference ID");
            requireNotNull(step, "Inline step instance");
        }
    }

    record InlineClass<T, C>(String id, Class<? extends Step<T, C>> stepClass) implements StepRef<T, C> {
        public InlineClass {
            requireNotBlank(id, "Step reference ID");
            requireNotNull(stepClass, "Inline step class");
        }
    }

    record Conditional<T, C>(String id, ConditionalStepDefImpl<T, C> def) implements StepRef<T, C> {
        public Conditional {
            requireNotBlank(id, "Step reference ID");
            requireNotNull(def, "Conditional step def");
        }
    }

    record OperationById<T, C>(String id, MapperRef mapperRef) implements OperationRef<T, C> {
        public OperationById {
            requireNotBlank(id, "Operation reference ID");
            requireNotNull(mapperRef, "Mapper reference");
        }
    }

    record OperationInlineInstance<T, C>(String id, Operation<T, C> operation) implements OperationRef<T, C> {
        public OperationInlineInstance {
            requireNotBlank(id, "Operation reference ID");
            requireNotNull(operation, "Inline operation instance");
        }
    }

    record OperationInlineClass<T, C>(String id, Class<? extends Operation<T, C>> operationClass) implements OperationRef<T, C> {
        public OperationInlineClass {
            requireNotBlank(id, "Operation reference ID");
            requireNotNull(operationClass, "Inline operation class");
        }
    }
}
