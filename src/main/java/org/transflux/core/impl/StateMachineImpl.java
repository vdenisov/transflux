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
import org.transflux.core.state.StateResolver;
import org.transflux.core.transition.StepPath;
import org.transflux.core.transition.Transition;
import org.transflux.core.transition.TransitionResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.transflux.core.Preconditions.requireNotBlank;
import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Implementation of the {@link StateMachine} interface.
 *
 * @param <T> the type of entity managed by this state machine
 */
class StateMachineImpl<T> implements StateMachine<T> {
    private static final Logger log = LoggerFactory.getLogger(StateMachineImpl.class);

    // TODO Phase 4: extend across the async-submission boundary (§4.5.3.4) via
    //   capture/restore — the enclosing thread snapshots this set on submission and
    //   the worker installs it before entering the SM, so logical reentrancy stays
    //   detected when an operation spawns async work that calls back into the same
    //   SM for the same entity.
    private static final ThreadLocal<Set<EntityKey>> IN_FLIGHT = ThreadLocal.withInitial(HashSet::new);

    private final Class<T> entityType;

    private final String name;
    private final String description;
    private final String version;

    private final StateResolver<T> stateResolver;
    private final StateApplier<T> stateApplier;

    private final Map<String, State<T>> states = new LinkedHashMap<>();
    private final Map<String, BoundTransition<T, ?>> transitions = new LinkedHashMap<>();
    private final Registry<T> componentRegistry;
    private final StateMachineDefImpl<T> def;

    @SuppressWarnings({"unchecked", "rawtypes"})
    StateMachineImpl(StateMachineDefImpl<T> def) {
        this.def = def;
        this.entityType = def.getEntityType();
        this.name = def.getName();
        this.description = def.getDescription();
        this.version = def.getVersion();
        this.stateResolver = def.getStateResolver();
        this.stateApplier = def.getStateApplier();

        this.states.putAll(def.getStates().values().stream()
                              .collect(Collectors.toMap(StateDefImpl::getId, StateImpl::new)));

        RegistryImpl<T> registry = new RegistryImpl<>();
        this.componentRegistry = registry;

        Map<String, BoundCondition<T, ?>> conditionRegistry = def.buildBoundConditions();
        for (BoundCondition<T, ?> bc : conditionRegistry.values()) {
            Class<?> ctx = effectiveContextType(def, bc.id());
            registry.register(new Component.Condition(bc.id(), null, null, ctx, bc));
        }

        Map<String, BoundStep<T, ?>> boundSteps = def.buildBoundSteps();
        for (BoundStep<T, ?> bs : boundSteps.values()) {
            Class<?> ctx = effectiveContextType(def, bs.id());
            registry.register(new Component.Step(bs.id(), null, null, ctx, bs));
        }

        def.bindCompositeScopes(this, registry, conditionRegistry);

        def.buildBoundOperationsIncrementally(this, bo -> {
            Class<?> ctx = effectiveContextType(def, bo.id());
            registry.register(new Component.Operation(bo.id(), bo.name(), bo.description(), ctx, bo));
        });

        for (TransitionDefImpl<T, ?> td : def.getTransitionsById().values()) {
            this.transitions.put(td.getId(), buildTransition(td, conditionRegistry));
        }

        registry.flatten();
        def.flattenCompositeScopes();
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

    Class<T> getEntityType() {
        return entityType;
    }

    String getName() {
        return name;
    }

    String getDescription() {
        return description;
    }

    String getVersion() {
        return version;
    }

    StateResolver<T> getStateResolver() {
        return stateResolver;
    }

    StateApplier<T> getStateApplier() {
        return stateApplier;
    }

    Map<String, State<T>> getStates() {
        return states;
    }

    Map<String, BoundTransition<T, ?>> getTransitions() {
        return transitions;
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

    @SuppressWarnings({"unchecked"})
    private <C> TransitionResult<T> executeTransitionInternal(T entity, Object firingContext,
                                                                 BoundTransition<T, C> transition) {
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

        private void verifyFireContext(BoundTransition<T, ?> transition, Object firingContext) {
            Class<?> expected = transition.contextType();
            if (expected == Void.class) {
                if (firingContext != null) {
                    throw new TransfluxValidationException(
                        "Context type mismatch: transition '" + transition.id()
                            + "' expects Void (no context) but received "
                            + firingContext.getClass().getName());
                }
                return;
            }
            if (firingContext == null || expected == null || expected == Object.class) {
                return;
            }
            if (!expected.isInstance(firingContext)) {
                throw new TransfluxValidationException(
                    "Context type mismatch: transition '" + transition.id()
                        + "' expects " + expected.getName()
                        + " but received " + firingContext.getClass().getName());
            }
        }
    }
}
