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

import org.transflux.core.Identifiable;
import org.transflux.core.exception.TransfluxValidationException;
import org.transflux.core.transition.Transition;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Def-side anchor that builds an {@link Operation} from an ordered sequence of bound members.
 * <p>
 * {@code CompositeOperationDef} is the composite counterpart to {@link SimpleOperationDef}. A
 * composite carries an ordered list of <i>members</i>: each member is either a {@link Step} or
 * a nested {@link Operation}. Members are added through the {@code step(...)} and
 * {@code operation(...)} overloads in declaration order; at build time the framework resolves
 * each by-id reference against the state machine's step and operation registries (auto-
 * registering any inline references) and emits an executor {@link Operation} that invokes each
 * member in turn, passing the entity, context, and per-execution {@link Transition} view through.
 * Each executed step or operation id is recorded uniformly, whether dispatched by the composite
 * executor or by an explicit {@code transition.step("id")} / {@code transition.operation("id")}
 * call from a user operation.
 *
 * <p><b>Member context.</b> Inline-registered members (defined directly in this composite's
 * configurer through {@link #step(String, Step) step(id, step)},
 * {@link #operation(String, Operation) operation(id, operation)}, etc.) are typed against the
 * composite's own context {@code C} and always run pass-through — the parent context is handed
 * to the member unchanged. By-id references can target a step or operation with a different
 * context type; the call-site overloads accept an optional mapper specification (a registered
 * {@link MapperDef} by id, an inline {@link Function} for read-only projection, or a fully-
 * supplied {@link ContextMapper} instance) that bridges the parent-to-child boundary. The build
 * pipeline validates that pass-through references are assignment-compatible and that supplied
 * mappers' parent / child type tokens align with the call site and the referenced member.
 *
 * <p>A composite's own {@code id} is mandatory; {@code name} and {@code description} are
 * optional metadata. The composite must declare at least one member before the enclosing
 * transition is built.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
public interface CompositeOperationDef<T, C> extends OperationDef<T, C> {

    /**
     * Appends a pass-through reference to a step that is registered on the enclosing state
     * machine. The referenced id must be registered (or auto-registered through another
     * composite's inline reference) by the time the state machine is built. The referenced
     * step's context type must be assignable from this composite's {@code C}.
     *
     * @param registeredStepId the registered step id
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code registeredStepId} is {@code null} or blank
     */
    CompositeOperationDef<T, C> step(String registeredStepId);

    /**
     * Appends a by-id step reference with a mapper supplied by id. The referenced mapper must
     * be registered on the enclosing state machine via
     * {@link org.transflux.core.StateMachineDef#mapper(String, Class, Class, ContextMapper)
     * StateMachineDef.mapper(...)} and its parent / child type tokens must align with this
     * composite's {@code C} and the referenced step's context type.
     *
     * @param registeredStepId the registered step id
     * @param mapperId the registered mapper id
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if either argument is {@code null} or blank
     */
    CompositeOperationDef<T, C> step(String registeredStepId, String mapperId);

    /**
     * Appends a by-id step reference with an inline read-only parent-to-child function. The
     * function is wrapped in a {@link ContextMapper} whose {@code mapFrom} is the default no-op
     * — appropriate when the referenced step has no results to fold back into this composite's
     * context.
     *
     * @param registeredStepId the registered step id
     * @param inlineMapTo the parent-to-child projection; never {@code null}
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code registeredStepId} is {@code null}/blank or
     *         {@code inlineMapTo} is {@code null}
     */
    CompositeOperationDef<T, C> step(String registeredStepId, Function<C, ?> inlineMapTo);

    /**
     * Appends a by-id step reference with an inline fully-supplied {@link ContextMapper}.
     *
     * @param registeredStepId the registered step id
     * @param inlineMapper the mapper; never {@code null}
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code registeredStepId} is {@code null}/blank or
     *         {@code inlineMapper} is {@code null}
     */
    CompositeOperationDef<T, C> step(String registeredStepId, ContextMapper<C, ?> inlineMapper);

    /**
     * {@link Identifiable} overload of {@link #step(String)} — delegates via
     * {@link Identifiable#getId()}.
     *
     * @param registeredStep an identifiable supplying the step id
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code registeredStep} is {@code null}
     */
    CompositeOperationDef<T, C> step(Identifiable registeredStep);

    /**
     * {@link Identifiable} overload of {@link #step(String, String)} — both step and mapper
     * supplied as identifiables.
     *
     * @param registeredStep an identifiable supplying the step id
     * @param mapper an identifiable supplying the mapper id
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if either argument is {@code null}
     */
    CompositeOperationDef<T, C> step(Identifiable registeredStep, Identifiable mapper);

    /**
     * Mixed-form overload of {@link #step(String, String)} — step identifiable + mapper id.
     *
     * @param registeredStep an identifiable supplying the step id
     * @param mapperId the registered mapper id
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code registeredStep} is {@code null} or
     *         {@code mapperId} is {@code null}/blank
     */
    CompositeOperationDef<T, C> step(Identifiable registeredStep, String mapperId);

    /**
     * Mixed-form overload of {@link #step(String, String)} — step id + mapper identifiable.
     *
     * @param registeredStepId the registered step id
     * @param mapper an identifiable supplying the mapper id
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code registeredStepId} is {@code null}/blank
     *         or {@code mapper} is {@code null}
     */
    CompositeOperationDef<T, C> step(String registeredStepId, Identifiable mapper);

    /**
     * Appends an inline step instance. The step is auto-registered on the enclosing state
     * machine under {@code id} at build time and can be referenced by id from elsewhere. Inline
     * steps are typed against this composite's {@code C} and always run pass-through.
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
     * {@link Identifiable} overload of {@link #step(String, Step)} for inline registration.
     *
     * @param stepIdentifiable an identifiable supplying the step id
     * @param step the step instance
     *
     * @return this def for chaining
     */
    CompositeOperationDef<T, C> step(Identifiable stepIdentifiable, Step<T, C> step);

    /**
     * Appends an inline step class. The framework reflectively instantiates the class via its
     * public no-arg constructor at state-machine build time and auto-registers it under
     * {@code id}. Inline steps always run pass-through.
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
     * {@link Identifiable} overload of {@link #step(String, Class)} for inline registration.
     *
     * @param stepIdentifiable an identifiable supplying the step id
     * @param stepClass the step class
     *
     * @return this def for chaining
     */
    CompositeOperationDef<T, C> step(Identifiable stepIdentifiable, Class<? extends Step<T, C>> stepClass);

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
     * {@link Identifiable} overload of {@link #conditional(String, Consumer)}.
     *
     * @param conditionalIdentifiable an identifiable supplying the conditional step id
     * @param configurer callback that configures the conditional
     *
     * @return this def for chaining
     */
    CompositeOperationDef<T, C> conditional(Identifiable conditionalIdentifiable, Consumer<ConditionalStepDef<T, C>> configurer);

    /**
     * Appends a pass-through reference to a nested operation that is registered on the
     * enclosing state machine. The referenced operation's context type must be assignable from
     * this composite's {@code C}.
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
     * Appends a by-id nested-operation reference with a mapper supplied by id.
     *
     * @param registeredOperationId the registered operation id
     * @param mapperId the registered mapper id
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if either argument is {@code null} or blank
     */
    CompositeOperationDef<T, C> operation(String registeredOperationId, String mapperId);

    /**
     * Appends a by-id nested-operation reference with an inline read-only parent-to-child
     * function. Equivalent to a {@link ContextMapper} whose {@code mapFrom} is the default no-op.
     *
     * @param registeredOperationId the registered operation id
     * @param inlineMapTo the parent-to-child projection; never {@code null}
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code registeredOperationId} is {@code null}/blank
     *         or {@code inlineMapTo} is {@code null}
     */
    CompositeOperationDef<T, C> operation(String registeredOperationId, Function<C, ?> inlineMapTo);

    /**
     * Appends a by-id nested-operation reference with an inline fully-supplied
     * {@link ContextMapper}.
     *
     * @param registeredOperationId the registered operation id
     * @param inlineMapper the mapper; never {@code null}
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code registeredOperationId} is {@code null}/blank
     *         or {@code inlineMapper} is {@code null}
     */
    CompositeOperationDef<T, C> operation(String registeredOperationId, ContextMapper<C, ?> inlineMapper);

    /**
     * {@link Identifiable} overload of {@link #operation(String)} — delegates via
     * {@link Identifiable#getId()}.
     *
     * @param registeredOperation an identifiable supplying the operation id
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code registeredOperation} is {@code null}
     */
    CompositeOperationDef<T, C> operation(Identifiable registeredOperation);

    /**
     * {@link Identifiable} overload of {@link #operation(String, String)} — both operation
     * and mapper supplied as identifiables.
     *
     * @param registeredOperation an identifiable supplying the operation id
     * @param mapper an identifiable supplying the mapper id
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if either argument is {@code null}
     */
    CompositeOperationDef<T, C> operation(Identifiable registeredOperation, Identifiable mapper);

    /**
     * Mixed-form overload of {@link #operation(String, String)} — operation identifiable +
     * mapper id.
     *
     * @param registeredOperation an identifiable supplying the operation id
     * @param mapperId the registered mapper id
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code registeredOperation} is {@code null}
     *         or {@code mapperId} is {@code null}/blank
     */
    CompositeOperationDef<T, C> operation(Identifiable registeredOperation, String mapperId);

    /**
     * Mixed-form overload of {@link #operation(String, String)} — operation id + mapper
     * identifiable.
     *
     * @param registeredOperationId the registered operation id
     * @param mapper an identifiable supplying the mapper id
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code registeredOperationId} is {@code null}/blank
     *         or {@code mapper} is {@code null}
     */
    CompositeOperationDef<T, C> operation(String registeredOperationId, Identifiable mapper);

    /**
     * Appends an inline nested operation instance. The operation is auto-registered on the
     * enclosing state machine under {@code id} at build time. Inline operations are typed
     * against this composite's {@code C} and always run pass-through.
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
     * {@link Identifiable} overload of {@link #operation(String, Operation)} for inline
     * registration.
     *
     * @param operationIdentifiable an identifiable supplying the operation id
     * @param operation the operation instance
     *
     * @return this def for chaining
     */
    CompositeOperationDef<T, C> operation(Identifiable operationIdentifiable, Operation<T, C> operation);

    /**
     * Appends an inline nested operation class. The framework reflectively instantiates the
     * class via its public no-arg constructor at state-machine build time and auto-registers it
     * under {@code id}. Inline operations always run pass-through.
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
     * {@link Identifiable} overload of {@link #operation(String, Class)} for inline
     * registration.
     *
     * @param operationIdentifiable an identifiable supplying the operation id
     * @param operationClass the operation class
     *
     * @return this def for chaining
     */
    CompositeOperationDef<T, C> operation(Identifiable operationIdentifiable, Class<? extends Operation<T, C>> operationClass);

    /**
     * Records a runtime type-assertion that the composite's declared context generic
     * {@code C} matches the supplied class. Useful for documentation, IDE legibility, and
     * the future YAML DSL where generics are erased. The framework does not validate this
     * against any SM-level context type (the SM no longer declares one); validation, if any,
     * is delegated to the enclosing transition's context type.
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
