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

package org.transflux.core.transition;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.transflux.core.Identifiable;
import org.transflux.core.StateMachineImpl;
import org.transflux.core.condition.BoundCondition;
import org.transflux.core.condition.Condition;
import org.transflux.core.condition.ConditionDescriptor;
import org.transflux.core.condition.ConditionResolver;
import org.transflux.core.exception.TransfluxValidationException;
import org.transflux.core.operation.BoundOperation;
import org.transflux.core.operation.CompositeOperationDef;
import org.transflux.core.operation.CompositeOperationDefImpl;
import org.transflux.core.operation.Operation;
import org.transflux.core.operation.OperationDefImpl;
import org.transflux.core.operation.SimpleOperationDef;
import org.transflux.core.operation.SimpleOperationDefImpl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.transflux.core.ValidationUtils.requireNotBlank;
import static org.transflux.core.ValidationUtils.requireNotNull;

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
public class TransitionDefImpl<T, C> implements TransitionDef<T, C> {
    private static final Logger log = LoggerFactory.getLogger(TransitionDefImpl.class);

    private final String id;
    private final String sourceStateId;
    private final String targetStateId;

    private OperationDefImpl<T, C> operationDef;
    private Class<C> contextType;

    private String name;
    private String description;

    private final List<ConditionDescriptor> preConditions = new ArrayList<>();
    private final List<ConditionDescriptor> postConditions = new ArrayList<>();

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
    public TransitionDefImpl(String id, String sourceStateId, String targetStateId) {
        this(id, sourceStateId, targetStateId, (Class<C>) Object.class);
    }

    public TransitionDefImpl(String id, String sourceStateId, String targetStateId, Class<C> contextType) {
        requireNotBlank(id, "Transition ID");
        requireNotBlank(sourceStateId, "Source state ID");
        requireNotBlank(targetStateId, "Target state ID");
        requireNotNull(contextType, "Transition context type");

        this.id = id;
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
        requireNotNull(contextType, "Transition context type");
        if (this.contextType != null && this.contextType != Object.class && this.contextType != contextType) {
            log.warn("Transition '{}' context type already declared as {}; overriding with {}",
                this.id, this.contextType.getName(), contextType.getName());
        }
        this.contextType = (Class<C>) contextType;
        return (TransitionDef<T, C2>) this;
    }

