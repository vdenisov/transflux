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

package org.transflux.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.transflux.core.condition.BoundCondition;
import org.transflux.core.exception.TransfluxValidationException;
import org.transflux.core.operation.BoundCompensation;
import org.transflux.core.operation.BoundOperation;
import org.transflux.core.operation.BoundStep;
import org.transflux.core.operation.Compensation;
import org.transflux.core.operation.Step;
import org.transflux.core.state.State;
import org.transflux.core.state.StateApplier;
import org.transflux.core.state.StateDefImpl;
import org.transflux.core.state.StateImpl;
import org.transflux.core.state.StateResolver;
import org.transflux.core.transition.Transition;
import org.transflux.core.transition.TransitionDefImpl;
import org.transflux.core.transition.TransitionImpl;
import org.transflux.core.transition.TransitionResult;
import org.transflux.core.transition.TransitionView;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.transflux.core.ValidationUtils.requireNotBlank;
import static org.transflux.core.ValidationUtils.requireNotNull;

/**
 * Implementation of the {@link StateMachine} interface.
 * <p>
 * This implementation serves as a concrete state machine that manages entity state
 * transitions and coordinates framework operations. It is constructed from a
 * {@link StateMachineDef} definition and maintains collections of states and transitions
 * along with metadata such as name, description, and version.
 *
 * <p><b>Note:</b> This is currently a placeholder implementation that will be enhanced
 * with full state machine functionality in future versions.
 *
 * @param <T> the type of entity managed by this state machine
 * @param <C> the host-supplied context type carried through transition execution
 */
public class StateMachineImpl<T, C> implements StateMachine<T, C> {
    private static final Logger log = LoggerFactory.getLogger(StateMachineImpl.class);

    private final Class<T> entityType;
    private final Class<C> contextType;

    private final String name;
    private final String description;
    private final String version;

    private final StateResolver<T> stateResolver;
    private final StateApplier<T> stateApplier;

    private final Map<String, State<T>> states = new LinkedHashMap<>();
    private final Map<String, TransitionImpl<T, C>> transitions = new LinkedHashMap<>();
    private final Map<String, BoundStep<T, C>> boundSteps;

    /**
     * Constructs a new StateMachineImpl from the provided state machine definition.
     * <p>
     * This constructor initializes the state machine with all necessary components
     * including entity type, metadata, state resolver, and collections of states
     * and transitions created from their respective definitions.
     *
     * @param def the state machine definition to construct this state machine from
     *
     * @throws TransfluxValidationException if the definition is null or invalid
     */
    public StateMachineImpl(StateMachineDefImpl<T, C> def) {
        this.entityType = def.getEntityType();
        this.contextType = def.getContextType();
        this.name = def.getName();
        this.description = def.getDescription();
        this.version = def.getVersion();
        this.stateResolver = def.getStateResolver();
        this.stateApplier = def.getStateApplier();

        this.states.putAll(def.getStates().values().stream()
                              .collect(Collectors.toMap(StateDefImpl::getId, StateImpl::new)));

        Map<String, BoundCondition<T, C>> conditionRegistry = def.buildBoundConditions();
        this.boundSteps = def.buildBoundSteps(this, conditionRegistry);

        for (TransitionDefImpl<T, C> td : def.getTransitionsById().values()) {
            this.transitions.put(td.getId(), new TransitionImpl<>(td, this, conditionRegistry));
        }
    }

    /**
     * Looks up a registered step by id.
     *
     * @param id the step id
     *
     * @return the bound step, or {@code null} if no step is registered under {@code id}
     */
    public BoundStep<T, C> getBoundStep(String id) {
        return boundSteps.get(id);
    }

