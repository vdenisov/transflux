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

import java.util.function.Consumer;

/**
 * An ordered list of action positions, and the grammar for filling one.
 * <p>
 * Three things in the DSL hold such a list - a declarative container, a conditional's branch, and
 * its default branch - and every one of them admits the same ways of naming an action. What
 * differs between them is what the enclosing thing <em>is</em>, not what a member may be: a
 * container is also an action, so it carries an id, a context type, compensation and listeners; a
 * branch belongs to its conditional and carries a condition instead.
 *
 * <p><b>Members come in two shapes, and the verb says which.</b> {@link #run(String) run(id)}
 * <em>references</em> an action visible in the enclosing lexical scope - registered on the state
 * machine, or declared inline inside the enclosing container. It makes no claim about how that
 * action was authored, because that is a property of its registration rather than of this call
 * site. {@link #step(String, Action) step(id, action)} <em>declares</em> a new action at this
 * position, and there the form is being chosen here, so the verb names it.
 *
 * <p><b>Member context.</b> Inline declarations are typed against the enclosing context {@code C}
 * and always run pass-through - the enclosing context reaches the member unchanged. A by-id
 * reference may target an action with a different context type; the {@code run(...)} overloads
 * accept an optional mapper specification - a registered {@link MapperDef} by id, or an inline
 * {@link ContextMapper}, which a lambda satisfies for the read-only projection case - that
 * bridges the boundary. The build pipeline validates that pass-through references are
 * assignment-compatible and that any supplied mapper's parent and child types line up with the
 * call site.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type the members run against
 * @param <SELF> the concrete def type, so every member form returns the type the caller is
 *               chaining on
 */
public interface ActionSequence<T, C, SELF extends ActionSequence<T, C, SELF>> {

    /**
     * Appends a reference to the action registered under {@code id}, in pass-through mode. The
     * referenced action's context type must be assignable from the enclosing context type.
     *
     * @param id the registered action id
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null} or blank
     */
    SELF run(String id);

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
    SELF run(String id, String mapperId);

    /**
     * Appends a reference to the action registered under {@code id}, mapping the context at the
     * call boundary with an inline {@link ContextMapper}.
     * <p>
     * A lambda is the read-only projection form: {@code ContextMapper} has one abstract method, so
     * {@code run("id", parent -> child)} supplies {@code mapTo} and leaves
     * {@link ContextMapper#mapFrom(Object, Object) mapFrom} the interface's no-op. There is
     * deliberately no separate {@code Function} overload - it would carry the same descriptor and
     * make every lambda written here ambiguous.
     *
     * @param id the registered action id
     * @param inlineMapper the mapper to apply at the boundary
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is blank or {@code inlineMapper} is
     *         {@code null}
     */
    SELF run(String id, ContextMapper<C, ?> inlineMapper);

    /**
     * {@link Identifiable} overload of {@link #run(String)}.
     *
     * @param registeredAction an identifiable supplying the action id
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code registeredAction} is {@code null}
     */
    SELF run(Identifiable registeredAction);

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
    SELF run(Identifiable registeredAction, Identifiable mapper);

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
    SELF run(Identifiable registeredAction, String mapperId);

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
    SELF run(String id, Identifiable mapper);

    /**
     * Appends a <em>forked</em> reference to the action registered under {@code id}: the member is
     * handed to the state machine's executor and runs on another thread, while the enclosing
     * sequence carries on to its next member without waiting.
     * <p>
     * A forked member is fire-and-forget. Nothing joins it and nothing cancels it, and its outcome
     * reaches neither the caller nor the
     * {@link org.transflux.core.transition.TransitionResult TransitionResult} - it appears on
     * neither the executed nor the compensated path. Observe one through an {@link ActionListener}
     * attached to the action itself, which fires for a forked invocation exactly as it does for a
     * synchronous one.
     *
     * <p>It owns its rollback. A compensation registered while the forked member runs unwinds that
     * member alone, and a failure inside it never rolls back the sequence that declared it. The
     * converse holds too: submission is the commitment point, so once the member is handed over it
     * runs even if this transition fails immediately afterwards.
     *
     * <p>The forked member's context is decided here: a mapper supplied at this call site produces
     * it, otherwise a context implementing {@link ForkableContext} is forked, otherwise the member
     * shares the enclosing context reference. Sharing is legitimate for work that only reads, and
     * the build warns where it cannot establish that much.
     *
     * <p><b>The entity is shared either way.</b> A forked member receives the same entity the
     * transition is running against, and the framework adds no locking around it - so a forked
     * member writing to the entity while the enclosing path also writes to it is a data race the
     * host owns, exactly as two concurrent transitions on one entity already are. {@link ForkableContext} isolates the
     * context; there is no equivalent for the entity, deliberately, since it belongs to the host.
     *
     * <p>An action reached this way may not drive the state machine that spawned it.
     *
     * @param id the registered action id
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null} or blank
     */
    SELF fork(String id);

