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
import org.transflux.core.condition.Condition;
import org.transflux.core.exception.TransfluxValidationException;
import org.transflux.core.operation.BoundOperation;
import org.transflux.core.operation.BoundStep;
import org.transflux.core.operation.CompositeOperationDef;
import org.transflux.core.operation.CompositeOperationDefImpl;
import org.transflux.core.operation.ConditionalStepDefImpl;
import org.transflux.core.operation.Operation;
import org.transflux.core.operation.OperationDefImpl;
import org.transflux.core.operation.Step;
import org.transflux.core.state.StateApplier;
import org.transflux.core.state.StateDef;
import org.transflux.core.state.StateDefImpl;
import org.transflux.core.state.StateResolver;
import org.transflux.core.transition.TransitionDef;
import org.transflux.core.transition.TransitionDefImpl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static java.util.Objects.requireNonNullElseGet;
import static org.transflux.core.ReflectionUtils.instantiateNoArg;
import static org.transflux.core.ValidationUtils.requireNotBlank;
import static org.transflux.core.ValidationUtils.requireNotNull;
import static org.transflux.core.ValidationUtils.warnIfSet;

/**
 * Builder class for defining and constructing state machines.
 *
 * @param <T> the type of entity managed by the state machine being defined
 */
public class StateMachineDefImpl<T> implements StateMachineDef<T> {
    private static final Logger log = LoggerFactory.getLogger(StateMachineDefImpl.class);

    private Class<T> entityType;
    private String name;
    private String description;
    private String version;

    private StateResolver<T> stateResolver;
    private StateApplier<T> stateApplier;

    private final Map<String, StateDefImpl<T>> states = new LinkedHashMap<>();

    private final Map<String, StepRegistration<T>> stepRegistrations = new LinkedHashMap<>();

    private final Map<String, ConditionRegistration<T>> conditionRegistrations = new LinkedHashMap<>();

    private final Map<String, OperationRegistration<T>> operationRegistrations = new LinkedHashMap<>();

    private final Map<String, ConditionalStepDefImpl<T, ?>> conditionalStepRegistrations = new LinkedHashMap<>();

    private final Map<String, CompositeOperationDefImpl<T, ?>> smCompositeOperations = new LinkedHashMap<>();

    private final Map<String, Class<?>> componentContextTypes = new LinkedHashMap<>();

    private final Map<String, TransitionDefImpl<T, ?>> transitionsById = new LinkedHashMap<>();
    private final Map<String, Map<String, List<TransitionDefImpl<T, ?>>>> transitionsBySourceTarget = new LinkedHashMap<>();

    public StateMachineDefImpl() {
    }

    @Override
    public StateMachineDef<T> forEntityType(Class<T> entityType) {
        requireNotNull(entityType, "Entity type");
        this.entityType = entityType;
        return this;
    }

    @Override
    public StateMachineDef<T> withName(String name) {
        warnIfSet(this.name, name, "Name", log);

        this.name = name;
        return this;
    }

    @Override
    public StateMachineDef<T> withDescription(String description) {
        warnIfSet(this.description, description, "Description", log);

        this.description = description;
        return this;
    }

    @Override
    public StateMachineDef<T> withVersion(String version) {
        warnIfSet(this.version, version, "Version", log);

        this.version = version;
        return this;
    }

    @Override
    public StateMachineDef<T> withStateResolver(StateResolver<T> stateResolver) {
        requireNotNull(stateResolver, "State resolver");

        if (this.stateResolver != null) {
            log.warn("State resolver is already defined: {}. Overriding previous value with {}",
                               this.stateResolver.getClass().getName(), stateResolver.getClass().getName());
        }

        this.stateResolver = stateResolver;
        return this;
    }

    @Override
    public StateMachineDef<T> step(String id, Step<T, ?> step) {
        requireNotBlank(id, "Step ID");
        requireNotNull(step, "Step");
        registerStepInstance(id, step);
        return this;
    }

