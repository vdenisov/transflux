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

import org.transflux.core.Identifiable;
import org.transflux.core.StateMachine;
import org.transflux.core.exception.TransfluxReentrancyException;
import org.transflux.core.exception.TransfluxValidationException;
import org.transflux.core.action.ActionExecution;
import org.transflux.core.action.ActionPhase;
import org.transflux.core.action.Compensation;
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
import org.transflux.core.transition.TransitionPhase;
import org.transflux.core.transition.TransitionResult;
import org.transflux.core.trigger.Trigger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.transflux.core.Preconditions.requireNotBlank;
import static org.transflux.core.Preconditions.requireNotNull;
import static org.transflux.core.impl.ThrowingUtils.sneakyGet;

/**
 * Implementation of the {@link StateMachine} interface.
 *
 * @param <T> the type of entity managed by this state machine
 */
class StateMachineImpl<T> implements StateMachine<T> {
    // TODO: extend the reentrancy guard across the async-submission boundary via
    //   capture/restore — the enclosing thread snapshots this set on submission and
    //   the worker installs it before entering the SM, so logical reentrancy stays
    //   detected when an operation spawns async work that calls back into the same
    //   SM for the same entity.
    private static final ThreadLocal<Set<EntityKey>> IN_FLIGHT = ThreadLocal.withInitial(HashSet::new);

    private final StateResolver<T> stateResolver;
    private final StateApplier<T> stateApplier;

    private final Map<String, State<T>> states = new LinkedHashMap<>();
    private final Map<String, BoundTransition<T, ?>> transitions = new LinkedHashMap<>();
    private final Map<String, TriggerImpl> triggers = new LinkedHashMap<>();

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

    private final Registry<T> componentRegistry;
    private final StateMachineDefImpl<T> def;