    /**
     * Acquires the step's {@link Compensation}, pushes it onto the view's rollback stack, then
     * dispatches the step's {@link Step#execute(Object, Object, Transition)} against the same
     * view. Shared by the composite executor and by {@code TransitionView.step("id")} so both
     * step-id tracking and compensation registration are uniform regardless of who initiates
     * the step.
     *
     * <p>The compensation is captured <em>before</em> {@code execute} runs. This is deliberate:
     * if {@code execute} throws partway through producing side effects (created remote entities,
     * inserted database rows, sent messages), the compensation is already on the stack and the
     * enclosing transition catch will run it, giving the step a chance to roll back whatever
     * it managed to do. Implementations whose compensation depends on completion-time state
     * should write that state into the entity or context during {@code execute} and have the
     * returned compensation read it back at rollback time. {@code execute} sees the same entity
     * and context references {@code getCompensation} already saw.
     *
     * <p>Sequencing within this method, and how it interacts with failures:
     * <ul>
     *   <li>{@code getCompensation} runs first. If it throws, neither {@code execute} nor the
     *       id-recording happens; the exception propagates and the step is treated as having
     *       never started (its id appears on neither {@code executedStepIds} nor
     *       {@code compensatedStepIds}).</li>
     *   <li>If {@code getCompensation} returns non-{@code null}, the compensation is pushed.
     *       A {@code null} return is a no-op.</li>
     *   <li>{@code execute} runs. If it throws, the exception propagates; the step's id is
     *       <em>not</em> appended to {@code executedStepIds} (executed = completed), but its
     *       compensation is on the stack and will run when the enclosing catch drains it.</li>
     *   <li>On successful return, the step's id is appended to {@code executedStepIds}.</li>
     * </ul>
     *
     * @param boundStep the bound step to invoke
     * @param view the per-execution transition view that owns the current scope
     *
     * @param <T> the entity type
     * @param <C> the context type
     */
    public static <T, C> void runBoundStep(BoundStep<T, C> boundStep, TransitionView<T, C> view) {
        Step<T, C> step = boundStep.step();
        Compensation<T, C> compensation = step.getCompensation(view.getEntity(), view.getContext());

        view.pushCompensation(boundStep.id(), compensation);
        step.execute(view.getEntity(), view.getContext(), view);

        view.recordExecutedStepId(boundStep.id());
    }

    /**
     * Returns the entity type managed by this state machine.
     *
     * @return the entity class type
     */
    public Class<T> getEntityType() {
        return entityType;
    }

    /**
     * Returns the context type carried through transitions in this state machine.
     *
     * @return the context class type, may be {@code null} if not configured
     */
    public Class<C> getContextType() {
        return contextType;
    }

    /**
     * Returns the human-readable name of this state machine.
     *
     * @return the name of the state machine, may be {@code null}
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the description of this state machine.
     *
     * @return the description of the state machine, may be {@code null}
     */
    public String getDescription() {
        return description;
    }

    /**
     * Returns the version of this state machine definition.
     *
     * @return the version string, may be {@code null}
     */
    public String getVersion() {
        return version;
    }

    /**
     * Returns the state resolver used to determine entity current states.
     *
     * @return the state resolver for this state machine
     */
    public StateResolver<T> getStateResolver() {
        return stateResolver;
    }

    /**
     * Returns the state applier used to write the new state after a successful transition.
     *
     * @return the state applier, may be {@code null} if not configured
     */
    public StateApplier<T> getStateApplier() {
        return stateApplier;
    }

    /**
     * Returns an immutable map of all states defined in this state machine.
     *
     * @return a map of state IDs to state instances
     */
    public Map<String, State<T>> getStates() {
        return states;
    }

    /**
     * Returns an immutable map of all transitions defined in this state machine.
     *
     * @return a map of transition IDs to transition instances
     */
    public Map<String, TransitionImpl<T, C>> getTransitions() {
        return transitions;
    }

    @Override
    public EntityBinding<T, C> entity(T entity) {
        requireNotNull(entity, "Entity");
        return new EntityBindingImpl(entity);
    }

    @Override
    public TransitionResult<T, C> executeTransition(T entity, String targetStateId) {
        return entity(entity).transitionTo(targetStateId);
    }

