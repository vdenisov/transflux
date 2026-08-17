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

package org.transflux.core.action;

import org.transflux.core.Identifiable;
import org.transflux.core.exception.TransfluxValidationException;
import org.transflux.core.transition.Transition;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Def-side anchor for a <em>declarative</em> action - one authored as an ordered list of members
 * rather than as a Java body.
 * <p>
 * {@code OperationDef} is the counterpart to {@link StepDef}. Declaration order is execution
 * order: at build time the framework resolves each member and emits an {@link Action} that
 * invokes them in turn, passing the entity, context and per-execution {@link Transition} view
 * through. There is no body for a host to implement.
 *
 * <p><b>Members come in two shapes, and the verb says which.</b> {@link #run(String) run(id)}
 * <em>references</em> an action registered elsewhere; it makes no claim about how that action
 * was authored, because that is a property of its registration rather than of this call site.
 * {@link #step(String, Action) step(id, action)} and {@link #conditional} <em>declare</em> a new
 * action at this position, in the enclosing composite's lexical scope, and there the form is
 * being chosen here so the verb names it.
 *
 * <p><b>Member context.</b> Inline declarations are typed against the composite's own context
 * {@code C} and always run pass-through - the parent context reaches the member unchanged. A
 * by-id reference may target an action with a different context type; the {@code run(...)}
 * overloads accept an optional mapper specification (a registered {@link MapperDef} by id, an
 * inline {@link Function} for read-only projection, or a fully-supplied {@link ContextMapper}
 * instance) that bridges the parent-to-child boundary. The build pipeline validates that
 * pass-through references are assignment-compatible and that any supplied mapper's parent and
 * child types line up with the call site.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through this operation's execution
 */
public interface OperationDef<T, C> extends ActionDef<T, C> {

    /**
     * Appends a reference to the action registered under {@code id}, in pass-through mode. The
     * referenced action's context type must be assignable from this operation's context type.
     *
     * @param id the registered action id
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null} or blank
     */
    OperationDef<T, C> run(String id);

    /**
     * Appends a reference to the action registered under {@code id}, applying the registered
     * mapper identified by {@code mapperId} at the call boundary.
     *
     * @param id the registered action id
     * @param mapperId the registered mapper id
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if either argument is {@code null} or blank
     */
    OperationDef<T, C> run(String id, String mapperId);

    /**
     * Appends a reference to the action registered under {@code id} with an inline read-only
     * parent-to-child projection. The supplied function is wrapped as a {@link ContextMapper}
     * whose {@link ContextMapper#mapFrom(Object, Object) mapFrom} is a no-op.
     *
     * @param id the registered action id
     * @param inlineMapTo the parent-to-child projection
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is blank or {@code inlineMapTo} is
     *         {@code null}
     */
    OperationDef<T, C> run(String id, Function<C, ?> inlineMapTo);

    /**
     * Appends a reference to the action registered under {@code id} with an inline
     * fully-supplied {@link ContextMapper}.
     *
     * @param id the registered action id
     * @param inlineMapper the mapper to apply at the boundary
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is blank or {@code inlineMapper} is
     *         {@code null}
     */
    OperationDef<T, C> run(String id, ContextMapper<C, ?> inlineMapper);

    /**
     * {@link Identifiable} overload of {@link #run(String)}.
     *
     * @param registeredAction an identifiable supplying the action id
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code registeredAction} is {@code null}
     */
    OperationDef<T, C> run(Identifiable registeredAction);

    /**
     * {@link Identifiable} overload of {@link #run(String, String)} - both action and mapper
     * supplied as identifiables.
     *
     * @param registeredAction an identifiable supplying the action id
     * @param mapper an identifiable supplying the mapper id
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if either argument is {@code null}
     */
    OperationDef<T, C> run(Identifiable registeredAction, Identifiable mapper);

    /**
     * Mixed-form overload of {@link #run(String, String)} - action identifiable + mapper id.
     *
     * @param registeredAction an identifiable supplying the action id
     * @param mapperId the registered mapper id
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code registeredAction} is {@code null} or
     *         {@code mapperId} is {@code null}/blank
     */
    OperationDef<T, C> run(Identifiable registeredAction, String mapperId);

    /**
     * Mixed-form overload of {@link #run(String, String)} - action id + mapper identifiable.
     *
     * @param id the registered action id
     * @param mapper an identifiable supplying the mapper id
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank or {@code mapper}
     *         is {@code null}
     */
    OperationDef<T, C> run(String id, Identifiable mapper);

    /**
     * Declares an imperative action inline at this position, from a pre-constructed
     * {@link Action} instance. The action is registered into this operation's lexical scope
     * under {@code id} and is visible only from inside this operation's subtree.
     *
     * @param id the action id; must be unique across the state machine
     * @param action the action to invoke
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is blank or {@code action} is
     *         {@code null}
     */
    OperationDef<T, C> step(String id, Action<T, C> action);

    /**
     * {@link Identifiable} overload of {@link #step(String, Action)}.
     *
     * @param actionIdentifiable an identifiable supplying the action id
     * @param action the action to invoke
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code actionIdentifiable} is {@code null}
     */
    OperationDef<T, C> step(Identifiable actionIdentifiable, Action<T, C> action);

    /**
     * Declares an imperative action inline at this position, from a class the framework
     * instantiates through its public no-arg constructor at build time.
     *
     * @param id the action id; must be unique across the state machine
     * @param actionClass the action class
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is blank or {@code actionClass} is
     *         {@code null}
     */
    OperationDef<T, C> step(String id, Class<? extends Action<T, C>> actionClass);

    /**
     * {@link Identifiable} overload of {@link #step(String, Class)}.
     *
     * @param actionIdentifiable an identifiable supplying the action id
     * @param actionClass the action class
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code actionIdentifiable} is {@code null}
     */
    OperationDef<T, C> step(Identifiable actionIdentifiable, Class<? extends Action<T, C>> actionClass);

    /**
     * Configurer form of the inline declaration, for a member that also wants a name, a
     * description, or listeners. The configurer must call {@code using(...)} to supply the body.
     *
     * @param id the action id; must be unique across the state machine
     * @param configurer callback that configures the member
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is blank or {@code configurer} is
     *         {@code null}
     */
    OperationDef<T, C> step(String id, Consumer<StepDef<T, C>> configurer);

    /**
     * {@link Identifiable} overload of {@link #step(String, Consumer)}.
     *
     * @param actionIdentifiable an identifiable supplying the action id
     * @param configurer callback that configures the member
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code actionIdentifiable} is {@code null}
     */
    OperationDef<T, C> step(Identifiable actionIdentifiable, Consumer<StepDef<T, C>> configurer);

    /**
     * Declares a multi-branch conditional at this position - a declarative action whose ordering
     * rule is "first matching branch" rather than "all, in order".
     *
     * @param id the conditional's id; must be unique across the state machine
     * @param configurer callback that declares the branches
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is blank or {@code configurer} is
     *         {@code null}
     */
    OperationDef<T, C> conditional(String id, Consumer<ConditionalOperationDef<T, C>> configurer);

    /**
     * {@link Identifiable} overload of {@link #conditional(String, Consumer)}.
     *
     * @param conditionalIdentifiable an identifiable supplying the conditional's id
     * @param configurer callback that declares the branches
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code conditionalIdentifiable} is {@code null}
     */
    OperationDef<T, C> conditional(Identifiable conditionalIdentifiable, Consumer<ConditionalOperationDef<T, C>> configurer);

    /**
     * Declares the context type this operation's members run against.
     *
     * @param contextType the context class
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code contextType} is {@code null}
     */
    OperationDef<T, C> usingContext(Class<C> contextType);

    @Override
    OperationDef<T, C> withName(String name);

    @Override
    OperationDef<T, C> withDescription(String description);

    @Override
    OperationDef<T, C> withCompensation(Compensation<T, C> compensation);

    @Override
    OperationDef<T, C> withCompensation(Class<? extends Compensation<T, C>> compensationClass);

    @Override
    OperationDef<T, C> onStart(String listenerId, ActionListener<T, C> listener);

    @Override
    OperationDef<T, C> onStart(Identifiable listenerIdentifiable, ActionListener<T, C> listener);

    @Override
    OperationDef<T, C> onStart(String listenerId, Class<? extends ActionListener<T, C>> listenerClass);

    @Override
    OperationDef<T, C> onStart(Identifiable listenerIdentifiable,
                               Class<? extends ActionListener<T, C>> listenerClass);

    @Override
    OperationDef<T, C> onStart(String listenerId, Consumer<ActionListenerDef<T, C>> configurer);

    @Override
    OperationDef<T, C> onStart(Identifiable listenerIdentifiable,
                               Consumer<ActionListenerDef<T, C>> configurer);

    @Override
    OperationDef<T, C> onComplete(String listenerId, ActionListener<T, C> listener);

    @Override
    OperationDef<T, C> onComplete(Identifiable listenerIdentifiable, ActionListener<T, C> listener);

    @Override
    OperationDef<T, C> onComplete(String listenerId,
                                  Class<? extends ActionListener<T, C>> listenerClass);

    @Override
    OperationDef<T, C> onComplete(Identifiable listenerIdentifiable,
                                  Class<? extends ActionListener<T, C>> listenerClass);

    @Override
    OperationDef<T, C> onComplete(String listenerId, Consumer<ActionListenerDef<T, C>> configurer);

    @Override
    OperationDef<T, C> onComplete(Identifiable listenerIdentifiable,
                                  Consumer<ActionListenerDef<T, C>> configurer);

    @Override
    OperationDef<T, C> onError(String listenerId, ActionListener<T, C> listener);

    @Override
    OperationDef<T, C> onError(Identifiable listenerIdentifiable, ActionListener<T, C> listener);

    @Override
    OperationDef<T, C> onError(String listenerId, Class<? extends ActionListener<T, C>> listenerClass);

    @Override
    OperationDef<T, C> onError(Identifiable listenerIdentifiable,
                               Class<? extends ActionListener<T, C>> listenerClass);

    @Override
    OperationDef<T, C> onError(String listenerId, Consumer<ActionListenerDef<T, C>> configurer);

    @Override
    OperationDef<T, C> onError(Identifiable listenerIdentifiable,
                               Consumer<ActionListenerDef<T, C>> configurer);
}
