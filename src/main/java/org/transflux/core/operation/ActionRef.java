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

import org.transflux.core.StateMachineImpl;
import org.transflux.core.exception.TransfluxValidationException;

import static org.transflux.core.ValidationUtils.requireNotBlank;
import static org.transflux.core.ValidationUtils.requireNotNull;

/**
 * Package-private discriminated reference to an action inside a composite operation's
 * declaration-time action list.
 * <p>
 * An action is either a step (the historical kind, recorded via the {@code step(...)}
 * overloads on {@link CompositeOperationDef}) or a nested operation (recorded via the
 * {@code operation(...)} overloads). The partitioning is expressed through two sealed
 * sub-interfaces — {@link StepRef} and {@link OperationRef} — each of which knows how to
 * {@linkplain #resolve(StateMachineImpl, String) resolve} itself against the appropriate
 * registry on the enclosing state machine. The composite executor never has to ask which
 * kind a ref is; it just calls {@code resolve}.
 * <p>
 * Variants are grouped under their marker sub-interface:
 * <ul>
 *   <li>{@link StepRef} — refs that resolve against the step registry. Permits {@link ById},
 *       {@link InlineInstance}, {@link InlineClass}, {@link Conditional}.</li>
 *   <li>{@link OperationRef} — refs that resolve against the operation registry. Permits
 *       {@link OperationById}, {@link OperationInlineInstance}, {@link OperationInlineClass}.</li>
 * </ul>
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
sealed interface ActionRef<T, C> permits ActionRef.StepRef, ActionRef.OperationRef {

    String id();

    /**
     * Resolves this ref against the supplied state machine's registries and returns the
     * matching {@link BoundAction}. The two sealed sub-interfaces each pick the correct
     * registry — {@link StepRef} looks up steps, {@link OperationRef} looks up operations.
     *
     * @param stateMachine the enclosing state machine carrying the step and operation
     *                     registries
     * @param enclosingCompositeId the id of the composite that declared this ref, surfaced
     *                             in the error message when the ref does not resolve
     *
     * @return the bound action; never {@code null}
     *
     * @throws TransfluxValidationException if no entry is registered under {@link #id()} in
     *         the registry this ref resolves against
     */
    BoundAction<T, C> resolve(StateMachineImpl<T, C> stateMachine, String enclosingCompositeId);

    static <T, C> ActionRef<T, C> byId(String id) {
        return new ById<>(id);
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
        return new OperationById<>(id);
    }

    static <T, C> ActionRef<T, C> operationInline(String id, Operation<T, C> operation) {
        return new OperationInlineInstance<>(id, operation);
    }

    static <T, C> ActionRef<T, C> operationInline(String id, Class<? extends Operation<T, C>> operationClass) {
        return new OperationInlineClass<>(id, operationClass);
    }

    /**
     * Marker sub-interface for refs that resolve against the state machine's step registry.
     * The {@code resolve} default looks the id up via
     * {@link StateMachineImpl#getBoundStep(String)} and throws
     * {@link TransfluxValidationException} on miss.
     *
     * @param <T> the entity type the surrounding state machine manages
     * @param <C> the host-supplied context type carried through transition execution
     */
    sealed interface StepRef<T, C> extends ActionRef<T, C>
        permits ActionRef.ById, ActionRef.InlineInstance, ActionRef.InlineClass, ActionRef.Conditional {

        @Override
        default BoundAction<T, C> resolve(StateMachineImpl<T, C> stateMachine, String enclosingCompositeId) {
            BoundStep<T, C> bound = stateMachine.getBoundStep(id());
            if (bound == null) {
                throw new TransfluxValidationException(
                    "CompositeOperationDef '" + enclosingCompositeId
                        + "' references unknown step id '" + id() + "'");
            }
            return bound;
        }
    }

    /**
     * Marker sub-interface for refs that resolve against the state machine's operation
     * registry. The {@code resolve} default looks the id up via
     * {@link StateMachineImpl#getBoundOperation(String)} and throws
     * {@link TransfluxValidationException} on miss.
     *
     * @param <T> the entity type the surrounding state machine manages
     * @param <C> the host-supplied context type carried through transition execution
     */
    sealed interface OperationRef<T, C> extends ActionRef<T, C>
        permits ActionRef.OperationById, ActionRef.OperationInlineInstance, ActionRef.OperationInlineClass {

        @Override
        default BoundAction<T, C> resolve(StateMachineImpl<T, C> stateMachine, String enclosingCompositeId) {
            BoundOperation<T, C> bound = stateMachine.getBoundOperation(id());
            if (bound == null) {
                throw new TransfluxValidationException(
                    "CompositeOperationDef '" + enclosingCompositeId
                        + "' references unknown operation id '" + id() + "'");
            }
            return bound;
        }
    }

    record ById<T, C>(String id) implements StepRef<T, C> {
        public ById {
            requireNotBlank(id, "Step reference ID");
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

    record OperationById<T, C>(String id) implements OperationRef<T, C> {
        public OperationById {
            requireNotBlank(id, "Operation reference ID");
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
