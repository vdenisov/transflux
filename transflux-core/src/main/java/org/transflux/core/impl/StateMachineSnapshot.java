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

import org.transflux.core.StateMachine;
import org.transflux.core.exception.TransfluxConditionException;
import org.transflux.core.exception.TransfluxContextException;
import org.transflux.core.exception.TransfluxValidationException;
import org.transflux.core.action.ActionExecution;
import org.transflux.core.action.ActionPhase;
import org.transflux.core.action.AsyncRejectionPolicy;
import org.transflux.core.action.Compensation;
import org.transflux.core.action.ContextMapper;
import org.transflux.core.action.ForkableContext;
import org.transflux.core.action.Action;
import org.transflux.core.state.State;
import org.transflux.core.state.StateApplier;
import org.transflux.core.state.StateChange;
import org.transflux.core.state.StatePhase;
import org.transflux.core.state.StateResolver;
import org.transflux.core.transition.ProcessResult;
import org.transflux.core.transition.ActionPath;
import org.transflux.core.transition.Transition;
import org.transflux.core.transition.TransitionExecution;
import org.transflux.core.transition.TransitionListener;
import org.transflux.core.transition.TransitionPhase;
import org.transflux.core.transition.TransitionResult;
import org.transflux.core.trigger.Trigger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.transflux.core.Preconditions.requireNotBlank;
import static org.transflux.core.Preconditions.requireNotNull;
import static org.transflux.core.impl.ThrowingUtils.sneakyGet;

/**
 * One built version of a state machine: the states, transitions, triggers, listeners and registries
 * a single definition resolved into, together with the engine that executes them.
 * <p>
 * Everything here is immutable once construction returns, which is what lets
 * {@link StateMachineImpl} hand out a snapshot per call and replace it underneath without splitting
 * an execution between two versions. What is <i>not</i> per version - the executor, the reentrancy
 * guard and the ban on driving the machine from a branch - lives on the handle and is reached
 * through {@link #handle}.
 *
 * @param <T> the type of entity managed by this state machine
 */
class StateMachineSnapshot<T> {
    private final StateMachineImpl<T> handle;

    private final StateResolver<? super T> stateResolver;
    private final StateApplier<? super T> stateApplier;

    private final Map<String, State> states = new LinkedHashMap<>();
    private final Map<String, BoundTransition<T, ?>> transitions = new LinkedHashMap<>();
    private final Map<String, TriggerImpl> triggers = new LinkedHashMap<>();

    /** Transitions indexed by the source state they leave, so a targeted lookup scans only those. */
    private final Map<String, List<BoundTransition<T, ?>>> transitionsBySource = new LinkedHashMap<>();

    /**
     * Host-driven triggers indexed by the source state they leave, each paired with its already
     * resolved transition. Dispatch is a scan of the entity's current state only, and the lists
     * preserve declaration order so first-match semantics are unchanged.
     */
    private final Map<String, List<TriggerBinding<T, EventTriggerImpl<T>>>> eventTriggersBySource =
        new LinkedHashMap<>();
    private final Map<String, List<TriggerBinding<T, DataTriggerImpl<T, ?>>>> dataTriggersBySource =
        new LinkedHashMap<>();

    /**
     * For each manual trigger, the transition it fires from a given source state. A registered
     * trigger sits on any number of transitions, so {@code fire(id)} picks the attachment leaving
     * the entity's current state; the build rejects two attachments that would share one.
     */
    private final Map<String, Map<String, BoundTransition<T, ?>>> manualTriggerTargets =
        new LinkedHashMap<>();

    /**
     * State listeners indexed by the state they observe, in notification order: the state's own
     * listeners first, then the ones registered against every state. Merging at build time keeps
     * notification a single map lookup.
     */
    private final Map<String, List<BoundStateListener<T>>> entryListenersByState = new LinkedHashMap<>();
    private final Map<String, List<BoundStateListener<T>>> exitListenersByState = new LinkedHashMap<>();

    /**
     * Listeners registered against every action, bound once. They are notified after the running
     * action's own listeners rather than merged into each bound action, because one bound action is
     * shared by every call site that reaches it and merging would bind the same global listener
     * once per action.
     */
    private final BoundActionListeners<T, Object> globalActionListeners;

    /**
     * The global action listeners left after each disabling action's declaration, keyed by the
     * identity of the declaration's frozen copy, which is what a bound action carries. Filled once
     * at build, so notification never filters.
     */
    private final Map<GlobalListenerDisables, BoundActionListeners<T, Object>> filteredActionGlobals =
        new IdentityHashMap<>();

    /**
     * Registered mappers, resolved once: a built machine answers from what it was built with, not
     * from a def the host may have gone on registering into.
     */
    private final Map<String, ContextMapper<Object, Object>> mappers = new LinkedHashMap<>();

    private final Registry<T> componentRegistry;

    /**
     * Copied rather than read back off the def, which nothing freezes at build: the same
     * definition builds any number of machines, and each answers with what it was built from.
     */
    private final String id;
    private final String name;
    private final String description;
    private final String version;

    private final AsyncRejectionPolicy asyncRejectionPolicy;

    @SuppressWarnings({"unchecked", "rawtypes"})
    StateMachineSnapshot(StateMachineDefImpl<T> def, StateMachineImpl<T> handle) {
        this.handle = handle;
        this.id = def.getId();
        this.name = def.getName();
        this.description = def.getDescription();
        this.version = def.getVersion();
        this.stateResolver = def.getStateResolver();
        this.stateApplier = def.getStateApplier();
        this.asyncRejectionPolicy = def.getAsyncRejectionPolicy();

        this.states.putAll(def.getStates().values().stream()
                              .collect(Collectors.toMap(StateDefImpl::getId, StateImpl::new)));

        for (MapperDefImpl<?, ?> mapperDef : def.getMapperRegistrations().values()) {
            this.mappers.put(mapperDef.getId(), (ContextMapper<Object, Object>) mapperDef.buildMapper());
        }

        buildStateListenerIndexes(def);
        this.globalActionListeners = bindGlobalActionListeners(def);

        RegistryImpl<T> registry = new RegistryImpl<>();
        this.componentRegistry = registry;

        // The order below is load-bearing:
        //  1. SM-level conditions and steps populate the root registry first, so that
        //  2. composite scopes (bindCompositeScopes) are allocated and every container is
        //     registered under its id (buildBoundOperations), before
        //  3. every declared member binds in one pass, once each container's own bound action is
        //     in the scope its members resolve against — which is what lets a member reference a
        //     container declared after its own — and finally
        //  4. flatten() runs strictly last (root, then composite scopes), collapsing each chain
        //     so runtime resolve() is a single map lookup. Members are resolved against the
        //     still-chained scopes during build, so flattening earlier — or switching
        //     build-time resolution from resolve() to get() — breaks root fallback.
        Map<String, BoundCondition<T, ?>> conditionRegistry = def.buildBoundConditions();
        for (BoundCondition<T, ?> bc : conditionRegistry.values()) {
            Class<?> ctx = effectiveContextType(def, bc.id());
            registry.register(new Component.Condition(bc.id(), ctx, bc));
        }

        Map<String, BoundAction<T, ?>> boundSteps = def.buildBoundActions();
        for (BoundAction<T, ?> bs : boundSteps.values()) {
            Class<?> ctx = effectiveContextType(def, bs.id());
            registry.register(new Component.Action(bs.id(), ctx, bs));
        }

        def.bindCompositeScopes(registry, conditionRegistry);
        Loggers.BUILD_REGISTRY.debug("Container scopes bound to the root registry");

        def.buildBoundOperations(bo -> {
            Class<?> ctx = effectiveContextType(def, bo.id());
            registry.register(new Component.Action(bo.id(), ctx, bo));
        });

        BoundTransitionListeners<T, Object> globalTransitionListeners = bindGlobalTransitionListeners(def);
        for (TransitionDefImpl<T, ?> td : def.getTransitionsById().values()) {
            BoundTransition<T, ?> transition = buildTransition(td, conditionRegistry, globalTransitionListeners,
                                                               def.listenerBinder());
            this.transitions.put(td.getId(), transition);
            this.transitionsBySource.computeIfAbsent(transition.sourceStateId(), s -> new ArrayList<>())
                                    .add(transition);
        }

        registerTriggers(def, conditionRegistry);

        def.bindDeferredMembers(this);
        def.visitActionDefs(actionDef -> indexFilteredActionGlobals(actionDef.getDisabledGlobals().frozen()));

        registry.flatten();
        def.flattenCompositeScopes();
        Loggers.BUILD_REGISTRY.debug("Registry scopes flattened, rootComponents={}", registry.ids().size());
    }

