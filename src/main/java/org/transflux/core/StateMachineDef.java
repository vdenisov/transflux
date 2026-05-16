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

import org.transflux.core.condition.Condition;
import org.transflux.core.exception.TransfluxValidationException;
import org.transflux.core.operation.CompositeOperationDef;
import org.transflux.core.operation.Step;
import org.transflux.core.state.StateApplier;
import org.transflux.core.state.StateDef;
import org.transflux.core.state.StateResolver;
import org.transflux.core.transition.TransitionDef;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Builder interface for defining and constructing state machines.
 * <p>
 * {@code StateMachineDef} provides the main fluent API entry point for creating state machine
 * definitions in Transflux. It manages the configuration of entity types, metadata
 * (name, description, version), state resolvers, states, and transitions, providing
 * a declarative DSL for building complex state machines in a readable and maintainable way.
 *
 * <p>The StateMachineDef supports method chaining throughout the definition process,
 * allowing for concise and expressive state machine configurations that can be easily
 * understood and maintained.
 *
 * <p><b>Example usage:</b>
 * <pre>{@code
 * StateMachine<Order, OrderContext> orderStateMachine = Transflux.defineStateMachine()
 *     .forEntityType(Order.class)
 *     .withName("Order Processing State Machine")
 *     .withDescription("Manages the lifecycle of customer orders")
 *     .withVersion("1.0")
 *     .withStateResolver(order -> order.getStatus())
 *     .state("pending")
 *         .withName("Pending Order")
 *         .withDescription("Order received but not yet processed")
 *         .transitionsTo("processing", "start-processing")
 *         .transitionsTo("cancelled", "cancel-order")
 *     .state("processing")
 *         .withName("Processing Order")
 *         .transitionsTo("shipped", "ship-order")
 *         .transitionsTo("cancelled", "cancel-order")
 *     .state("shipped")
 *         .withName("Shipped Order")
 *         .transitionsTo("delivered", "mark-delivered")
 *     .state("delivered")
 *         .withName("Delivered Order")
 *     .state("cancelled")
 *         .withName("Cancelled Order")
 *     .build();
 * }</pre>
 *
 * @param <T> the type of entity managed by the state machine being defined
 * @param <C> the host-supplied context type carried through transition execution
 */
public interface StateMachineDef<T, C> {

    StateMachineDef<T, C> forEntityType(Class<T> entityType);

    /**
     * Optionally records the context type the state machine will carry. The framework does
     * not enforce this at runtime (operations are wired against the generic {@code <T, C>}
     * pair); the value is retained for diagnostics and tooling.
     *
     * @param contextType the context class; never {@code null}
     *
     * @return this state machine def for chaining
     *
     * @throws TransfluxValidationException if {@code contextType} is {@code null}
     */
    StateMachineDef<T, C> forContextType(Class<C> contextType);

    /**
     * Opens a context-typed registration scope. The configurer registers reusable components
     * (steps, conditions, composite operations) tagged with {@code contextType}; the framework
     * verifies context compatibility at build time when a by-id reference resolves against them.
     *
     * <p>Multiple invocations of {@code useContext} are permitted and accumulate, both for the
     * same context class (further registrations land in the same logical bucket) and for
     * different context classes (each scope's registrations are tagged with their own class).
     *
     * <p>{@link Void} is a valid context class for components that do not consume a context.
     *
     * @param contextType the scope's context class; never {@code null}
     * @param configurer callback that performs the registrations; never {@code null}
     * @param <C2> the scope context class
     *
     * @return this state machine def for chaining
     *
     * @throws TransfluxValidationException if either argument is {@code null}
     */
    <C2> StateMachineDef<T, C> useContext(Class<C2> contextType, Consumer<ContextScope<T, C2>> configurer);

    /**
     * Sets the human-readable name for this state machine.
     * <p>
     * This method allows you to provide a descriptive name for the state machine
     * that can be used in documentation, user interfaces, and logging.
     *
     * @param name the human-readable name for this state machine
     *
     * @return this StateMachineDef instance for method chaining
     */
    StateMachineDef<T, C> withName(String name);