    @SuppressWarnings({"unchecked", "rawtypes"})
    StateMachineImpl(StateMachineDefImpl<T> def) {
        this.def = def;
        this.stateResolver = def.getStateResolver();
        this.stateApplier = def.getStateApplier();

        this.states.putAll(def.getStates().values().stream()
                              .collect(Collectors.toMap(StateDefImpl::getId, StateImpl::new)));

        buildStateListenerIndexes(def);
        this.globalActionListeners = bindGlobalActionListeners(def);

        RegistryImpl<T> registry = new RegistryImpl<>();
        this.componentRegistry = registry;

        // The order below is load-bearing:
        //  1. SM-level conditions and steps populate the root registry first, so that
        //  2. composite scopes (bindCompositeScopes) and operations (buildBoundOperations...)
        //     can resolve by-id refs against the root via the parent chain, and finally
        //  3. flatten() runs strictly last (root, then composite scopes), collapsing each chain
        //     so runtime resolve() is a single map lookup. Composite refs are resolved against
        //     the still-chained scopes during build, so flattening earlier — or switching
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

        def.buildBoundOperationsIncrementally(this, bo -> {
            Class<?> ctx = effectiveContextType(def, bo.id());
            registry.register(new Component.Action(bo.id(), ctx, bo));
        });

        BoundTransitionListeners<T, Object> globalTransitionListeners = bindGlobalTransitionListeners(def);
        for (TransitionDefImpl<T, ?> td : def.getTransitionsById().values()) {
            this.transitions.put(td.getId(), buildTransition(td, conditionRegistry, globalTransitionListeners));
        }

        for (TransitionDefImpl<T, ?> td : def.getTransitionsById().values()) {
            registerManualTriggers(td, conditionRegistry);
            registerEventTriggers(td);
            registerDataTriggers(td, conditionRegistry);
        }

        registry.flatten();
        def.flattenCompositeScopes();
        Loggers.BUILD_REGISTRY.debug("Registry scopes flattened, rootComponents={}", registry.ids().size());
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

    /**
     * Returns the {@link StateMachineDefImpl} this state machine was built from.
     *
     * @return the def
     */
    StateMachineDefImpl<T> getDef() {
        return def;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    BoundAction<T, ?> getBoundAction(String id) {
        return componentRegistry.resolve(id)
            .filter(Component.Action.class::isInstance)
            .map(c -> ((Component.Action) c).bound())
            .orElse(null);
    }

    Optional<String> findInlineSiblingScope(String id, String excludingCompositeId) {
        return def.findInlineSiblingScope(id, excludingCompositeId);
    }

    /**
     * Runs the transition's own action through the same path every other action takes, so the
     * root of the execution tree obeys the ordering and compensation rules its children do.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T, C> void runRootAction(ExecutingTransitionImpl<T, C> view, BoundAction<T, C> bound) {
        view.runAction((BoundAction) bound, null);
    }

    StateApplier<T> getStateApplier() {
        return stateApplier;
    }

    @Override
    public EntityBinding<T> entity(T entity) {
        requireNotNull(entity, "Entity");
        return new EntityBindingImpl(entity);
    }

    @Override
    public TransitionResult<T> executeTransition(T entity, String targetStateId) {
        return entity(entity).transitionTo(targetStateId);
    }

    @Override
    public TransitionResult<T> executeTransition(T entity, Identifiable targetState) {
        requireNotNull(targetState, "Target state identifiable");
        return executeTransition(entity, targetState.getId());
    }

    @Override
    public TransitionResult<T> executeTransition(T entity, String targetStateId, String transitionId) {
        return entity(entity).transitionTo(targetStateId, transitionId);
    }

    @Override
    public TransitionResult<T> executeTransition(T entity, Identifiable targetState, Identifiable transition) {
        requireNotNull(targetState, "Target state identifiable");
        requireNotNull(transition, "Transition identifiable");
        return executeTransition(entity, targetState.getId(), transition.getId());
    }

    @Override
    public TransitionResult<T> executeTransition(T entity, Identifiable targetState, String transitionId) {
        requireNotNull(targetState, "Target state identifiable");
        return executeTransition(entity, targetState.getId(), transitionId);
    }

    @Override
    public TransitionResult<T> executeTransition(T entity, String targetStateId, Identifiable transition) {
        requireNotNull(transition, "Transition identifiable");
        return executeTransition(entity, targetStateId, transition.getId());
    }

    @Override
    public String resolveCurrentState(T entity) {
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

    @Override
    public Collection<Trigger> getTriggers() {
        return List.<Trigger>copyOf(triggers.values());
    }

    @Override
    public <X extends Trigger> Collection<X> getTriggers(Class<X> kind) {
        requireNotNull(kind, "Trigger kind");
        return triggers.values().stream()
            .filter(kind::isInstance)
            .map(kind::cast)
            .collect(Collectors.toUnmodifiableList());
    }

    @Override
    public Trigger getTrigger(String triggerId) {
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

    @Override
    public Trigger getTrigger(Identifiable trigger) {
        requireNotNull(trigger, "Trigger identifiable");
        return getTrigger(trigger.getId());
    }

    State<T> getState(String stateId) {
        requireNotBlank(stateId, "State ID");

        State<T> state = states.get(stateId);
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void registerManualTriggers(TransitionDefImpl<T, ?> td,
                                        Map<String, BoundCondition<T, ?>> conditionRegistry) {
        for (ManualTriggerDefImpl<T, ?> mt : td.getManualTriggers()) {
            putTrigger(mt.buildBoundTrigger((Map) conditionRegistry));
        }
    }

    private void registerEventTriggers(TransitionDefImpl<T, ?> td) {
        BoundTransition<T, ?> transition = getTransition(td.getId());
        for (EventTriggerDefImpl<T, ?> et : td.getEventTriggers()) {
            EventTriggerImpl<T> trigger = et.buildBoundTrigger();
            putTrigger(trigger);
            eventTriggersBySource
                .computeIfAbsent(transition.sourceStateId(), s -> new ArrayList<>())
                .add(new TriggerBinding<>(trigger, transition));
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void registerDataTriggers(TransitionDefImpl<T, ?> td,
                                      Map<String, BoundCondition<T, ?>> conditionRegistry) {
        BoundTransition<T, ?> transition = getTransition(td.getId());
        for (DataTriggerDefImpl<T, ?> dt : td.getDataTriggers()) {
            DataTriggerImpl<T, ?> trigger = dt.buildBoundTrigger((Map) conditionRegistry);
            putTrigger(trigger);
            dataTriggersBySource
                .computeIfAbsent(transition.sourceStateId(), s -> new ArrayList<>())
                .add(new TriggerBinding<>(trigger, transition));
        }
    }

    private void putTrigger(TriggerImpl trigger) {
        if (triggers.containsKey(trigger.getId())) {
            throw new TransfluxValidationException(
                "Trigger id '" + trigger.getId() + "' is already registered");
        }
        triggers.put(trigger.getId(), trigger);
    }

    /**
     * Resolves every declared state's entry and exit listeners into notification order. The
     * globals are bound once and shared across states, so a class-form global listener yields one
     * instance rather than one per state.
     */
    private void buildStateListenerIndexes(StateMachineDefImpl<T> def) {
        List<BoundStateListener<T>> globalEntry = bindStateListeners(def.getGlobalEntryListeners());
        List<BoundStateListener<T>> globalExit = bindStateListeners(def.getGlobalExitListeners());

        for (StateDefImpl<T> sd : def.getStates().values()) {
            entryListenersByState.put(sd.getId(),
                concatListeners(bindStateListeners(sd.getEntryListeners()), globalEntry));
            exitListenersByState.put(sd.getId(),
                concatListeners(bindStateListeners(sd.getExitListeners()), globalExit));
        }
    }

    private List<BoundStateListener<T>> bindStateListeners(List<StateListenerDefImpl<T>> defs) {
        if (defs.isEmpty()) {
            return List.of();
        }

        List<BoundStateListener<T>> bound = new ArrayList<>(defs.size());
        for (StateListenerDefImpl<T> ld : defs) {
            bound.add(ld.buildBoundListener());
        }

        return List.copyOf(bound);
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

        StateChange<T> change =
            new StateChange<>(phase, states.get(stateId), TransitionImpl.of(transition));

        for (BoundStateListener<T> listener : listeners) {
            try {
                listener.listener().onState(entity, context, change);
            } catch (Exception e) {
                Loggers.EXECUTION_LISTENER.warn(
                    "State listener threw, listenerId={}, phase={}, stateId={}, errorType={}, error={}",
                    listener.id(), phase, stateId, e.getClass().getName(), e.getMessage());
            }
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
            try {
                listener.listener().onTransition(entity, context, execution);
            } catch (Exception e) {
                Loggers.EXECUTION_LISTENER.warn(
                    "Transition listener threw, listenerId={}, phase={}, transitionId={}, errorType={}, error={}",
                    listener.id(), phase, transition.id(), e.getClass().getName(), e.getMessage());
            }
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
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    <C> void notifyActionListeners(BoundAction<T, C> bound, ActionPhase phase, T entity, C context,
                                   ActionPath path, Transition transition, Throwable error) {
        List<BoundActionListener<T, C>> own = bound.listeners().forPhase(phase);
        List<BoundActionListener<T, Object>> global = globalActionListeners.forPhase(phase);
        if (own.isEmpty() && global.isEmpty()) {
            return;
        }

        ActionExecution execution = new ActionExecution(
            phase, path, bound.kind(), transition, error);

        for (BoundActionListener<T, C> listener : own) {
            notifyActionListener(listener, entity, context, execution);
        }
        for (BoundActionListener<T, Object> listener : global) {
            notifyActionListener((BoundActionListener) listener, entity, context, execution);
        }
    }

    private <C> void notifyActionListener(BoundActionListener<T, C> listener, T entity, C context,
                                          ActionExecution execution) {
        try {
            listener.listener().onAction(entity, context, execution);
        } catch (Exception e) {
            Loggers.EXECUTION_LISTENER.warn(
                "Action listener threw, listenerId={}, phase={}, actionPath={}, errorType={}, error={}",
                listener.id(), execution.phase(), execution.path(), e.getClass().getName(),
                e.getMessage());
        }
    }

    /**
     * Resolves the state machine's global action listeners once, so a class-form listener yields a
     * single instance shared by every action rather than one per action.
     */
    private BoundActionListeners<T, Object> bindGlobalActionListeners(StateMachineDefImpl<T> def) {
        return new BoundActionListeners<>(
            bindActionListeners(def.getGlobalActionStartListeners()),
            bindActionListeners(def.getGlobalActionCompleteListeners()),
            bindActionListeners(def.getGlobalActionErrorListeners()));
    }

    private static <T, C> List<BoundActionListener<T, C>> bindActionListeners(
            List<ActionListenerDefImpl<T, C>> defs) {
        if (defs.isEmpty()) {
            return List.of();
        }

        List<BoundActionListener<T, C>> bound = new ArrayList<>(defs.size());
        for (ActionListenerDefImpl<T, C> ld : defs) {
            bound.add(ld.buildBoundListener());
        }

        return List.copyOf(bound);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <C> BoundTransition<T, C> buildTransition(TransitionDefImpl<T, C> td,
                                                      Map<String, BoundCondition<T, ?>> conditionRegistry,
                                                      BoundTransitionListeners<T, Object> globals) {
        BoundTransitionListeners<T, C> listeners = new BoundTransitionListeners<>(
            concatListeners(bindTransitionListeners(td.getStartListeners()), (List) globals.onStart()),
            concatListeners(bindTransitionListeners(td.getCompleteListeners()), (List) globals.onComplete()),
            concatListeners(bindTransitionListeners(td.getErrorListeners()), (List) globals.onError()));

        return BoundTransition.from(td, this, (Map) conditionRegistry, listeners);
    }

    /**
     * Resolves the state machine's global transition listeners once, so a class-form listener
     * yields one instance shared by every transition rather than one instance per transition.
     */
    private BoundTransitionListeners<T, Object> bindGlobalTransitionListeners(StateMachineDefImpl<T> def) {
        return new BoundTransitionListeners<>(
            bindTransitionListeners(def.getGlobalStartListeners()),
            bindTransitionListeners(def.getGlobalCompleteListeners()),
            bindTransitionListeners(def.getGlobalErrorListeners()));
    }

    private <C> List<BoundTransitionListener<T, C>> bindTransitionListeners(
            List<TransitionListenerDefImpl<T, C>> defs) {
        if (defs.isEmpty()) {
            return List.of();
        }

        List<BoundTransitionListener<T, C>> bound = new ArrayList<>(defs.size());
        for (TransitionListenerDefImpl<T, C> ld : defs) {
            bound.add(ld.buildBoundListener());
        }

        return List.copyOf(bound);
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
        var matchingTransitions = transitions.values().stream()
            .filter(t -> t.sourceStateId().equals(sourceStateId)
                      && t.targetStateId().equals(targetStateId))
            .toList();

        if (matchingTransitions.isEmpty()) {
            throw new TransfluxValidationException(
                String.format("No transition exists from state '%s' to state '%s'",
                           sourceStateId, targetStateId)
            );
        }

        if (matchingTransitions.size() > 1) {
            String candidateIds = matchingTransitions.stream()
                .map(BoundTransition::id)
                .collect(Collectors.joining(", ", "[", "]"));

            throw new TransfluxValidationException(
                String.format("Multiple transitions exist from state '%s' to state '%s': %s. " +
                           "Please specify the transition ID explicitly.",
                           sourceStateId, targetStateId, candidateIds)
            );
        }

        return matchingTransitions.get(0);
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

        EntityKey key = new EntityKey(this, entity);
        Set<EntityKey> inFlight = IN_FLIGHT.get();
        if (inFlight.contains(key)) {
            // The entity's type, never the entity: it belongs to the host and may be full of PII,
            // and this message surfaces wherever the resulting stack trace is printed.
            throw new TransfluxReentrancyException(
                "Reentrant transition '" + transitionId + "' to state '" + targetStateId
                    + "' rejected, entityType=" + entity.getClass().getName()
                    + " (a transition is already in flight for the same state machine and entity)");
        }
        inFlight.add(key);

        C context = (C) firingContext;
        Instant startedAt = Instant.now();
        ExecutingTransitionImpl<T, C> view = new ExecutingTransitionImpl<>(this, transition, entity, context);

        // Gates the terminal hooks: a pre-condition that rejects or throws never reached the start
        // hook, so it must not produce an unmatched error notification.
        boolean started = false;

        Loggers.EXECUTION_TRANSITION.debug("Transition starting, transitionId={}, {}->{}",
                                           transitionId, sourceStateId, targetStateId);

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

            BoundAction<T, C> boundAction = transition.boundAction();
            if (boundAction != null) {
                runRootAction(view, boundAction);
            }

            // Thrown rather than returned so a violation unwinds through the catch below, which
            // drains the compensation stack and reports what it rolled back. That is the same
            // path a post-condition that *throws* already takes, and it leaves the state applier
            // below unreached, so the entity's state is not committed.
            for (BoundCondition<T, C> pc : transition.boundPostConditions()) {
                if (!pc.evaluate(BoundCondition.Role.POST_CONDITION, entity, context, view.asReadOnly())) {
                    throw new TransfluxValidationException("Post-condition '" + pc.id()
                        + "' failed for transition '" + transitionId + "'");
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
            List<BoundCompensation<T, C>> drained = view.drainCompensationsLifo();
            List<ActionPath> compensatedPath = new ArrayList<>(drained.size());

            if (!drained.isEmpty()) {
                // The class name, never the message: an exception raised by the framework itself can
                // carry the entity, and a host's own exception can carry anything at all.
                Loggers.EXECUTION_COMPENSATION.info(
                    "Draining compensations, transitionId={}, count={}, errorType={}",
                    transitionId, drained.size(), e.getClass().getName());
            }

            for (BoundCompensation<T, C> bc : drained) {
                compensatedPath.add(bc.path());
                try {
                    bc.compensation().compensate(entity, bc.context());
                } catch (Exception ce) {
                    Loggers.EXECUTION_COMPENSATION.warn(
                        "Compensation threw, actionPath={}, errorType={}, error={}",
                        bc.path(), ce.getClass().getName(), ce.getMessage());
                }
            }

            TransitionResult<T> failed = TransitionResult.failure(entity,
                                                                  sourceStateId,
                                                                  targetStateId,
                                                                  transitionId,
                                                                  e,
                                                                  view.getExecutedPath(),
                                                                  compensatedPath,
                                                                  startedAt,
                                                                  Instant.now());

            if (Loggers.EXECUTION_TRANSITION.isDebugEnabled()) {
                Loggers.EXECUTION_TRANSITION.debug(
                    "Transition failed, transitionId={}, errorType={}, compensated={}",
                    transitionId, e.getClass().getName(), compensatedPath.size());
            }

            if (started) {
                notifyTransitionListeners(transition, TransitionPhase.ERROR, entity, context,
                                          firingTrigger, failed);
            }

            return failed;
        } finally {
            inFlight.remove(key);
            if (inFlight.isEmpty()) {
                IN_FLIGHT.remove();
            }
        }
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
            new TransfluxValidationException("Pre-condition '" + rejecting.id()
                + "' failed for transition '" + transition.id() + "'"),
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

    private record EntityKey(StateMachineImpl<?> sm, Object entity) {

        @Override
            public boolean equals(Object o) {
                if (this == o) {
                    return true;
                }

                if (!(o instanceof EntityKey other)) {
                    return false;
                }

                return this.sm == other.sm && this.entity == other.entity;
            }

            @Override
            public int hashCode() {
                return System.identityHashCode(sm) * 31 + System.identityHashCode(entity);
            }
        }

    private class EntityBindingImpl implements EntityBinding<T> {
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
            BoundTransition<T, ?> transition = StateMachineImpl.this.getTransition(transitionId);

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
        public TransitionResult<T> transitionTo(Identifiable targetState) {
            requireNotNull(targetState, "Target state identifiable");
            return transitionTo(targetState.getId());
        }

        @Override
        public TransitionResult<T> transitionTo(Identifiable targetState, Identifiable transition) {
            requireNotNull(targetState, "Target state identifiable");
            requireNotNull(transition, "Transition identifiable");
            return transitionTo(targetState.getId(), transition.getId());
        }

        @Override
        public TransitionResult<T> transitionTo(Identifiable targetState, String transitionId) {
            requireNotNull(targetState, "Target state identifiable");
            return transitionTo(targetState.getId(), transitionId);
        }

        @Override
        public TransitionResult<T> transitionTo(String targetStateId, Identifiable transition) {
            requireNotNull(transition, "Transition identifiable");
            return transitionTo(targetStateId, transition.getId());
        }

        @Override
        public TransitionResult<T> transitionTo(Identifiable targetState, Object firingContext) {
            requireNotNull(targetState, "Target state identifiable");
            return transitionTo(targetState.getId(), firingContext);
        }

        @Override
        public TransitionResult<T> transitionTo(Identifiable targetState, Identifiable transition, Object firingContext) {
            requireNotNull(targetState, "Target state identifiable");
            requireNotNull(transition, "Transition identifiable");
            return transitionTo(targetState.getId(), transition.getId(), firingContext);
        }

        @Override
        public TransitionResult<T> transitionTo(Identifiable targetState, String transitionId, Object firingContext) {
            requireNotNull(targetState, "Target state identifiable");
            return transitionTo(targetState.getId(), transitionId, firingContext);
        }

        @Override
        public TransitionResult<T> transitionTo(String targetStateId, Identifiable transition, Object firingContext) {
            requireNotNull(transition, "Transition identifiable");
            return transitionTo(targetStateId, transition.getId(), firingContext);
        }

        @Override
        public TransitionResult<T> fire(String triggerId) {
            return fire(triggerId, (Object) null);
        }

        @Override
        public TransitionResult<T> fire(String triggerId, Object firingContext) {
            requireNotBlank(triggerId, "Trigger ID");

            TriggerImpl trigger = StateMachineImpl.this.getTriggerImpl(triggerId);
            trigger.checkDirectlyFireable();
            BoundTransition<T, ?> transition = StateMachineImpl.this.getTransition(trigger.getTransitionId());

            String currentStateId = resolveCurrentState(entity);
            if (!transition.sourceStateId().equals(currentStateId)) {
                throw new TransfluxValidationException(
                    String.format("Entity is in state '%s' but trigger '%s' requires source state '%s'",
                        currentStateId, triggerId, transition.sourceStateId())
                );
            }

            verifyFireContext(transition, firingContext);
            return fireWith(entity, firingContext, transition, trigger);
        }

        @Override
        public TransitionResult<T> fire(Identifiable trigger) {
            requireNotNull(trigger, "Trigger identifiable");
            return fire(trigger.getId());
        }

        @Override
        public TransitionResult<T> fire(Identifiable trigger, Object firingContext) {
            requireNotNull(trigger, "Trigger identifiable");
            return fire(trigger.getId(), firingContext);
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
            List<TriggerBinding<T, EventTriggerImpl<T>>> candidates = leaving.stream()
                .filter(binding -> binding.trigger().getEventId().equals(eventId))
                .toList();
            if (Loggers.TRIGGER.isDebugEnabled()) {
                Loggers.TRIGGER.debug("Event dispatch scan, eventId={}, currentState={}, candidates={}, leaving={}",
                                      eventId, currentStateId, candidates.size(), leaving.size());
            }

            for (TriggerBinding<T, EventTriggerImpl<T>> binding : candidates) {
                EventTriggerImpl<T> trigger = binding.trigger();
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
        public ProcessResult<T> processEvent(Identifiable event, Object eventData) {
            requireNotNull(event, "Event identifiable");
            return processEvent(event.getId(), eventData);
        }

        @Override
        public ProcessResult<T> processEvent(Identifiable event, Object eventData, Object context) {
            requireNotNull(event, "Event identifiable");
            return processEvent(event.getId(), eventData, context);
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
