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
import org.transflux.core.StateMachine;
import org.transflux.core.exception.TransfluxReentrancyException;
import org.transflux.core.exception.TransfluxValidationException;
import org.transflux.core.operation.Compensation;
import org.transflux.core.operation.Step;
import org.transflux.core.state.State;
import org.transflux.core.state.StateApplier;
import org.transflux.core.state.StateChange;
import org.transflux.core.state.StatePhase;
import org.transflux.core.state.StateResolver;
import org.transflux.core.transition.ProcessResult;
import org.transflux.core.transition.StepPath;
import org.transflux.core.transition.Transition;
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
    private static final Logger log = LoggerFactory.getLogger(StateMachineImpl.class);

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

        Map<String, BoundStep<T, ?>> boundSteps = def.buildBoundSteps();
        for (BoundStep<T, ?> bs : boundSteps.values()) {
            Class<?> ctx = effectiveContextType(def, bs.id());
            registry.register(new Component.Step(bs.id(), ctx, bs));
        }

        def.bindCompositeScopes(registry, conditionRegistry);

        def.buildBoundOperationsIncrementally(this, bo -> {
            Class<?> ctx = effectiveContextType(def, bo.id());
            registry.register(new Component.Operation(bo.id(), ctx, bo));
        });

        for (TransitionDefImpl<T, ?> td : def.getTransitionsById().values()) {
            this.transitions.put(td.getId(), buildTransition(td, conditionRegistry));
        }

        for (TransitionDefImpl<T, ?> td : def.getTransitionsById().values()) {
            registerManualTriggers(td, conditionRegistry);
            registerEventTriggers(td);
            registerDataTriggers(td, conditionRegistry);
        }

        registry.flatten();
        def.flattenCompositeScopes();
    }

    Registry<T> getComponentRegistry() {
        return componentRegistry;
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
    BoundStep<T, ?> getBoundStep(String id) {
        return componentRegistry.resolve(id)
            .filter(Component.Step.class::isInstance)
            .map(c -> ((Component.Step) c).bound())
            .orElse(null);
    }

    Optional<String> findInlineSiblingScope(String id, String excludingCompositeId) {
        return def.findInlineSiblingScope(id, excludingCompositeId);
    }

    /**
     * Acquires the step's {@link Compensation}, pushes it onto the view's rollback stack, then
     * dispatches the step's {@link Step#execute(Object, Object, Transition)} against the same
     * view.
     */
    static <T, C> void runBoundStep(BoundStep<T, C> boundStep, TransitionView<T, C> view) {
        Step<T, C> step = boundStep.step();
        Compensation<T, C> compensation = step.getCompensation(view.getEntity(), view.getContext());

        view.pushCompensation(boundStep.id(), compensation);
        step.execute(view.getEntity(), view.getContext(), view);

        view.recordExecutedId(boundStep.id());
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

        if (stateId == null || stateId.isBlank()) {
            throw new TransfluxValidationException(
                "State resolver returned null or blank state ID for entity: " + entity
            );
        }

        if (!states.containsKey(stateId)) {
            throw new TransfluxValidationException(
                String.format("State resolver returned unknown state ID '%s' for entity: %s",
                           stateId, entity)
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
                concatStateListeners(bindStateListeners(sd.getEntryListeners()), globalEntry));
            exitListenersByState.put(sd.getId(),
                concatStateListeners(bindStateListeners(sd.getExitListeners()), globalExit));
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

    private List<BoundStateListener<T>> concatStateListeners(List<BoundStateListener<T>> own,
                                                             List<BoundStateListener<T>> global) {
        if (global.isEmpty()) {
            return own;
        }
        if (own.isEmpty()) {
            return global;
        }

        List<BoundStateListener<T>> all = new ArrayList<>(own.size() + global.size());
        all.addAll(own);
        all.addAll(global);

        return List.copyOf(all);
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
            new StateChange<>(phase, states.get(stateId), new TopologyTransition<>(transition));

        for (BoundStateListener<T> listener : listeners) {
            try {
                listener.listener().onState(entity, context, change);
            } catch (Exception e) {
                log.warn("State listener '{}' threw '{}' on {} of state '{}': {}",
                         listener.id(), e.getClass().getName(), phase, stateId, e.getMessage());
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <C> BoundTransition<T, C> buildTransition(TransitionDefImpl<T, C> td,
                                                      Map<String, BoundCondition<T, ?>> conditionRegistry) {
        return BoundTransition.from(td, this, (Map) conditionRegistry);
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
        return executeTransitionInternal(entity, firingContext, transition, List.of());
    }

    @SuppressWarnings({"unchecked"})
    private <C> TransitionResult<T> executeTransitionInternal(T entity, Object firingContext,
                                                                 BoundTransition<T, C> transition,
                                                                 List<BoundCondition<T, C>> additionalPreConditions) {
        String sourceStateId = transition.sourceStateId();
        String targetStateId = transition.targetStateId();
        String transitionId = transition.id();

        EntityKey key = new EntityKey(this, entity);
        Set<EntityKey> inFlight = IN_FLIGHT.get();
        if (inFlight.contains(key)) {
            throw new TransfluxReentrancyException(
                "Reentrant transition '" + transitionId + "' to state '" + targetStateId
                    + "' rejected for entity: " + entity
                    + " (a transition is already in flight for the same state machine and entity)");
        }
        inFlight.add(key);

        C context = (C) firingContext;
        Instant startedAt = Instant.now();
        TransitionView<T, C> view = new TransitionView<>(this, transition, entity, context);

        try {
            for (BoundCondition<T, C> pc : transition.boundPreConditions()) {
                if (!pc.condition().test(entity, context, view)) {
                    return TransitionResult.failure(
                        entity, sourceStateId, targetStateId, transitionId,
                        new TransfluxValidationException("Pre-condition '" + pc.id()
                            + "' failed for transition '" + transitionId + "'"),
                        view.getExecutedPath(), null, startedAt, Instant.now());
                }
            }

            for (BoundCondition<T, C> pc : additionalPreConditions) {
                if (!pc.condition().test(entity, context, view)) {
                    return TransitionResult.failure(
                        entity, sourceStateId, targetStateId, transitionId,
                        new TransfluxValidationException("Pre-condition '" + pc.id()
                            + "' failed for transition '" + transitionId + "'"),
                        view.getExecutedPath(), null, startedAt, Instant.now());
                }
            }

            notifyStateExit(transition, entity, context);

            BoundOperation<T, C> boundOperation = transition.boundOperation();
            if (boundOperation != null) {
                view.recordExecutedId(boundOperation.id());
                view.enterOperation(boundOperation.id());
                try {
                    boundOperation.operation().execute(entity, context, view);
                } finally {
                    view.exitOperation();
                }
            }

            for (BoundCondition<T, C> pc : transition.boundPostConditions()) {
                if (!pc.condition().test(entity, context, view)) {
                    return TransitionResult.failure(
                        entity, sourceStateId, targetStateId, transitionId,
                        new TransfluxValidationException("Post-condition '" + pc.id()
                            + "' failed for transition '" + transitionId + "'"),
                        view.getExecutedPath(), null, startedAt, Instant.now());
                }
            }

            if (stateApplier != null) {
                stateApplier.applyState(entity, targetStateId);
            }

            notifyStateEntry(transition, entity, context);

            return TransitionResult.success(entity, sourceStateId, targetStateId, transitionId,
                    view.getExecutedPath(), startedAt, Instant.now());

        } catch (Exception e) {
            List<BoundCompensation<T, C>> drained = view.drainCompensationsLifo();
            List<StepPath> compensatedPath = new ArrayList<>(drained.size());

            for (BoundCompensation<T, C> bc : drained) {
                compensatedPath.add(bc.path());
                try {
                    bc.compensation().compensate(entity, context);
                } catch (Exception ce) {
                    log.warn("Compensation for step '{}' threw '{}': {}",
                            bc.path(), ce.getClass().getName(), ce.getMessage());
                }
            }

            return TransitionResult.failure(entity,
                                            sourceStateId,
                                            targetStateId,
                                            transitionId,
                                            e,
                                            view.getExecutedPath(),
                                            compensatedPath,
                                            startedAt,
                                            Instant.now());
        } finally {
            inFlight.remove(key);
            if (inFlight.isEmpty()) {
                IN_FLIGHT.remove();
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private TransitionResult<T> fireWith(T entity, Object firingContext,
                                         BoundTransition<T, ?> transition, TriggerImpl trigger) {
        List additionalPreConditions = trigger.preConditions();
        return executeTransitionInternal(entity, firingContext, (BoundTransition) transition, additionalPreConditions);
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
            for (TriggerBinding<T, EventTriggerImpl<T>> binding : eventTriggersLeaving(currentStateId)) {
                EventTriggerImpl<T> trigger = binding.trigger();
                if (!trigger.getEventId().equals(eventId)) {
                    continue;
                }
                if (!contextFits(binding.transition(), context)) {
                    logIneligibleContext(trigger, binding.transition(), context);
                    continue;
                }
                boolean matches = sneakyGet(() -> trigger.matches(eventData, entity, context),
                    "Event trigger '" + trigger.getId() + "' filter failed");
                if (!matches) {
                    continue;
                }
                return ProcessResult.fired(trigger.getId(),
                    executeTransitionInternal(entity, context, binding.transition()));
            }
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
            for (TriggerBinding<T, DataTriggerImpl<T, ?>> binding : dataTriggersLeaving(currentStateId)) {
                DataTriggerImpl<T, ?> trigger = binding.trigger();
                if (!contextFits(binding.transition(), context)) {
                    logIneligibleContext(trigger, binding.transition(), context);
                    continue;
                }
                if (!gateHolds(trigger, binding.transition(), context)) {
                    continue;
                }
                return ProcessResult.fired(trigger.getId(),
                    executeTransitionInternal(entity, context, binding.transition()));
            }
            return ProcessResult.notFired();
        }

        private void logIneligibleContext(Trigger trigger, BoundTransition<T, ?> transition, Object context) {
            log.debug("Trigger '{}' is not eligible: {}", trigger.getId(),
                contextMismatchMessage(transition, context));
        }

        private List<TriggerBinding<T, EventTriggerImpl<T>>> eventTriggersLeaving(String currentStateId) {
            return eventTriggersBySource.getOrDefault(currentStateId, List.of());
        }

        private List<TriggerBinding<T, DataTriggerImpl<T, ?>>> dataTriggersLeaving(String currentStateId) {
            return dataTriggersBySource.getOrDefault(currentStateId, List.of());
        }

        /**
         * Evaluates a data trigger's gate ahead of any transition being entered, so the gate never
         * touches the reentrancy guard. The view handed to the gate exposes the transition's
         * topology but rejects step and operation dispatch; a held gate fires a fresh execution
         * that gets a full view of its own.
         */
        @SuppressWarnings("unchecked")
        private <C> boolean gateHolds(DataTriggerImpl<T, C> trigger, BoundTransition<T, ?> transition,
                                      Object context) {
            C ctx = (C) context;
            Transition<T, C> probe = new TopologyTransition<>((BoundTransition<T, C>) transition);
            return sneakyGet(() -> trigger.gate().condition().test(entity, ctx, probe),
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