    /**
     * Sets the description for this state machine.
     * <p>
     * This method allows you to provide additional details about the state machine's
     * purpose, behavior, or business domain within your application.
     *
     * @param description the description for this state machine
     *
     * @return this StateMachineDef instance for method chaining
     */
    StateMachineDef<T, C> withDescription(String description);

    /**
     * Sets the version for this state machine definition.
     * <p>
     * This method allows you to specify a version identifier for the state machine
     * definition, which can be useful for versioning, deployment tracking, and
     * compatibility management.
     *
     * @param version the version identifier for this state machine definition
     *
     * @return this StateMachineDef instance for method chaining
     */
    StateMachineDef<T, C> withVersion(String version);

    /**
     * Sets the state resolver used to determine the current state of entities.
     * <p>
     * The state resolver is a critical component that bridges your domain entities
     * with the Transflux framework, allowing the state machine to understand the
     * current state of entities without imposing specific storage requirements.
     *
     * @param stateResolver the state resolver implementation
     *
     * @return this StateMachineDef instance for method chaining
     *
     * @throws TransfluxValidationException if the state resolver is null
     */
    StateMachineDef<T, C> withStateResolver(StateResolver<T> stateResolver);

    /**
     * Sets the state applier used to commit the new state to an entity after a successful
     * transition.
     * <p>
     * The state applier is the write-side counterpart to {@link StateResolver}. The framework
     * invokes it exactly once per successful transition, after all post-conditions have
     * passed and immediately before {@code onComplete} listeners are notified.
     *
     * <p>The applier is optional. If omitted, the framework will not write back any state
     * after a successful transition; this is appropriate for purely transient transitions
     * where the host discards the entity post-transition, or for hosts that mutate state
     * inside steps and do not need a separate finalization step.
     *
     * @param stateApplier the state applier implementation
     *
     * @return this StateMachineDef instance for method chaining
     *
     * @throws TransfluxValidationException if the state applier is null
     */
    StateMachineDef<T, C> withStateApplier(StateApplier<T> stateApplier);

    /**
     * Registers a step instance against this state machine under the given id.
     * <p>
     * The id must be non-blank and unique across the state machine. Re-registering an
     * identical {@link Step} instance under the same id is a no-op; registering a different
     * instance under an already-claimed id raises {@link TransfluxValidationException}.
     *
     * <p>Inline step references declared inside a {@link CompositeOperationDef} auto-register
     * on this state machine under their declared id, and the same uniqueness rule applies.
     *
     * @param id the step id
     * @param step the step instance; never {@code null}
     *
     * @return this state machine def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank, {@code step}
     *         is {@code null}, or another step is already registered under {@code id}
     */
    StateMachineDef<T, C> step(String id, Step<T, C> step);

    /**
     * Registers a step class against this state machine under the given id. The framework
     * reflectively instantiates the class via its public no-arg constructor when the state
     * machine is built.
     * <p>
     * Re-registering the same class under the same id is a no-op; registering a different
     * class under an already-claimed id raises {@link TransfluxValidationException}.
     *
     * @param id the step id
     * @param stepClass the step class; never {@code null}
     *
     * @return this state machine def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank, {@code stepClass}
     *         is {@code null}, or another step is already registered under {@code id}
     */
    StateMachineDef<T, C> step(String id, Class<? extends Step<T, C>> stepClass);

    /**
     * Registers a condition instance against this state machine under the given id.
     * <p>
     * The id must be non-blank and unique across the state machine's condition registry.
     * Re-registering an identical {@link Condition} instance under the same id is a no-op;
     * registering a different instance under an already-claimed id raises
     * {@link TransfluxValidationException}.
     *
     * @param id the condition id
     * @param condition the condition instance; never {@code null}
     *
     * @return this state machine def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank, {@code condition}
     *         is {@code null}, or another condition is already registered under {@code id}
     */
    StateMachineDef<T, C> condition(String id, Condition<T, C> condition);

