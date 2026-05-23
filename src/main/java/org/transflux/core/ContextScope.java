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
import org.transflux.core.operation.Operation;
import org.transflux.core.operation.Step;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Context-typed registration scope handed to a {@link StateMachineDef#forContext(Class, Consumer)}
 * configurer.
 * <p>
 * Every reusable component (step, condition, composite operation) registered via this scope is
 * tagged with the scope's context class {@code C}. The framework uses that tag at build time
 * to verify that by-id references resolve against a component declared for the referring
 * scope's context type.
 *
 * <p>{@link Void} is a valid context class for components that do not consume a context.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the context class this scope binds to
 */
public interface ContextScope<T, C> {

    /**
     * Registers a step instance under {@code id}, tagged with this scope's context class.
     *
     * @param id the step id
     * @param step the step instance; never {@code null}
     *
     * @return this scope for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank, {@code step}
     *         is {@code null}, or another component is already registered under {@code id}
     */
    ContextScope<T, C> step(String id, Step<T, C> step);

    /**
     * Registers a step class under {@code id}, tagged with this scope's context class. The
     * framework instantiates the class via its public no-arg constructor at build time.
     *
     * @param id the step id
     * @param stepClass the step class; never {@code null}
     *
     * @return this scope for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank,
     *         {@code stepClass} is {@code null}, or another component is already registered
     *         under {@code id}
     */
    ContextScope<T, C> step(String id, Class<? extends Step<T, C>> stepClass);

    /**
     * Registers a condition instance under {@code id}, tagged with this scope's context class.
     *
     * @param id the condition id
     * @param condition the condition instance; never {@code null}
     *
     * @return this scope for chaining
     */
    ContextScope<T, C> condition(String id, Condition<T, C> condition);

    /**
     * Registers a condition class under {@code id}, tagged with this scope's context class.
     *
     * @param id the condition id
     * @param conditionClass the condition class; never {@code null}
     *
     * @return this scope for chaining
     */
    ContextScope<T, C> condition(String id, Class<? extends Condition<T, C>> conditionClass);

    /**
     * Registers an entity-only predicate as a condition under {@code id}, tagged with this
     * scope's context class. The predicate is adapted into a {@link Condition} that ignores
     * the context.
     *
     * @param id the condition id
     * @param predicate the predicate; never {@code null}
     *
     * @return this scope for chaining
     */
    ContextScope<T, C> condition(String id, Predicate<T> predicate);

    /**
     * Registers a SpEL expression as a condition under {@code id}, tagged with this scope's
     * context class.
     *
     * @param id the condition id
     * @param spelExpression the SpEL expression text; never {@code null} or blank
     *
     * @return this scope for chaining
     */
    ContextScope<T, C> condition(String id, String spelExpression);

    /**
     * Registers a composite operation under {@code id}, tagged with this scope's context class.
     * The configurer is invoked synchronously against a freshly-constructed composite def; it
     * may declare inline steps, conditional steps, and nested operations against the typed
     * {@code C}.
     *
     * @param id the composite operation id
     * @param configurer callback that configures the composite
     *
     * @return this scope for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank,
     *         {@code configurer} is {@code null}, or another component is already registered
     *         under {@code id}
     */
    ContextScope<T, C> compositeOperation(String id, Consumer<CompositeOperationDef<T, C>> configurer);

    /**
     * Registers an {@link Operation} instance under {@code id}, tagged with this scope's
     * context class.
     *
     * @param id the operation id
     * @param operation the operation instance; never {@code null}
     *
     * @return this scope for chaining
     */
    ContextScope<T, C> operation(String id, Operation<T, C> operation);

    /**
     * Registers an {@link Operation} class under {@code id}, tagged with this scope's context
     * class. The framework instantiates the class via its public no-arg constructor at build
     * time.
     *
     * @param id the operation id
     * @param operationClass the operation class; never {@code null}
     *
     * @return this scope for chaining
     */
    ContextScope<T, C> operation(String id, Class<? extends Operation<T, C>> operationClass);
}
