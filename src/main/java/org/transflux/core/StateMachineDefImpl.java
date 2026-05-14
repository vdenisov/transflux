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

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.transflux.core.ValidationUtils.requireNotBlank;
import static org.transflux.core.ValidationUtils.requireNotNull;
import static org.transflux.core.ValidationUtils.warnIfSet;

/**
 * Builder class for defining and constructing state machines.
 * <p>
 * {@code StateMachineDef} provides the main fluent API entry point for creating state machine
 * definitions in Transflux. It manages the configuration of entity types, metadata
 * (name, description, version), state resolvers, states, and transitions, providing
 * a declarative DSL for building complex state machines in a readable and maintainable way.
 *
 * <p>The StateMachineDef supports method chaining throughout the definition process,
 * allowing for concise and expressive state machine configurations that can be easily
 * understood and maintained.
 *
 * <p><b>Example usage:</b>
 * <pre>{@code
 * StateMachine<Order, OrderContext> orderStateMachine = new StateMachineDef<Order, OrderContext>()
 *     .forEntityType(Order.class)
 *     .withName("Order Processing State Machine")
 *     .withDescription("Manages the lifecycle of customer orders")
 *     .withVersion("1.0")
 *     .withStateResolver(order -> order.getStatus())
 *     .state("pending")
 *         .withName("Pending Order")
 *         .withDescription("Order received but not yet processed")
 *         .transitionsTo("processing", "start-processing")
 *         .transitionsTo("cancelled", "cancel-order")
 *     .state("processing")
 *         .withName("Processing Order")
 *         .transitionsTo("shipped", "ship-order")
 *         .transitionsTo("cancelled", "cancel-order")
 *     .state("shipped")
 *         .withName("Shipped Order")
 *         .transitionsTo("delivered", "mark-delivered")
 *     .state("delivered")
 *         .withName("Delivered Order")
 *     .state("cancelled")
 *         .withName("Cancelled Order")
 *     .build();
 * }</pre>
 *
 * @param <T> the type of entity managed by the state machine being defined
 * @param <C> the host-supplied context type carried through transition execution
 */
class StateMachineDefImpl<T, C> implements StateMachineDef<T, C> {
    private static final Logger log = LoggerFactory.getLogger(StateMachineDefImpl.class);

    private Class<T> entityType;
    private Class<C> contextType;
    private String name;
    private String description;
    private String version;

    private StateResolver<T> stateResolver;
    private StateApplier<T> stateApplier;

    private final Map<String, StateDefImpl<T, C>> states = new LinkedHashMap<>();

    private final Map<String, StepRegistration<T, C>> stepRegistrations = new LinkedHashMap<>();

    // transitionId -> TransitionDefImpl
    private final Map<String, TransitionDefImpl<T, C>> transitionsById = new LinkedHashMap<>();
    // Source-target index: sourceStateId -> targetStateId -> list of TransitionDefImpl
    private final Map<String, Map<String, List<TransitionDefImpl<T, C>>>> transitionsBySourceTarget = new LinkedHashMap<>();

    StateMachineDefImpl() {
    }

    @Override
    public StateMachineDef<T, C> forEntityType(Class<T> entityType) {
        requireNotNull(entityType, "Entity type");
        this.entityType = entityType;
        return this;
    }

    @Override
    public StateMachineDef<T, C> forContextType(Class<C> contextType) {
        requireNotNull(contextType, "Context type");
        this.contextType = contextType;
        return this;
    }

    /**
     * Sets the human-readable name for this state machine.
     * <p>
     * This method allows you to provide a descriptive name for the state machine
     * that can be used in documentation, user interfaces, and logging.
     *
     * @param name the human-readable name for this state machine
     *
     * @return this StateMachineDef instance for method chaining
     */
    @Override
    public StateMachineDef<T, C> withName(String name) {
        warnIfSet(this.name, name, "Name", log);

        this.name = name;
        return this;
    }

