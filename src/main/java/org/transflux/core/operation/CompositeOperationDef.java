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
import org.transflux.core.transition.Transition;

import java.util.function.Consumer;

/**
 * Def-side anchor that builds an {@link Operation} from an ordered sequence of bound steps.
 * <p>
 * {@code CompositeOperationDef} is the composite counterpart to {@link SimpleOperationDef}.
 * The user appends step references in declaration order; at build time the framework resolves
 * each reference against the state machine's step registry (auto-registering any inline
 * references) and emits an executor {@link Operation} that invokes each {@link Step} in turn,
 * passing the entity, context, and per-execution {@link Transition} view through to each one.
 * Each executed step's id is recorded uniformly, whether the step is driven by the composite
 * executor or by an explicit {@code transition.step("id")} call from a user operation.
 *
 * <p>The composite's own {@code id} is mandatory; {@code name} and {@code description} are
 * optional metadata. The composite must declare at least one step before the enclosing
 * transition is built.
 *
 * <p>The three {@code step(...)} overloads may be called in any order and any number of times.
 * Each call appends one step to the end of the composite's step list.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
public interface CompositeOperationDef<T, C> extends OperationDef<T, C> {

    /**
     * Appends a reference to a step that is registered on the enclosing state machine. The
     * referenced id must be registered (or auto-registered through another composite's inline
     * reference) by the time the state machine is built.
     *
     * @param registeredStepId the registered step id
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code registeredStepId} is {@code null} or blank
     */
    CompositeOperationDef<T, C> step(String registeredStepId);

    /**
     * Appends an inline step instance. The step is auto-registered on the enclosing state
     * machine under {@code id} at build time and can be referenced by id from elsewhere.
     *
     * @param id the step id
     * @param step the step instance; never {@code null}
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank or {@code step}
     *         is {@code null}
     */
    CompositeOperationDef<T, C> step(String id, Step<T, C> step);

    /**
     * Appends an inline step class. The framework reflectively instantiates the class via its
     * public no-arg constructor at state-machine build time and auto-registers it under
     * {@code id}.
     *
     * @param id the step id
     * @param stepClass the step class; never {@code null}
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank or
     *         {@code stepClass} is {@code null}
     */
    CompositeOperationDef<T, C> step(String id, Class<? extends Step<T, C>> stepClass);

    /**
     * Appends a multi-branch conditional step under the supplied id. The supplied configurer
     * defines the branches, optional default branch, and no-match behavior.
     *
     * @param id the conditional step id; the executor built from the configurer is
     *           auto-registered on the enclosing state machine under this id
     * @param configurer callback that configures the conditional
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank or
     *         {@code configurer} is {@code null}
     */
    CompositeOperationDef<T, C> conditional(String id, Consumer<ConditionalStepDef<T, C>> configurer);

    /**
     * Appends a reference to a nested operation that is registered on the enclosing state
     * machine. The referenced id must be registered (or auto-registered through another
     * composite's inline reference) by the time the state machine is built. The nested
     * operation runs in pass-through mode: it receives the parent's context object verbatim.
     *
     * @param registeredOperationId the registered operation id
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code registeredOperationId} is {@code null}
     *         or blank
     */
    CompositeOperationDef<T, C> operation(String registeredOperationId);

    /**
     * Appends an inline nested operation instance. The operation is auto-registered on the
     * enclosing state machine under {@code id} at build time and can be referenced by id from
     * elsewhere. The nested operation runs in pass-through mode: it receives the parent's
     * context object verbatim.
     *
     * @param id the operation id
     * @param operation the operation instance; never {@code null}
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank or
     *         {@code operation} is {@code null}
     */
    CompositeOperationDef<T, C> operation(String id, Operation<T, C> operation);

    /**
     * Appends an inline nested operation class. The framework reflectively instantiates the
     * class via its public no-arg constructor at state-machine build time and auto-registers
     * it under {@code id}. The nested operation runs in pass-through mode: it receives the
     * parent's context object verbatim.
     *
     * @param id the operation id
     * @param operationClass the operation class; never {@code null}
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank or
     *         {@code operationClass} is {@code null}
     */
    CompositeOperationDef<T, C> operation(String id, Class<? extends Operation<T, C>> operationClass);

    /**
     * Appends an inline nested operation instance with a lambda configurer for context
     * mapping and metadata. The operation is auto-registered on the enclosing state machine
     * under {@code id}. The configurer may declare a child context type via
     * {@code .usingContext(...)} and supply a {@link ContextMapper} (class or instance form)
     * or inline {@code .mapTo(...)} / {@code .mapFrom(...)} lambdas; mixing the two mapping
     * styles is rejected at build time. When the configurer leaves the nested operation in
     * pass-through mode (no {@code .usingContext} call and no mapping), behavior matches
     * {@link #operation(String, Operation)}.
     *
     * @param id the operation id
     * @param operation the operation instance; never {@code null}
     * @param configurer callback that configures context mapping and metadata
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank,
     *         {@code operation} is {@code null}, {@code configurer} is {@code null}, or the
     *         configured mapping is inconsistent
     */
    CompositeOperationDef<T, C> operation(String id, Operation<T, ?> operation,
                                          Consumer<NestedOperationDef<T, C, C>> configurer);

    /**
     * Appends an inline nested operation class with a lambda configurer for context mapping
     * and metadata. The framework reflectively instantiates the class via its public no-arg
     * constructor at state-machine build time and auto-registers it under {@code id}. The
     * configurer rules mirror {@link #operation(String, Operation, Consumer)}.
     *
     * @param id the operation id
     * @param operationClass the operation class; never {@code null}
     * @param configurer callback that configures context mapping and metadata
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank,
     *         {@code operationClass} is {@code null}, {@code configurer} is {@code null}, or
     *         the configured mapping is inconsistent
     */
    CompositeOperationDef<T, C> operation(String id, Class<? extends Operation<T, ?>> operationClass,
                                          Consumer<NestedOperationDef<T, C, C>> configurer);

    /**
     * Records a runtime type-assertion that the composite's declared context generic
     * {@code C} matches the supplied class. Useful for documentation, IDE legibility, and
     * the future YAML DSL where generics are erased. At {@link #build} time the framework
     * validates this assertion against the enclosing state machine's
     * {@linkplain org.transflux.core.StateMachineDef#forContextType(Class) declared context
     * type} and throws on mismatch.
     *
     * @param contextType the context class; never {@code null}
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code contextType} is {@code null}
     */
    CompositeOperationDef<T, C> usingContext(Class<C> contextType);

    @Override
    CompositeOperationDef<T, C> withName(String name);

    @Override
    CompositeOperationDef<T, C> withDescription(String description);
}