    /**
     * Returns the unique identifier of this transition.
     *
     * @return the transition ID
     */
    @Override
    public String getId() {
        return id;
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
     * Package-private hook used by {@link TransitionImpl} to materialize the runtime
     * {@link BoundOperation}, or {@code null} when this transition has no operation attached.
     *
     * @param stateMachine the enclosing state machine; required by composite operations to
     *                     resolve step references against the step registry
     *
     * @return the bound operation, or {@code null}
     */
    public BoundOperation<T, C> buildBoundOperation(StateMachineImpl<T> stateMachine) {
        if (operationDef == null) {
            return null;
        }
        if (operationDef instanceof SimpleOperationDefImpl<T, C> simple) {
            return simple.build();
        }
        if (operationDef instanceof CompositeOperationDefImpl<T, C> composite) {
            return composite.build(stateMachine);
        }
        throw new TransfluxValidationException(
            "Unsupported operation def: " + operationDef.getClass().getName());
    }

    public OperationDefImpl<T, C> getOperationDef() {
        return operationDef;
    }

    /**
     * Returns the appended pre-condition descriptors in declaration order.
     *
     * @return an unmodifiable view of the pre-condition descriptor list
     */
    public List<ConditionDescriptor> getPreConditionDescriptors() {
        return Collections.unmodifiableList(preConditions);
    }

    /**
     * Returns the appended post-condition descriptors in declaration order.
     *
     * @return an unmodifiable view of the post-condition descriptor list
     */
    public List<ConditionDescriptor> getPostConditionDescriptors() {
        return Collections.unmodifiableList(postConditions);
    }

    /**
     * Resolves this transition's pre-condition descriptors into {@link BoundCondition}
     * instances against the supplied registry.
     *
     * <p>This is framework-internal infrastructure used by Transflux's own runtime; user code
     * should not invoke it directly.
     *
     * @param registry the state machine's resolved condition registry, keyed by id
     *
     * @return an unmodifiable list of resolved bound pre-conditions, in declaration order
     *
     * @throws TransfluxValidationException if any descriptor cannot be resolved
     */
    public List<BoundCondition<T, C>> buildBoundPreConditions(Map<String, BoundCondition<T, C>> registry) {
        return buildBoundConditionList(preConditions, registry, "pre");
    }

    /**
     * Resolves this transition's post-condition descriptors into {@link BoundCondition}
     * instances against the supplied registry.
     *
     * <p>This is framework-internal infrastructure used by Transflux's own runtime; user code
     * should not invoke it directly.
     *
     * @param registry the state machine's resolved condition registry, keyed by id
     *
     * @return an unmodifiable list of resolved bound post-conditions, in declaration order
     *
     * @throws TransfluxValidationException if any descriptor cannot be resolved
     */
    public List<BoundCondition<T, C>> buildBoundPostConditions(Map<String, BoundCondition<T, C>> registry) {
        return buildBoundConditionList(postConditions, registry, "post");
    }

    @Override
    public String toString() {
        return "TransitionDefImpl{" +
            "id='" + id + '\'' +
            ", sourceStateId='" + sourceStateId + '\'' +
            ", targetStateId='" + targetStateId + '\'' +
            '}';
    }

    @Override
    public TransitionDef<T, C> simpleOperation(String id, Operation<T, C> operation) {
        SimpleOperationDefImpl<T, C> def = newSimpleOperationDef(id);
        def.using(operation);
        attachOperation(def);
        return this;
    }

    @Override
    public TransitionDef<T, C> simpleOperation(String id, Class<? extends Operation<T, C>> operationClass) {
        SimpleOperationDefImpl<T, C> def = newSimpleOperationDef(id);
        def.using(operationClass);
        attachOperation(def);
        return this;
    }

    @Override
    public TransitionDef<T, C> simpleOperation(String id, Consumer<SimpleOperationDef<T, C>> configurer) {
        requireNotNull(configurer, "Simple operation configurer");
        SimpleOperationDefImpl<T, C> def = newSimpleOperationDef(id);
        configurer.accept(def);
        attachOperation(def);
        return this;
    }

    @Override
    public TransitionDef<T, C> compositeOperation(String id, Consumer<CompositeOperationDef<T, C>> configurer) {
        requireNotNull(configurer, "Composite operation configurer");
        CompositeOperationDefImpl<T, C> composite = new CompositeOperationDefImpl<>(id);
        configurer.accept(composite);
        attachOperation(composite);
        return this;
    }

    @Override
    public TransitionDef<T, C> step(String registeredStepId) {
        requireNotBlank(registeredStepId, "Step ID");
        CompositeOperationDefImpl<T, C> composite =
            new CompositeOperationDefImpl<>("transition-" + this.id + "-op");
        composite.step(registeredStepId);
        attachOperation(composite);
        return this;
    }

    @Override
    public TransitionDef<T, C> withName(String name) {
        if (this.name != null) {
            log.warn("Name is already defined for transition '{}': {}. Overriding previous value with {}",
                this.id, this.name, name);
        }
        this.name = name;
        return this;
    }

    @Override
    public TransitionDef<T, C> withDescription(String description) {
        if (this.description != null) {
            log.warn("Description is already defined for transition '{}': {}. Overriding previous value with {}",
                this.id, this.description, description);
        }
        this.description = description;
        return this;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    @Override
    public TransitionDef<T, C> preCondition(String registeredConditionId) {
        requireNotBlank(registeredConditionId, "Registered condition ID");
        return appendPreCondition(ConditionDescriptor.ref(registeredConditionId));
    }

    @Override
    public TransitionDef<T, C> preConditionExpression(String expression) {
        requireNotBlank(expression, "Expression");
        return appendPreCondition(ConditionDescriptor.expression(expression));
    }

    @Override
    public TransitionDef<T, C> preCondition(String id, Condition<T, C> condition) {
        requireNotBlank(id, "Condition ID");
        requireNotNull(condition, "Condition");
        return appendPreCondition(ConditionDescriptor.instanceBased(id, condition));
    }

    @Override
    public TransitionDef<T, C> preCondition(String id, Class<? extends Condition<T, C>> conditionClass) {
        requireNotBlank(id, "Condition ID");
        requireNotNull(conditionClass, "Condition class");
        return appendPreCondition(ConditionDescriptor.classBased(id, conditionClass));
    }

    @Override
    public TransitionDef<T, C> preCondition(String id, Predicate<T> predicate) {
        requireNotBlank(id, "Condition ID");
        requireNotNull(predicate, "Predicate");
        return appendPreCondition(ConditionDescriptor.predicate(id, predicate));
    }

    @Override
    public TransitionDef<T, C> preCondition(String id, String expression) {
        requireNotBlank(id, "Condition ID");
        requireNotBlank(expression, "Expression");
        return appendPreCondition(ConditionDescriptor.expression(id, expression));
    }

    @Override
    public TransitionDef<T, C> postCondition(String registeredConditionId) {
        requireNotBlank(registeredConditionId, "Registered condition ID");
        return appendPostCondition(ConditionDescriptor.ref(registeredConditionId));
    }

    @Override
    public TransitionDef<T, C> postConditionExpression(String expression) {
        requireNotBlank(expression, "Expression");
        return appendPostCondition(ConditionDescriptor.expression(expression));
    }

    @Override
    public TransitionDef<T, C> postCondition(String id, Condition<T, C> condition) {
        requireNotBlank(id, "Condition ID");
        requireNotNull(condition, "Condition");
        return appendPostCondition(ConditionDescriptor.instanceBased(id, condition));
    }

    @Override
    public TransitionDef<T, C> postCondition(String id, Class<? extends Condition<T, C>> conditionClass) {
        requireNotBlank(id, "Condition ID");
        requireNotNull(conditionClass, "Condition class");
        return appendPostCondition(ConditionDescriptor.classBased(id, conditionClass));
    }

    @Override
    public TransitionDef<T, C> postCondition(String id, Predicate<T> predicate) {
        requireNotBlank(id, "Condition ID");
        requireNotNull(predicate, "Predicate");
        return appendPostCondition(ConditionDescriptor.predicate(id, predicate));
    }

    @Override
    public TransitionDef<T, C> postCondition(String id, String expression) {
        requireNotBlank(id, "Condition ID");
        requireNotBlank(expression, "Expression");
        return appendPostCondition(ConditionDescriptor.expression(id, expression));
    }

    private TransitionDef<T, C> appendPreCondition(ConditionDescriptor descriptor) {
        preConditions.add(descriptor);
        return this;
    }

    private TransitionDef<T, C> appendPostCondition(ConditionDescriptor descriptor) {
        postConditions.add(descriptor);
        return this;
    }

    @Override
    public TransitionDef<T, C> addManualTrigger() {
        throw new UnsupportedOperationException("Triggers not yet implemented");
    }

    @Override
    public TransitionDef<T, C> addManualTrigger(String id) {
        throw new UnsupportedOperationException("Triggers not yet implemented");
    }

    @Override
    public TransitionDef<T, C> addEventTrigger(String id) {
        throw new UnsupportedOperationException("Triggers not yet implemented");
    }

    @Override
    public TransitionDef<T, C> addEventTrigger(String id, String eventId) {
        throw new UnsupportedOperationException("Triggers not yet implemented");
    }

    @Override
    public TransitionDef<T, C> addEventTrigger(Identifiable event) {
        throw new UnsupportedOperationException("Triggers not yet implemented");
    }

    @Override
    public TransitionDef<T, C> addEventTrigger(String id, Identifiable event) {
        throw new UnsupportedOperationException("Triggers not yet implemented");
    }

    @Override
    public TransitionDef<T, C> addEventTrigger(BiPredicate<String, T> condition) {
        throw new UnsupportedOperationException("Triggers not yet implemented");
    }

    @Override
    public TransitionDef<T, C> addEventTrigger(String id, BiPredicate<String, T> condition) {
        throw new UnsupportedOperationException("Triggers not yet implemented");
    }

    @Override
    public TransitionDef<T, C> addDataTrigger(String id) {
        throw new UnsupportedOperationException("Triggers not yet implemented");
    }

    @Override
    public TransitionDef<T, C> addDataTrigger(Predicate<T> condition) {
        throw new UnsupportedOperationException("Triggers not yet implemented");
    }

    @Override
    public TransitionDef<T, C> addDataTrigger(String id, Predicate<T> condition) {
        throw new UnsupportedOperationException("Triggers not yet implemented");
    }

    private SimpleOperationDefImpl<T, C> newSimpleOperationDef(String operationId) {
        return new SimpleOperationDefImpl<>(operationId);
    }

    private void attachOperation(OperationDefImpl<T, C> def) {
        if (this.operationDef != null) {
            log.warn("Operation is already defined for transition '{}'; overriding previous value", this.id);
        }
        this.operationDef = def;
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
            String path = "transition:" + id + ":" + slot + "[" + i + "]";
            bound.add(ConditionResolver.resolve(descriptors.get(i), registry, path));
        }
        return Collections.unmodifiableList(bound);
    }
}