    /**
     * Sets the description for this state machine.
     * <p>
     * This method allows you to provide additional details about the state machine's
     * purpose, behavior, or business domain within your application.
     *
     * @param description the description for this state machine
     *
     * @return this StateMachineDef instance for method chaining
     */
    @Override
    public StateMachineDef<T, C> withDescription(String description) {
        warnIfSet(this.description, description, "Description", log);

        this.description = description;
        return this;
    }

    /**
     * Sets the version for this state machine definition.
     * <p>
     * This method allows you to specify a version identifier for the state machine
     * definition, which can be useful for versioning, deployment tracking, and
     * compatibility management.
     *
     * @param version the version identifier for this state machine definition
     *
     * @return this StateMachineDef instance for method chaining
     */
    @Override
    public StateMachineDef<T, C> withVersion(String version) {
        warnIfSet(this.version, version, "Version", log);

        this.version = version;
        return this;
    }

    /**
     * Sets the state resolver used to determine the current state of entities.
     * <p>
     * The state resolver is a critical component that bridges your domain entities
     * with the Transflux framework, allowing the state machine to understand the
     * current state of entities without imposing specific storage requirements.
     *
     * @param stateResolver the state resolver implementation
     *
     * @return this StateMachineDef instance for method chaining
     *
     * @throws TransfluxValidationException if the state resolver is null
     */
    @Override
    public StateMachineDef<T, C> withStateResolver(StateResolver<T> stateResolver) {
        requireNotNull(stateResolver, "State resolver");

        if (this.stateResolver != null) {
            log.warn("State resolver is already defined: {}. Overriding previous value with {}",
                               this.stateResolver.getClass().getName(), stateResolver.getClass().getName());
        }

        this.stateResolver = stateResolver;
        return this;
    }

    @Override
    public StateMachineDef<T, C> step(String id, Step<T, C> step) {
        requireNotBlank(id, "Step ID");
        requireNotNull(step, "Step");
        registerStepInstance(id, step);
        return this;
    }

    @Override
    public StateMachineDef<T, C> step(String id, Class<? extends Step<T, C>> stepClass) {
        requireNotBlank(id, "Step ID");
        requireNotNull(stepClass, "Step class");
        registerStepClass(id, stepClass);
        return this;
    }

    /**
     * Records an instance-based step registration, enforcing uniqueness with same-instance
     * re-registration tolerated as a no-op.
     *
     * @param id the step id
     * @param step the step instance
     *
     * @throws TransfluxValidationException if {@code id} is already registered with a
     *         different instance or with any class
     */
    private void registerStepInstance(String id, Step<T, C> step) {
        StepRegistration<T, C> existing = stepRegistrations.get(id);
        if (existing == null) {
            stepRegistrations.put(id, StepRegistration.ofInstance(step));
            return;
        }
        if (existing.instance != null && existing.instance == step) {
            return;
        }
        throw new TransfluxValidationException("Step ID '" + id + "' is already registered");
    }

    /**
     * Records a class-based step registration, enforcing uniqueness with same-class
     * re-registration tolerated as a no-op.
     *
     * @param id the step id
     * @param stepClass the step class
     *
     * @throws TransfluxValidationException if {@code id} is already registered with a
     *         different class or with any instance
     */
    private void registerStepClass(String id, Class<? extends Step<T, C>> stepClass) {
        StepRegistration<T, C> existing = stepRegistrations.get(id);
        if (existing == null) {
            stepRegistrations.put(id, StepRegistration.ofClass(stepClass));
            return;
        }
        if (existing.stepClass != null && existing.stepClass.equals(stepClass)) {
            return;
        }
        throw new TransfluxValidationException("Step ID '" + id + "' is already registered");
    }