    /**
     * Registers a condition class against this state machine under the given id. The framework
     * reflectively instantiates the class via its public no-arg constructor when the state
     * machine is built.
     * <p>
     * Re-registering the same class under the same id is a no-op; registering a different
     * class under an already-claimed id raises {@link TransfluxValidationException}.
     *
     * @param id the condition id
     * @param conditionClass the condition class; never {@code null}
     *
     * @return this state machine def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank,
     *         {@code conditionClass} is {@code null}, or another condition is already registered
     *         under {@code id}
     */
    StateMachineDef<T, C> condition(String id, Class<? extends Condition<T, C>> conditionClass);

    /**
     * Registers a {@link Predicate} over the entity as a condition under the given id. The
     * predicate is adapted into a {@link Condition} that ignores the context and the per-execution
     * transition view.
     *
     * @param id the condition id
     * @param predicate the predicate; never {@code null}
     *
     * @return this state machine def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank, {@code predicate}
     *         is {@code null}, or another condition is already registered under {@code id}
     */
    StateMachineDef<T, C> condition(String id, Predicate<T> predicate);

    /**
     * Registers a SpEL expression as a condition under the given id. The expression is parsed
     * lazily by the shared evaluator at first invocation; the entity is bound as the SpEL root
     * object, and the context and the per-execution transition view are bound as {@code #context}
     * and {@code #transition} respectively.
     *
     * @param id the condition id
     * @param spelExpression the SpEL expression text; never {@code null} or blank
     *
     * @return this state machine def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank,
     *         {@code spelExpression} is {@code null}/blank, or another condition is already
     *         registered under {@code id}
     */
    StateMachineDef<T, C> condition(String id, String spelExpression);

    /**
     * Begins defining a new state in the state machine.
     * <p>
     * This method creates a new state definition with the specified identifier
     * and returns a {@code StateDef} instance that allows you to configure
     * the state's properties and transitions using the fluent API.
     *
     * @param stateId the unique identifier for the new state
     *
     * @return a {@code StateDef} instance for configuring the new state
     *
     * @throws TransfluxValidationException if the state ID is null, blank, or already defined
     */
    StateDef<T, C> state(String stateId);

    /**
     * Begins defining a new state using an identifiable object.
     * <p>
     * This method creates a new state definition using the ID from the provided
     * identifiable object and returns a {@code StateDef} instance that allows you
     * to configure the state's properties and transitions using the fluent API.
     *
     * @param stateIdentifiable an identifiable object providing the state ID
     *
     * @return a {@code StateDef} instance for configuring the new state
     *
     * @throws TransfluxValidationException if the identifiable is null, its ID is null/blank, or already defined
     */
    StateDef<T, C> state(Identifiable stateIdentifiable);

    /**
     * Completes the state machine definition and creates the final {@code StateMachine} instance.
     * <p>
     * This method finalizes the state machine configuration, validates all
     * definitions, and returns a concrete implementation that can be used to
     * manage entity state transitions.
     *
     * @return a configured {@code StateMachine} instance ready for use
     *
     * @throws TransfluxValidationException if the state machine definition is incomplete or invalid
     */
    StateMachine<T, C> build();

    /**
     * Retrieves a transition definition between two specific states.
     * <p>
     * This method allows you to access a transition definition using the source
     * and target state identifiers, enabling further configuration of the transition's
     * properties such as operations, conditions, triggers, and listeners.
     * This is particularly useful for programmatic configuration after the initial
     * state machine definition.
     *
     * @param sourceStateId the identifier of the source state
     * @param targetStateId the identifier of the target state
     *
     * @return the transition definition between the specified states
     *
     * @throws TransfluxValidationException if either state ID is null/blank or if no transition exists between the specified states
     */
    TransitionDef<T, C> getTransition(String sourceStateId, String targetStateId);

    /**
     * Retrieves a transition definition by its unique identifier.
     * <p>
     * This method allows you to access a transition definition using its unique
     * transition identifier, enabling further configuration of the transition's
     * properties such as operations, conditions, triggers, and listeners.
     * This provides an alternative way to reference transitions when you know
     * the specific transition ID rather than the source and target states.
     *
     * @param transitionId the unique identifier of the transition
     *
     * @return the transition definition with the specified identifier
     *
     * @throws TransfluxValidationException if the transition ID is null/blank or if no transition exists with the specified ID
     */
    TransitionDef<T, C> getTransition(String transitionId);
}
