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

package org.transflux.core.impl;

import org.transflux.core.action.ActionDef;
import org.transflux.core.action.ActionListener;
import org.transflux.core.action.ActionListenerDef;
import org.transflux.core.action.ActionPhase;
import org.transflux.core.action.Compensation;
import org.transflux.core.action.CompensationRouteDef;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Sealed base for concrete {@link ActionDef} implementations.
 * <p>
 * The shared metadata ({@code id}, {@code name}, {@code description}) and the fluent
 * {@code withName} / {@code withDescription} setters live on {@link IdentifiedDefImpl}; the
 * {@code SELF} type parameter threads each concrete subclass back into the base so those setters
 * return the precise subclass type covariantly.
 *
 * <p>The abstract dispatch methods ({@link #buildBound}, {@link #checkRefs},
 * {@link #bindMembers}, {@link #bindScope}, {@link #visitScopeOwners}, {@link #declaresFork},
 * {@link #ownByIdReferenceIds}, {@link #collectNestedCycleNodes}) let the state-machine build
 * pipeline drive every authoring form uniformly. {@link StepDefImpl} no-ops the ones that walk
 * children, since an imperative action binds none at definition time.
 *
 * <p>{@link #flattenScope}, {@link #collectScopes} and {@link #scanScopeFor} are deliberately
 * {@code final} here rather than abstract: two forms own scopes, and each of those three has to
 * reach every scope beneath this action. Writing them once over {@link #visitScopeOwners} is what
 * stops one of them reaching a position the others miss.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 * @param <SELF> the concrete subclass type, used for covariant fluent returns
 */
sealed abstract class ActionDefImpl<T, C, SELF extends ActionDefImpl<T, C, SELF>>
    extends IdentifiedDefImpl<SELF> implements ActionDef<T, C>
    permits StepDefImpl, OperationDefImpl, ConditionalOperationDefImpl {

    /**
     * This action's lexical scope, allocated during the build by whoever parents it. Null for an
     * imperative action, which owns none, and until the scope-binding pass has run.
     */
    private RegistryImpl<T> scopeRegistry;

    private final ActionListenerSink<T, C, SELF> listeners = new ActionListenerSink<>(this, self());

    private final CompensationSink<T, C, SELF> compensation = new CompensationSink<>(this, self());

    /**
     * The context this action was declared against, or {@code null} when its declaration site
     * supplied none - an action declared inline takes the enclosing position's, which is not known
     * until the scope-binding pass runs.
     */
    private final Class<C> declaredContextType;

    /**
     * @param id the action id
     * @param label the authored form, used verbatim in diagnostics ({@code "step"} /
     *              {@code "operation"}) so a message names what the author wrote rather than
     *              flattening both forms to "action"
     * @param idLabel the label used when rejecting a blank id
     * @param declaredContextType the context declared at the declaration site, or {@code null}
     *                            when it declared none
     */
    protected ActionDefImpl(String id, String label, String idLabel, Class<C> declaredContextType) {
        super(id, label, idLabel);
        this.declaredContextType = declaredContextType;
    }

    @Override
    @SuppressWarnings("unchecked")
    public final Class<C> contextType() {
        return declaredContextType != null ? declaredContextType : (Class<C>) Object.class;
    }

    /**
     * The context this action's declaration site named, or {@code null} when it named none.
     * Distinct from {@link #contextType()}, which reports the {@code Object} sentinel in that
     * case - the build has to tell "declared as Object" from "not declared".
     *
     * @return the declared context, or {@code null}
     */
    final Class<C> declaredContext() {
        return declaredContextType;
    }

    /**
     * Extends a visible-scope chain with the scope this action owns, innermost first. A reference
     * made inside it resolves through its own scope before walking out, which is the order
     * {@code Registry} follows at runtime.
     *
     * @param enclosing the chain as seen by the enclosing position
     * @param ownScopeId the id of the scope this action owns
     *
     * @return a new chain; the argument is left alone, since siblings share it
     */
    static List<String> inside(List<String> enclosing, String ownScopeId) {
        List<String> chain = new ArrayList<>(enclosing.size() + 1);
        chain.add(ownScopeId);
        chain.addAll(enclosing);
        return chain;
    }

    /**
     * Resolves the context <em>type</em> this declaration named: its own when the declaration site
     * supplied one, {@link Object} included, the enclosing position's otherwise. This is the type
     * javac typed the members from, and every pass that has to agree with javac asks this one - the
     * registry tag, and the pass that records what a by-id reference to an inline id is checked
     * against.
     * <p>
     * What the members are <em>handed</em> at runtime is a different question, and
     * {@link #handedDownContext} answers it. The two differ for exactly one shape - an unmapped
     * declaration naming {@code Object} - and a pass asking one of them while reading the other's
     * answer is how a legal definition gets rejected against a type nobody wrote.
     *
     * @param inheritedContext the enclosing position's context, or {@code null} at a root
     *
     * @return the context to tag with; never {@code null}
     */
    @SuppressWarnings("unchecked")
    final Class<C> effectiveContext(Class<?> inheritedContext) {
        if (declaredContextType != null) {
            return declaredContextType;
        }
        return (Class<C>) (inheritedContext != null ? inheritedContext : Object.class);
    }

    /**
     * Resolves the context <em>object</em> this declaration's members are handed at runtime. The
     * passes reasoning about the live object ask this one: whether a pass-through boundary is legal
     * ({@link #boundaryIsLegal}), what a by-id reference made from inside can be handed, and what a
     * forked member would share. {@link #effectiveContext} answers the type question instead.
     * <p>
     * Three rules, in order. A declaration that names no context, or restates the enclosing one,
     * runs against the enclosing one. A <em>mapped</em> declaration runs against what it named,
     * whatever that is - the mapper produces it, so nothing has to be assignable. An unmapped
     * declaration naming {@link Object} is handed the enclosing object unchanged: it accepts
     * anything, so pass-through changes nothing about what arrives. Anything else runs against what
     * it named, and {@link #boundaryIsLegal} is what rejects the cases where it could not legally
     * reach it.
     *
     * @param inheritedContext the enclosing position's context; {@code null} is read as
     *                         {@code Object}
     * @param mapped whether the call site that declared this action supplied a mapper
     *
     * @return the context this action's members are handed; never {@code null}
     */
    final Class<?> handedDownContext(Class<?> inheritedContext, boolean mapped) {
        Class<?> enclosing = inheritedContext != null ? inheritedContext : Object.class;
        if (declaredContextType == null || declaredContextType == enclosing) {
            return enclosing;
        }
        if (mapped) {
            return declaredContextType;
        }
        if (declaredContextType == Object.class) {
            return enclosing;
        }
        return declaredContextType;
    }

    /**
     * Reports whether an unmapped declaration could legally reach the context it named. Only a
     * pass-through declaration can fail: it is handed the enclosing context verbatim, so what it
     * named has to accept that - it may widen, never narrow.
     *
     * @param inheritedContext the enclosing position's context; {@code null} is read as
     *                         {@code Object}
     * @param mapped whether the call site supplied a mapper
     *
     * @return whether the boundary is legal
     */
    final boolean boundaryIsLegal(Class<?> inheritedContext, boolean mapped) {
        Class<?> enclosing = inheritedContext != null ? inheritedContext : Object.class;
        if (mapped || declaredContextType == null || declaredContextType == enclosing
                || declaredContextType == Object.class) {
            return true;
        }
        return declaredContextType.isAssignableFrom(enclosing);
    }

    @Override
    public SELF withCompensation(Compensation<T, C> compensation) {
        return this.compensation.withCompensationInstance(compensation);
    }

    @Override
    public <X extends Throwable> CompensationRouteDef<T, C, X, SELF> forException(
            Class<X> exceptionType) {
        return compensation.forException(exceptionType);
    }

    /**
     * Resolves the compensation table declared on this def: the fallback and every route, each
     * paired with the failure type it answers for.
     *
     * @return the declared table, or {@code null} when the def declared nothing
     */
    final BoundCompensationRouter<T, C> buildCompensationRouter() {
        return compensation.buildRouter();
    }

    @Override
    public SELF onStart(String listenerId, ActionListener<T, C> listener) {
        return listeners.instanceBased(ActionPhase.START, listenerId, listener);
    }

    @Override
    public SELF onStart(String listenerId, Consumer<ActionListenerDef<T, C>> configurer) {
        return listeners.configured(ActionPhase.START, listenerId, configurer);
    }

    @Override
    public SELF onComplete(String listenerId, ActionListener<T, C> listener) {
        return listeners.instanceBased(ActionPhase.COMPLETE, listenerId, listener);
    }

    @Override
    public SELF onComplete(String listenerId, Consumer<ActionListenerDef<T, C>> configurer) {
        return listeners.configured(ActionPhase.COMPLETE, listenerId, configurer);
    }

    @Override
    public SELF onError(String listenerId, ActionListener<T, C> listener) {
        return listeners.instanceBased(ActionPhase.ERROR, listenerId, listener);
    }

    @Override
    public SELF onError(String listenerId, Consumer<ActionListenerDef<T, C>> configurer) {
        return listeners.configured(ActionPhase.ERROR, listenerId, configurer);
    }

    /**
     * Returns the listener defs collected for one hook, in declaration order. Read by the build
     * when it claims listener ids against the state-machine-wide namespace.
     *
     * @param phase the hook to read
     *
     * @return that hook's listener defs
     */
    final List<ActionListenerDefImpl<T, C>> getListeners(ActionPhase phase) {
        return listeners.forPhase(phase);
    }

    /**
     * Resolves this def's own listeners into their bound form.
     *
     * @return the three hook lists, in declaration order
     */
    final BoundActionListeners<T, C> buildBoundListeners() {
        return listeners.buildBound();
    }

    /**
     * Build-time hook: reports every listener id declared on this action and, for the declarative
     * form, on the actions nested beneath it. The sink receives the listener id and a label naming
     * where it was declared, so a collision in the state-machine-wide listener namespace can point
     * at the offender.
     *
     * @param sink receives {@code (listenerId, ownerLabel)} for each declared listener
     */
    void collectListenerIds(BiConsumer<String, String> sink) {
        emitOwnListenerIds(sink);
    }

    /**
     * Reports the listeners declared directly on this def, hook by hook.
     *
     * @param sink receives {@code (listenerId, ownerLabel)} for each declared listener
     */
    protected final void emitOwnListenerIds(BiConsumer<String, String> sink) {
        for (ActionPhase phase : ActionPhase.values()) {
            for (ActionListenerDefImpl<T, C> ld : getListeners(phase)) {
                sink.accept(ld.getId(), defLabel() + " via " + ActionListenerSink.hook(phase));
            }
        }
    }

    /**
     * Resolves this action into a runtime {@link BoundAction}. A composite's members are not
     * resolved here - {@link #bindMembers} installs them afterwards, once every scope is
     * populated - so what comes back may still be waiting for them.
     *
     * @return the bound action
     */
    abstract BoundAction<T, C> buildBound();

    /**
     * Build-time hook: validates this operation's member references (if any) against the
     * supplied scope context and the SM def's component / mapper registries. The simple variant
     * no-ops; the composite variant walks its {@link ActionRef} list.
     *
     * @param scopeContext the call site's enclosing context type
     * @param scopeLabel a human-readable label for the call site (e.g. {@code "transition 't1'"}),
     *                   used in error messages
     * @param contextOwner names the position {@code scopeContext} was declared on, which is not
     *                     always the call site: a member that declares no context of its own is
     *                     handed the enclosing one, so the position to fix is further out
     * @param visibleScopes the ids of the scopes a reference from here resolves through, innermost
     *                      first - an inline id outside them is not this position's to judge
     * @param smDef the state-machine def whose registries the check consults
     */
    abstract void checkRefs(Class<?> scopeContext, String scopeLabel, String contextOwner,
                            List<String> visibleScopes, StateMachineDefImpl<T> smDef);

    /**
     * Build-time hook: resolves every member this action declares - its own, and those inside any
     * conditional it holds - against the matching lexical scope, and installs them on the
     * executors that will iterate them. Runs after every scope is populated and every container's
     * bound action is registered, which is why it is separate from {@link #buildBound}: a
     * container's bound action goes into the scope its own members resolve against, so a member
     * may name a container declared after it. The simple variant no-ops.
     *
     * @param stateMachine the state machine under construction
     * @param positionLabel names this action's position in the definition tree, extended by every
     *                      position nested beneath it and surfaced when a member fails to resolve
     */
    abstract void bindMembers(StateMachineImpl<T> stateMachine, String positionLabel);

    /**
     * Records the context type every action declared inline beneath this one is written against,
     * keyed by declaring scope and id.
     * <p>
     * A by-id reference is checked against its callee's context, and for a registered component
     * that context comes from the registration. An inline declaration has no registration, so
     * without this pass a reference to one would be checked against the {@code Object} default and
     * admitted whatever the contexts were - which is only safe while every action in a subtree
     * shares one context, and stops being true as soon as a declaration can name its own.
     *
     * <p>It runs before {@link #checkRefs}, over the whole definition, because a member may
     * reference an id declared after it or in an enclosing scope.
     *
     * @param scopeContext the context type this action's own members are written against - its
     *                     {@link #effectiveContext}, not what they are handed; {@code null} is read
     *                     as {@code Object}
     * @param sink receives each inline declaration, with the scope that holds it
     */
    abstract void collectMemberContexts(Class<?> scopeContext, InlineContextSink sink);

    /**
     * Build-time hook: allocates and populates this operation's lexical-scope registry against
     * the enclosing SM. The simple variant no-ops; the composite variant creates a child
     * {@link RegistryImpl} under {@code rootRegistry} and registers its inline members and
     * conditional bound steps into it.
     *
     * @param rootRegistry the SM root registry that scopes parent to
     * @param canonical the per-build canonical-payload table enforcing SM-wide id uniqueness
     * @param conditionRegistry the resolved SM-wide condition registry
     * @param inheritedContext the context of the position this action is attached to, or
     *                         {@code null} when it is registered rather than attached
     */
    abstract void bindScope(RegistryImpl<T> rootRegistry,
                            Map<String, Object> canonical,
                            Map<String, BoundCondition<T, ?>> conditionRegistry,
                            Class<?> inheritedContext);

    /**
     * Wires this action's lexical scope. Called once during state-machine construction, before
     * {@link #buildBound()} runs.
     *
     * @param scopeRegistry the scope registry; never {@code null}
     */
    final void setScopeRegistry(RegistryImpl<T> scopeRegistry) {
        this.scopeRegistry = scopeRegistry;
    }

    /**
     * Returns this action's own lexical scope, without descending.
     *
     * @return the scope registry, or {@code null} when this action owns none
     */
    final RegistryImpl<T> ownScope() {
        return scopeRegistry;
    }

    /**
     * Build-time hook: visits every action beneath this one that owns a lexical scope, at any
     * depth and in any position, excluding this action itself. The walks below are written once
     * over it, so a new position that can hold a scope cannot be reached by some of them and
     * missed by others.
     *
     * @param visitor receives each scope-owning descendant
     */
    abstract void visitScopeOwners(Consumer<ActionDefImpl<T, C, ?>> visitor);

    /**
     * Flattens this action's scope and every scope beneath it, so runtime
     * {@link Registry#resolve(String)} is a single map lookup. Order does not matter:
     * {@link RegistryImpl#flatten()} walks the whole ancestor chain itself.
     */
    final void flattenScope() {
        if (scopeRegistry != null) {
            scopeRegistry.flatten();
        }
        visitScopeOwners(owner -> {
            if (owner.scopeRegistry != null) {
                owner.scopeRegistry.flatten();
            }
        });
    }

    /**
     * Deposits this action's scope and every scope beneath it, so a pass that has to reach
     * components living only inside a scope sees the nested ones too.
     *
     * @param sink receives each scope registry, outermost first
     */
    final void collectScopes(Consumer<Registry<T>> sink) {
        if (scopeRegistry != null) {
            sink.accept(scopeRegistry);
        }
        visitScopeOwners(owner -> {
            if (owner.scopeRegistry != null) {
                sink.accept(owner.scopeRegistry);
            }
        });
    }

    /**
     * Build-time diagnostic: reports which action in this subtree holds {@code id} in its own
     * scope, so an "unknown id" message can say where the id does live. Ids are unique, so at
     * most one scope can answer.
     *
     * @param id the id being scanned for
     * @param excludingId the id of the action originating the search, excluded from the scan
     *
     * @return the holder's id, or empty
     */
    final Optional<String> scanScopeFor(String id, String excludingId) {
        if (holdsInScope(id, excludingId)) {
            return Optional.of(getId());
        }

        String[] hit = {null};
        visitScopeOwners(owner -> {
            if (hit[0] == null && owner.holdsInScope(id, excludingId)) {
                hit[0] = owner.getId();
            }
        });
        return Optional.ofNullable(hit[0]);
    }

    private boolean holdsInScope(String id, String excludingId) {
        return !getId().equals(excludingId) && scopeRegistry != null
            && scopeRegistry.get(id).isPresent();
    }

    /**
     * Reports whether anything in this action's subtree is forked, which is what tells the build
     * whether an executor is needed at all. The imperative variant answers {@code false}: it
     * declares no members, and a body that dispatches by id cannot fork.
     *
     * @return whether a forked member is declared anywhere beneath this action
     */
    abstract boolean declaresFork();

    /**
     * Returns the ids this action reaches by reference - its outgoing edges for cycle detection.
     * A reference to an imperative action cannot close a cycle, so the caller narrows this to ids
     * that name a node before walking it.
     *
     * @return the referenced ids in declaration order
     */
    abstract List<String> ownByIdReferenceIds();

    /**
     * Build-time hook: deposits every dispatching action declared <em>beneath</em> this one into
     * the cycle detector's node table, each with the by-id references it reaches. This action's
     * own entry is not deposited - only the caller knows whether it is registered under an id that
     * resolves to it, and an id nothing can name can never be part of a cycle. The simple variant
     * deposits nothing: an imperative action's dispatches happen against a live transition, which
     * is not visible at definition time.
     *
     * @param sink receives {@code (id, referencedIds)} per node
     */
    abstract void collectNestedCycleNodes(BiConsumer<String, List<String>> sink);

}