    @Override
    public StateMachineDef<T> step(String id, Class<? extends Step<T, ?>> stepClass) {
        requireNotBlank(id, "Step ID");
        requireNotNull(stepClass, "Step class");
        registerStepClass(id, stepClass);
        return this;
    }

    @Override
    public <C> StateMachineDef<T> step(String id, Class<C> contextType, Step<T, C> step) {
        requireNotBlank(id, "Step ID");
        requireNotNull(contextType, "Context type");
        requireNotNull(step, "Step");
        registerStepInstance(id, step);
        tagContextType(id, contextType);
        return this;
    }

    @Override
    public <C> StateMachineDef<T> step(String id, Class<C> contextType, Class<? extends Step<T, C>> stepClass) {
        requireNotBlank(id, "Step ID");
        requireNotNull(contextType, "Context type");
        requireNotNull(stepClass, "Step class");
        registerStepClass(id, stepClass);
        tagContextType(id, contextType);
        return this;
    }

    private void registerStepInstance(String id, Step<T, ?> step) {
        StepRegistration<T> existing = stepRegistrations.get(id);
        if (existing == null) {
            checkIdNotRegisteredAsOperation(id);
            stepRegistrations.put(id, StepRegistration.ofInstance(step));
            return;
        }

        if (existing.instance != null && existing.instance == step) {
            return;
        }

        throw new TransfluxValidationException("Step ID '" + id + "' is already registered");
    }

    private void registerStepClass(String id, Class<? extends Step<T, ?>> stepClass) {
        StepRegistration<T> existing = stepRegistrations.get(id);
        if (existing == null) {
            checkIdNotRegisteredAsOperation(id);
            stepRegistrations.put(id, StepRegistration.ofClass(stepClass));
            return;
        }

        if (existing.stepClass != null && existing.stepClass.equals(stepClass)) {
            return;
        }

        throw new TransfluxValidationException("Step ID '" + id + "' is already registered");
    }

    private void checkIdNotRegisteredAsOperation(String id) {
        if (operationRegistrations.containsKey(id)) {
            throw new TransfluxValidationException(
                "ID '" + id + "' is already registered as an operation");
        }
    }

    private void checkIdNotRegisteredAsStep(String id) {
        if (stepRegistrations.containsKey(id)) {
            throw new TransfluxValidationException(
                "ID '" + id + "' is already registered as a step");
        }
        if (conditionalStepRegistrations.containsKey(id)) {
            throw new TransfluxValidationException(
                "ID '" + id + "' is already registered as a conditional step");
        }
    }

    private void registerOperationInstance(String id, Operation<T, ?> operation) {
        OperationRegistration<T> existing = operationRegistrations.get(id);
        if (existing == null) {
            checkIdNotRegisteredAsStep(id);
            operationRegistrations.put(id, OperationRegistration.ofInstance(operation));
            return;
        }

        if (existing.instance != null && existing.instance == operation) {
            return;
        }

        throw new TransfluxValidationException("Operation ID '" + id + "' is already registered");
    }

