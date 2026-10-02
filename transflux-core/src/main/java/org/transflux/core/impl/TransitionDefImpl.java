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

import org.transflux.core.condition.Condition;
import org.transflux.core.condition.ConditionDescriptor;
import org.transflux.core.exception.TransfluxValidationException;
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
class TransitionDefImpl<T, C> extends IdentifiedDefImpl<TransitionDefImpl<T, C>>
    implements TransitionDef<T, C>, ActionSequenceDelegate<T, C, TransitionDef<T, C>> {
    private final String sourceStateId;
    private final String targetStateId;

    /**
     * The ordered member list this transition runs. Every member form is delegated to it, and it
     * answers every build hook the enclosing passes drive, so a transition's body is a container
     * in all but name - the name being the point, since it reports itself as the transition.
     */
    private final OperationDefImpl<T, C> body;

    private final Class<C> contextType;

    private final ConditionDescriptorSink<T, C, TransitionDef<T, C>> preConditions =
        new ConditionDescriptorSink<>(this, this, "preCondition");
    private final ConditionDescriptorSink<T, C, TransitionDef<T, C>> postConditions =
        new ConditionDescriptorSink<>(this, this, "postCondition");
    private final List<String> triggerRefs = new ArrayList<>();
    private final List<ManualTriggerDefImpl<T, C>> manualTriggers = new ArrayList<>();
    private final List<EventTriggerDefImpl<T, C>> eventTriggers = new ArrayList<>();
    private final List<DataTriggerDefImpl<T, C>> dataTriggers = new ArrayList<>();
    private final List<ListenerEntry<TransitionListenerDefImpl<T, C>>> startListeners = new ArrayList<>();
    private final List<ListenerEntry<TransitionListenerDefImpl<T, C>>> completeListeners = new ArrayList<>();
    private final List<ListenerEntry<TransitionListenerDefImpl<T, C>>> errorListeners = new ArrayList<>();

    private final GlobalListenerDisables disabledGlobals = new GlobalListenerDisables(this);

    private final InPlaceListenerIds inPlaceListenerIds;

    /**
     * Constructs a transition belonging to a definition.
     *
     * @param smd the definition it belongs to
     * @param id the unique identifier for this transition
     * @param sourceStateId the ID of the source state
     * @param targetStateId the ID of the target state
     * @param contextType the transition's context class
     *
     * @throws TransfluxValidationException if any parameter is null or blank
     */
    TransitionDefImpl(StateMachineDefImpl<T> smd, String id, String sourceStateId, String targetStateId,
                      Class<C> contextType) {
        super(id, "transition", "Transition ID");
        requireNotNull(smd, "State machine definition");
        requireNotBlank(sourceStateId, "Source state ID");
        requireNotBlank(targetStateId, "Target state ID");
        requireNotNull(contextType, "Transition context type");

        this.sourceStateId = sourceStateId;
        this.targetStateId = targetStateId;
        this.contextType = contextType;
        this.inPlaceListenerIds = new InPlaceListenerIds(smd, "transition '" + id + "'");
        this.body = OperationDefImpl.transitionBody(id);
    }

    /**
     * Opens the body's configurer alongside this def's, because the member grammar's guard is the
     * body's: {@code t.step(...)} reaches {@code ActionSequenceSink}, which asks the def that owns
     * the sink whether its configurer is running. Flipping both keeps one rule - a def is inert
     * once its lambda returns - covering the transition and the members declared on it alike.
     */
    @Override
    void beginConfigurer() {
        super.beginConfigurer();
        body.beginConfigurer();
    }

    @Override
    void endConfigurer() {
        body.endConfigurer();
        super.endConfigurer();
    }

    @Override
    public Class<C> getContextType() {
        return contextType;
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
     * Package-private hook used by {@link BoundTransition} to materialize the runtime executable
     * for this transition's body, or {@code null} when nothing was declared on it.
     *
     * @return the bound body, or {@code null}
     */
    BoundAction<T, C> buildBoundAction() {
        return body.hasMembers() ? body.buildBound() : null;
    }

    /**
     * Returns the body as the def the build passes walk, or {@code null} when it holds no members.
     * <p>
     * Reporting {@code null} for an empty body is what lets every one of those passes keep the
     * shape it had when a transition carried at most one action: each skips a transition that
     * declared none, and a body with nothing in it is that case. The members are settled by the
     * time any of them runs, since all of them run after the configurer has returned.
     *
     * @return the body, or {@code null}
     */
    ActionDefImpl<T, C, ?> getActionDef() {
        return body.hasMembers() ? body : null;
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
     * @param evaluator the evaluator expression conditions bind through
     *
     * @return an unmodifiable list of resolved bound pre-conditions, in declaration order
     *
     * @throws TransfluxValidationException if any descriptor cannot be resolved
     */
    List<BoundCondition<T, C>> buildBoundPreConditions(Map<String, BoundCondition<T, C>> registry,
                                                       SpelConditionEvaluator evaluator) {
        return buildBoundConditionList(preConditions.descriptors(), registry, "pre", evaluator);
    }

    /**
     * Resolves this transition's post-condition descriptors into {@link BoundCondition}
     * instances against the supplied registry.
     *
     * @param registry the state machine's resolved condition registry, keyed by id
     * @param evaluator the evaluator expression conditions bind through
     *
     * @return an unmodifiable list of resolved bound post-conditions, in declaration order
     *
     * @throws TransfluxValidationException if any descriptor cannot be resolved
     */
    List<BoundCondition<T, C>> buildBoundPostConditions(Map<String, BoundCondition<T, C>> registry,
                                                        SpelConditionEvaluator evaluator) {
        return buildBoundConditionList(postConditions.descriptors(), registry, "post", evaluator);
    }

    /**
     * Returns every trigger declared on this transition, in the order dispatch scans them: manual,
     * then event, then data, each in declaration order.
     *
     * @return the trigger defs
     */
    List<TriggerDefImpl<T, C, ?>> getDeclaredTriggers() {
        List<TriggerDefImpl<T, C, ?>> declared = new ArrayList<>(manualTriggers);
        declared.addAll(eventTriggers);
        declared.addAll(dataTriggers);
        return declared;
    }

    /**
     * Returns the ids of the registered triggers this transition attaches, in declaration order.
     * Dispatch scans a transition's own declarations before these.
     *
     * @return the attached trigger ids; never {@code null}
     */
    List<String> getTriggerRefs() {
        return triggerRefs;
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
    public ActionSequenceSink<T, C> sequenceSink() {
        return body.sequenceSink();
    }

    @Override
    public TransitionDef<T, C> sequenceSelf() {
        return this;
    }

    @Override
    public TransitionDef<T, C> preCondition(String registeredConditionId) {
        return preConditions.ref(registeredConditionId);
    }

    @Override
    public TransitionDef<T, C> preConditionExpression(String expression) {
        return preConditions.expression(expression);
    }

    @Override
    public TransitionDef<T, C> preCondition(String id, Condition<? super T, C> condition) {
        return preConditions.instanceBased(id, condition);
    }

    @Override
    public TransitionDef<T, C> preCondition(String id, BiPredicate<? super T, C> predicate) {
        return preConditions.predicate(id, predicate);
    }

    @Override
    public TransitionDef<T, C> preCondition(String id, Predicate<? super T> predicate) {
        return preConditions.predicate(id, predicate);
    }

    @Override
    public TransitionDef<T, C> preCondition(String id, String expression) {
        return preConditions.expression(id, expression);
    }

    @Override
    public TransitionDef<T, C> postCondition(String registeredConditionId) {
        return postConditions.ref(registeredConditionId);
    }

    @Override
    public TransitionDef<T, C> postConditionExpression(String expression) {
        return postConditions.expression(expression);
    }

    @Override
    public TransitionDef<T, C> postCondition(String id, Condition<? super T, C> condition) {
        return postConditions.instanceBased(id, condition);
    }

    @Override
    public TransitionDef<T, C> postCondition(String id, BiPredicate<? super T, C> predicate) {
        return postConditions.predicate(id, predicate);
    }

    @Override
    public TransitionDef<T, C> postCondition(String id, Predicate<? super T> predicate) {
        return postConditions.predicate(id, predicate);
    }

    @Override
    public TransitionDef<T, C> postCondition(String id, String expression) {
        return postConditions.expression(id, expression);
    }

    @Override
    public TransitionDef<T, C> addTrigger(String triggerId) {
        requireConfigurerActive("addTrigger");
        requireNotBlank(triggerId, "Trigger ID");
        triggerRefs.add(triggerId);
        return this;
    }

    @Override
    public TransitionDef<T, C> addManualTrigger(String id) {
        requireConfigurerActive("addManualTrigger");
        requireNotBlank(id, "Trigger ID");
        manualTriggers.add(new ManualTriggerDefImpl<>(id, this));
        return this;
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
    public TransitionDef<T, C> addEventTrigger(String eventId) {
        return addEventTrigger(eventId, eventId);
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
    public TransitionDef<T, C> onStart(String listenerId) {
        requireConfigurerActive("onStart");
        requireNotBlank(listenerId, "Transition listener ID");
        startListeners.add(ListenerEntry.reference(listenerId));
        return this;
    }

    @Override
    public TransitionDef<T, C> onComplete(String listenerId) {
        requireConfigurerActive("onComplete");
        requireNotBlank(listenerId, "Transition listener ID");
        completeListeners.add(ListenerEntry.reference(listenerId));
        return this;
    }

    @Override
    public TransitionDef<T, C> onError(String listenerId) {
        requireConfigurerActive("onError");
        requireNotBlank(listenerId, "Transition listener ID");
        errorListeners.add(ListenerEntry.reference(listenerId));
        return this;
    }

    @Override
    public TransitionDef<T, C> onStart(String listenerId, TransitionListener<? super T, C> listener) {
        requireConfigurerActive("onStart");
        requireNotBlank(listenerId, "Transition listener ID");
        requireNotNull(listener, "Transition listener");
        startListeners.add(declare(listenerId, "onStart", l -> l.using(listener)));
        return this;
    }

    @Override
    public TransitionDef<T, C> onStart(String listenerId, Consumer<TransitionListenerDef<T, C>> configurer) {
        requireConfigurerActive("onStart");
        requireNotBlank(listenerId, "Transition listener ID");
        requireNotNull(configurer, "Transition listener configurer");
        startListeners.add(declare(listenerId, "onStart", configurer));
        return this;
    }

    @Override
    public TransitionDef<T, C> onComplete(String listenerId, TransitionListener<? super T, C> listener) {
        requireConfigurerActive("onComplete");
        requireNotBlank(listenerId, "Transition listener ID");
        requireNotNull(listener, "Transition listener");
        completeListeners.add(declare(listenerId, "onComplete", l -> l.using(listener)));
        return this;
    }

    @Override
    public TransitionDef<T, C> onComplete(String listenerId, Consumer<TransitionListenerDef<T, C>> configurer) {
        requireConfigurerActive("onComplete");
        requireNotBlank(listenerId, "Transition listener ID");
        requireNotNull(configurer, "Transition listener configurer");
        completeListeners.add(declare(listenerId, "onComplete", configurer));
        return this;
    }

    @Override
    public TransitionDef<T, C> onError(String listenerId, TransitionListener<? super T, C> listener) {
        requireConfigurerActive("onError");
        requireNotBlank(listenerId, "Transition listener ID");
        requireNotNull(listener, "Transition listener");
        errorListeners.add(declare(listenerId, "onError", l -> l.using(listener)));
        return this;
    }

    @Override
    public TransitionDef<T, C> onError(String listenerId, Consumer<TransitionListenerDef<T, C>> configurer) {
        requireConfigurerActive("onError");
        requireNotBlank(listenerId, "Transition listener ID");
        requireNotNull(configurer, "Transition listener configurer");
        errorListeners.add(declare(listenerId, "onError", configurer));
        return this;
    }

    /**
     * Returns this transition's start listeners in declaration order.
     *
     * @return the live start-listener list
     */
    List<ListenerEntry<TransitionListenerDefImpl<T, C>>> getStartListeners() {
        return startListeners;
    }

    /**
     * Returns this transition's completion listeners in declaration order.
     *
     * @return the live completion-listener list
     */
    List<ListenerEntry<TransitionListenerDefImpl<T, C>>> getCompleteListeners() {
        return completeListeners;
    }

    /**
     * Returns this transition's error listeners in declaration order.
     *
     * @return the live error-listener list
     */
    List<ListenerEntry<TransitionListenerDefImpl<T, C>>> getErrorListeners() {
        return errorListeners;
    }

    @Override
    public TransitionDefImpl<T, C> disableGlobalListener(String listenerId) {
        disabledGlobals.disable(listenerId);
        return this;
    }

    @Override
    public TransitionDefImpl<T, C> disableGlobalListeners(String... listenerIds) {
        disabledGlobals.disable(listenerIds);
        return this;
    }

    @Override
    public TransitionDefImpl<T, C> disableAllGlobalListeners() {
        disabledGlobals.disableAll();
        return this;
    }

    /**
     * Returns what this transition turned off among the state-machine-wide transition listeners.
     *
     * @return this transition's declaration; never {@code null}
     */
    GlobalListenerDisables getDisabledGlobals() {
        return disabledGlobals;
    }

    /**
     * Returns the listener ids this transition declares in place, for the state machine to claim
     * when the transition registers.
     *
     * @return the ids, with where each is declared
     */
    InPlaceListenerIds getInPlaceListenerIds() {
        return inPlaceListenerIds;
    }

    /**
     * Declares a listener in place at one of this transition's hooks.
     *
     * @param listenerId the listener's id
     * @param hook the hook's DSL method, such as {@code onStart}
     * @param configurer what declares the listener
     *
     * @return the hook entry
     *
     * @throws TransfluxValidationException if the id is already taken
     */
    private ListenerEntry<TransitionListenerDefImpl<T, C>> declare(
            String listenerId, String hook, Consumer<TransitionListenerDef<T, C>> configurer) {
        TransitionListenerDefImpl<T, C> listenerDef = new TransitionListenerDefImpl<>(listenerId);
        ConfigurableDefImpl.runConfigurer(listenerDef, configurer);
        inPlaceListenerIds.declare(listenerId, hook);
        return ListenerEntry.declared(listenerId, listenerDef);
    }

    private List<BoundCondition<T, C>> buildBoundConditionList(List<ConditionDescriptor> descriptors,
                                                               Map<String, BoundCondition<T, C>> registry,
                                                               String slot, SpelConditionEvaluator evaluator) {
        requireNotNull(registry, "Condition registry");
        if (descriptors.isEmpty()) {
            return Collections.emptyList();
        }
        List<BoundCondition<T, C>> bound = new ArrayList<>(descriptors.size());
        for (int i = 0; i < descriptors.size(); i++) {
            String path = "transition:" + getId() + ":" + slot + "[" + i + "]";
            bound.add(ConditionResolver.resolve(descriptors.get(i), registry, path, evaluator));
        }
        return Collections.unmodifiableList(bound);
    }
}
