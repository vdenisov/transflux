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

import java.util.function.Consumer;

/**
 * Def-side anchor for an action, carrying the framework-owned identity, metadata, and listeners
 * that pure {@link Action} executables do not.
 * <p>
 * Two concrete sub-types exist, one per authoring form: {@link StepDef} declares an imperative
 * action (a Java body, supplied as an instance or a class), and {@link OperationDef} declares a
 * declarative one (an ordered list of members, whose executable the framework synthesizes).
 * {@link ConditionalOperationDef} is a declarative variant whose ordering rule is "first matching
 * branch" rather than "all, in order".
 *
 * <p>The {@code id} is mandatory and must be unique across the state machine. {@code name} and
 * {@code description} are optional metadata for diagnostics and tooling.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
public interface ActionDef<T, C> extends Identifiable {

    /**
     * Returns the unique identifier of this action def.
     *
     * @return the action id; never {@code null} or blank
     */
    @Override
    String getId();

    /**
     * Returns the human-readable name of this action, or {@code null} when unset.
     *
     * @return the optional action name
     */
    String getName();

    /**
     * Returns the description of this action, or {@code null} when unset.
     *
     * @return the optional action description
     */
    String getDescription();

    /**
     * Returns the context class this action requires.
     * <p>
     * The default implementation returns {@link Object} as a permissive sentinel meaning
     * "any context is acceptable"; concrete defs override this to expose the actual class
     * supplied at registration so call-site mappers and pass-through compatibility checks
     * can validate the parent-to-child boundary at build time.
     *
     * @return the action's context class; never {@code null}
     */
    @SuppressWarnings("unchecked")
    default Class<C> contextType() {
        return (Class<C>) Object.class;
    }

    /**
     * Sets the human-readable name of this action.
     *
     * @param name the name; may be {@code null} to clear
     *
     * @return this def for chaining
     */
    ActionDef<T, C> withName(String name);

    /**
     * Sets the description of this action.
     *
     * @param description the description; may be {@code null} to clear
     *
     * @return this def for chaining
     */
    ActionDef<T, C> withDescription(String description);

    /**
     * Declares the {@link Compensation} that rolls this action's effects back, whatever the failure.
     * <p>
     * This is the second of the three authoring channels for a compensation. An imperative action can
     * return one per invocation from {@link Action#getCompensation(Object, Object)}; a declarative
     * container has no Java object to hang that on and declares one here instead. This one takes
     * precedence: a def that declares it here suppresses {@code getCompensation}, which is then not
     * consulted at all, since this answers every failure anyway.
     *
     * <p>Where {@link #forException(Class)} routes are also declared, this is the <em>fallback</em>:
     * the routes are tried first, in declaration order, and this runs only when none of them answers
     * for the failure. Its position on the chain does not matter, which is what the {@code with}
     * prefix promises everywhere else in this DSL - it sets a property rather than registering an
     * ordered hook.
     *
     * <p>The compensation is registered before the action runs, so an action that throws partway
     * through producing side effects still has its rollback on the stack. For a container that means
     * its compensation is registered before it dispatches anything, which has two consequences worth
     * expecting: the container's compensation is <em>additive</em> - its own and its members' all run,
     * members first - and it runs even when the first member fails immediately, before the container
     * itself did anything of its own.
     *
     * <p>At rollback the compensation receives the entity and the context the action ran against -
     * the mapped child context where the call site maps.
     *
     * <p>Calling this a second time replaces the prior declaration and logs a warning.
     *
     * @param compensation the compensation; never {@code null}
     *
     * @return this def for chaining
     *
     * @throws org.transflux.core.exception.TransfluxValidationException if {@code compensation} is
     *         {@code null}, or if the configurer has already returned
     */
    ActionDef<T, C> withCompensation(Compensation<T, C> compensation);

    /**
     * Class form of {@link #withCompensation(Compensation)}. The class is instantiated through its
     * public no-arg constructor when the state machine is built, so a class the framework cannot
     * instantiate fails the build rather than the first rollback.
     *
     * @param compensationClass the compensation class; never {@code null}
     *
     * @return this def for chaining
     *
     * @throws org.transflux.core.exception.TransfluxValidationException if
     *         {@code compensationClass} is {@code null}, or if the configurer has already returned
     */
    ActionDef<T, C> withCompensation(Class<? extends Compensation<T, C>> compensationClass);

    /**
     * Opens a compensation route: a rollback that applies to one kind of failure rather than to
     * every one. The returned {@link CompensationRouteDef} takes an optional guard and the
     * compensation itself, and hands this def back so the chain continues.
     *
     * <pre>{@code
     * .withCompensation(RefundCompensation.class)
     * .forException(GatewayTimeoutException.class)
     *     .withCompensation(ReconcileLaterCompensation.class)
     * }</pre>
     *
     * <p>Routes are tried in the order they are declared and the first whose exception type
     * <em>and</em> guard both hold wins - the same "first match" rule a conditional operation's
     * branches obey, and the same one a Java {@code catch} chain obeys. A route matches subclasses
     * of its declared type, so an unguarded route on a broad type shadows every narrower route
     * declared after it; the build warns when it can prove that.
     *
     * <p>Only one compensation ever runs for one action. The rollback is the first of these that
     * answers for the failure: a matching route, then the fallback declared by
     * {@link #withCompensation(Compensation)}, then whatever the action's own
     * {@link Action#getCompensation(Object, Object)} returned. So a matching route replaces the
     * fallback rather than running alongside it, and declaring routes does <em>not</em> suppress an
     * imperative action's own rollback - a route that misses has said nothing about this failure and
     * does not veto on its behalf. When nothing at all answers, this action is not rolled back and
     * does not appear on
     * {@link org.transflux.core.transition.TransitionResult#getCompensatedPath() the compensated
     * path}.
     *
     * <p>The failure a route is matched against is the one that ended the transition, which is the
     * same throwable every action on the rollback stack is matched against - not necessarily one
     * this action threw.
     *
     * @param exceptionType the failure type this route answers for; never {@code null}
     * @param <X> the failure type, threaded into the route's guard
     *
     * @return the new route, to be closed with {@code withCompensation(...)}
     *
     * @throws org.transflux.core.exception.TransfluxValidationException if {@code exceptionType} is
     *         {@code null}, or if the configurer has already returned
     */
    <X extends Throwable> CompensationRouteDef<T, C, X, ? extends ActionDef<T, C>> forException(
        Class<X> exceptionType);

    /**
     * Attaches a listener notified before this action's body runs.
     * <p>
     * The listener belongs to the action, not to any one call site, so it fires at every invocation
     * - whether the action is attached to a transition, declared or referenced as a container
     * member, reached through a conditional branch, or dispatched by id from another action's body.
     * Listeners attached here run before the state-machine-wide
     * {@code StateMachineDef.onAnyActionStart(...)} registrations, in declaration order within each
     * group.
     *
     * <p>Listeners observe and never gate: an exception thrown by one is logged and swallowed.
     *
     * @param listenerId the listener id, unique across the state machine; never {@code null} or
     *                   blank
     * @param listener the listener; never {@code null}
     *
     * @return this def for chaining
     *
     * @throws org.transflux.core.exception.TransfluxValidationException if either argument is
     *         {@code null} or the id is blank, or if the configurer has already returned
     */
    ActionDef<T, C> onStart(String listenerId, ActionListener<T, C> listener);

    /**
     * Class form of {@link #onStart(String, ActionListener)}. The class is instantiated once,
     * through its public no-arg constructor, when the state machine is built.
     *
     * @param listenerId the listener id
     * @param listenerClass the listener class
     *
     * @return this def for chaining
     */
    ActionDef<T, C> onStart(String listenerId, Class<? extends ActionListener<T, C>> listenerClass);

    /**
     * Configurer form of {@link #onStart(String, ActionListener)}, for a listener that also wants a
     * name or description.
     *
     * @param listenerId the listener id
     * @param configurer receives the listener def; must call {@code using(...)}
     *
     * @return this def for chaining
     */
    ActionDef<T, C> onStart(String listenerId, Consumer<ActionListenerDef<T, C>> configurer);

    /**
     * Attaches a listener notified after this action's body returns normally.
     * <p>
     * This hook and {@link #onError(String, ActionListener)} partition the outcomes: exactly one of
     * them follows every start notification, so a completion listener never has to check whether
     * the action worked. Ordering and the observe-don't-gate rule match
     * {@link #onStart(String, ActionListener)}.
     *
     * @param listenerId the listener id
     * @param listener the listener
     *
     * @return this def for chaining
     */
    ActionDef<T, C> onComplete(String listenerId, ActionListener<T, C> listener);

    /**
     * Class form of {@link #onComplete(String, ActionListener)}.
     *
     * @param listenerId the listener id
     * @param listenerClass the listener class
     *
     * @return this def for chaining
     */
    ActionDef<T, C> onComplete(String listenerId,
                               Class<? extends ActionListener<T, C>> listenerClass);

    /**
     * Configurer form of {@link #onComplete(String, ActionListener)}.
     *
     * @param listenerId the listener id
     * @param configurer receives the listener def; must call {@code using(...)}
     *
     * @return this def for chaining
     */
    ActionDef<T, C> onComplete(String listenerId, Consumer<ActionListenerDef<T, C>> configurer);

    /**
     * Attaches a listener notified when this action's body, or an action it dispatched, throws.
     * <p>
     * A failure is reported at every enclosing level as it propagates outwards, so a container
     * whose member failed is notified too, with the same throwable. Ordering and the
     * observe-don't-gate rule match {@link #onStart(String, ActionListener)}.
     *
     * @param listenerId the listener id
     * @param listener the listener
     *
     * @return this def for chaining
     */
    ActionDef<T, C> onError(String listenerId, ActionListener<T, C> listener);

    /**
     * Class form of {@link #onError(String, ActionListener)}.
     *
     * @param listenerId the listener id
     * @param listenerClass the listener class
     *
     * @return this def for chaining
     */
    ActionDef<T, C> onError(String listenerId, Class<? extends ActionListener<T, C>> listenerClass);

    /**
     * Configurer form of {@link #onError(String, ActionListener)}.
     *
     * @param listenerId the listener id
     * @param configurer receives the listener def; must call {@code using(...)}
     *
     * @return this def for chaining
     */
    ActionDef<T, C> onError(String listenerId, Consumer<ActionListenerDef<T, C>> configurer);

}