    /**
     * Forked form of {@link #run(String, String)} - see {@link #fork(String)} for what forking
     * changes. The registered mapper produces the forked member's context, so {@link ForkableContext} is
     * not consulted, and its {@link ContextMapper#mapFrom(Object, Object) mapFrom} is not applied:
     * there is no moment at which a forked member could write back.
     *
     * @param id the registered action id
     * @param mapperId the registered mapper id
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if either argument is {@code null} or blank
     */
    SELF fork(String id, String mapperId);

    /**
     * Forked form of {@link #run(String, ContextMapper)} - see {@link #fork(String)} for what
     * forking changes. A lambda is the projection form here too, and it runs on the submitting
     * thread before the branch starts.
     *
     * @param id the registered action id
     * @param inlineMapper the mapper to apply at the boundary
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is blank or {@code inlineMapper} is
     *         {@code null}
     */
    SELF fork(String id, ContextMapper<C, ?> inlineMapper);

    /**
     * {@link Identifiable} overload of {@link #fork(String)}.
     *
     * @param registeredAction an identifiable supplying the action id
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code registeredAction} is {@code null}
     */
    SELF fork(Identifiable registeredAction);

    /**
     * {@link Identifiable} overload of {@link #fork(String, String)} - both action and mapper
     * supplied as identifiables.
     *
     * @param registeredAction an identifiable supplying the action id
     * @param mapper an identifiable supplying the mapper id
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if either argument is {@code null}
     */
    SELF fork(Identifiable registeredAction, Identifiable mapper);

    /**
     * Mixed-form overload of {@link #fork(String, String)} - action identifiable + mapper id.
     *
     * @param registeredAction an identifiable supplying the action id
     * @param mapperId the registered mapper id
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code registeredAction} is {@code null} or
     *         {@code mapperId} is {@code null}/blank
     */
    SELF fork(Identifiable registeredAction, String mapperId);

    /**
     * Mixed-form overload of {@link #fork(String, String)} - action id + mapper identifiable.
     *
     * @param id the registered action id
     * @param mapper an identifiable supplying the mapper id
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank or {@code mapper}
     *         is {@code null}
     */
    SELF fork(String id, Identifiable mapper);

    /**
     * Declares an imperative action inline at this position, from a supplied {@link Action}
     * instance. The action is registered into the scope that owns this position - the enclosing
     * container's, or the conditional's when this is one of its branches - so it is visible from
     * anywhere inside that scope's subtree, including a sibling branch, and from nowhere outside
     * it. Its id must be unique across the state machine.
     *
     * @param id the action id; must be unique across the state machine
     * @param action the action to invoke
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is blank or {@code action} is
     *         {@code null}
     */
    SELF step(String id, Action<T, C> action);

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
    SELF step(Identifiable actionIdentifiable, Action<T, C> action);

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
    SELF step(String id, Class<? extends Action<T, C>> actionClass);

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
    SELF step(Identifiable actionIdentifiable, Class<? extends Action<T, C>> actionClass);

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
    SELF step(String id, Consumer<StepDef<T, C>> configurer);

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
    SELF step(Identifiable actionIdentifiable, Consumer<StepDef<T, C>> configurer);

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
    SELF conditional(String id, Consumer<ConditionalOperationDef<T, C>> configurer);

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
    SELF conditional(Identifiable conditionalIdentifiable, Consumer<ConditionalOperationDef<T, C>> configurer);

    /**
     * Declares a nested sequence at this position - an operation, declared in place rather than
     * registered elsewhere and referenced. Use it for a group of actions that belongs to one call
     * site: it can carry its own compensation and listeners, and it unwinds as a unit.
     *
     * <p>The nested operation is registered into the scope that owns this position - the
     * enclosing container's, or the conditional's when this is one of its branches - so it is
     * visible from anywhere inside that scope's subtree and from nowhere outside it, and its id
     * must be unique across the state machine. Its own members resolve against its own scope
     * first and the enclosing chain after, so it can reach what encloses it while nothing can
     * reach into it.
     *
     * <p>It is typed against the enclosing context and runs pass-through: the members declared
     * inside it receive the same context this position does. There is no way to give it a context
     * of its own - {@link OperationDef#usingContext(Class)} accepts only the enclosing type
     * restated, since the configurer has already bound it. To cross a context boundary, reference
     * a component declared against that context through {@link #run(String, ContextMapper)}.
     *
     * @param id the operation's id; must be unique across the state machine
     * @param configurer callback that declares the members
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code id} is blank or {@code configurer} is
     *         {@code null}
     */
    SELF operation(String id, Consumer<OperationDef<T, C>> configurer);

    /**
     * {@link Identifiable} overload of {@link #operation(String, Consumer)}.
     *
     * @param operationIdentifiable an identifiable supplying the operation's id
     * @param configurer callback that declares the members
     *
     * @return this def for chaining
     *
     * @throws TransfluxValidationException if {@code operationIdentifiable} is {@code null}
     */
    SELF operation(Identifiable operationIdentifiable, Consumer<OperationDef<T, C>> configurer);
}