    private void registerOperationClass(String id, Class<? extends Operation<T, ?>> operationClass) {
        OperationRegistration<T> existing = operationRegistrations.get(id);
        if (existing == null) {
            checkIdNotRegisteredAsStep(id);
            operationRegistrations.put(id, OperationRegistration.ofClass(operationClass));
            return;
        }

        if (existing.operationClass != null && existing.operationClass.equals(operationClass)) {
            return;
        }

        throw new TransfluxValidationException("Operation ID '" + id + "' is already registered");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void registerInlineOperations(Map<String, ? extends Operation<T, ?>> instances,
                                          Map<String, ? extends Class<? extends Operation<T, ?>>> classes) {
        for (Map.Entry<String, ? extends Operation<T, ?>> e : instances.entrySet()) {
            registerOperationInstance(e.getKey(), e.getValue());
        }

        for (Map.Entry<String, ? extends Class<? extends Operation<T, ?>>> e : classes.entrySet()) {
            registerOperationClass(e.getKey(), e.getValue());
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void collectInlineMemberRegistrations() {
        for (TransitionDefImpl<T, ?> td : transitionsById.values()) {
            OperationDefImpl<T, ?> op = td.getOperationDef();
            if (op instanceof CompositeOperationDefImpl<T, ?> composite) {
                walkCompositeForInlineMembers(composite);
            }
        }
        for (CompositeOperationDefImpl<T, ?> composite : smCompositeOperations.values()) {
            walkCompositeForInlineMembers(composite);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void walkCompositeForInlineMembers(CompositeOperationDefImpl<T, ?> composite) {
        registerInlineSteps((Map) composite.getInlineStepInstances(),
                            (Map) composite.getInlineStepClasses());
        registerInlineOperations((Map) composite.getInlineOperationInstances(),
                                 (Map) composite.getInlineOperationClasses());

        for (Map.Entry<String, ?> e : composite.getConditionalDefs().entrySet()) {
            String conditionalId = e.getKey();
            ConditionalStepDefImpl<T, ?> conditional = (ConditionalStepDefImpl<T, ?>) e.getValue();

            registerInlineSteps((Map) conditional.getInlineStepInstances(),
                                (Map) conditional.getInlineStepClasses());

            ConditionalStepDefImpl<T, ?> existing = conditionalStepRegistrations.get(conditionalId);
            if (existing != null && existing != conditional) {
                throw new TransfluxValidationException(
                    "Conditional step ID '" + conditionalId + "' is already registered");
            }

            if (stepRegistrations.containsKey(conditionalId)) {
                throw new TransfluxValidationException(
                    "Step ID '" + conditionalId + "' is already registered");
            }
            if (operationRegistrations.containsKey(conditionalId)) {
                throw new TransfluxValidationException(
                    "ID '" + conditionalId + "' is already registered as an operation");
            }
            conditionalStepRegistrations.put(conditionalId, conditional);
        }
    }

    private void registerInlineSteps(Map<String, Step<T, ?>> instances,
                                     Map<String, Class<? extends Step<T, ?>>> classes) {
        for (Map.Entry<String, Step<T, ?>> e : instances.entrySet()) {
            registerStepInstance(e.getKey(), e.getValue());
        }

        for (Map.Entry<String, Class<? extends Step<T, ?>>> e : classes.entrySet()) {
            registerStepClass(e.getKey(), e.getValue());
        }
    }

    @Override
    public StateMachineDef<T> condition(String id, Condition<T, ?> condition) {
        requireNotBlank(id, "Condition ID");
        requireNotNull(condition, "Condition");
        registerConditionInstance(id, condition);
        return this;
    }

    @Override
    public StateMachineDef<T> condition(String id, Class<? extends Condition<T, ?>> conditionClass) {
        requireNotBlank(id, "Condition ID");
        requireNotNull(conditionClass, "Condition class");
        registerConditionClass(id, conditionClass);
        return this;
    }

    @Override
    public StateMachineDef<T> condition(String id, Predicate<T> predicate) {
        requireNotBlank(id, "Condition ID");
        requireNotNull(predicate, "Predicate");
        registerConditionPredicate(id, predicate);
        return this;
    }

    @Override
    public StateMachineDef<T> condition(String id, String spelExpression) {
        requireNotBlank(id, "Condition ID");
        requireNotBlank(spelExpression, "SpEL expression");
        registerConditionExpression(id, spelExpression);
        return this;
    }

    @Override
    public <C> StateMachineDef<T> condition(String id, Class<C> contextType, Condition<T, C> condition) {
        requireNotBlank(id, "Condition ID");
        requireNotNull(contextType, "Context type");
        requireNotNull(condition, "Condition");
        registerConditionInstance(id, condition);
        tagContextType(id, contextType);
        return this;
    }

    @Override
    public <C> StateMachineDef<T> condition(String id, Class<C> contextType, Class<? extends Condition<T, C>> conditionClass) {
        requireNotBlank(id, "Condition ID");
        requireNotNull(contextType, "Context type");
        requireNotNull(conditionClass, "Condition class");
        registerConditionClass(id, conditionClass);
        tagContextType(id, contextType);
        return this;
    }

    @Override
    public <C> StateMachineDef<T> conditionPredicate(String id, Class<C> contextType, Predicate<T> predicate) {
        requireNotBlank(id, "Condition ID");
        requireNotNull(contextType, "Context type");
        requireNotNull(predicate, "Predicate");
        registerConditionPredicate(id, predicate);
        tagContextType(id, contextType);
        return this;
    }

    @Override
    public <C> StateMachineDef<T> conditionExpression(String id, Class<C> contextType, String spelExpression) {
        requireNotBlank(id, "Condition ID");
        requireNotNull(contextType, "Context type");
        requireNotBlank(spelExpression, "SpEL expression");
        registerConditionExpression(id, spelExpression);
        tagContextType(id, contextType);
        return this;
    }

    @Override
    public <C> StateMachineDef<T> compositeOperation(String id, Class<C> contextType, Consumer<CompositeOperationDef<T, C>> configurer) {
        registerScopedCompositeOperation(id, configurer, contextType);
        return this;
    }

    private void registerConditionInstance(String id, Condition<T, ?> condition) {
        ConditionRegistration<T> existing = conditionRegistrations.get(id);
        if (existing == null) {
            conditionRegistrations.put(id, ConditionRegistration.ofInstance(condition));
            return;
        }

        if (existing.instance != null && existing.instance == condition) {
            return;
        }

        throw new TransfluxValidationException("Condition ID '" + id + "' is already registered");
    }

    private void registerConditionClass(String id, Class<? extends Condition<T, ?>> conditionClass) {
        ConditionRegistration<T> existing = conditionRegistrations.get(id);
        if (existing == null) {
            conditionRegistrations.put(id, ConditionRegistration.ofClass(conditionClass));
            return;
        }

        if (existing.conditionClass != null && existing.conditionClass.equals(conditionClass)) {
            return;
        }

        throw new TransfluxValidationException("Condition ID '" + id + "' is already registered");
    }

    private void registerConditionPredicate(String id, Predicate<T> predicate) {
        ConditionRegistration<T> existing = conditionRegistrations.get(id);
        if (existing == null) {
            conditionRegistrations.put(id, ConditionRegistration.ofPredicate(predicate));
            return;
        }

        if (existing.predicate != null && existing.predicate == predicate) {
            return;
        }

        throw new TransfluxValidationException("Condition ID '" + id + "' is already registered");
    }

    private void registerConditionExpression(String id, String expression) {
        ConditionRegistration<T> existing = conditionRegistrations.get(id);
        if (existing == null) {
            conditionRegistrations.put(id, ConditionRegistration.ofExpression(expression));
            return;
        }

        if (existing.expression != null && existing.expression.equals(expression)) {
            return;
        }

        throw new TransfluxValidationException("Condition ID '" + id + "' is already registered");
    }

    /**
     * Resolves the condition registrations into {@link BoundCondition} instances. Called from
     * {@link StateMachineImpl} during state machine construction.
     *
     * <p>This is framework-internal infrastructure used by Transflux's own runtime; user code
     * should not invoke it directly.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Map<String, BoundCondition<T, ?>> buildBoundConditions() {
        Map<String, BoundCondition<T, ?>> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, ConditionRegistration<T>> e : conditionRegistrations.entrySet()) {
            resolved.put(e.getKey(), e.getValue().toBoundCondition(e.getKey()));
        }

        return Collections.unmodifiableMap(resolved);
    }

    /**
     * Resolves the step registrations into {@link BoundStep} instances. Framework-internal.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Map<String, BoundStep<T, ?>> buildBoundSteps(StateMachineImpl<T> stateMachine,
                                                         Map<String, BoundCondition<T, ?>> conditionRegistry) {
        collectInlineMemberRegistrations();

        Map<String, BoundStep<T, ?>> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, StepRegistration<T>> e : stepRegistrations.entrySet()) {
            resolved.put(e.getKey(), e.getValue().toBoundStep(e.getKey()));
        }

        for (Map.Entry<String, ConditionalStepDefImpl<T, ?>> e : conditionalStepRegistrations.entrySet()) {
            if (resolved.containsKey(e.getKey())) {
                throw new TransfluxValidationException(
                    "Step ID '" + e.getKey() + "' is already registered");
            }
            ConditionalStepDefImpl raw = e.getValue();
            resolved.put(e.getKey(), raw.buildBoundStep(stateMachine, (Map) conditionRegistry));
        }

        return Collections.unmodifiableMap(resolved);
    }

    /**
     * Resolves the operation registrations into {@link BoundOperation} instances. Framework-internal.
     */
    public Map<String, BoundOperation<T, ?>> buildBoundOperations(StateMachineImpl<T> stateMachine) {
        return buildBoundOperationsIncrementally(stateMachine, ignored -> {});
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public Map<String, BoundOperation<T, ?>> buildBoundOperationsIncrementally(
            StateMachineImpl<T> stateMachine,
            Consumer<BoundOperation<T, ?>> afterBuild) {
        Map<String, BoundOperation<T, ?>> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, OperationRegistration<T>> e : operationRegistrations.entrySet()) {
            BoundOperation<T, ?> bo = e.getValue().toBoundOperation(e.getKey());
            resolved.put(e.getKey(), bo);
            afterBuild.accept(bo);
        }
        for (Map.Entry<String, CompositeOperationDefImpl<T, ?>> e : smCompositeOperations.entrySet()) {
            if (resolved.containsKey(e.getKey())) {
                throw new TransfluxValidationException(
                    "Operation ID '" + e.getKey() + "' is already registered");
            }
            CompositeOperationDefImpl raw = e.getValue();
            BoundOperation<T, ?> bo = raw.build(stateMachine);
            resolved.put(e.getKey(), bo);
            afterBuild.accept(bo);
        }

        return Collections.unmodifiableMap(resolved);
    }

    @Override
    public <C> StateMachineDef<T> useContext(Class<C> contextType, Consumer<ContextScope<T, C>> configurer) {
        requireNotNull(contextType, "Context type");
        requireNotNull(configurer, "useContext configurer");
        ContextScopeImpl<T, C> scope = new ContextScopeImpl<>(this, contextType);
        configurer.accept(scope);
        return this;
    }

    private void tagContextType(String id, Class<?> contextType) {
        Class<?> existing = componentContextTypes.get(id);
        if (existing != null && existing != contextType) {
            throw new TransfluxValidationException(
                "Component id '" + id + "' is registered against context type "
                    + existing.getName() + "; cannot re-register against " + contextType.getName());
        }
        componentContextTypes.put(id, contextType);
    }

    public Class<?> getComponentContextType(String id) {
        return componentContextTypes.get(id);
    }

    public CompositeOperationDefImpl<T, ?> getSmCompositeOperation(String id) {
        return smCompositeOperations.get(id);
    }

    <C> void registerScopedStep(String id, Step<T, C> step, Class<C> contextType) {
        registerStepInstance(id, step);
        tagContextType(id, contextType);
    }

    <C> void registerScopedStep(String id, Class<? extends Step<T, C>> stepClass, Class<C> contextType) {
        registerStepClass(id, stepClass);
        tagContextType(id, contextType);
    }

    <C> void registerScopedCondition(String id, Condition<T, C> condition, Class<C> contextType) {
        registerConditionInstance(id, condition);
        tagContextType(id, contextType);
    }

    <C> void registerScopedCondition(String id, Class<? extends Condition<T, C>> conditionClass, Class<C> contextType) {
        registerConditionClass(id, conditionClass);
        tagContextType(id, contextType);
    }

    <C> void registerScopedCondition(String id, Predicate<T> predicate, Class<C> contextType) {
        registerConditionPredicate(id, predicate);
        tagContextType(id, contextType);
    }

    <C> void registerScopedCondition(String id, String expression, Class<C> contextType) {
        registerConditionExpression(id, expression);
        tagContextType(id, contextType);
    }

    <C> void registerScopedCompositeOperation(String id,
                                              Consumer<CompositeOperationDef<T, C>> configurer,
                                              Class<C> contextType) {
        requireNotBlank(id, "Composite operation ID");
        requireNotNull(contextType, "Context type");
        requireNotNull(configurer, "Composite operation configurer");
        if (smCompositeOperations.containsKey(id)) {
            throw new TransfluxValidationException(
                "Composite operation id '" + id + "' is already registered at SM level");
        }
        if (stepRegistrations.containsKey(id) || operationRegistrations.containsKey(id)
            || conditionRegistrations.containsKey(id) || conditionalStepRegistrations.containsKey(id)) {
            throw new TransfluxValidationException(
                "Component id '" + id + "' is already registered");
        }
        CompositeOperationDefImpl<T, C> composite = new CompositeOperationDefImpl<>(id);
        configurer.accept(composite);
        smCompositeOperations.put(id, composite);
        tagContextType(id, contextType);
    }

    @Override
    public StateMachineDef<T> withStateApplier(StateApplier<T> stateApplier) {
        requireNotNull(stateApplier, "State applier");

        if (this.stateApplier != null) {
            log.warn("State applier is already defined: {}. Overriding previous value with {}",
                               this.stateApplier.getClass().getName(), stateApplier.getClass().getName());
        }

        this.stateApplier = stateApplier;

        return this;
    }

    @Override
    public StateDef<T> state(String stateId) {
        if (states.containsKey(stateId)) {
            throw new TransfluxValidationException("State ID " + stateId + " already defined");
        }

        var stateDef = new StateDefImpl<T>(this, stateId);
        states.put(stateDef.getId(), stateDef);
        return stateDef;
    }

    @Override
    public StateDef<T> state(Identifiable stateIdentifiable) {
        requireNotNull(stateIdentifiable, "State identifiable");

        return state(stateIdentifiable.getId());
    }

    /**
     * Registers a transition between two states.
     *
     * @param sourceStateId the ID of the source state
     * @param targetStateId the ID of the target state
     * @param transitionId the unique identifier for the transition
     * @param contextType optional pre-bound context type; may be {@code null}
     */
    public void registerTransition(String sourceStateId, String targetStateId, String transitionId) {
        registerTransition(sourceStateId, targetStateId, transitionId, null);
    }

    public void registerTransition(String sourceStateId, String targetStateId, String transitionId,
                                   Class<?> contextType) {
        requireNotBlank(sourceStateId, "Source state ID");
        requireNotBlank(targetStateId, "Target state ID");
        requireNotBlank(transitionId, "Transition ID");

        if (transitionsById.containsKey(transitionId)) {
            throw new TransfluxValidationException("Transition ID " + transitionId + " already defined");
        }

        var byTarget = transitionsBySourceTarget.computeIfAbsent(sourceStateId, k -> new LinkedHashMap<>());
        var list = byTarget.computeIfAbsent(targetStateId, k -> new ArrayList<>(1));

        TransitionDefImpl<T, ?> def = createTransition(transitionId, sourceStateId, targetStateId, contextType);
        list.add(def);
        transitionsById.put(transitionId, def);
    }

    private <C> TransitionDefImpl<T, C> createTransition(String transitionId, String sourceStateId,
                                                          String targetStateId, Class<?> contextType) {
        @SuppressWarnings("unchecked")
        Class<C> ctx = (Class<C>) (contextType != null ? contextType : Object.class);
        return new TransitionDefImpl<>(transitionId, sourceStateId, targetStateId, ctx);
    }

    @Override
    public StateMachine<T> build() {
        validateContextCompatibilityAndCycles();
        return new StateMachineImpl<>(this);
    }

    private void validateContextCompatibilityAndCycles() {
        for (TransitionDefImpl<T, ?> td : transitionsById.values()) {
            Class<?> transitionContext = td.getContextType();
            OperationDefImpl<T, ?> op = td.getOperationDef();
            if (op instanceof CompositeOperationDefImpl<T, ?> composite) {
                checkCompositeRefs(composite, transitionContext,
                    "transition '" + td.getId() + "'");
            }
        }
        for (Map.Entry<String, CompositeOperationDefImpl<T, ?>> e : smCompositeOperations.entrySet()) {
            Class<?> scopeContext = componentContextTypes.get(e.getKey());
            checkCompositeRefs(e.getValue(), scopeContext,
                "SM-level composite '" + e.getKey() + "'");
        }
        detectCompositeCycles();
    }

    private void checkCompositeRefs(CompositeOperationDefImpl<T, ?> composite,
                                    Class<?> scopeContext,
                                    String scopeLabel) {
        if (scopeContext == null || scopeContext == Object.class) {
            return;
        }
        for (String stepId : composite.getStepByIdReferenceIds()) {
            Class<?> stepCtx = componentContextTypes.get(stepId);
            if (stepCtx != null && stepCtx != scopeContext) {
                throw new TransfluxValidationException(
                    "Context type mismatch: " + scopeLabel + " (context "
                        + scopeContext.getName() + ") references step '" + stepId
                        + "' declared for context " + stepCtx.getName());
            }
        }
        for (String opId : composite.getOperationByIdReferenceIds()) {
            Class<?> opCtx = componentContextTypes.get(opId);
            if (opCtx != null && opCtx != scopeContext) {
                throw new TransfluxValidationException(
                    "Context type mismatch: " + scopeLabel + " (context "
                        + scopeContext.getName() + ") references operation '" + opId
                        + "' declared for context " + opCtx.getName());
            }
        }
    }

    private void detectCompositeCycles() {
        java.util.Set<String> visited = new java.util.HashSet<>();
        java.util.Deque<String> stack = new java.util.ArrayDeque<>();
        for (String id : smCompositeOperations.keySet()) {
            if (!visited.contains(id)) {
                dfsComposite(id, visited, stack);
            }
        }
    }

    private void dfsComposite(String id, java.util.Set<String> visited, java.util.Deque<String> stack) {
        if (stack.contains(id)) {
            java.util.List<String> path = new ArrayList<>(stack);
            path.add(id);
            int start = path.indexOf(id);
            throw new TransfluxValidationException(
                "Composite operation cycle detected: " + String.join(" -> ", path.subList(start, path.size())));
        }
        if (visited.contains(id)) {
            return;
        }
        CompositeOperationDefImpl<T, ?> composite = smCompositeOperations.get(id);
        if (composite == null) {
            return;
        }
        stack.push(id);
        for (String refId : composite.getOperationByIdReferenceIds()) {
            if (smCompositeOperations.containsKey(refId)) {
                dfsComposite(refId, visited, stack);
            }
        }
        stack.pop();
        visited.add(id);
    }

    public Class<T> getEntityType() {
        return entityType;
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

    public Map<String, StateDefImpl<T>> getStates() {
        return states;
    }

    public Map<String, TransitionDefImpl<T, ?>> getTransitionsById() {
        return transitionsById;
    }

    @Override
    public TransitionDef<T, ?> getTransition(String sourceStateId, String targetStateId) {
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

    private static final class StepRegistration<T> {
        private final Step<T, ?> instance;
        private final Class<? extends Step<T, ?>> stepClass;

        private StepRegistration(Step<T, ?> instance, Class<? extends Step<T, ?>> stepClass) {
            this.instance = instance;
            this.stepClass = stepClass;
        }

        static <T> StepRegistration<T> ofInstance(Step<T, ?> instance) {
            return new StepRegistration<>(instance, null);
        }

        static <T> StepRegistration<T> ofClass(Class<? extends Step<T, ?>> stepClass) {
            return new StepRegistration<>(null, stepClass);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        BoundStep<T, ?> toBoundStep(String id) {
            Step<T, ?> resolved = instance != null ? instance : (Step<T, ?>) instantiateNoArg((Class) stepClass, "Step");
            return BoundStep.of(id, (Step) resolved);
        }
    }

    private static final class OperationRegistration<T> {
        private final Operation<T, ?> instance;
        private final Class<? extends Operation<T, ?>> operationClass;

        private OperationRegistration(Operation<T, ?> instance,
                                      Class<? extends Operation<T, ?>> operationClass) {
            this.instance = instance;
            this.operationClass = operationClass;
        }

        static <T> OperationRegistration<T> ofInstance(Operation<T, ?> instance) {
            return new OperationRegistration<>(instance, null);
        }

        static <T> OperationRegistration<T> ofClass(Class<? extends Operation<T, ?>> operationClass) {
            return new OperationRegistration<>(null, operationClass);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        BoundOperation<T, ?> toBoundOperation(String id) {
            Operation<T, ?> resolved = instance != null
                ? instance
                : (Operation<T, ?>) instantiateNoArg((Class) operationClass, "Operation");
            return BoundOperation.of(id, null, null, (Operation) resolved);
        }
    }

    private static final class ConditionRegistration<T> {
        private final Condition<T, ?> instance;
        private final Class<? extends Condition<T, ?>> conditionClass;
        private final Predicate<T> predicate;
        private final String expression;

        private ConditionRegistration(Condition<T, ?> instance,
                                      Class<? extends Condition<T, ?>> conditionClass,
                                      Predicate<T> predicate,
                                      String expression) {
            this.instance = instance;
            this.conditionClass = conditionClass;
            this.predicate = predicate;
            this.expression = expression;
        }

        static <T> ConditionRegistration<T> ofInstance(Condition<T, ?> instance) {
            return new ConditionRegistration<>(instance, null, null, null);
        }

        static <T> ConditionRegistration<T> ofClass(Class<? extends Condition<T, ?>> conditionClass) {
            return new ConditionRegistration<>(null, conditionClass, null, null);
        }

        static <T> ConditionRegistration<T> ofPredicate(Predicate<T> predicate) {
            return new ConditionRegistration<>(null, null, predicate, null);
        }

        static <T> ConditionRegistration<T> ofExpression(String expression) {
            return new ConditionRegistration<>(null, null, null, expression);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        BoundCondition<T, ?> toBoundCondition(String id) {
            if (instance != null) {
                return BoundCondition.of(id, (Condition) instance);
            }
            if (conditionClass != null) {
                return BoundCondition.of(id, (Condition) instantiateNoArg((Class) conditionClass, "Condition"));
            }
            if (predicate != null) {
                Predicate<T> p = predicate;
                Condition<T, Object> adapted = (entity, ctx, transition) -> p.test(entity);
                return BoundCondition.of(id, (Condition) adapted);
            }
            return BoundCondition.fromExpression(id, expression);
        }
    }

    @Override
    public TransitionDef<T, ?> getTransition(String transitionId) {
        var td = transitionsById.get(transitionId);
        if (td == null) {
            throw new TransfluxValidationException("Transition '" + transitionId + "' not found");
        }
        return td;
    }
}