    @Override
    public TransitionResult<T, C> executeTransition(T entity, String targetStateId, String transitionId) {
        return entity(entity).transitionTo(targetStateId, transitionId);
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
    public State<T> getState(String stateId) {
        requireNotBlank(stateId, "State ID");

        State<T> state = states.get(stateId);
        if (state == null) {
            throw new TransfluxValidationException("State '" + stateId + "' does not exist");
        }

        return state;
    }

    @Override
    public Transition<T, C> getTransition(String transitionId) {
        requireNotBlank(transitionId, "Transition ID");

        Transition<T, C> transition = transitions.get(transitionId);
        if (transition == null) {
            throw new TransfluxValidationException("Transition '" + transitionId + "' does not exist");
        }

        return transition;
    }

    /**
     * Finds a unique transition from source to target state.
     *
     * @param sourceStateId the source state ID
     * @param targetStateId the target state ID
     *
     * @return the transition
     *
     * @throws TransfluxValidationException if no transition exists or multiple transitions exist
     */
    private TransitionImpl<T, C> findTransition(String sourceStateId, String targetStateId) {
        // Filter transitions by source and target
        var matchingTransitions = transitions.values().stream()
            .filter(t -> t.getSourceStateId().equals(sourceStateId)
                      && t.getTargetStateId().equals(targetStateId))
            .toList();

        if (matchingTransitions.isEmpty()) {
            throw new TransfluxValidationException(
                String.format("No transition exists from state '%s' to state '%s'",
                           sourceStateId, targetStateId)
            );
        }

        if (matchingTransitions.size() > 1) {
            throw new TransfluxValidationException(
                String.format("Multiple transitions exist from state '%s' to state '%s'. " +
                           "Please specify the transition ID explicitly.",
                           sourceStateId, targetStateId)
            );
        }

        return matchingTransitions.get(0);
    }

    /**
     * Executes a transition, handling the full lifecycle.
     *
     * @param entity the entity to transition
     * @param context the host-supplied context object, may be {@code null}
     * @param transition the transition to execute
     *
     * @return the transition result
     */
    private TransitionResult<T, C> executeTransitionInternal(T entity, C context,
                                                             TransitionImpl<T, C> transition) {
        String sourceStateId = transition.getSourceStateId();
        String targetStateId = transition.getTargetStateId();
        String transitionId = transition.getId();

        Instant startedAt = Instant.now();
        TransitionView<T, C> view = new TransitionView<>(this, transition, entity, context);

        try {
            for (BoundCondition<T, C> pc : transition.getBoundPreConditions()) {
                if (!pc.condition().test(entity, context, view)) {
                    return TransitionResult.failure(
                        entity, sourceStateId, targetStateId, transitionId,
                        new TransfluxValidationException("Pre-condition '" + pc.id()
                            + "' failed for transition '" + transitionId + "'"),
                        view.getExecutedStepIds(), null, startedAt, Instant.now());
                }
            }
            // TODO: notifyStart(view) seam.

            BoundOperation<T, C> boundOperation = transition.getBoundOperation();
            if (boundOperation != null) {
                boundOperation.operation().execute(entity, context, view);
            }

            for (BoundCondition<T, C> pc : transition.getBoundPostConditions()) {
                if (!pc.condition().test(entity, context, view)) {
                    return TransitionResult.failure(
                        entity, sourceStateId, targetStateId, transitionId,
                        new TransfluxValidationException("Post-condition '" + pc.id()
                            + "' failed for transition '" + transitionId + "'"),
                        view.getExecutedStepIds(), null, startedAt, Instant.now());
                }
            }

            if (stateApplier != null) {
                stateApplier.applyState(entity, targetStateId);
            }

            // TODO: notifyComplete(view) seam.

            return TransitionResult.success(entity, sourceStateId, targetStateId, transitionId,
                    view.getExecutedStepIds(), startedAt, Instant.now());

        } catch (Exception e) {
            List<BoundCompensation<T, C>> drained = view.drainCompensationsLifo();
            List<String> compensatedStepIds = new ArrayList<>(drained.size());

            for (BoundCompensation<T, C> bc : drained) {
                compensatedStepIds.add(bc.stepId());
                try {
                    bc.compensation().compensate(entity, context);
                } catch (Exception ce) {
                    log.warn("Compensation for step '{}' threw '{}': {}",
                            bc.stepId(), ce.getClass().getName(), ce.getMessage());
                }
            }

            return TransitionResult.failure(entity,
                                            sourceStateId,
                                            targetStateId,
                                            transitionId,
                                            e,
                                            view.getExecutedStepIds(),
                                            compensatedStepIds,
                                            startedAt,
                                            Instant.now());
        }
    }

    private class EntityBindingImpl implements EntityBinding<T, C> {
        private final T entity;
        private C context;

        EntityBindingImpl(T entity) {
            this.entity = entity;
        }

        @Override
        public EntityBinding<T, C> withContext(C context) {
            this.context = context;
            return this;
        }

        @Override
        public TransitionResult<T, C> transitionTo(String targetStateId) {
            requireNotBlank(targetStateId, "Target state ID");

            String currentStateId = resolveCurrentState(entity);
            TransitionImpl<T, C> transition = findTransition(currentStateId, targetStateId);
            return executeTransitionInternal(entity, context, transition);
        }

        @Override
        public TransitionResult<T, C> transitionTo(String targetStateId, String transitionId) {
            requireNotBlank(targetStateId, "Target state ID");
            requireNotBlank(transitionId, "Transition ID");

            String currentStateId = resolveCurrentState(entity);
            Transition<T, C> abstractTransition = StateMachineImpl.this.getTransition(transitionId);

            if (!abstractTransition.getSourceStateId().equals(currentStateId)) {
                throw new TransfluxValidationException(
                    String.format("Entity is in state '%s' but transition '%s' requires source state '%s'",
                               currentStateId, transitionId, abstractTransition.getSourceStateId())
                );
            }
            if (!abstractTransition.getTargetStateId().equals(targetStateId)) {
                throw new TransfluxValidationException(
                    String.format("Transition '%s' leads to state '%s' but target state '%s' was requested",
                               transitionId, abstractTransition.getTargetStateId(), targetStateId)
                );
            }

            return executeTransitionInternal(entity, context, (TransitionImpl<T, C>) abstractTransition);
        }
    }
}
