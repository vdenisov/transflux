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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

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
 */
public class StateMachineImpl<T> implements StateMachine<T> {
    private final Class<T> entityType;

    private final String name;
    private final String description;
    private final String version;

    private final StateResolver<T> stateResolver;
    private final StateApplier<T> stateApplier;

    private final Map<String, State<T>> states = new LinkedHashMap<>();
    private final Map<String, Transition<T>> transitions = new LinkedHashMap<>();

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
    StateMachineImpl(StateMachineDefImpl<T> def) {
        this.entityType = def.getEntityType();
        this.name = def.getName();
        this.description = def.getDescription();
        this.version = def.getVersion();
        this.stateResolver = def.getStateResolver();
        this.stateApplier = def.getStateApplier();

        this.states.putAll(def.getStates().values().stream()
                              .collect(Collectors.toMap(StateDefImpl::getId, StateImpl::new)));

        this.transitions.putAll(def.getTransitionsById().values().stream()
                                   .collect(Collectors.toMap(TransitionDef::getId, TransitionImpl::new)));
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
    public Map<String, Transition<T>> getTransitions() {
        return transitions;
    }

    @Override
    public TransitionResult<T> executeTransition(T entity, String targetStateId) {
        if (entity == null) {
            throw new TransfluxValidationException("Entity cannot be null");
        }
        if (targetStateId == null || targetStateId.isBlank()) {
            throw new TransfluxValidationException("Target state ID cannot be null or blank");
        }

        // Resolve current state
        String currentStateId = resolveCurrentState(entity);

        // Find transition(s) from current to target state
        Transition<T> transition = findTransition(currentStateId, targetStateId);

        // Execute the transition
        return executeTransitionInternal(entity, transition);
    }

    @Override
    public TransitionResult<T> executeTransition(T entity, String targetStateId, String transitionId) {
        if (entity == null) {
            throw new TransfluxValidationException("Entity cannot be null");
        }
        if (targetStateId == null || targetStateId.isBlank()) {
            throw new TransfluxValidationException("Target state ID cannot be null or blank");
        }
        if (transitionId == null || transitionId.isBlank()) {
            throw new TransfluxValidationException("Transition ID cannot be null or blank");
        }

        // Resolve current state
        String currentStateId = resolveCurrentState(entity);

        // Get the specific transition
        Transition<T> transition = getTransition(transitionId);

        // Verify the transition matches the expected source and target
        if (!transition.getSourceStateId().equals(currentStateId)) {
            throw new TransfluxValidationException(
                String.format("Entity is in state '%s' but transition '%s' requires source state '%s'",
                           currentStateId, transitionId, transition.getSourceStateId())
            );
        }
        if (!transition.getTargetStateId().equals(targetStateId)) {
            throw new TransfluxValidationException(
                String.format("Transition '%s' leads to state '%s' but target state '%s' was requested",
                           transitionId, transition.getTargetStateId(), targetStateId)
            );
        }

        // Execute the transition
        return executeTransitionInternal(entity, transition);
    }

    @Override
    public String resolveCurrentState(T entity) {
        if (entity == null) {
            throw new TransfluxValidationException("Entity cannot be null");
        }
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
        if (stateId == null || stateId.isBlank()) {
            throw new TransfluxValidationException("State ID cannot be null or blank");
        }

        State<T> state = states.get(stateId);
        if (state == null) {
            throw new TransfluxValidationException("State '" + stateId + "' does not exist");
        }

        return state;
    }

    @Override
    public Transition<T> getTransition(String transitionId) {
        if (transitionId == null || transitionId.isBlank()) {
            throw new TransfluxValidationException("Transition ID cannot be null or blank");
        }

        Transition<T> transition = transitions.get(transitionId);
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
     * @return the transition
     * @throws TransfluxValidationException if no transition exists or multiple transitions exist
     */
    private Transition<T> findTransition(String sourceStateId, String targetStateId) {
        // Filter transitions by source and target
        var matchingTransitions = transitions.values().stream()
            .filter(t -> t.getSourceStateId().equals(sourceStateId)
                      && t.getTargetStateId().equals(targetStateId))
            .collect(Collectors.toList());

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
     * Executes a transition, handling the full lifecycle including operation execution.
     * <p>
     * This is a basic implementation that will be enhanced with pre-conditions,
     * post-conditions, listeners, and error handling in future phases.
     *
     * @param entity the entity to transition
     * @param transition the transition to execute
     * @return the transition result
     */
    private TransitionResult<T> executeTransitionInternal(T entity, Transition<T> transition) {
        String sourceStateId = transition.getSourceStateId();
        String targetStateId = transition.getTargetStateId();
        String transitionId = transition.getId();

        Instant startedAt = Instant.now();

        try {
            // TODO: Execute pre-conditions
            // TODO: Notify transition start listeners (and source-state onExit)
            // TODO: Execute operation if present
            // TODO: Execute post-conditions

            if (stateApplier != null) {
                stateApplier.applyState(entity, targetStateId);
            }

            // TODO: Notify transition complete listeners (and target-state onEntry)

            return TransitionResult.success(entity, sourceStateId, targetStateId, transitionId,
                    startedAt, Instant.now());

        } catch (Exception e) {
            // TODO: Execute compensation logic
            return TransitionResult.failure(entity, sourceStateId, targetStateId, transitionId, e,
                    startedAt, Instant.now());
        }
    }
}
