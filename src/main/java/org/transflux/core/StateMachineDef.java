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
import org.transflux.core.operation.ContextMapper;
import org.transflux.core.operation.Operation;
import org.transflux.core.operation.Step;
import org.transflux.core.state.StateApplier;
import org.transflux.core.state.StateDef;
import org.transflux.core.state.StateResolver;
import org.transflux.core.transition.TransitionDef;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Builder interface for defining and constructing state machines.
 * <p>
 * {@code StateMachineDef} provides the main fluent API entry point for creating state machine
 * definitions in Transflux. It manages the configuration of entity types, metadata
 * (name, description, version), state resolvers, states, and transitions, providing
 * a declarative DSL for building complex state machines in a readable and maintainable way.
 *
 * <p>The state machine is not parameterized by a single context type. Each transition declares
 * its own context type — either by {@code transitionsTo(target, id, Class<C>, configurer)} or
 * by calling {@link TransitionDef#usingContext(Class)} inside the transition configurer body.
 * SM-level reusable components (steps, conditions, operations) are registered with an optional
 * explicit {@link Class} context tag via the typed overloads below, or grouped under a
 * {@link #forContext(Class, Consumer) forContext} scope.
 *
 * @param <T> the type of entity managed by the state machine being defined
 */
public interface StateMachineDef<T> {

    StateMachineDef<T> forEntityType(Class<T> entityType);

    /**
     * Opens a context-typed registration scope. The configurer registers reusable components
     * (steps, conditions, composite operations) tagged with {@code contextType}; the framework
     * verifies context compatibility at build time when a by-id reference resolves against them.
     *
     * <p>Multiple invocations of {@code forContext} are permitted and accumulate, both for the
     * same context class (further registrations land in the same logical bucket) and for
     * different context classes (each scope's registrations are tagged with their own class).
     *
     * <p>{@link Void} is a valid context class for components that do not consume a context.
     *
     * @param contextType the scope's context class; never {@code null}
     * @param configurer callback that performs the registrations; never {@code null}
     * @param <C> the scope context class
     *
     * @return this state machine def for chaining
     *
     * @throws TransfluxValidationException if either argument is {@code null}
     */
    <C> StateMachineDef<T> forContext(Class<C> contextType, Consumer<ContextScope<T, C>> configurer);

    StateMachineDef<T> withName(String name);

    StateMachineDef<T> withDescription(String description);

    StateMachineDef<T> withVersion(String version);

    StateMachineDef<T> withStateResolver(StateResolver<T> stateResolver);

    StateMachineDef<T> withStateApplier(StateApplier<T> stateApplier);

    /**
     * Registers a step instance against this state machine under the given id, without a
     * declared context type. The step's context parameter is treated as {@link Object} at
     * the registry level; the actual context value is whatever the referencing transition
     * supplies at fire time.
     *
     * @param id the step id
     * @param step the step instance; never {@code null}
     *
     * @return this state machine def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank, {@code step}
     *         is {@code null}, or another step is already registered under {@code id}
     */
    StateMachineDef<T> step(String id, Step<T, ?> step);

    /**
     * Registers a step class against this state machine under the given id, without a
     * declared context type.
     *
     * @param id the step id
     * @param stepClass the step class; never {@code null}
     *
     * @return this state machine def for chaining
     */
    StateMachineDef<T> step(String id, Class<? extends Step<T, ?>> stepClass);

    /**
     * Registers a step instance against this state machine under the given id, tagged with
     * the supplied context class. Equivalent to a {@code useContext(contextType, scope -> scope.step(...))}
     * call but expressed inline.
     *
     * @param id the step id
     * @param contextType the step's declared context class
     * @param step the step instance
     * @param <C> the context class
     *
     * @return this state machine def for chaining
     */
    <C> StateMachineDef<T> step(String id, Class<C> contextType, Step<T, C> step);

    /**
     * Registers a step class against this state machine under the given id, tagged with
     * the supplied context class.
     *
     * @param id the step id
     * @param contextType the step's declared context class
     * @param stepClass the step class
     * @param <C> the context class
     *
     * @return this state machine def for chaining
     */
    <C> StateMachineDef<T> step(String id, Class<C> contextType, Class<? extends Step<T, C>> stepClass);

    /**
     * Registers a condition instance against this state machine under the given id, without
     * a declared context type.
     */
    StateMachineDef<T> condition(String id, Condition<T, ?> condition);

    /**
     * Registers a condition class against this state machine under the given id, without
     * a declared context type.
     */
    StateMachineDef<T> condition(String id, Class<? extends Condition<T, ?>> conditionClass);

    /**
     * Registers an entity-only predicate as a condition under the given id.
     */
    StateMachineDef<T> condition(String id, Predicate<T> predicate);

    /**
     * Registers a SpEL expression as a condition under the given id.
     */
    StateMachineDef<T> condition(String id, String spelExpression);

    /**
     * Registers a condition instance against this state machine under the given id, tagged
     * with the supplied context class.
     */
    <C> StateMachineDef<T> condition(String id, Class<C> contextType, Condition<T, C> condition);

    /**
     * Registers a condition class against this state machine under the given id, tagged
     * with the supplied context class.
     */
    <C> StateMachineDef<T> condition(String id, Class<C> contextType, Class<? extends Condition<T, C>> conditionClass);

    /**
     * Registers an entity-only predicate as a condition under the given id, tagged with
     * the supplied context class.
     */
    <C> StateMachineDef<T> conditionPredicate(String id, Class<C> contextType, Predicate<T> predicate);

    /**
     * Registers a SpEL expression as a condition under the given id, tagged with the
     * supplied context class.
     */
    <C> StateMachineDef<T> conditionExpression(String id, Class<C> contextType, String spelExpression);

    /**
     * Registers a composite operation against this state machine under the given id, tagged
     * with the supplied context class. The configurer is invoked synchronously against a
     * freshly-constructed composite def.
     */
    <C> StateMachineDef<T> compositeOperation(String id, Class<C> contextType, Consumer<CompositeOperationDef<T, C>> configurer);

    /**
     * Registers an {@link Operation} instance against this state machine under the given id,
     * tagged with the supplied context class. The registered operation can be referenced by id
     * from any number of call sites — composite members, {@code TransitionView.operation(...)}
     * dispatches — that either pass through a compatible parent context or supply a mapper
     * whose child type matches {@code contextType}.
     *
     * @param id the operation id
     * @param contextType the operation's declared context class; never {@code null}
     * @param operation the operation instance; never {@code null}
     * @param <C> the operation's context type
     *
     * @return this state machine def for chaining
     */
    <C> StateMachineDef<T> operation(String id, Class<C> contextType, Operation<T, C> operation);

    /**
     * Registers an {@link Operation} class against this state machine under the given id,
     * tagged with the supplied context class. The framework instantiates it via its public
     * no-arg constructor at build time.
     *
     * @param id the operation id
     * @param contextType the operation's declared context class; never {@code null}
     * @param operationClass the operation class; never {@code null}
     * @param <C> the operation's context type
     *
     * @return this state machine def for chaining
     */
    <C> StateMachineDef<T> operation(String id, Class<C> contextType, Class<? extends Operation<T, C>> operationClass);

    /**
     * Registers a {@link ContextMapper} instance against this state machine under the given id,
     * with explicit parent and child type tokens. The registered mapper can be referenced by id
     * from any call site (composite member, {@code TransitionView.operation(...)} dispatch,
     * {@code async} block) where its parent type is assignable from the call site's context and
     * its child type matches the called step or operation's required context.
     *
     * @param id the mapper id
     * @param parentType the parent context class
     * @param childType the child context class
     * @param mapper the mapper instance; never {@code null}
     * @param <P> the parent context type
     * @param <N> the child context type
     *
     * @return this state machine def for chaining
     *
     * @throws TransfluxValidationException if any argument is {@code null}/blank or another
     *         mapper is already registered under {@code id}
     */
    <P, N> StateMachineDef<T> mapper(String id, Class<P> parentType, Class<N> childType,
                                     ContextMapper<P, N> mapper);

    /**
     * Registers a {@link ContextMapper} class against this state machine under the given id.
     * The framework instantiates it via its public no-arg constructor at build time.
     *
     * @param id the mapper id
     * @param parentType the parent context class
     * @param childType the child context class
     * @param mapperClass the mapper class; never {@code null}
     * @param <P> the parent context type
     * @param <N> the child context type
     *
     * @return this state machine def for chaining
     */
    <P, N> StateMachineDef<T> mapper(String id, Class<P> parentType, Class<N> childType,
                                     Class<? extends ContextMapper<P, N>> mapperClass);

    /**
     * Registers a read-only parent-to-child function as a mapper. The framework wraps it in a
     * {@link ContextMapper} whose {@link ContextMapper#mapFrom(Object, Object) mapFrom} is the
     * default no-op — appropriate when the called step or operation has no results to fold back
     * into the parent's context.
     *
     * @param id the mapper id
     * @param parentType the parent context class
     * @param childType the child context class
     * @param mapTo the parent-to-child projection; never {@code null}
     * @param <P> the parent context type
     * @param <N> the child context type
     *
     * @return this state machine def for chaining
     */
    <P, N> StateMachineDef<T> mapper(String id, Class<P> parentType, Class<N> childType,
                                     Function<P, N> mapTo);

    /**
     * Declares a state on this state machine. The configurer is invoked synchronously against
     * a freshly-constructed {@link StateDef} carrying {@code stateId}; the def becomes inert
     * once the lambda returns.
     *
     * @param stateId the state ID; never {@code null} or blank
     * @param configurer callback that configures the state; never {@code null}
     *
     * @return this state machine def for chaining
     *
     * @throws TransfluxValidationException if either argument is {@code null}, {@code stateId}
     *         is blank, or another state with the same id has already been declared
     */
    StateMachineDef<T> state(String stateId, Consumer<StateDef<T>> configurer);

    /**
     * Declares a state on this state machine using an {@link Identifiable} as the id source.
     *
     * @param stateIdentifiable an identifiable providing the state id; never {@code null}
     * @param configurer callback that configures the state; never {@code null}
     *
     * @return this state machine def for chaining
     *
     * @throws TransfluxValidationException if either argument is {@code null}, the id is blank,
     *         or another state with the same id has already been declared
     */
    StateMachineDef<T> state(Identifiable stateIdentifiable, Consumer<StateDef<T>> configurer);

    StateMachine<T> build();

    TransitionDef<T, ?> getTransition(String sourceStateId, String targetStateId);

    TransitionDef<T, ?> getTransition(String transitionId);
}