    /**
     * Returns the mapper registered under {@code id} when this machine was built.
     *
     * @param id the mapper id
     *
     * @return the mapper, or {@code null} if none was registered under that id
     */
    ContextMapper<Object, Object> getMapper(String id) {
        return mappers.get(id);
    }

    Registry<T> getComponentRegistry() {
        return componentRegistry;
    }

    int stateCount() {
        return states.size();
    }

    int transitionCount() {
        return transitions.size();
    }

    int triggerCount() {
        return triggers.size();
    }

    /** Root-registry entries only; a container's inline members live in its own scope. */
    int componentCount() {
        return componentRegistry.ids().size();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    BoundAction<T, ?> getBoundAction(String id) {
        return componentRegistry.resolve(id)
            .filter(Component.Action.class::isInstance)
            .map(c -> ((Component.Action) c).bound())
            .orElse(null);
    }

    /**
     * Runs a transition's body - its members, in order.
     * <p>
     * Invoked directly rather than through {@link ExecutingTransitionImpl#runAction}, because the
     * body is not an action: it has no id in the action namespace, so there is nothing to qualify
     * a path with, nothing to capture a compensation for, and no listener to notify. The executor
     * pushes the body's scope and dispatches each member, and every member takes the one execution
     * path from there.
     * <p>
     * The entity and context come off the view rather than from the caller: the cast is raw, so
     * javac cannot check them, and the view is where every member reads them from anyway.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T, C> void runBody(ExecutingTransitionImpl<T, C> view, BoundAction<T, C> body) {
        ((Action) body.action()).execute(view.getEntity(), view.getContext(), view);
    }

    StateApplier<? super T> getStateApplier() {
        return stateApplier;
    }

    /**
     * Hands one branch to the executor the handle owns, filling in this definition's default policy
     * when the forked action declared none.
     *
     * @param task the branch to run
     * @param path the forked member's qualified path, for diagnostics
     * @param declared the policy the forked action declared, or {@code null} to take this
     *                 definition's default
     *
     * @throws java.util.concurrent.RejectedExecutionException under
     *         {@link AsyncRejectionPolicy#FAIL}, and under {@link AsyncRejectionPolicy#BLOCK} when
     *         waiting cannot help, failing the transition that was forking
     */
    void submitBranch(Runnable task, ActionPath path, AsyncRejectionPolicy declared) {
        handle.submitBranch(task, path, policyFor(declared));
    }

    /**
     * Resolves the policy in force for one piece of async work: what it declared, or this
     * definition's default when it declared nothing.
     *
     * @param declared the declared policy, or {@code null}
     *
     * @return the policy to apply; never {@code null}
     */
    AsyncRejectionPolicy policyFor(AsyncRejectionPolicy declared) {
        return declared != null ? declared : asyncRejectionPolicy;
    }

    /**
     * Refuses async work the executor could never run as asked, before anything is spent preparing
     * it.
     *
     * @param policy the resolved rejection policy; never {@code null}
     * @param subject what the work is, for the message
     *
     * @throws TransfluxValidationException when no executor is configured, or when
     *         {@link AsyncRejectionPolicy#BLOCK} is asked for against a host-supplied one
     */
    void requireAsyncAccepted(AsyncRejectionPolicy policy, Object subject) {
        handle.requireAsyncAccepted(policy, subject);
    }

    /**
     * Marks the calling thread as running a branch this state machine spawned. Paired with
     * {@link #exitAsyncBranch()} in a {@code finally}.
     */
    void enterAsyncBranch() {
        handle.enterAsyncBranch();
    }

    /** Clears the mark {@link #enterAsyncBranch()} set. */
    void exitAsyncBranch() {
        handle.exitAsyncBranch();
    }

    /** @return the id the definition declared, or {@code null} */
    String getId() {
        return id;
    }

    /** @return the name the definition declared, or {@code null} */
    String getName() {
        return name;
    }

    /** @return the description the definition declared, or {@code null} */
    String getDescription() {
        return description;
    }

    /** @return the version the definition declared, or {@code null} */
    String getVersion() {
        return version;
    }

    StateMachine.EntityBinding<T> entity(T entity) {
        requireNotNull(entity, "Entity");
        return new EntityBindingImpl(entity);
    }

    String resolveCurrentState(T entity) {
        requireNotNull(entity, "Entity");
        if (stateResolver == null) {
            throw new TransfluxValidationException(
                "No state resolver configured for this state machine"
            );
        }

        String stateId = stateResolver.resolveState(entity);

        // The entity's type, never the entity: it belongs to the host and may be full of PII, and
        // this message surfaces wherever the resulting stack trace is printed.
        if (stateId == null || stateId.isBlank()) {
            throw new TransfluxValidationException(
                "State resolver returned null or blank state ID, entityType=" + entity.getClass().getName()
            );
        }

        if (!states.containsKey(stateId)) {
            throw new TransfluxValidationException(
                String.format("State resolver returned unknown state ID '%s', entityType=%s",
                           stateId, entity.getClass().getName())
            );
        }

        return stateId;
    }

    Collection<Trigger> getTriggers() {
        return List.<Trigger>copyOf(triggers.values());
    }

    <X extends Trigger> Collection<X> getTriggers(Class<X> kind) {
        requireNotNull(kind, "Trigger kind");
        return triggers.values().stream()
            .filter(kind::isInstance)
            .map(kind::cast)
            .collect(Collectors.toUnmodifiableList());
    }

    Trigger getTrigger(String triggerId) {
        return getTriggerImpl(triggerId);
    }

    /**
     * Resolves a registered trigger to its runtime implementation, which carries the dispatch seams
     * the public {@link Trigger} view does not expose.
     *
     * @param triggerId the trigger id to resolve
     *
     * @return the registered trigger
     *
     * @throws TransfluxValidationException if no trigger is registered under that id
     */
    TriggerImpl getTriggerImpl(String triggerId) {
        requireNotBlank(triggerId, "Trigger ID");
        TriggerImpl trigger = triggers.get(triggerId);
        if (trigger == null) {
            throw new TransfluxValidationException("Trigger '" + triggerId + "' does not exist");
        }
        return trigger;
    }

    State getState(String stateId) {
        requireNotBlank(stateId, "State ID");

        State state = states.get(stateId);
        if (state == null) {
            throw new TransfluxValidationException("State '" + stateId + "' does not exist");
        }

        return state;
    }

    BoundTransition<T, ?> getTransition(String transitionId) {
        requireNotBlank(transitionId, "Transition ID");

        BoundTransition<T, ?> transition = transitions.get(transitionId);
        if (transition == null) {
            throw new TransfluxValidationException("Transition '" + transitionId + "' does not exist");
        }

        return transition;
    }

    /**
     * Builds every trigger the definition declares and indexes it per attachment.
     * <p>
     * A trigger is built exactly once however many transitions attach it, because it is one
     * trigger: the catalog lists it once and reports them all. The scan order a transition
     * contributes is its own declarations first, then the registrations it attached, which is what
     * first-match dispatch reads.
     *
     * @param def the definition being built
     * @param conditionRegistry the resolved conditions a manual trigger's pre-conditions and a data
     *                          trigger's gate reference by id
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void registerTriggers(StateMachineDefImpl<T> def,
                                  Map<String, BoundCondition<T, ?>> conditionRegistry) {
        Map<String, List<TransitionDefImpl<T, ?>>> attachments = new LinkedHashMap<>();
        Map<String, TriggerDefImpl<T, ?, ?>> defsById = new LinkedHashMap<>();

        for (TransitionDefImpl<T, ?> td : def.getTransitionsById().values()) {
            for (TriggerDefImpl<T, ?, ?> declared : declaredTriggers(td)) {
                claimTriggerId(defsById, declared);
                attachments.computeIfAbsent(declared.getId(), k -> new ArrayList<>()).add(td);
            }
            for (String ref : td.getTriggerRefs()) {
                claimTriggerId(defsById, def.getTriggerRegistrations().get(ref));
                attachments.computeIfAbsent(ref, k -> new ArrayList<>()).add(td);
            }
        }

        // A registration nothing attached is still a trigger the catalog reports, with no
        // transitions - a component library may register one a later definition attaches.
        for (TriggerDefImpl<T, ?, ?> registered : def.getTriggerRegistrations().values()) {
            claimTriggerId(defsById, registered);
        }

        for (Map.Entry<String, TriggerDefImpl<T, ?, ?>> entry : defsById.entrySet()) {
            List<String> transitionIds = attachments.getOrDefault(entry.getKey(), List.of())
                                                    .stream()
                                                    .map(TransitionDefImpl::getId)
                                                    .toList();
            putTrigger(buildTrigger(entry.getValue(), transitionIds, conditionRegistry));
        }

        // Indexing walks the transitions again rather than the triggers, because a source state's
        // scan order is the order its transitions declared their triggers - and a registration
        // attached by an earlier transition must not drag a later one's attachment up with it.
        for (TransitionDefImpl<T, ?> td : def.getTransitionsById().values()) {
            BoundTransition<T, ?> transition = getTransition(td.getId());
            for (TriggerDefImpl<T, ?, ?> declared : declaredTriggers(td)) {
                indexAttachment(triggers.get(declared.getId()), transition);
            }
            for (String ref : td.getTriggerRefs()) {
                indexAttachment(triggers.get(ref), transition);
            }
        }
    }

    /**
     * Claims one id for one trigger def. Attaching a registration several times names the same def
     * every time and is the point of the feature; two <em>different</em> defs under one id is the
     * ordinary duplicate, whichever kinds they are and wherever each was declared.
     *
     * @param defsById the ids claimed so far
     * @param triggerDef the def claiming its id
     *
     * @throws TransfluxValidationException if another def claimed the id
     */
    private void claimTriggerId(Map<String, TriggerDefImpl<T, ?, ?>> defsById, TriggerDefImpl<T, ?, ?> triggerDef) {
        String id = triggerDef.getId();
        TriggerDefImpl<T, ?, ?> prior = defsById.putIfAbsent(id, triggerDef);
        if (prior == null || prior == triggerDef) {
            return;
        }
        String first = prior.getOwner() == null ? null : prior.getOwner().getId();
        String second = triggerDef.getOwner() == null ? null : triggerDef.getOwner().getId();
        String where;
        if (first != null && first.equals(second)) {
            where = "declared twice on transition '" + first + "'";
        } else if (first != null && second != null) {
            where = "declared on transition '" + first + "' and on transition '" + second + "'";
        } else {
            // Exactly one is a registration: a second registration under one id is refused when it is made.
            where = "registered on the state machine and declared on transition '"
                + (first != null ? first : second) + "'";
        }
        // addEventTrigger(eventId) names the trigger after its event, so one event on two transitions lands here.
        String advice = prior instanceof EventTriggerDefImpl<?, ?> a && triggerDef instanceof EventTriggerDefImpl<?, ?> b
            && a.getEventId() != null && a.getEventId().equals(b.getEventId()) && first != null && second != null
            ? "; to fire several transitions on one event, register the trigger once with eventTrigger(...) and"
                + " attach it to each with addTrigger(id)"
            : "";
        throw new TransfluxValidationException(
            "Trigger id '" + id + "' is " + where + "; ids are unique across this state machine's triggers" + advice);
    }

    /** The triggers a transition declares in place, in the order dispatch scans them. */
    private List<TriggerDefImpl<T, ?, ?>> declaredTriggers(TransitionDefImpl<T, ?> td) {
        List<TriggerDefImpl<T, ?, ?>> declared = new ArrayList<>();
        declared.addAll(td.getManualTriggers());
        declared.addAll(td.getEventTriggers());
        declared.addAll(td.getDataTriggers());
        return declared;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private TriggerImpl buildTrigger(TriggerDefImpl<T, ?, ?> triggerDef, List<String> transitionIds,
                                     Map<String, BoundCondition<T, ?>> conditionRegistry) {
        if (triggerDef instanceof ManualTriggerDefImpl manual) {
            return manual.buildBoundTrigger((Map) conditionRegistry, transitionIds);
        }
        if (triggerDef instanceof EventTriggerDefImpl event) {
            return event.buildBoundTrigger(transitionIds);
        }
        return ((DataTriggerDefImpl) triggerDef).buildBoundTrigger((Map) conditionRegistry,
                                                                   transitionIds);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void indexAttachment(TriggerImpl trigger, BoundTransition<T, ?> transition) {
        String source = transition.sourceStateId();
        if (trigger instanceof EventTriggerImpl<?> event) {
            eventTriggersBySource.computeIfAbsent(source, s -> new ArrayList<>())
                                 .add(new TriggerBinding(event, transition));
        } else if (trigger instanceof DataTriggerImpl<?, ?> data) {
            dataTriggersBySource.computeIfAbsent(source, s -> new ArrayList<>())
                                .add(new TriggerBinding(data, transition));
        } else {
            manualTriggerTargets.computeIfAbsent(trigger.getId(), t -> new LinkedHashMap<>())
                                .put(source, transition);
        }
        Loggers.BUILD_BINDING.debug("Trigger attached, triggerId={}, transitionId={}",
                                    trigger.getId(), transition.id());
    }

    /**
     * Picks the transition a manual trigger fires from the entity's current state.
     * <p>
     * One trigger may sit on several transitions, so the current state is what selects between
     * them. The build rejects two attachments leaving one state, which is what makes the choice
     * unambiguous here.
     *
     * @param trigger the trigger being fired
     * @param currentStateId the state the entity is in
     *
     * @return the transition to execute; never {@code null}
     *
     * @throws TransfluxValidationException if the trigger sits on no transition leaving that state
     */
    private BoundTransition<T, ?> manualTarget(TriggerImpl trigger, String currentStateId) {
        Map<String, BoundTransition<T, ?>> bySource =
            manualTriggerTargets.getOrDefault(trigger.getId(), Map.of());
        BoundTransition<T, ?> transition = bySource.get(currentStateId);
        if (transition == null) {
            throw new TransfluxValidationException(
                String.format("Entity is in state '%s' but trigger '%s' leaves %s",
                              currentStateId, trigger.getId(),
                              bySource.isEmpty() ? "no state" : "only " + bySource.keySet()));
        }

        return transition;
    }

    private void putTrigger(TriggerImpl trigger) {
        // Ids are unique by now: every def reached here was claimed by claimTriggerId.
        triggers.put(trigger.getId(), trigger);
    }

    /**
     * Resolves every declared state's entry and exit listeners into notification order. The
     * globals are bound once and shared across states rather than rebound per state.
     */
    private void buildStateListenerIndexes(StateMachineDefImpl<T> def) {
        ListenerRegistrations<T> binder = def.listenerBinder();
        Map<String, StateListenerDefImpl<T>> globalScope =
            ListenerRegistrations.ownScope(def.getGlobalEntryListeners(), def.getGlobalExitListeners());
        List<BoundStateListener<T>> globalEntry =
            binder.bindStates(def.getGlobalEntryListeners(), globalScope);
        List<BoundStateListener<T>> globalExit =
            binder.bindStates(def.getGlobalExitListeners(), globalScope);

        for (StateDefImpl<T> sd : def.getStates().values()) {
            Map<String, StateListenerDefImpl<T>> ownScope =
                ListenerRegistrations.ownScope(sd.getEntryListeners(), sd.getExitListeners());
            // The state's own deny-list applies here rather than at the hook: this merge is
            // already per state, so filtering costs the build one pass and notification nothing.
            GlobalListenerDisables disabled = sd.getDisabledGlobals();
            entryListenersByState.put(sd.getId(),
                concatListeners(binder.bindStates(sd.getEntryListeners(), ownScope),
                                disabled.filter(globalEntry, BoundStateListener::id)));
            exitListenersByState.put(sd.getId(),
                concatListeners(binder.bindStates(sd.getExitListeners(), ownScope),
                                disabled.filter(globalExit, BoundStateListener::id)));
        }
    }

    private <C> void notifyStateExit(BoundTransition<T, C> transition, T entity, C context) {
        notifyStateListeners(transition.sourceStateId(), StatePhase.EXIT, transition, entity, context);
    }

    private <C> void notifyStateEntry(BoundTransition<T, C> transition, T entity, C context) {
        notifyStateListeners(transition.targetStateId(), StatePhase.ENTRY, transition, entity, context);
    }

    /**
     * Notifies one state's listeners, in order, isolating each from the others and from the
     * transition. A listener that throws is logged and skipped: listeners observe, and neither
     * hook is positioned where failing the transition would be honest — the exit hook runs before
     * any step could be compensated, the entry hook after the state has been committed.
     */
    private <C> void notifyStateListeners(String stateId, StatePhase phase,
                                          BoundTransition<T, C> transition, T entity, C context) {
        List<BoundStateListener<T>> listeners = phase == StatePhase.ENTRY
            ? entryListenersByState.get(stateId)
            : exitListenersByState.get(stateId);

        if (listeners == null || listeners.isEmpty()) {
            return;
        }

        StateChange change =
            new StateChange(phase, states.get(stateId), TransitionImpl.of(transition));

        for (BoundStateListener<T> listener : listeners) {
            deliver(listener.id(), listener.async(), context, ctx -> {
                try {
                    listener.listener().onState(entity, ctx, change);
                } catch (Exception | Error e) {
                    ThrowingUtils.rethrowIfFatal(e);
                    // The class name, never the message: a host's own exception can carry anything
                    // at all, including the entity the listener was handed.
                    Loggers.EXECUTION_LISTENER.warn(
                        "State listener threw, listenerId={}, phase={}, stateId={}, errorType={}",
                        listener.id(), phase, stateId, e.getClass().getName());
                }
            });
        }
    }

    /**
     * Notifies one hook of a transition's listeners, in order, isolating each from the others and
     * from the transition. A listener that throws is logged and skipped: listeners observe, and no
     * hook is positioned where failing the transition would be honest — the start hook runs before
     * any step could be compensated, and the two terminal hooks after the outcome is settled.
     */
    private <C> void notifyTransitionListeners(BoundTransition<T, C> transition, TransitionPhase phase,
                                               T entity, C context, Trigger firedBy,
                                               TransitionResult<T> result) {
        List<BoundTransitionListener<T, C>> listeners = transition.boundListeners().forPhase(phase);
        if (listeners.isEmpty()) {
            return;
        }

        TransitionExecution<T> execution = new TransitionExecution<>(
            phase, TransitionImpl.of(transition), firedBy, result);

        for (BoundTransitionListener<T, C> listener : listeners) {
            // The payload record is invariant, so a listener written against a supertype cannot be
            // handed it directly; it only ever produces T, which is what makes the cast sound.
            @SuppressWarnings("unchecked")
            TransitionListener<T, C> target = (TransitionListener<T, C>) listener.listener();
            deliver(listener.id(), listener.async(), context, ctx -> {
                try {
                    target.onTransition(entity, ctx, execution);
                } catch (Exception | Error e) {
                    ThrowingUtils.rethrowIfFatal(e);
                    Loggers.EXECUTION_LISTENER.warn(
                        "Transition listener threw, listenerId={}, phase={}, transitionId={}, errorType={}",
                        listener.id(), phase, transition.id(), e.getClass().getName());
                }
            });
        }
    }

    /**
     * Notifies one hook of an action's listeners - the action's own first, then the ones registered
     * against every action - isolating each from the others and from the execution. A listener that
     * throws is logged and skipped: listeners observe, and this hook sits in the middle of a live
     * execution, where failing on an observer's behalf would compensate work the transition itself
     * had no complaint about.
     *
     * @param bound the action being run
     * @param phase which hook is firing
     * @param entity the entity the transition is running against
     * @param context the context the action itself runs against - the mapped child context at a
     *                mapped call site
     * @param path the action's qualified path within this execution
     * @param transition the read-only view of the transition being executed - the caller's own, so
     *                   that the one view built per execution serves every notification in it
     * @param error the failure at {@link ActionPhase#ERROR}, otherwise {@code null}
     * @param duration how long the body ran, or {@code null} at {@link ActionPhase#START}
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    <C> void notifyActionListeners(BoundAction<T, C> bound, ActionPhase phase, T entity, C context,
                                   ActionPath path, Transition transition, Throwable error,
                                   Duration duration) {
        List<BoundActionListener<T, C>> own = bound.listeners().forPhase(phase);
        List<BoundActionListener<T, Object>> global = globalsFor(bound.disabledGlobals()).forPhase(phase);
        if (own.isEmpty() && global.isEmpty()) {
            return;
        }

        ActionExecution execution = new ActionExecution(
            phase, path, bound.kind(), transition, error, duration);

        for (BoundActionListener<T, C> listener : own) {
            notifyActionListener(listener, entity, context, execution);
        }
        for (BoundActionListener<T, Object> listener : global) {
            notifyActionListener((BoundActionListener) listener, entity, context, execution);
        }
    }

    /**
     * Returns the global action listeners an action with this declaration is observed by. An
     * action that disables nothing - almost every action - pays one field read; the rest, one
     * lookup of a list filtered at build.
     */
    private BoundActionListeners<T, Object> globalsFor(GlobalListenerDisables disabled) {
        if (disabled.disablesNothing()) {
            return globalActionListeners;
        }
        BoundActionListeners<T, Object> filtered = filteredActionGlobals.get(disabled);
        // Every def is indexed at build; a miss must still suppress, never fail open.
        return filtered != null ? filtered : filterActionGlobals(disabled);
    }

    private void indexFilteredActionGlobals(GlobalListenerDisables disabled) {
        if (!disabled.disablesNothing()) {
            filteredActionGlobals.put(disabled, filterActionGlobals(disabled));
        }
    }

    private BoundActionListeners<T, Object> filterActionGlobals(GlobalListenerDisables disabled) {
        return new BoundActionListeners<>(
            disabled.filter(globalActionListeners.forPhase(ActionPhase.START), BoundActionListener::id),
            disabled.filter(globalActionListeners.forPhase(ActionPhase.COMPLETE), BoundActionListener::id),
            disabled.filter(globalActionListeners.forPhase(ActionPhase.ERROR), BoundActionListener::id));
    }

    private <C> void notifyActionListener(BoundActionListener<T, C> listener, T entity, C context,
                                          ActionExecution execution) {
        deliver(listener.id(), listener.async(), context, ctx -> {
            try {
                listener.listener().onAction(entity, ctx, execution);
            } catch (Exception | Error e) {
                ThrowingUtils.rethrowIfFatal(e);
                Loggers.EXECUTION_LISTENER.warn(
                    "Action listener threw, listenerId={}, phase={}, actionPath={}, errorType={}",
                    listener.id(), execution.phase(), execution.path(), e.getClass().getName());
            }
        });
    }

    /**
     * Notifies one listener, in line or on the executor as it declared.
     * <p>
     * An async notification is fire-and-forget like a forked branch, and runs under the same mark:
     * it may not drive this state machine, and a {@code BLOCK} submission from it runs inline
     * rather than parking a worker. Nothing about it can fail the transition - a context that will
     * not fork, or an executor that will not take the work, loses this one notification with a
     * warning.
     *
     * @param listenerId the listener's id, for diagnostics
     * @param async the listener's policy, or {@code null} to notify in line
     * @param context the context the listener would receive in line
     * @param notification the invocation, carrying its own catch-and-warn
     * @param <C> the context type
     */
    private <C> void deliver(String listenerId, AsyncRejectionPolicy async, C context,
                             Consumer<C> notification) {
        if (async == null) {
            notification.accept(context);
            return;
        }

        C listenerContext;
        try {
            listenerContext = forkForListener(context, listenerId);
        } catch (Exception | Error e) {
            ThrowingUtils.rethrowIfFatal(e);
            Loggers.EXECUTION_LISTENER.warn("Async listener not notified, listenerId={}, errorType={}",
                                            listenerId, e.getClass().getName());
            return;
        }

        Runnable task = () -> {
            enterAsyncBranch();
            try {
                notification.accept(listenerContext);
            } finally {
                exitAsyncBranch();
            }
        };

        String lost;
        try {
            lost = handle.submitAsync(task, async, "listener '" + listenerId + "'");
        } catch (RejectedExecutionException e) {
            // BLOCK against a closed executor: a listener has no transition to fail, so it is lost.
            lost = e.getClass().getName();
        }
        if (lost != null) {
            // Per occurrence, unthrottled: a spike of these is how an undersized pool shows up.
            Loggers.EXECUTION_ASYNC.warn("Async listener not started, listenerId={}, errorType={}",
                                         listenerId, lost);
        }
    }

    @SuppressWarnings("unchecked")
    private static <C> C forkForListener(C context, String listenerId) {
        if (!(context instanceof ForkableContext<?> forkable)) {
            return context;
        }

        Object forked = forkable.fork();
        if (forked == null) {
            throw new TransfluxContextException(listenerId,
                "ForkableContext returned null while notifying async listener '" + listenerId
                    + "'; fork() must produce a context");
        }
        return (C) forked;
    }

    /**
     * Resolves the state machine's global action listeners once, so every action shares one bound
     * record rather than rebinding them per action.
     */
    private BoundActionListeners<T, Object> bindGlobalActionListeners(StateMachineDefImpl<T> def) {
        ListenerRegistrations<T> binder = def.listenerBinder();
        // The eight state-machine-wide hooks are one owner between them, so a reference at any of
        // them reaches a listener declared at any other.
        Map<String, ActionListenerDefImpl<T, Object>> scope = ListenerRegistrations.ownScope(
            def.getGlobalActionStartListeners(), def.getGlobalActionCompleteListeners(),
            def.getGlobalActionErrorListeners());
        return new BoundActionListeners<>(
            binder.bindActions(def.getGlobalActionStartListeners(), scope),
            binder.bindActions(def.getGlobalActionCompleteListeners(), scope),
            binder.bindActions(def.getGlobalActionErrorListeners(), scope));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <C> BoundTransition<T, C> buildTransition(TransitionDefImpl<T, C> td,
                                                      Map<String, BoundCondition<T, ?>> conditionRegistry,
                                                      BoundTransitionListeners<T, Object> globals,
                                                      ListenerRegistrations<T> binder) {
        GlobalListenerDisables disabled = td.getDisabledGlobals();
        Map<String, TransitionListenerDefImpl<T, C>> ownScope = ListenerRegistrations.ownScope(
            td.getStartListeners(), td.getCompleteListeners(), td.getErrorListeners());
        BoundTransitionListeners<T, C> listeners = new BoundTransitionListeners<>(
            concatListeners(binder.bindTransitions(td.getStartListeners(), ownScope),
                            (List) disabled.filter(globals.onStart(), BoundTransitionListener::id)),
            concatListeners(binder.bindTransitions(td.getCompleteListeners(), ownScope),
                            (List) disabled.filter(globals.onComplete(), BoundTransitionListener::id)),
            concatListeners(binder.bindTransitions(td.getErrorListeners(), ownScope),
                            (List) disabled.filter(globals.onError(), BoundTransitionListener::id)));

        return BoundTransition.from(td, (Map) conditionRegistry, listeners);
    }

    /**
     * Resolves the state machine's global transition listeners once, so every transition shares
     * one bound record rather than rebinding them per transition.
     */
    private BoundTransitionListeners<T, Object> bindGlobalTransitionListeners(StateMachineDefImpl<T> def) {
        ListenerRegistrations<T> binder = def.listenerBinder();
        Map<String, TransitionListenerDefImpl<T, Object>> scope = ListenerRegistrations.ownScope(
            def.getGlobalStartListeners(), def.getGlobalCompleteListeners(),
            def.getGlobalErrorListeners());
        return new BoundTransitionListeners<>(
            binder.bindTransitions(def.getGlobalStartListeners(), scope),
            binder.bindTransitions(def.getGlobalCompleteListeners(), scope),
            binder.bindTransitions(def.getGlobalErrorListeners(), scope));
    }

    private <X> List<X> concatListeners(List<X> own, List<X> global) {
        if (global.isEmpty()) {
            return own;
        }
        if (own.isEmpty()) {
            return global;
        }

        List<X> all = new ArrayList<>(own.size() + global.size());
        all.addAll(own);
        all.addAll(global);

        return List.copyOf(all);
    }

    private Class<?> effectiveContextType(StateMachineDefImpl<T> def, String id) {
        Class<?> declared = def.getComponentContextType(id);
        return declared != null ? declared : Object.class;
    }

    private BoundTransition<T, ?> findTransition(String sourceStateId, String targetStateId) {
        List<BoundTransition<T, ?>> leaving = transitionsBySource.getOrDefault(sourceStateId, List.of());

        // A plain loop: this is on every transitionTo(target), and the ambiguous case is an error path.
        BoundTransition<T, ?> match = null;
        for (BoundTransition<T, ?> candidate : leaving) {
            if (!candidate.targetStateId().equals(targetStateId)) {
                continue;
            }
            if (match != null) {
                String candidateIds = leaving.stream()
                    .filter(t -> t.targetStateId().equals(targetStateId))
                    .map(BoundTransition::id)
                    .collect(Collectors.joining(", ", "[", "]"));

                throw new TransfluxValidationException(
                    String.format("Multiple transitions exist from state '%s' to state '%s': %s. " +
                               "Please specify the transition ID explicitly.",
                               sourceStateId, targetStateId, candidateIds)
                );
            }
            match = candidate;
        }

        if (match == null) {
            throw new TransfluxValidationException(
                String.format("No transition exists from state '%s' to state '%s'",
                           sourceStateId, targetStateId)
            );
        }

        return match;
    }

    private <C> TransitionResult<T> executeTransitionInternal(T entity, Object firingContext,
                                                                 BoundTransition<T, C> transition) {
        return executeTransitionInternal(entity, firingContext, transition, null);
    }

    /**
     * Runs one transition end to end.
     *
     * @param firingTrigger the trigger that caused this execution, or {@code null} when the host
     *                      invoked the transition directly. It contributes the pre-conditions its
     *                      kind imposes on top of the transition's own, and is what listeners see
     *                      as the execution's origin.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private <C> TransitionResult<T> executeTransitionInternal(T entity, Object firingContext,
                                                                 BoundTransition<T, C> transition,
                                                                 TriggerImpl firingTrigger) {
        List<BoundCondition<T, C>> additionalPreConditions = firingTrigger == null
            ? List.of()
            : (List) firingTrigger.preConditions();

        String sourceStateId = transition.sourceStateId();
        String targetStateId = transition.targetStateId();
        String transitionId = transition.id();

        // The backstop for an EntityBinding captured on the spawning thread and used inside the
        // branch, which would otherwise have passed the check at entity(...) before the fork.
        handle.rejectIfInsideAsyncBranch();
        handle.enterTransition(entity, transitionId, targetStateId);

        C context = (C) firingContext;
        Instant startedAt = Instant.now();
        ExecutingTransitionImpl<T, C> view = new ExecutingTransitionImpl<>(this, transition, entity, context);

        // Gates the terminal hooks: a pre-condition that rejects or throws never reached the start
        // hook, so it must not produce an unmatched error notification.
        boolean started = false;

        if (Loggers.EXECUTION_TRANSITION.isDebugEnabled()) {
            Loggers.EXECUTION_TRANSITION.debug(
                "Transition starting, transitionId={}, sourceStateId={}, targetStateId={}",
                transitionId, sourceStateId, targetStateId);
        }

        try {
            // Both pre-condition loops return straight out on rejection rather than unwinding
            // through the catch below, and that is exact rather than lax: a condition is handed the
            // read-only view, so none of them can have dispatched anything and the compensation
            // stack is provably still empty. The post-condition loop further down throws instead,
            // because by then it is not.
            for (BoundCondition<T, C> pc : transition.boundPreConditions()) {
                if (!pc.evaluate(BoundCondition.Role.PRE_CONDITION, entity, context, view.asReadOnly())) {
                    return preConditionRejected(entity, transition, pc, view, startedAt);
                }
            }

            for (BoundCondition<T, C> pc : additionalPreConditions) {
                if (!pc.evaluate(BoundCondition.Role.PRE_CONDITION, entity, context, view.asReadOnly())) {
                    return preConditionRejected(entity, transition, pc, view, startedAt);
                }
            }

            started = true;
            notifyTransitionListeners(transition, TransitionPhase.START, entity, context, firingTrigger, null);
            notifyStateExit(transition, entity, context);

            BoundAction<T, C> body = transition.boundAction();
            if (body != null) {
                runBody(view, body);
            }

            // Thrown rather than returned so a violation unwinds through the catch below, which
            // drains the compensation stack and reports what it rolled back. That is the same
            // path a post-condition that *throws* already takes, and it leaves the state applier
            // below unreached, so the entity's state is not committed.
            for (BoundCondition<T, C> pc : transition.boundPostConditions()) {
                if (!pc.evaluate(BoundCondition.Role.POST_CONDITION, entity, context, view.asReadOnly())) {
                    throw new TransfluxConditionException(
                        pc.id(), TransfluxConditionException.Role.POST_CONDITION, transitionId);
                }
            }

            if (stateApplier != null) {
                stateApplier.applyState(entity, targetStateId);
                Loggers.EXECUTION_TRANSITION.debug("State applied, transitionId={}, state={}",
                                                   transitionId, targetStateId);
            } else {
                Loggers.EXECUTION_TRANSITION.debug(
                    "No state applier configured, state not written, transitionId={}, state={}",
                    transitionId, targetStateId);
            }

            TransitionResult<T> succeeded = TransitionResult.success(
                entity, sourceStateId, targetStateId, transitionId,
                view.getExecutedPath(), startedAt, Instant.now());

            Loggers.EXECUTION_TRANSITION.debug("Transition succeeded, transitionId={}, actions={}",
                                               transitionId, succeeded.getExecutedPath().size());

            notifyTransitionListeners(transition, TransitionPhase.COMPLETE, entity, context,
                                      firingTrigger, succeeded);
            notifyStateEntry(transition, entity, context);

            return succeeded;

        } catch (Exception e) {
            return rollBack(view, transition, entity, context, firingTrigger, e, started, startedAt);
        } catch (Error e) {
            // Rolled back and reported to listeners like any other failure, then rethrown - an
            // Error is never turned into a result. The exception is a JVM that can no longer be
            // trusted to run rollback handlers. Nothing past the applier can land here: observers
            // contain their own failures.
            if (!ThrowingUtils.isFatal(e)) {
                rollBack(view, transition, entity, context, firingTrigger, e, started, startedAt);
            }
            throw e;
        } finally {
            handle.exitTransition(entity);
        }
    }

    /**
     * Drains the failed execution's compensations, builds its failure result, and notifies the
     * error hook when the start hook was reached.
     *
     * @param failure what ended the transition; every compensation routes against it
     * @param started whether the start hook fired, which gates the error hook
     *
     * @return the failure result
     */
    private <C> TransitionResult<T> rollBack(ExecutingTransitionImpl<T, C> view,
                                             BoundTransition<T, C> transition, T entity, C context,
                                             TriggerImpl firingTrigger, Throwable failure,
                                             boolean started, Instant startedAt) {
        List<ActionPath> compensatedPath =
            CompensationDrain.forTransition(view, entity, failure, transition.id());

        TransitionResult<T> failed = TransitionResult.failure(entity,
                                                              transition.sourceStateId(),
                                                              transition.targetStateId(),
                                                              transition.id(),
                                                              failure,
                                                              view.getExecutedPath(),
                                                              compensatedPath,
                                                              startedAt,
                                                              Instant.now());

        if (Loggers.EXECUTION_TRANSITION.isDebugEnabled()) {
            Loggers.EXECUTION_TRANSITION.debug(
                "Transition failed, transitionId={}, errorType={}, compensated={}",
                transition.id(), failure.getClass().getName(), compensatedPath.size());
        }

        if (started) {
            notifyTransitionListeners(transition, TransitionPhase.ERROR, entity, context,
                                      firingTrigger, failed);
        }

        return failed;
    }

    /**
     * Reports a pre-condition rejection and builds the failure result both loops return.
     *
     * <p>A rejection here is not an error and produces no listener notification — §2.4 places the
     * start hook after the pre-conditions — so this line is the only trace a rejected transition
     * leaves behind. The stack is provably empty at this point, hence no drain and no compensated
     * path.
     */
    private static <T, C> TransitionResult<T> preConditionRejected(T entity,
                                                                   BoundTransition<T, C> transition,
                                                                   BoundCondition<T, C> rejecting,
                                                                   ExecutingTransitionImpl<T, C> view,
                                                                   Instant startedAt) {
        Loggers.EXECUTION_TRANSITION.debug("Transition rejected by pre-condition, transitionId={}, conditionId={}",
                                           transition.id(), rejecting.id());

        return TransitionResult.failure(
            entity, transition.sourceStateId(), transition.targetStateId(), transition.id(),
            new TransfluxConditionException(rejecting.id(),
                                            TransfluxConditionException.Role.PRE_CONDITION,
                                            transition.id()),
            view.getExecutedPath(), null, startedAt, Instant.now());
    }

    /**
     * The single point every trigger-driven execution passes through, which is why the fired line is
     * emitted here rather than at each dispatch site — {@code Trigger fired} is then a complete
     * record of firings, manual ones included.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private TransitionResult<T> fireWith(T entity, Object firingContext,
                                         BoundTransition<T, ?> transition, TriggerImpl trigger) {
        Loggers.TRIGGER.debug("Trigger fired, triggerId={}, transitionId={}", trigger.getId(), transition.id());
        return executeTransitionInternal(entity, firingContext, (BoundTransition) transition, trigger);
    }

    /**
     * Reports whether a firing context satisfies a transition's declared context type.
     * <p>
     * Targeted entry points turn a {@code false} here into a rejection, while the dispatch entry
     * points treat it as ineligibility and keep scanning — so a trigger's filter or gate never sees
     * a context its own transition would refuse.
     *
     * @param transition the transition whose declared context type applies
     * @param firingContext the host-supplied context; may be {@code null}
     *
     * @return {@code true} if the context is acceptable
     */
    private static boolean contextFits(BoundTransition<?, ?> transition, Object firingContext) {
        Class<?> expected = transition.contextType();
        if (expected == Void.class) {
            return firingContext == null;
        }
        if (firingContext == null || expected == null || expected == Object.class) {
            return true;
        }
        return expected.isInstance(firingContext);
    }

    /**
     * The same mismatch as {@link #contextMismatchMessage}, rendered as one compact token for the
     * {@code reason=} field of a trigger-scan line, so a host can grep {@code context-incompatible}
     * across a scan and still read which types disagreed.
     *
     * <p>The token holds no {@code ", "} and no whitespace: those separate the {@code key=} fields
     * of the surrounding line, and a consumer splitting on them must not find a phantom field inside
     * one field's value.
     */
    private static String contextMismatchReason(BoundTransition<?, ?> transition, Object firingContext) {
        String expected = transition.contextType() == Void.class
            ? "Void"
            : transition.contextType().getName();
        return "context-incompatible(expects=" + expected
            + ";got=" + firingContext.getClass().getName() + ")";
    }

    private static String contextMismatchMessage(BoundTransition<?, ?> transition, Object firingContext) {
        if (transition.contextType() == Void.class) {
            return "Context type mismatch: transition '" + transition.id()
                + "' expects Void (no context) but received "
                + firingContext.getClass().getName();
        }
        return "Context type mismatch: transition '" + transition.id()
            + "' expects " + transition.contextType().getName()
            + " but received " + firingContext.getClass().getName();
    }

    /**
     * A host-driven trigger paired with the transition it fires, resolved once at build time so
     * dispatch does not re-look-up the transition per candidate.
     *
     * @param <T> the entity type the surrounding state machine manages
     * @param <X> the concrete trigger kind
     */
    private record TriggerBinding<T, X extends TriggerImpl>(X trigger, BoundTransition<T, ?> transition) {
    }

    private class EntityBindingImpl implements StateMachine.EntityBinding<T> {
        private final T entity;

        EntityBindingImpl(T entity) {
            this.entity = entity;
        }

        @Override
        public TransitionResult<T> transitionTo(String targetStateId) {
            return transitionTo(targetStateId, (Object) null);
        }

        @Override
        public TransitionResult<T> transitionTo(String targetStateId, String transitionId) {
            return transitionTo(targetStateId, transitionId, null);
        }

        @Override
        public TransitionResult<T> transitionTo(String targetStateId, Object firingContext) {
            requireNotBlank(targetStateId, "Target state ID");

            String currentStateId = resolveCurrentState(entity);
            BoundTransition<T, ?> transition = findTransition(currentStateId, targetStateId);
            verifyFireContext(transition, firingContext);
            return executeTransitionInternal(entity, firingContext, transition);
        }

        @Override
        public TransitionResult<T> transitionTo(String targetStateId, String transitionId, Object firingContext) {
            requireNotBlank(targetStateId, "Target state ID");
            requireNotBlank(transitionId, "Transition ID");

            String currentStateId = resolveCurrentState(entity);
            BoundTransition<T, ?> transition = StateMachineSnapshot.this.getTransition(transitionId);

            if (!transition.sourceStateId().equals(currentStateId)) {
                throw new TransfluxValidationException(
                    String.format("Entity is in state '%s' but transition '%s' requires source state '%s'",
                        currentStateId, transitionId, transition.sourceStateId())
                );
            }
            if (!transition.targetStateId().equals(targetStateId)) {
                throw new TransfluxValidationException(
                    String.format("Transition '%s' leads to state '%s' but target state '%s' was requested",
                        transitionId, transition.targetStateId(), targetStateId)
                );
            }

            verifyFireContext(transition, firingContext);
            return executeTransitionInternal(entity, firingContext, transition);
        }

        @Override
        public TransitionResult<T> fire(String triggerId) {
            return fire(triggerId, (Object) null);
        }

        @Override
        public TransitionResult<T> fire(String triggerId, Object firingContext) {
            requireNotBlank(triggerId, "Trigger ID");

            TriggerImpl trigger = StateMachineSnapshot.this.getTriggerImpl(triggerId);
            trigger.checkDirectlyFireable();

            String currentStateId = resolveCurrentState(entity);
            BoundTransition<T, ?> transition = StateMachineSnapshot.this.manualTarget(trigger, currentStateId);

            verifyFireContext(transition, firingContext);
            return fireWith(entity, firingContext, transition, trigger);
        }

        @Override
        public ProcessResult<T> processEvent(String eventId, Object eventData) {
            return processEvent(eventId, eventData, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public ProcessResult<T> processEvent(String eventId, Object eventData, Object context) {
            requireNotBlank(eventId, "Event ID");

            String currentStateId = resolveCurrentState(entity);
            // A trigger listening for another event was never a candidate, so it gets no skip line -
            // one per unrelated trigger would bury the reasons that do explain a fired() == false.
            // The count is the explanation instead, as it is for wrong-source-state, and 'leaving'
            // separates "nothing here listens for this event" from "no event trigger leaves at all".
            List<TriggerBinding<T, EventTriggerImpl<T>>> leaving = eventTriggersLeaving(currentStateId);
            if (Loggers.TRIGGER.isDebugEnabled()) {
                // Counted here rather than filtered into a list the scan below would reuse: the
                // count exists for this line alone, and the scan runs on every event dispatch.
                long candidates = leaving.stream()
                    .filter(binding -> binding.trigger().getEventId().equals(eventId))
                    .count();
                Loggers.TRIGGER.debug("Event dispatch scan, eventId={}, currentState={}, candidates={}, leaving={}",
                                      eventId, currentStateId, candidates, leaving.size());
            }

            for (TriggerBinding<T, EventTriggerImpl<T>> binding : leaving) {
                EventTriggerImpl<T> trigger = binding.trigger();
                if (!trigger.getEventId().equals(eventId)) {
                    continue;
                }
                if (!contextFits(binding.transition(), context)) {
                    logSkippedIncompatibleContext(trigger, binding.transition(), context);
                    continue;
                }
                boolean matches = sneakyGet(() -> trigger.matches(eventData, entity, context),
                    "Event trigger '" + trigger.getId() + "' filter failed");
                if (!matches) {
                    logSkipped(trigger, "filter-rejected");
                    continue;
                }
                return ProcessResult.fired(trigger.getId(),
                    fireWith(entity, context, binding.transition(), trigger));
            }

            Loggers.TRIGGER.debug("No trigger fired, eventId={}, currentState={}", eventId, currentStateId);
            return ProcessResult.notFired();
        }

        @Override
        public ProcessResult<T> processDataChange() {
            return processDataChange(null);
        }

        @Override
        public ProcessResult<T> processDataChange(Object context) {
            String currentStateId = resolveCurrentState(entity);
            List<TriggerBinding<T, DataTriggerImpl<T, ?>>> candidates = dataTriggersLeaving(currentStateId);
            if (Loggers.TRIGGER.isDebugEnabled()) {
                Loggers.TRIGGER.debug("Data change dispatch scan, currentState={}, candidates={}",
                                      currentStateId, candidates.size());
            }

            for (TriggerBinding<T, DataTriggerImpl<T, ?>> binding : candidates) {
                DataTriggerImpl<T, ?> trigger = binding.trigger();
                if (!contextFits(binding.transition(), context)) {
                    logSkippedIncompatibleContext(trigger, binding.transition(), context);
                    continue;
                }
                if (!gateHolds(trigger, binding.transition(), context)) {
                    logSkipped(trigger, "gate-rejected");
                    continue;
                }
                return ProcessResult.fired(trigger.getId(),
                    fireWith(entity, context, binding.transition(), trigger));
            }

            Loggers.TRIGGER.debug("No trigger fired, currentState={}", currentStateId);
            return ProcessResult.notFired();
        }

        private void logSkipped(Trigger trigger, String reason) {
            Loggers.TRIGGER.debug("Trigger skipped, triggerId={}, reason={}", trigger.getId(), reason);
        }

        /** Guarded separately: the reason token is built by concatenation, not by the logger. */
        private void logSkippedIncompatibleContext(Trigger trigger, BoundTransition<T, ?> transition,
                                                   Object context) {
            if (Loggers.TRIGGER.isDebugEnabled()) {
                logSkipped(trigger, contextMismatchReason(transition, context));
            }
        }

        private List<TriggerBinding<T, EventTriggerImpl<T>>> eventTriggersLeaving(String currentStateId) {
            return eventTriggersBySource.getOrDefault(currentStateId, List.of());
        }

        private List<TriggerBinding<T, DataTriggerImpl<T, ?>>> dataTriggersLeaving(String currentStateId) {
            return dataTriggersBySource.getOrDefault(currentStateId, List.of());
        }

        /**
         * Evaluates a data trigger's gate ahead of any transition being entered, so the gate never
         * touches the reentrancy guard. The gate is a condition and therefore sees topology only;
         * a held gate fires a fresh execution, which is where dispatch becomes available.
         */
        @SuppressWarnings("unchecked")
        private <C> boolean gateHolds(DataTriggerImpl<T, C> trigger, BoundTransition<T, ?> transition,
                                      Object context) {
            C ctx = (C) context;
            Transition probe = TransitionImpl.of(transition);
            return sneakyGet(() -> trigger.gate().evaluate(BoundCondition.Role.TRIGGER_GATE, entity, ctx, probe),
                "Data trigger '" + trigger.getId() + "' gate condition failed");
        }

        private void verifyFireContext(BoundTransition<T, ?> transition, Object firingContext) {
            if (contextFits(transition, firingContext)) {
                return;
            }
            throw new TransfluxValidationException(contextMismatchMessage(transition, firingContext));
        }
    }
}
