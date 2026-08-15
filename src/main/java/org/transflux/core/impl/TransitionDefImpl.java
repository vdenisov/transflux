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
import org.transflux.core.condition.Condition;
import org.transflux.core.condition.ConditionDescriptor;
import org.transflux.core.exception.TransfluxValidationException;
import org.transflux.core.action.OperationDef;
import org.transflux.core.action.Action;
import org.transflux.core.action.StepDef;
import org.transflux.core.transition.Transition;
import org.transflux.core.transition.TransitionDef;
import org.transflux.core.transition.TransitionListener;
import org.transflux.core.transition.TransitionListenerDef;
import org.transflux.core.trigger.DataTriggerDef;
import org.transflux.core.trigger.EventTriggerDef;
import org.transflux.core.trigger.ManualTriggerDef;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.transflux.core.Preconditions.requireNotBlank;
import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Definition implementation class for transitions between states in a state machine.
 * <p>
 * {@link TransitionDef} represents the configuration and metadata for a transition,
 * including the unique identifier, source state, and target state. This class
 * is used internally during state machine construction to store transition
 * definitions before they are converted into concrete {@link Transition} instances.
 *
 * <p>{@code TransitionDef} instances are created internally by the framework when
 * transitions are registered through the fluent API and should not be
 * instantiated directly by client code.
 *
 * @param <T> the entity type managed by the enclosing state machine
 * @param <C> the host-supplied context type carried through transition execution
 */
class TransitionDefImpl<T, C> extends IdentifiedDefImpl<TransitionDefImpl<T, C>> implements TransitionDef<T, C> {
    private static final Logger log = LoggerFactory.getLogger(TransitionDefImpl.class);

    private final String sourceStateId;
    private final String targetStateId;

    private ActionDefImpl<T, C, ?> operationDef;
    private String registeredOperationRefId;
    private Class<C> contextType;

    private final ConditionDescriptorSink<T, C, TransitionDef<T, C>> preConditions =
        new ConditionDescriptorSink<>(this, this, "preCondition");
    private final ConditionDescriptorSink<T, C, TransitionDef<T, C>> postConditions =
        new ConditionDescriptorSink<>(this, this, "postCondition");
    private final List<ManualTriggerDefImpl<T, C>> manualTriggers = new ArrayList<>();
    private final List<EventTriggerDefImpl<T, C>> eventTriggers = new ArrayList<>();
    private final List<DataTriggerDefImpl<T, C>> dataTriggers = new ArrayList<>();
    private final List<TransitionListenerDefImpl<T, C>> startListeners = new ArrayList<>();
    private final List<TransitionListenerDefImpl<T, C>> completeListeners = new ArrayList<>();
    private final List<TransitionListenerDefImpl<T, C>> errorListeners = new ArrayList<>();

    /**
     * Constructs a new TransitionDefImpl with the specified parameters.
     * <p>
     * This package-private constructor is used internally by the framework
     * to create transition definitions during state machine construction.
     *
     * @param id the unique identifier for this transition
     * @param sourceStateId the ID of the source state
     * @param targetStateId the ID of the target state
     *
     * @throws TransfluxValidationException if any parameter is null or blank
     */
    @SuppressWarnings("unchecked")
    TransitionDefImpl(String id, String sourceStateId, String targetStateId) {
        this(id, sourceStateId, targetStateId, (Class<C>) Object.class);
    }

    TransitionDefImpl(String id, String sourceStateId, String targetStateId, Class<C> contextType) {
        super(id, "transition", "Transition ID");
        requireNotBlank(sourceStateId, "Source state ID");
        requireNotBlank(targetStateId, "Target state ID");
        requireNotNull(contextType, "Transition context type");

        this.sourceStateId = sourceStateId;
        this.targetStateId = targetStateId;
        this.contextType = contextType;
    }