    /**
     * Walks every transition's operation def for inline step refs and registers each one on
     * this state-machine def. Same-instance / same-class collisions on an id are tolerated;
     * any other collision raises {@link TransfluxValidationException}.
     */
    private void collectInlineStepRegistrations() {
        for (TransitionDefImpl<T, C> td : transitionsById.values()) {
            OperationDefImpl<T, C> op = td.getOperationDef();
            if (!(op instanceof CompositeOperationDefImpl)) {
                continue;
            }
            CompositeOperationDefImpl<T, C> composite = (CompositeOperationDefImpl<T, C>) op;
            for (StepRef<T, C> ref : composite.getStepRefs()) {
                if (ref instanceof StepRef.InlineInstance) {
                    registerStepInstance(ref.getId(), ((StepRef.InlineInstance<T, C>) ref).getStep());
                } else if (ref instanceof StepRef.InlineClass) {
                    registerStepClass(ref.getId(), ((StepRef.InlineClass<T, C>) ref).getStepClass());
                }
            }
        }
    }

    /**
     * Resolves the step registrations into {@link BoundStep} instances, reflectively
     * instantiating class-form entries. Called from {@link StateMachineImpl} during state
     * machine construction.
     *
     * @return an unmodifiable map of step id to bound step
     *
     * @throws TransfluxValidationException if any class-form registration cannot be
     *         instantiated through its no-arg constructor
     */
    Map<String, BoundStep<T, C>> buildBoundSteps() {
        collectInlineStepRegistrations();
        Map<String, BoundStep<T, C>> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, StepRegistration<T, C>> e : stepRegistrations.entrySet()) {
            resolved.put(e.getKey(), e.getValue().toBoundStep(e.getKey()));
        }
        return Collections.unmodifiableMap(resolved);
    }

    @Override
    public StateMachineDef<T, C> withStateApplier(StateApplier<T> stateApplier) {
        requireNotNull(stateApplier, "State applier");

        if (this.stateApplier != null) {
            log.warn("State applier is already defined: {}. Overriding previous value with {}",
                               this.stateApplier.getClass().getName(), stateApplier.getClass().getName());
        }

        this.stateApplier = stateApplier;
        return this;
    }

    /**
     * Begins defining a new state in the state machine.
     * <p>
     * This method creates a new state definition with the specified ID and returns
     * a StateDef instance for further configuration through the fluent API.
     *
     * @param stateId the unique identifier for the new state
     *
     * @return a StateDef instance for configuring the new state
     *
     * @throws TransfluxValidationException if the state ID is already defined
     */
    @Override
    public StateDef<T, C> state(String stateId) {
        if (states.containsKey(stateId)) {
            throw new TransfluxValidationException("State ID " + stateId + " already defined");
        }

        var stateDef = new StateDefImpl<T, C>(this, stateId);
        states.put(stateDef.getId(), stateDef);
        return stateDef;
    }

    /**
     * Begins defining a new state using an identifiable object for the state ID.
     *
     * @param stateIdentifiable an identifiable object providing the state ID
     *
     * @return a StateDef instance for configuring the new state
     *
     * @throws TransfluxValidationException if the identifiable is null or state ID is already defined
     */
    @Override
    public StateDef<T, C> state(Identifiable stateIdentifiable) {
        requireNotNull(stateIdentifiable, "State identifiable");

        return state(stateIdentifiable.getId());
    }

    /**
     * Registers a transition between two states.
     * <p>
     * This package-private method is used internally by StateDef to register
     * transitions when they are defined through the fluent API.
     *
     * @param sourceStateId the ID of the source state
     * @param targetStateId the ID of the target state
     * @param transitionId the unique identifier for the transition
     *
     * @throws TransfluxValidationException if any parameter is null/blank or transition ID already exists
     */
    void registerTransition(String sourceStateId, String targetStateId, String transitionId) {
        requireNotBlank(sourceStateId, "Source state ID");
        requireNotBlank(targetStateId, "Target state ID");
        requireNotBlank(transitionId, "Transition ID");

        if (transitionsById.containsKey(transitionId)) {
            throw new TransfluxValidationException("Transition ID " + transitionId + " already defined");
        }

        var byTarget = transitionsBySourceTarget.computeIfAbsent(sourceStateId, k -> new LinkedHashMap<>());
        var list = byTarget.computeIfAbsent(targetStateId, k -> new ArrayList<>(1)); // More than one transition between states is uncommon

        var def = new TransitionDefImpl<T, C>(transitionId, sourceStateId, targetStateId);
        list.add(def);
        transitionsById.put(transitionId, def);
    }

    /**
     * Completes the state machine definition and creates the final StateMachine instance.
     * <p>
     * This method finalizes the state machine configuration and returns a concrete
     * StateMachine implementation that can be used to manage entity state transitions.
     *
     * @return the constructed StateMachine instance
     *
     * @throws IllegalStateException if the state machine definition is invalid or incomplete
     */
    @Override
    public StateMachine<T, C> build() {
        return new StateMachineImpl<>(this);
    }

    public Class<T> getEntityType() {
        return entityType;
    }

    public Class<C> getContextType() {
        return contextType;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getVersion() {
        return version;
    }

    public StateResolver<T> getStateResolver() {
        return stateResolver;
    }

    public StateApplier<T> getStateApplier() {
        return stateApplier;
    }

    Map<String, StateDefImpl<T, C>> getStates() {
        return states;
    }

    Map<String, TransitionDefImpl<T, C>> getTransitionsById() {
        return transitionsById;
    }

    @Override
    public TransitionDef<T, C> getTransition(String sourceStateId, String targetStateId) {
        var byTarget = transitionsBySourceTarget.get(sourceStateId);
        if (byTarget == null) {
            throw new TransfluxValidationException("Source state '" + sourceStateId + "' not found");
        }

        var list = byTarget.get(targetStateId);
        if (list == null || list.isEmpty()) {
            throw new TransfluxValidationException("No transitions found for source state '" + sourceStateId
                                                   + "' and target state '" + targetStateId + "'");
        }

        if (list.size() > 1) {
            throw new TransfluxValidationException("Multiple transitions found for source state '" + sourceStateId
                                                   + "' and target state '" + targetStateId + "', use transition ID instead");
        }

        return list.get(0);
    }

    /**
     * Holds one step registration kept on the state-machine def. Exactly one of
     * {@link #instance} or {@link #stepClass} is non-null.
     */
    private static final class StepRegistration<T, C> {
        private final Step<T, C> instance;
        private final Class<? extends Step<T, C>> stepClass;

        private StepRegistration(Step<T, C> instance, Class<? extends Step<T, C>> stepClass) {
            this.instance = instance;
            this.stepClass = stepClass;
        }

        static <T, C> StepRegistration<T, C> ofInstance(Step<T, C> instance) {
            return new StepRegistration<>(instance, null);
        }

        static <T, C> StepRegistration<T, C> ofClass(Class<? extends Step<T, C>> stepClass) {
            return new StepRegistration<>(null, stepClass);
        }

        BoundStep<T, C> toBoundStep(String id) {
            if (instance != null) {
                return BoundStep.of(id, instance);
            }

            try {
                Step<T, C> resolved = stepClass.getDeclaredConstructor().newInstance();
                return BoundStep.of(id, resolved);
            } catch (NoSuchMethodException e) {
                throw new TransfluxValidationException(
                    "Step class '" + stepClass.getName() + "' has no accessible no-arg constructor", e);
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
                throw new TransfluxValidationException(
                    "Failed to instantiate step class '" + stepClass.getName() + "'", e);
            }
        }
    }

    @Override
    public TransitionDef<T, C> getTransition(String transitionId) {
        var td = transitionsById.get(transitionId);
        if (td == null) {
            throw new TransfluxValidationException("Transition '" + transitionId + "' not found");
        }
        return td;
    }
}
