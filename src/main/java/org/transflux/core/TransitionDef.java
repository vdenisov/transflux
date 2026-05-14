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

package org.transflux.core;

import java.util.function.Consumer;

/**
 * Definition interface for transitions between states in a state machine.
 * <p>
 * TransitionDef represents the configuration and metadata for a transition, including the
 * unique identifier, source state, target state, and the operation that runs while the
 * transition is in flight.
 *
 * <p>TransitionDef instances are created internally by the framework when transitions are
 * registered through the fluent API and should not be instantiated directly by client code.
 *
 * <p><b>Attaching operations.</b> A transition carries at most one operation. The operation
 * is either <i>simple</i> (a single {@link Operation} executing all the business logic on its
 * own) or <i>composite</i> (an ordered list of reusable {@link Step}s). The fluent API exposes
 * both kinds symmetrically:
 *
 * <pre>{@code
 * // Simple, no extra configuration (class form):
 * .simpleOperation("activate", ActivateOperation.class)
 *
 * // Simple, no extra configuration (instance form):
 * .simpleOperation("activate", new ActivateOperation())
 *
 * // Simple, with name/description set inside the configurer:
 * .simpleOperation("activate", op -> op
 *     .name("Activate Subscription")
 *     .description("Marks the subscription active and bills the first period")
 *     .using(ActivateOperation.class))
 *
 * // Composite, with an ordered list of steps:
 * .compositeOperation("validate-and-pay", composite -> composite
 *     .name("Validate and Charge")
 *     .step("validate-cart")
 *     .step("compute-total")
 *     .step("charge", ChargeStep.class))
 *
 * // Sugar for a single-step composite that references a registered step:
 * .step("validate-cart")
 * }</pre>
 *
 * Each method returns {@code TransitionDef<T, C>} so chained calls stay scoped to the
 * transition. The configurer forms grant temporary write access to the underlying operation
 * def; the def is not exposed to the caller after the lambda returns, which keeps the
 * operation immutable from the moment it is attached.
 *
 * @param <T> the entity type managed by the enclosing state machine
 * @param <C> the host-supplied context type carried through transition execution
 */
public interface TransitionDef<T, C> extends OperationlessTransitionDef<T, C>, Identifiable {

    /**
     * Returns the unique identifier of this transition.
     *
     * @return the transition ID
     */
    @Override
    String getId();

    /**
     * Returns the ID of the source state for this transition.
     *
     * @return the source state ID
     */
    String getSourceStateId();

    /**
     * Returns the ID of the target state for this transition.
     *
     * @return the target state ID
     */
    String getTargetStateId();

    /**
     * Attaches a simple operation using a pre-constructed {@link Operation} instance.
     *
     * @param id the operation id; never {@code null} or blank
     * @param operation the operation instance; never {@code null}
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank or
     *         {@code operation} is {@code null}
     */
    TransitionDef<T, C> simpleOperation(String id, Operation<T, C> operation);

    /**
     * Attaches a simple operation using an {@link Operation} class. The framework instantiates
     * it via its public no-arg constructor at state machine build time.
     *
     * @param id the operation id; never {@code null} or blank
     * @param operationClass the operation class; never {@code null}
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank or
     *         {@code operationClass} is {@code null}
     */
    TransitionDef<T, C> simpleOperation(String id, Class<? extends Operation<T, C>> operationClass);

    /**
     * Attaches a simple operation built through a fluent configurer. Use this form when you
     * want to set {@code name} / {@code description} alongside the operation source.
     * <p>
     * The configurer is invoked synchronously against a freshly-constructed
     * {@link SimpleOperationDef} carrying the supplied {@code id}; it must call
     * {@code .using(...)} before returning. The def is not exposed to the caller after the
     * lambda returns.
     *
     * @param id the operation id; never {@code null} or blank
     * @param configurer the fluent configurer; never {@code null}
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank,
     *         {@code configurer} is {@code null}, or the configurer leaves the def without
     *         an operation source
     */
    TransitionDef<T, C> simpleOperation(String id, Consumer<SimpleOperationDef<T, C>> configurer);

    /**
     * Attaches a composite operation built through a fluent configurer. The composite must
     * declare at least one step.
     * <p>
     * The configurer is invoked synchronously against a freshly-constructed
     * {@link CompositeOperationDef} carrying the supplied {@code id}; it must append at least
     * one step before returning. The def is not exposed to the caller after the lambda returns.
     *
     * @param id the operation id; never {@code null} or blank
     * @param configurer the fluent configurer; never {@code null}
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank,
     *         {@code configurer} is {@code null}, or the configurer leaves the composite
     *         without any steps
     */
    TransitionDef<T, C> compositeOperation(String id, Consumer<CompositeOperationDef<T, C>> configurer);

    /**
     * Convenience: attaches a single-step composite operation that references a step already
     * registered on the enclosing state machine. The composite is assigned a deterministic id
     * derived from this transition's id ({@code "transition-{transitionId}-op"}).
     *
     * @param registeredStepId the registered step id
     *
     * @return this transition def for chaining
     *
     * @throws TransfluxValidationException if {@code registeredStepId} is {@code null} or blank
     */
    TransitionDef<T, C> step(String registeredStepId);
}