    @Override
    public Class<C> getContextType() {
        return contextType;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <C2> TransitionDef<T, C2> usingContext(Class<C2> contextType) {
        requireConfigurerActive("usingContext");
        requireNotNull(contextType, "Transition context type");
        if (this.contextType != null && this.contextType != Object.class && this.contextType != contextType) {
            log.warn("Transition '{}' context type already declared as {}; overriding with {}",
                getId(), this.contextType.getName(), contextType.getName());
        }
        this.contextType = (Class<C>) contextType;
        return (TransitionDef<T, C2>) this;
    }

    /**
     * Returns the identifier of the source state for this transition.
     *
     * @return the source state ID
     */
    @Override
    public String getSourceStateId() {
        return sourceStateId;
    }

    /**
     * Returns the identifier of the target state for this transition.
     *
     * @return the target state ID
     */
    @Override
    public String getTargetStateId() {
        return targetStateId;
    }

    /**
     * Package-private hook used by {@link BoundTransition} to materialize the runtime
     * {@link BoundAction}, or {@code null} when this transition has no operation attached.
     *
     * @param stateMachine the enclosing state machine; required by composite operations to
     *                     resolve step references against the step registry
     *
     * @return the bound operation, or {@code null}
     */
    @SuppressWarnings({"unchecked"})
    BoundAction<T, C> buildBoundOperation(StateMachineImpl<T> stateMachine) {
        if (registeredOperationRefId != null) {
            Component<T> component = stateMachine.getComponentRegistry()
                .resolve(registeredOperationRefId).orElse(null);
            if (component == null) {
                throw new TransfluxValidationException(
                    "Transition '" + getId() + "' references unknown action id '"
                        + registeredOperationRefId + "'");
            }
            if (!(component instanceof Component.Action<T, ?> opComp)) {
                throw new TransfluxValidationException(
                    "Transition '" + getId() + "' references id '" + registeredOperationRefId
                        + "' which is registered as a "
                        + component.getClass().getSimpleName().toLowerCase()
                        + ", not an action");
            }
            Class<?> opCtx = stateMachine.getDef().getComponentContextType(registeredOperationRefId);
            Class<?> txCtx = this.contextType != null ? this.contextType : Object.class;
            if (opCtx != null && opCtx != Object.class && !opCtx.isAssignableFrom(txCtx)) {
                throw new TransfluxValidationException(
                    "Transition '" + getId() + "' (context " + txCtx.getName()
                        + ") cannot attach SM-level operation '" + registeredOperationRefId
                        + "' (context " + opCtx.getName() + "): context types are not assignable");
            }
            return (BoundAction<T, C>) opComp.bound();
        }
        if (operationDef == null) {
            return null;
        }
        return operationDef.buildBound(stateMachine);
    }

    ActionDefImpl<T, C, ?> getOperationDef() {
        return operationDef;
    }

    /**
     * Returns the appended pre-condition descriptors in declaration order.
     *
     * @return an unmodifiable view of the pre-condition descriptor list
     */
    List<ConditionDescriptor> getPreConditionDescriptors() {
        return preConditions.descriptors();
    }

    /**
     * Returns the appended post-condition descriptors in declaration order.
     *
     * @return an unmodifiable view of the post-condition descriptor list
     */
    List<ConditionDescriptor> getPostConditionDescriptors() {
        return postConditions.descriptors();
    }

    /**
     * Resolves this transition's pre-condition descriptors into {@link BoundCondition}
     * instances against the supplied registry.
     *
     * @param registry the state machine's resolved condition registry, keyed by id
     *
     * @return an unmodifiable list of resolved bound pre-conditions, in declaration order
     *
     * @throws TransfluxValidationException if any descriptor cannot be resolved
     */
    List<BoundCondition<T, C>> buildBoundPreConditions(Map<String, BoundCondition<T, C>> registry) {
        return buildBoundConditionList(preConditions.descriptors(), registry, "pre");
    }

    /**
     * Resolves this transition's post-condition descriptors into {@link BoundCondition}
     * instances against the supplied registry.
     *
     * @param registry the state machine's resolved condition registry, keyed by id
     *
     * @return an unmodifiable list of resolved bound post-conditions, in declaration order
     *
     * @throws TransfluxValidationException if any descriptor cannot be resolved
     */
    List<BoundCondition<T, C>> buildBoundPostConditions(Map<String, BoundCondition<T, C>> registry) {
        return buildBoundConditionList(postConditions.descriptors(), registry, "post");
    }

    /**
     * Returns the manual triggers declared on this transition, in declaration order.
     *
     * @return the manual trigger defs
     */
    List<ManualTriggerDefImpl<T, C>> getManualTriggers() {
        return manualTriggers;
    }

    /**
     * Returns the event triggers declared on this transition, in declaration order.
     *
     * @return the event trigger defs
     */
    List<EventTriggerDefImpl<T, C>> getEventTriggers() {
        return eventTriggers;
    }

    /**
     * Returns the data triggers declared on this transition, in declaration order.
     *
     * @return the data trigger defs
     */
    List<DataTriggerDefImpl<T, C>> getDataTriggers() {
        return dataTriggers;
    }

    @Override
    public String toString() {
        return "TransitionDefImpl{" +
            "id='" + getId() + '\'' +
            ", sourceStateId='" + sourceStateId + '\'' +
            ", targetStateId='" + targetStateId + '\'' +
            '}';
    }

    @Override
    public TransitionDef<T, C> simpleOperation(String id, Action<T, C> operation) {
        requireConfigurerActive("simpleOperation");
        StepDefImpl<T, C> def = newSimpleOperationDef(id);
        ConfigurableDefImpl.runConfigurer(def, d -> d.using(operation));
        attachOperation(def);
        return this;
    }

    @Override
    public TransitionDef<T, C> simpleOperation(Identifiable operationIdentifiable, Action<T, C> operation) {
        requireNotNull(operationIdentifiable, "Operation identifiable");
        return simpleOperation(operationIdentifiable.getId(), operation);
    }

    @Override
    public TransitionDef<T, C> simpleOperation(String id, Class<? extends Action<T, C>> operationClass) {
        requireConfigurerActive("simpleOperation");
        StepDefImpl<T, C> def = newSimpleOperationDef(id);
        ConfigurableDefImpl.runConfigurer(def, d -> d.using(operationClass));
        attachOperation(def);
        return this;
    }

    @Override
    public TransitionDef<T, C> simpleOperation(Identifiable operationIdentifiable, Class<? extends Action<T, C>> operationClass) {
        requireNotNull(operationIdentifiable, "Operation identifiable");
        return simpleOperation(operationIdentifiable.getId(), operationClass);
    }

    @Override
    public TransitionDef<T, C> simpleOperation(String id, Consumer<StepDef<T, C>> configurer) {
        requireConfigurerActive("simpleOperation");
        requireNotNull(configurer, "Simple operation configurer");
        StepDefImpl<T, C> def = newSimpleOperationDef(id);
        ConfigurableDefImpl.runConfigurer(def, configurer);
        attachOperation(def);
        return this;
    }

    @Override
    public TransitionDef<T, C> simpleOperation(Identifiable operationIdentifiable, Consumer<StepDef<T, C>> configurer) {
        requireNotNull(operationIdentifiable, "Operation identifiable");
        return simpleOperation(operationIdentifiable.getId(), configurer);
    }

    @Override
    public TransitionDef<T, C> compositeOperation(String id, Consumer<OperationDef<T, C>> configurer) {
        requireConfigurerActive("compositeOperation");
        requireNotNull(configurer, "Composite operation configurer");
        OperationDefImpl<T, C> composite = new OperationDefImpl<>(id);
        ConfigurableDefImpl.runConfigurer(composite, configurer);
        attachOperation(composite);
        return this;
    }

    @Override
    public TransitionDef<T, C> compositeOperation(Identifiable operationIdentifiable, Consumer<OperationDef<T, C>> configurer) {
        requireNotNull(operationIdentifiable, "Operation identifiable");
        return compositeOperation(operationIdentifiable.getId(), configurer);
    }

    @Override
    public TransitionDef<T, C> operation(String registeredOperationId) {
        requireConfigurerActive("operation");
        requireNotBlank(registeredOperationId, "Operation reference ID");
        warnIfOperationSet();
        this.operationDef = null;
        this.registeredOperationRefId = registeredOperationId;
        return this;
    }

    @Override
    public TransitionDef<T, C> operation(Identifiable registeredOperation) {
        requireNotNull(registeredOperation, "Operation identifiable");
        return operation(registeredOperation.getId());
    }

    @Override
    public TransitionDef<T, C> preCondition(String registeredConditionId) {
        return preConditions.ref(registeredConditionId);
    }

    @Override
    public TransitionDef<T, C> preCondition(Identifiable registeredCondition) {
        return preConditions.ref(registeredCondition);
    }

    @Override
    public TransitionDef<T, C> preConditionExpression(String expression) {
        return preConditions.expression(expression);
    }

    @Override
    public TransitionDef<T, C> preCondition(String id, Condition<T, C> condition) {
        return preConditions.instanceBased(id, condition);
    }

    @Override
    public TransitionDef<T, C> preCondition(Identifiable conditionIdentifiable, Condition<T, C> condition) {
        return preConditions.instanceBased(conditionIdentifiable, condition);
    }

    @Override
    public TransitionDef<T, C> preCondition(String id, Class<? extends Condition<T, C>> conditionClass) {
        return preConditions.classBased(id, conditionClass);
    }

    @Override
    public TransitionDef<T, C> preCondition(Identifiable conditionIdentifiable, Class<? extends Condition<T, C>> conditionClass) {
        return preConditions.classBased(conditionIdentifiable, conditionClass);
    }

    @Override
    public TransitionDef<T, C> preCondition(String id, BiPredicate<T, C> predicate) {
        return preConditions.predicate(id, predicate);
    }

    @Override
    public TransitionDef<T, C> preCondition(Identifiable conditionIdentifiable, BiPredicate<T, C> predicate) {
        return preConditions.predicate(conditionIdentifiable, predicate);
    }

    @Override
    public TransitionDef<T, C> preCondition(String id, Predicate<T> predicate) {
        return preConditions.predicate(id, predicate);
    }

    @Override
    public TransitionDef<T, C> preCondition(Identifiable conditionIdentifiable, Predicate<T> predicate) {
        return preConditions.predicate(conditionIdentifiable, predicate);
    }

    @Override
    public TransitionDef<T, C> preCondition(String id, String expression) {
        return preConditions.expression(id, expression);
    }

    @Override
    public TransitionDef<T, C> preCondition(Identifiable conditionIdentifiable, String expression) {
        return preConditions.expression(conditionIdentifiable, expression);
    }

    @Override
    public TransitionDef<T, C> postCondition(String registeredConditionId) {
        return postConditions.ref(registeredConditionId);
    }

    @Override
    public TransitionDef<T, C> postCondition(Identifiable registeredCondition) {
        return postConditions.ref(registeredCondition);
    }

    @Override
    public TransitionDef<T, C> postConditionExpression(String expression) {
        return postConditions.expression(expression);
    }

    @Override
    public TransitionDef<T, C> postCondition(String id, Condition<T, C> condition) {
        return postConditions.instanceBased(id, condition);
    }

    @Override
    public TransitionDef<T, C> postCondition(Identifiable conditionIdentifiable, Condition<T, C> condition) {
        return postConditions.instanceBased(conditionIdentifiable, condition);
    }

    @Override
    public TransitionDef<T, C> postCondition(String id, Class<? extends Condition<T, C>> conditionClass) {
        return postConditions.classBased(id, conditionClass);
    }

    @Override
    public TransitionDef<T, C> postCondition(Identifiable conditionIdentifiable, Class<? extends Condition<T, C>> conditionClass) {
        return postConditions.classBased(conditionIdentifiable, conditionClass);
    }

    @Override
    public TransitionDef<T, C> postCondition(String id, BiPredicate<T, C> predicate) {
        return postConditions.predicate(id, predicate);
    }

    @Override
    public TransitionDef<T, C> postCondition(Identifiable conditionIdentifiable, BiPredicate<T, C> predicate) {
        return postConditions.predicate(conditionIdentifiable, predicate);
    }

    @Override
    public TransitionDef<T, C> postCondition(String id, Predicate<T> predicate) {
        return postConditions.predicate(id, predicate);
    }

    @Override
    public TransitionDef<T, C> postCondition(Identifiable conditionIdentifiable, Predicate<T> predicate) {
        return postConditions.predicate(conditionIdentifiable, predicate);
    }

    @Override
    public TransitionDef<T, C> postCondition(String id, String expression) {
        return postConditions.expression(id, expression);
    }

    @Override
    public TransitionDef<T, C> postCondition(Identifiable conditionIdentifiable, String expression) {
        return postConditions.expression(conditionIdentifiable, expression);
    }

    @Override
    public TransitionDef<T, C> addManualTrigger(String id) {
        requireConfigurerActive("addManualTrigger");
        requireNotBlank(id, "Trigger ID");
        manualTriggers.add(new ManualTriggerDefImpl<>(id, this));
        return this;
    }

    @Override
    public TransitionDef<T, C> addManualTrigger(Identifiable triggerIdentifiable) {
        requireNotNull(triggerIdentifiable, "Trigger identifiable");
        return addManualTrigger(triggerIdentifiable.getId());
    }

    @Override
    public TransitionDef<T, C> addManualTrigger(String id, Consumer<ManualTriggerDef<T, C>> configurer) {
        requireConfigurerActive("addManualTrigger");
        requireNotBlank(id, "Trigger ID");
        requireNotNull(configurer, "Manual trigger configurer");
        ManualTriggerDefImpl<T, C> trigger = new ManualTriggerDefImpl<>(id, this);
        ConfigurableDefImpl.runConfigurer(trigger, configurer);
        manualTriggers.add(trigger);
        return this;
    }

    @Override
    public TransitionDef<T, C> addManualTrigger(Identifiable triggerIdentifiable, Consumer<ManualTriggerDef<T, C>> configurer) {
        requireNotNull(triggerIdentifiable, "Trigger identifiable");
        return addManualTrigger(triggerIdentifiable.getId(), configurer);
    }

    @Override
    public TransitionDef<T, C> addEventTrigger(String id, String eventId) {
        requireConfigurerActive("addEventTrigger");
        requireNotBlank(id, "Trigger ID");
        requireNotBlank(eventId, "Event ID");
        EventTriggerDefImpl<T, C> trigger = new EventTriggerDefImpl<>(id, this);
        ConfigurableDefImpl.runConfigurer(trigger, t -> t.onEvent(eventId));
        eventTriggers.add(trigger);
        return this;
    }

    @Override
    public TransitionDef<T, C> addEventTrigger(Identifiable triggerIdentifiable, String eventId) {
        requireNotNull(triggerIdentifiable, "Trigger identifiable");
        return addEventTrigger(triggerIdentifiable.getId(), eventId);
    }

    @Override
    public TransitionDef<T, C> addEventTrigger(String id, Identifiable event) {
        requireNotNull(event, "Event identifiable");
        return addEventTrigger(id, event.getId());
    }

    @Override
    public TransitionDef<T, C> addEventTrigger(Identifiable triggerIdentifiable, Identifiable event) {
        requireNotNull(triggerIdentifiable, "Trigger identifiable");
        requireNotNull(event, "Event identifiable");
        return addEventTrigger(triggerIdentifiable.getId(), event.getId());
    }

    @Override
    public TransitionDef<T, C> addEventTrigger(Identifiable event) {
        requireNotNull(event, "Event identifiable");
        return addEventTrigger(event.getId(), event.getId());
    }

    @Override
    public TransitionDef<T, C> addEventTrigger(String id, Consumer<EventTriggerDef<T, C>> configurer) {
        requireConfigurerActive("addEventTrigger");
        requireNotBlank(id, "Trigger ID");
        requireNotNull(configurer, "Event trigger configurer");
        EventTriggerDefImpl<T, C> trigger = new EventTriggerDefImpl<>(id, this);
        ConfigurableDefImpl.runConfigurer(trigger, configurer);
        eventTriggers.add(trigger);
        return this;
    }

    @Override
    public TransitionDef<T, C> addEventTrigger(Identifiable triggerIdentifiable, Consumer<EventTriggerDef<T, C>> configurer) {
        requireNotNull(triggerIdentifiable, "Trigger identifiable");
        return addEventTrigger(triggerIdentifiable.getId(), configurer);
    }

    @Override
    public TransitionDef<T, C> addDataTrigger(String id, Consumer<DataTriggerDef<T, C>> configurer) {
        requireConfigurerActive("addDataTrigger");
        requireNotBlank(id, "Trigger ID");
        requireNotNull(configurer, "Data trigger configurer");
        DataTriggerDefImpl<T, C> trigger = new DataTriggerDefImpl<>(id, this);
        ConfigurableDefImpl.runConfigurer(trigger, configurer);
        dataTriggers.add(trigger);
        return this;
    }

    @Override
    public TransitionDef<T, C> addDataTrigger(Identifiable triggerIdentifiable, Consumer<DataTriggerDef<T, C>> configurer) {
        requireNotNull(triggerIdentifiable, "Trigger identifiable");
        return addDataTrigger(triggerIdentifiable.getId(), configurer);
    }

    @Override
    public TransitionDef<T, C> onStart(String listenerId, TransitionListener<T, C> listener) {
        requireConfigurerActive("onStart");
        requireNotBlank(listenerId, "Transition listener ID");
        requireNotNull(listener, "Transition listener");
        startListeners.add(declareListener(listenerId, l -> l.using(listener)));
        return this;
    }

    @Override
    public TransitionDef<T, C> onStart(Identifiable listenerIdentifiable, TransitionListener<T, C> listener) {
        requireNotNull(listenerIdentifiable, "Transition listener identifiable");
        return onStart(listenerIdentifiable.getId(), listener);
    }

    @Override
    public TransitionDef<T, C> onStart(String listenerId, Class<? extends TransitionListener<T, C>> listenerClass) {
        requireConfigurerActive("onStart");
        requireNotBlank(listenerId, "Transition listener ID");
        requireNotNull(listenerClass, "Transition listener class");
        startListeners.add(declareListener(listenerId, l -> l.using(listenerClass)));
        return this;
    }

    @Override
    public TransitionDef<T, C> onStart(Identifiable listenerIdentifiable,
                                       Class<? extends TransitionListener<T, C>> listenerClass) {
        requireNotNull(listenerIdentifiable, "Transition listener identifiable");
        return onStart(listenerIdentifiable.getId(), listenerClass);
    }

    @Override
    public TransitionDef<T, C> onStart(String listenerId, Consumer<TransitionListenerDef<T, C>> configurer) {
        requireConfigurerActive("onStart");
        requireNotBlank(listenerId, "Transition listener ID");
        requireNotNull(configurer, "Transition listener configurer");
        startListeners.add(declareListener(listenerId, configurer));
        return this;
    }

    @Override
    public TransitionDef<T, C> onStart(Identifiable listenerIdentifiable,
                                       Consumer<TransitionListenerDef<T, C>> configurer) {
        requireNotNull(listenerIdentifiable, "Transition listener identifiable");
        return onStart(listenerIdentifiable.getId(), configurer);
    }

    @Override
    public TransitionDef<T, C> onComplete(String listenerId, TransitionListener<T, C> listener) {
        requireConfigurerActive("onComplete");
        requireNotBlank(listenerId, "Transition listener ID");
        requireNotNull(listener, "Transition listener");
        completeListeners.add(declareListener(listenerId, l -> l.using(listener)));
        return this;
    }

    @Override
    public TransitionDef<T, C> onComplete(Identifiable listenerIdentifiable, TransitionListener<T, C> listener) {
        requireNotNull(listenerIdentifiable, "Transition listener identifiable");
        return onComplete(listenerIdentifiable.getId(), listener);
    }

    @Override
    public TransitionDef<T, C> onComplete(String listenerId, Class<? extends TransitionListener<T, C>> listenerClass) {
        requireConfigurerActive("onComplete");
        requireNotBlank(listenerId, "Transition listener ID");
        requireNotNull(listenerClass, "Transition listener class");
        completeListeners.add(declareListener(listenerId, l -> l.using(listenerClass)));
        return this;
    }

    @Override
    public TransitionDef<T, C> onComplete(Identifiable listenerIdentifiable,
                                          Class<? extends TransitionListener<T, C>> listenerClass) {
        requireNotNull(listenerIdentifiable, "Transition listener identifiable");
        return onComplete(listenerIdentifiable.getId(), listenerClass);
    }

    @Override
    public TransitionDef<T, C> onComplete(String listenerId, Consumer<TransitionListenerDef<T, C>> configurer) {
        requireConfigurerActive("onComplete");
        requireNotBlank(listenerId, "Transition listener ID");
        requireNotNull(configurer, "Transition listener configurer");
        completeListeners.add(declareListener(listenerId, configurer));
        return this;
    }

    @Override
    public TransitionDef<T, C> onComplete(Identifiable listenerIdentifiable,
                                          Consumer<TransitionListenerDef<T, C>> configurer) {
        requireNotNull(listenerIdentifiable, "Transition listener identifiable");
        return onComplete(listenerIdentifiable.getId(), configurer);
    }

    @Override
    public TransitionDef<T, C> onError(String listenerId, TransitionListener<T, C> listener) {
        requireConfigurerActive("onError");
        requireNotBlank(listenerId, "Transition listener ID");
        requireNotNull(listener, "Transition listener");
        errorListeners.add(declareListener(listenerId, l -> l.using(listener)));
        return this;
    }

    @Override
    public TransitionDef<T, C> onError(Identifiable listenerIdentifiable, TransitionListener<T, C> listener) {
        requireNotNull(listenerIdentifiable, "Transition listener identifiable");
        return onError(listenerIdentifiable.getId(), listener);
    }

    @Override
    public TransitionDef<T, C> onError(String listenerId, Class<? extends TransitionListener<T, C>> listenerClass) {
        requireConfigurerActive("onError");
        requireNotBlank(listenerId, "Transition listener ID");
        requireNotNull(listenerClass, "Transition listener class");
        errorListeners.add(declareListener(listenerId, l -> l.using(listenerClass)));
        return this;
    }

    @Override
    public TransitionDef<T, C> onError(Identifiable listenerIdentifiable,
                                       Class<? extends TransitionListener<T, C>> listenerClass) {
        requireNotNull(listenerIdentifiable, "Transition listener identifiable");
        return onError(listenerIdentifiable.getId(), listenerClass);
    }

    @Override
    public TransitionDef<T, C> onError(String listenerId, Consumer<TransitionListenerDef<T, C>> configurer) {
        requireConfigurerActive("onError");
        requireNotBlank(listenerId, "Transition listener ID");
        requireNotNull(configurer, "Transition listener configurer");
        errorListeners.add(declareListener(listenerId, configurer));
        return this;
    }

    @Override
    public TransitionDef<T, C> onError(Identifiable listenerIdentifiable,
                                       Consumer<TransitionListenerDef<T, C>> configurer) {
        requireNotNull(listenerIdentifiable, "Transition listener identifiable");
        return onError(listenerIdentifiable.getId(), configurer);
    }

    /**
     * Returns this transition's start listeners in declaration order.
     *
     * @return the live start-listener list
     */
    List<TransitionListenerDefImpl<T, C>> getStartListeners() {
        return startListeners;
    }

    /**
     * Returns this transition's completion listeners in declaration order.
     *
     * @return the live completion-listener list
     */
    List<TransitionListenerDefImpl<T, C>> getCompleteListeners() {
        return completeListeners;
    }

    /**
     * Returns this transition's error listeners in declaration order.
     *
     * @return the live error-listener list
     */
    List<TransitionListenerDefImpl<T, C>> getErrorListeners() {
        return errorListeners;
    }

    /**
     * Builds a listener def and runs its configurer. Unlike a state listener, the id is not
     * claimed here: a transition def holds no reference to the enclosing state machine def, so
     * the shared listener namespace is checked once the definition is built.
     */
    private TransitionListenerDefImpl<T, C> declareListener(String listenerId,
                                                            Consumer<TransitionListenerDef<T, C>> configurer) {
        TransitionListenerDefImpl<T, C> listenerDef = new TransitionListenerDefImpl<>(listenerId);
        ConfigurableDefImpl.runConfigurer(listenerDef, configurer);
        return listenerDef;
    }

    private StepDefImpl<T, C> newSimpleOperationDef(String operationId) {
        return new StepDefImpl<>(operationId);
    }

    private void attachOperation(ActionDefImpl<T, C, ?> def) {
        warnIfOperationSet();
        this.registeredOperationRefId = null;
        this.operationDef = def;
    }

    private void warnIfOperationSet() {
        if (this.operationDef != null || this.registeredOperationRefId != null) {
            log.warn("Operation is already defined for transition '{}'; overriding previous value", getId());
        }
    }

    private List<BoundCondition<T, C>> buildBoundConditionList(List<ConditionDescriptor> descriptors,
                                                               Map<String, BoundCondition<T, C>> registry,
                                                               String slot) {
        requireNotNull(registry, "Condition registry");
        if (descriptors.isEmpty()) {
            return Collections.emptyList();
        }
        List<BoundCondition<T, C>> bound = new ArrayList<>(descriptors.size());
        for (int i = 0; i < descriptors.size(); i++) {
            String path = "transition:" + getId() + ":" + slot + "[" + i + "]";
            bound.add(ConditionResolver.resolve(descriptors.get(i), registry, path));
        }
        return Collections.unmodifiableList(bound);
    }
}
