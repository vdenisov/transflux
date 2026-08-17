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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.transflux.core.Identifiable;
import org.transflux.core.action.ActionDef;
import org.transflux.core.action.ActionListener;
import org.transflux.core.action.ActionListenerDef;
import org.transflux.core.action.ActionPhase;
import org.transflux.core.action.Compensation;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Sealed base for concrete {@link ActionDef} implementations.
 * <p>
 * The shared metadata ({@code id}, {@code name}, {@code description}) and the fluent
 * {@code withName} / {@code withDescription} setters live on {@link IdentifiedDefImpl}; the
 * {@code SELF} type parameter threads each concrete subclass back into the base so those setters
 * return the precise subclass type covariantly.
 *
 * <p>The abstract dispatch methods ({@link #buildBound}, {@link #checkRefs},
 * {@link #checkBranchRefs}, {@link #bindScope}, {@link #flattenScope}, {@link #scanScopeFor},
 * {@link #getScopeRegistry}) let the state-machine build pipeline drive both authoring forms
 * uniformly. {@link StepDefImpl} no-ops the scope and ref hooks, since an imperative action
 * binds no children at definition time; only {@link OperationDefImpl} carries real bodies for
 * them.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 * @param <SELF> the concrete subclass type, used for covariant fluent returns
 */
sealed abstract class ActionDefImpl<T, C, SELF extends ActionDefImpl<T, C, SELF>>
    extends IdentifiedDefImpl<SELF> implements ActionDef<T, C>
    permits StepDefImpl, OperationDefImpl {

    private static final Logger log = LoggerFactory.getLogger(ActionDefImpl.class);

    private final ActionListenerSink<T, C, SELF> listeners = new ActionListenerSink<>(this, self());

    private final InstanceOrClassSource<Compensation<T, C>> compensation =
        new InstanceOrClassSource<>(log, "Compensation source", defLabel());

    /**
     * @param id the action id
     * @param label the authored form, used verbatim in diagnostics ({@code "step"} /
     *              {@code "operation"}) so a message names what the author wrote rather than
     *              flattening both forms to "action"
     * @param idLabel the label used when rejecting a blank id
     */
    protected ActionDefImpl(String id, String label, String idLabel) {
        super(id, label, idLabel);
    }

    @Override
    public SELF withCompensation(Compensation<T, C> compensation) {
        requireConfigurerActive("withCompensation");
        requireNotNull(compensation, "Compensation");
        this.compensation.setInstance(compensation);
        return self();
    }

    @Override
    public SELF withCompensation(Class<? extends Compensation<T, C>> compensationClass) {
        requireConfigurerActive("withCompensation");
        requireNotNull(compensationClass, "Compensation class");
        this.compensation.setClass(compensationClass);
        return self();
    }

    /**
     * Resolves the compensation declared on this def, instantiating the class form if that is what
     * was supplied.
     *
     * @return the declared compensation, or {@code null} when the def declared none
     */
    final Compensation<T, C> buildDeclaredCompensation() {
        return compensation.resolveOptional("Compensation");
    }

    @Override
    public SELF onStart(String listenerId, ActionListener<T, C> listener) {
        return listeners.instanceBased(ActionPhase.START, listenerId, listener);
    }

    @Override
    public SELF onStart(Identifiable listenerIdentifiable, ActionListener<T, C> listener) {
        return listeners.instanceBased(ActionPhase.START, listenerIdentifiable, listener);
    }

    @Override
    public SELF onStart(String listenerId, Class<? extends ActionListener<T, C>> listenerClass) {
        return listeners.classBased(ActionPhase.START, listenerId, listenerClass);
    }

    @Override
    public SELF onStart(Identifiable listenerIdentifiable,
                        Class<? extends ActionListener<T, C>> listenerClass) {
        return listeners.classBased(ActionPhase.START, listenerIdentifiable, listenerClass);
    }

    @Override
    public SELF onStart(String listenerId, Consumer<ActionListenerDef<T, C>> configurer) {
        return listeners.configured(ActionPhase.START, listenerId, configurer);
    }

    @Override
    public SELF onStart(Identifiable listenerIdentifiable,
                        Consumer<ActionListenerDef<T, C>> configurer) {
        return listeners.configured(ActionPhase.START, listenerIdentifiable, configurer);
    }

    @Override
    public SELF onComplete(String listenerId, ActionListener<T, C> listener) {
        return listeners.instanceBased(ActionPhase.COMPLETE, listenerId, listener);
    }

    @Override
    public SELF onComplete(Identifiable listenerIdentifiable, ActionListener<T, C> listener) {
        return listeners.instanceBased(ActionPhase.COMPLETE, listenerIdentifiable, listener);
    }

    @Override
    public SELF onComplete(String listenerId, Class<? extends ActionListener<T, C>> listenerClass) {
        return listeners.classBased(ActionPhase.COMPLETE, listenerId, listenerClass);
    }

    @Override
    public SELF onComplete(Identifiable listenerIdentifiable,
                           Class<? extends ActionListener<T, C>> listenerClass) {
        return listeners.classBased(ActionPhase.COMPLETE, listenerIdentifiable, listenerClass);
    }

    @Override
    public SELF onComplete(String listenerId, Consumer<ActionListenerDef<T, C>> configurer) {
        return listeners.configured(ActionPhase.COMPLETE, listenerId, configurer);
    }

    @Override
    public SELF onComplete(Identifiable listenerIdentifiable,
                           Consumer<ActionListenerDef<T, C>> configurer) {
        return listeners.configured(ActionPhase.COMPLETE, listenerIdentifiable, configurer);
    }

    @Override
    public SELF onError(String listenerId, ActionListener<T, C> listener) {
        return listeners.instanceBased(ActionPhase.ERROR, listenerId, listener);
    }

    @Override
    public SELF onError(Identifiable listenerIdentifiable, ActionListener<T, C> listener) {
        return listeners.instanceBased(ActionPhase.ERROR, listenerIdentifiable, listener);
    }

    @Override
    public SELF onError(String listenerId, Class<? extends ActionListener<T, C>> listenerClass) {
        return listeners.classBased(ActionPhase.ERROR, listenerId, listenerClass);
    }

    @Override
    public SELF onError(Identifiable listenerIdentifiable,
                        Class<? extends ActionListener<T, C>> listenerClass) {
        return listeners.classBased(ActionPhase.ERROR, listenerIdentifiable, listenerClass);
    }

    @Override
    public SELF onError(String listenerId, Consumer<ActionListenerDef<T, C>> configurer) {
        return listeners.configured(ActionPhase.ERROR, listenerId, configurer);
    }

    @Override
    public SELF onError(Identifiable listenerIdentifiable,
                        Consumer<ActionListenerDef<T, C>> configurer) {
        return listeners.configured(ActionPhase.ERROR, listenerIdentifiable, configurer);
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
     * Resolves this operation into a runtime {@link BoundAction}. The {@code stateMachine}
     * argument is consumed by the composite variant to resolve member references; the simple
     * variant ignores it.
     *
     * @param stateMachine the enclosing state machine under construction
     *
     * @return the bound operation
     */
    abstract BoundAction<T, C> buildBound(StateMachineImpl<T> stateMachine);

    /**
     * Build-time hook: validates this operation's member references (if any) against the
     * supplied scope context and the SM def's component / mapper registries. The simple variant
     * no-ops; the composite variant walks its {@link ActionRef} list.
     *
     * @param scopeContext the call site's enclosing context type
     * @param scopeLabel a human-readable label for the call site (e.g. {@code "transition 't1'"}),
     *                   used in error messages
     * @param smDef the state-machine def whose registries the check consults
     */
    abstract void checkRefs(Class<?> scopeContext, String scopeLabel, StateMachineDefImpl<T> smDef);

    /**
     * Build-time hook: verifies that every by-id member declared inside a conditional's branches
     * resolves in this action's lexical scope. Runs after the scope registries are populated and
     * flattened, which is why it is separate from {@link #checkRefs} — branch members are the one
     * position whose ids cannot be checked before that point. The simple variant no-ops.
     */
    abstract void checkBranchRefs();

    /**
     * Build-time hook: allocates and populates this operation's lexical-scope registry against
     * the enclosing SM. The simple variant no-ops; the composite variant creates a child
     * {@link RegistryImpl} under {@code rootRegistry} and registers its inline members and
     * conditional bound steps into it.
     *
     * @param rootRegistry the SM root registry that scopes parent to
     * @param canonical the per-build canonical-payload table enforcing SM-wide id uniqueness
     * @param conditionRegistry the resolved SM-wide condition registry
     */
    abstract void bindScope(RegistryImpl<T> rootRegistry,
                            Map<String, Object> canonical,
                            Map<String, BoundCondition<T, ?>> conditionRegistry);

    /**
     * Build-time hook: flattens this operation's scope registry (if any) so runtime
     * {@link Registry#resolve(String)} is a single map lookup. The simple variant no-ops.
     */
    abstract void flattenScope();

    /**
     * Build-time diagnostic hook: returns this operation's id when its local scope registry
     * contains an entry for {@code id} and this operation's id is not {@code excludingId}.
     * Used by {@link ActionRef} resolution to enrich "unknown id" diagnostics when an id
     * exists inline in a sibling composite. The simple variant always returns
     * {@link Optional#empty()}.
     *
     * @param id the id being scanned for
     * @param excludingId the id of the composite originating the search (excluded from the scan)
     *
     * @return this composite's id when the scan matches, otherwise empty
     */
    abstract Optional<String> scanScopeFor(String id, String excludingId);

    /**
     * Build-time hook: returns this operation's lexical-scope registry so the build can reach the
     * components that live only inside it. The simple variant, and a composite whose scope has not
     * been bound yet, return {@code null}.
     *
     * @return the scope registry, or {@code null} when this operation owns none
     */
    abstract Registry<T> getScopeRegistry();
}
