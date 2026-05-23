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

import org.transflux.core.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.transflux.core.impl.BoundCondition;
import org.transflux.core.condition.Condition;
import org.transflux.core.exception.TransfluxValidationException;
import org.transflux.core.impl.ActionRef;
import org.transflux.core.impl.BoundOperation;
import org.transflux.core.impl.BoundStep;
import org.transflux.core.operation.CompositeOperationDef;
import org.transflux.core.impl.CompositeOperationDefImpl;
import org.transflux.core.impl.ConditionalStepDefImpl;
import org.transflux.core.operation.ContextMapper;
import org.transflux.core.operation.MapperDef;
import org.transflux.core.impl.MapperDefImpl;
import org.transflux.core.impl.MapperRef;
import org.transflux.core.operation.Operation;
import org.transflux.core.impl.OperationDefImpl;
import org.transflux.core.operation.Step;
import org.transflux.core.state.StateApplier;
import org.transflux.core.state.StateDef;
import org.transflux.core.impl.StateDefImpl;
import org.transflux.core.state.StateResolver;
import org.transflux.core.transition.TransitionDef;
import org.transflux.core.impl.TransitionDefImpl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.transflux.core.impl.ReflectionUtils.instantiateNoArg;
import static org.transflux.core.impl.ValidationUtils.requireNotBlank;
import static org.transflux.core.impl.ValidationUtils.requireNotNull;
import static org.transflux.core.impl.ValidationUtils.warnIfSet;

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

    private final Map<String, CompositeOperationDefImpl<T, ?>> smCompositeOperations = new LinkedHashMap<>();

    private final Map<String, MapperDefImpl<?, ?>> mapperRegistrations = new LinkedHashMap<>();

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

    /**
     * Wires a {@link RegistryImpl} scope onto every composite operation declared on this
     * state-machine def — both the SM-level composites and those embedded in transitions — and
     * populates each scope with the composite's inline-declared members (steps, operations) and
     * the bound steps for the conditionals it owns. Each scope's parent is the supplied root
     * registry, so by-id refs from inside a composite first check the composite-local entries
     * and fall back to root.
     *
     * <p>This pass also enforces SM-wide id uniqueness: every composite-local id is added to the
     * same {@code globalIds} set that already holds SM-level ids; a collision anywhere fails the
     * build with a {@link TransfluxValidationException}.
     *
     * <p>This is framework-internal infrastructure used by {@link StateMachineImpl} during state
     * machine construction; user code does not invoke it directly.
     *
     * @param stateMachine the state machine under construction; conditional executors close
     *                     over it for runtime step lookup
     * @param rootRegistry the state-machine's root registry — the parent of every composite scope
     * @param conditionRegistry the resolved SM-wide condition registry
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void bindCompositeScopes(StateMachineImpl<T> stateMachine,
                                    RegistryImpl<T> rootRegistry,
                                    Map<String, BoundCondition<T, ?>> conditionRegistry) {
        Map<String, Object> canonical = new HashMap<>();

        for (Map.Entry<String, StepRegistration<T>> e : stepRegistrations.entrySet()) {
            canonical.put(e.getKey(), payloadOf(e.getValue()));
        }

        for (Map.Entry<String, OperationRegistration<T>> e : operationRegistrations.entrySet()) {
            canonical.put(e.getKey(), payloadOf(e.getValue()));
        }

        canonical.putAll(conditionRegistrations);

        for (String id : smCompositeOperations.keySet()) {
            canonical.put(id, smCompositeOperations.get(id));
        }

        for (String id : mapperRegistrations.keySet()) {
            canonical.put(id, mapperRegistrations.get(id));
        }

        for (TransitionDefImpl<T, ?> td : transitionsById.values()) {
            OperationDefImpl<T, ?> op = td.getOperationDef();
            if (op instanceof CompositeOperationDefImpl<T, ?> composite) {
                bindCompositeScope((CompositeOperationDefImpl) composite, rootRegistry,
                                   canonical, stateMachine, (Map) conditionRegistry);
            }
        }

        for (CompositeOperationDefImpl<T, ?> composite : smCompositeOperations.values()) {
            bindCompositeScope((CompositeOperationDefImpl) composite, rootRegistry,
                               canonical, stateMachine, (Map) conditionRegistry);
        }
    }

    private static <T> Object payloadOf(StepRegistration<T> reg) {
        return reg.instance != null ? reg.instance : reg.stepClass;
    }

    private static <T> Object payloadOf(OperationRegistration<T> reg) {
        return reg.instance != null ? reg.instance : reg.operationClass;
    }

    /**
     * Flattens the scope registry of every composite operation declared on this state-machine
     * def. Called after every component has been bound into its appropriate registry so each
     * scope's {@link Registry#resolve(String)} becomes a single local-map lookup.
     *
     * <p>This is framework-internal infrastructure used by {@link StateMachineImpl} during state
     * machine construction; user code does not invoke it directly.
     */
    public void flattenCompositeScopes() {
        for (TransitionDefImpl<T, ?> td : transitionsById.values()) {
            OperationDefImpl<T, ?> op = td.getOperationDef();
            if (op instanceof CompositeOperationDefImpl<T, ?> composite) {
                RegistryImpl<T> scope = composite.getScopeRegistry();
                if (scope != null) {
                    scope.flatten();
                }
            }
        }
        for (CompositeOperationDefImpl<T, ?> composite : smCompositeOperations.values()) {
            RegistryImpl<T> scope = composite.getScopeRegistry();
            if (scope != null) {
                scope.flatten();
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <C> void bindCompositeScope(CompositeOperationDefImpl<T, C> composite,
                                        RegistryImpl<T> rootRegistry,
                                        Map<String, Object> canonical,
                                        StateMachineImpl<T> stateMachine,
                                        Map<String, BoundCondition<T, C>> conditionRegistry) {
        RegistryImpl<T> scope = new RegistryImpl<>(rootRegistry);
        composite.setScopeRegistry(scope);

        Class<C> compositeCtx = composite.contextType();

        // Inline steps declared directly on the composite.
        for (Map.Entry<String, Step<T, C>> e : composite.getInlineStepInstances().entrySet()) {
            registerInlineStepInScope(scope, canonical, e.getKey(), e.getValue(), compositeCtx);
        }
        for (Map.Entry<String, Class<? extends Step<T, C>>> e : composite.getInlineStepClasses().entrySet()) {
            registerInlineStepClassInScope(scope, canonical, e.getKey(), (Class) e.getValue(), compositeCtx);
        }

        // Inline operations declared directly on the composite.
        for (Map.Entry<String, Operation<T, C>> e : composite.getInlineOperationInstances().entrySet()) {
            registerInlineOperationInScope(scope, canonical, e.getKey(), e.getValue(), compositeCtx);
        }
        for (Map.Entry<String, Class<? extends Operation<T, C>>> e : composite.getInlineOperationClasses().entrySet()) {
            registerInlineOperationClassInScope(scope, canonical, e.getKey(), (Class) e.getValue(), compositeCtx);
        }

        // Conditionals: inline steps inside their branches plus the conditional's own bound step.
        for (Map.Entry<String, ConditionalStepDefImpl<T, C>> e : composite.getConditionalDefs().entrySet()) {
            ConditionalStepDefImpl<T, C> conditional = e.getValue();

            for (Map.Entry<String, Step<T, C>> ie : conditional.getInlineStepInstances().entrySet()) {
                registerInlineStepInScope(scope, canonical, ie.getKey(), ie.getValue(), compositeCtx);
            }
            for (Map.Entry<String, Class<? extends Step<T, C>>> ie : conditional.getInlineStepClasses().entrySet()) {
                registerInlineStepClassInScope(scope, canonical, ie.getKey(), (Class) ie.getValue(), compositeCtx);
            }

            String conditionalId = e.getKey();
            claimCanonical(canonical, conditionalId, conditional);
            if (scope.get(conditionalId).isPresent()) {
                continue;
            }
            BoundStep<T, C> boundConditional = conditional.buildBoundStep(stateMachine, conditionRegistry);
            scope.register(new Component.Step<>(conditionalId, null, null, compositeCtx, boundConditional));
        }
    }

    private <C> void registerInlineStepInScope(RegistryImpl<T> scope, Map<String, Object> canonical,
                                               String id, Step<T, C> step, Class<C> contextType) {
        claimCanonical(canonical, id, step);
        if (scope.get(id).isPresent()) {
            return;
        }
        BoundStep<T, C> bound = BoundStep.of(id, step);
        scope.register(new Component.Step<>(id, null, null, contextType, bound));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <C> void registerInlineStepClassInScope(RegistryImpl<T> scope, Map<String, Object> canonical,
                                                    String id, Class<? extends Step<T, C>> stepClass,
                                                    Class<C> contextType) {
        claimCanonical(canonical, id, stepClass);
        if (scope.get(id).isPresent()) {
            return;
        }
        Step<T, C> resolved = (Step<T, C>) instantiateNoArg((Class) stepClass, "Step");
        BoundStep<T, C> bound = BoundStep.of(id, resolved);
        scope.register(new Component.Step<>(id, null, null, contextType, bound));
    }

    private <C> void registerInlineOperationInScope(RegistryImpl<T> scope, Map<String, Object> canonical,
                                                    String id, Operation<T, C> operation,
                                                    Class<C> contextType) {
        claimCanonical(canonical, id, operation);
        if (scope.get(id).isPresent()) {
            return;
        }
        BoundOperation<T, C> bound = BoundOperation.of(id, null, null, operation);
        scope.register(new Component.Operation<>(id, null, null, contextType, bound));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <C> void registerInlineOperationClassInScope(RegistryImpl<T> scope, Map<String, Object> canonical,
                                                         String id, Class<? extends Operation<T, C>> operationClass,
                                                         Class<C> contextType) {
        claimCanonical(canonical, id, operationClass);
        if (scope.get(id).isPresent()) {
            return;
        }
        Operation<T, C> resolved = (Operation<T, C>) instantiateNoArg((Class) operationClass, "Operation");
        BoundOperation<T, C> bound = BoundOperation.of(id, null, null, resolved);
        scope.register(new Component.Operation<>(id, null, null, contextType, bound));
    }

    /**
     * Records the canonical payload for {@code id} in the per-build global table. Idempotent
     * for an existing identical payload (same instance reference, or equal {@link Class}
     * object) — mirrors the idempotency rules of the SM-level {@code registerStepInstance} /
     * {@code registerStepClass} pair and friends. A different payload under the same id raises
     * {@link TransfluxValidationException}, enforcing SM-wide id uniqueness.
     */
    private void claimCanonical(Map<String, Object> canonical, String id, Object payload) {
        Object existing = canonical.get(id);
        if (existing == null) {
            canonical.put(id, payload);
            return;
        }
        if (existing == payload) {
            return;
        }
        if (existing instanceof Class<?> && payload instanceof Class<?> && existing.equals(payload)) {
            return;
        }
        throw new TransfluxValidationException(
            "Component id '" + id + "' is already registered");
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

    @Override
    public <C> StateMachineDef<T> operation(String id, Class<C> contextType, Operation<T, C> operation) {
        requireNotBlank(id, "Operation ID");
        requireNotNull(contextType, "Context type");
        requireNotNull(operation, "Operation");
        registerOperationInstance(id, operation);
        tagContextType(id, contextType);
        return this;
    }

    @Override
    public <C> StateMachineDef<T> operation(String id, Class<C> contextType, Class<? extends Operation<T, C>> operationClass) {
        requireNotBlank(id, "Operation ID");
        requireNotNull(contextType, "Context type");
        requireNotNull(operationClass, "Operation class");
        registerOperationClass(id, operationClass);
        tagContextType(id, contextType);
        return this;
    }

    @Override
    public <P, N> StateMachineDef<T> mapper(String id,
                                            Class<P> parentType,
                                            Class<N> childType,
                                            ContextMapper<P, N> mapper) {
        requireNotBlank(id, "Mapper ID");
        requireNotNull(parentType, "Mapper parent type");
        requireNotNull(childType, "Mapper child type");
        requireNotNull(mapper, "Context mapper");
        registerMapper(new MapperDefImpl<>(id, parentType, childType).using(mapper));
        return this;
    }

    @Override
    public <P, N> StateMachineDef<T> mapper(String id,
                                            Class<P> parentType,
                                            Class<N> childType,
                                            Class<? extends ContextMapper<P, N>> mapperClass) {
        requireNotBlank(id, "Mapper ID");
        requireNotNull(parentType, "Mapper parent type");
        requireNotNull(childType, "Mapper child type");
        requireNotNull(mapperClass, "Context mapper class");
        registerMapper(new MapperDefImpl<>(id, parentType, childType).using(mapperClass));
        return this;
    }

    @Override
    public <P, N> StateMachineDef<T> mapper(String id,
                                            Class<P> parentType,
                                            Class<N> childType,
                                            Function<P, N> mapTo) {
        requireNotBlank(id, "Mapper ID");
        requireNotNull(parentType, "Mapper parent type");
        requireNotNull(childType, "Mapper child type");
        requireNotNull(mapTo, "mapTo function");
        registerMapper(new MapperDefImpl<>(id, parentType, childType).using(mapTo));
        return this;
    }

    private void registerMapper(MapperDefImpl<?, ?> def) {
        MapperDefImpl<?, ?> existing = mapperRegistrations.get(def.getId());
        if (existing != null && existing != def) {
            throw new TransfluxValidationException("Mapper ID '" + def.getId() + "' is already registered");
        }
        mapperRegistrations.put(def.getId(), def);
    }

    /**
     * Returns the {@link MapperDef} registered under {@code id}, or {@code null} if none.
     *
     * <p>This is framework-internal infrastructure used by the build pipeline to resolve
     * call-site mapper references; user code does not invoke it directly.
     *
     * @param id the mapper id
     *
     * @return the registered mapper def, or {@code null}
     */
    public MapperDef<?, ?> getMapperDef(String id) {
        return mapperRegistrations.get(id);
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
    public Map<String, BoundCondition<T, ?>> buildBoundConditions() {
        Map<String, BoundCondition<T, ?>> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, ConditionRegistration<T>> e : conditionRegistrations.entrySet()) {
            resolved.put(e.getKey(), e.getValue().toBoundCondition(e.getKey()));
        }

        return Collections.unmodifiableMap(resolved);
    }

    /**
     * Resolves the SM-level step registrations into {@link BoundStep} instances. Composite-local
     * inline steps and conditionals are bound into their owning composite's scope by
     * {@link #bindCompositeScopes(StateMachineImpl, RegistryImpl, Map)} and are not included
     * here.
     *
     * <p>This is framework-internal infrastructure used by {@link StateMachineImpl} during state
     * machine construction; user code does not invoke it directly.
     *
     * @return an unmodifiable map of SM-level step id to bound step
     */
    public Map<String, BoundStep<T, ?>> buildBoundSteps() {
        Map<String, BoundStep<T, ?>> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, StepRegistration<T>> e : stepRegistrations.entrySet()) {
            resolved.put(e.getKey(), e.getValue().toBoundStep(e.getKey()));
        }
        return Collections.unmodifiableMap(resolved);
    }

    /**
     * Resolves the operation registrations into {@link BoundOperation} instances. Framework-internal.
     */
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
    public <C> StateMachineDef<T> forContext(Class<C> contextType, Consumer<ContextScope<T, C>> configurer) {
        requireNotNull(contextType, "Context type");
        requireNotNull(configurer, "forContext configurer");
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

    <C> void registerScopedOperation(String id, Operation<T, C> operation, Class<C> contextType) {
        registerOperationInstance(id, operation);
        tagContextType(id, contextType);
    }

    <C> void registerScopedOperation(String id, Class<? extends Operation<T, C>> operationClass, Class<C> contextType) {
        registerOperationClass(id, operationClass);
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
            || conditionRegistrations.containsKey(id)) {
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
    public StateMachineDef<T> state(String stateId, Consumer<StateDef<T>> configurer) {
        requireNotBlank(stateId, "State ID");
        requireNotNull(configurer, "State configurer");
        StateDefImpl<T> stateDef = registerState(stateId);
        stateDef.beginConfigurer();
        try {
            configurer.accept(stateDef);
        } finally {
            stateDef.endConfigurer();
        }
        return this;
    }

    @Override
    public StateMachineDef<T> state(Identifiable stateIdentifiable, Consumer<StateDef<T>> configurer) {
        requireNotNull(stateIdentifiable, "State identifiable");
        return state(stateIdentifiable.getId(), configurer);
    }

    private StateDefImpl<T> registerState(String stateId) {
        if (states.containsKey(stateId)) {
            throw new TransfluxValidationException("State ID " + stateId + " already defined");
        }
        var stateDef = new StateDefImpl<>(this, stateId);
        states.put(stateDef.getId(), stateDef);
        return stateDef;
    }

    /**
     * Registers a transition between two states with pass-through ({@link Object}) context.
     *
     * <p>This is framework-internal infrastructure used by Transflux's own DSL; user code
     * should not invoke it directly.
     *
     * @param sourceStateId the ID of the source state
     * @param targetStateId the ID of the target state
     * @param transitionId the unique identifier for the transition
     *
     * @return the newly registered transition def
     */
    public TransitionDefImpl<T, Object> registerTransition(String sourceStateId, String targetStateId,
                                                           String transitionId) {
        return registerTransition(sourceStateId, targetStateId, transitionId, Object.class);
    }

    /**
     * Registers a transition between two states tagged with the supplied context type.
     *
     * <p>This is framework-internal infrastructure used by Transflux's own DSL; user code
     * should not invoke it directly.
     *
     * @param sourceStateId the ID of the source state
     * @param targetStateId the ID of the target state
     * @param transitionId the unique identifier for the transition
     * @param contextType the transition's context class; never {@code null}
     * @param <C> the context type
     *
     * @return the newly registered transition def
     */
    public <C> TransitionDefImpl<T, C> registerTransition(String sourceStateId, String targetStateId,
                                                          String transitionId, Class<C> contextType) {
        requireNotBlank(sourceStateId, "Source state ID");
        requireNotBlank(targetStateId, "Target state ID");
        requireNotBlank(transitionId, "Transition ID");
        requireNotNull(contextType, "Context type");

        if (transitionsById.containsKey(transitionId)) {
            throw new TransfluxValidationException("Transition ID " + transitionId + " already defined");
        }

        var byTarget = transitionsBySourceTarget.computeIfAbsent(sourceStateId, k -> new LinkedHashMap<>());
        var list = byTarget.computeIfAbsent(targetStateId, k -> new ArrayList<>(1));

        TransitionDefImpl<T, C> def = new TransitionDefImpl<>(transitionId, sourceStateId, targetStateId, contextType);
        list.add(def);
        transitionsById.put(transitionId, def);
        return def;
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
        if (scopeContext == null) {
            scopeContext = Object.class;
        }

        for (ActionRef<T, ?> ref : composite.getActionRefs()) {
            if (ref instanceof ActionRef.ById<T, ?> stepRef) {
                Class<?> componentCtx = componentContextTypes.getOrDefault(stepRef.id(), Object.class);
                checkMemberRef(scopeContext, scopeLabel, "step", stepRef.id(),
                    componentCtx, stepRef.mapperRef());
            } else if (ref instanceof ActionRef.OperationById<T, ?> opRef) {
                Class<?> componentCtx = componentContextTypes.getOrDefault(opRef.id(), Object.class);
                checkMemberRef(scopeContext, scopeLabel, "operation", opRef.id(),
                    componentCtx, opRef.mapperRef());
            }
        }
    }

    private void checkMemberRef(Class<?> scopeContext, String scopeLabel, String kind,
                                String memberId, Class<?> componentContext, MapperRef mapperRef) {
        if (mapperRef instanceof MapperRef.PassThrough) {
            if (componentContext == Object.class || componentContext.isAssignableFrom(scopeContext)) {
                return;
            }
            throw new TransfluxValidationException(
                "Context type mismatch: " + scopeLabel + " (context " + scopeContext.getName()
                    + ") references " + kind + " '" + memberId
                    + "' declared for context " + componentContext.getName()
                    + " without a mapper; supply a mapper to bridge the boundary");
        }
        if (mapperRef instanceof MapperRef.ById byId) {
            MapperDefImpl<?, ?> mapperDef = mapperRegistrations.get(byId.mapperId());
            if (mapperDef == null) {
                throw new TransfluxValidationException(
                    scopeLabel + " references unknown mapper '" + byId.mapperId() + "' at " + kind
                        + " '" + memberId + "'");
            }
            Class<?> mapperParent = mapperDef.parentType();
            Class<?> mapperChild = mapperDef.childType();
            if (!mapperParent.isAssignableFrom(scopeContext)) {
                throw new TransfluxValidationException(
                    "Mapper '" + byId.mapperId() + "' parent type " + mapperParent.getName()
                        + " is not assignable from " + scopeLabel + " context " + scopeContext.getName());
            }
            if (componentContext != Object.class && !componentContext.isAssignableFrom(mapperChild)) {
                throw new TransfluxValidationException(
                    "Mapper '" + byId.mapperId() + "' child type " + mapperChild.getName()
                        + " is not assignable to " + kind + " '" + memberId + "' context "
                        + componentContext.getName());
            }
        }
        // Inline Function and inline ContextMapper forms cannot be reliably introspected at
        // build time (generic-parameter erasure); their P/N alignment is checked at first
        // dispatch when the user-supplied value is invoked.
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

    private record StepRegistration<T>(Step<T, ?> instance, Class<? extends Step<T, ?>> stepClass) {

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

    private record OperationRegistration<T>(Operation<T, ?> instance, Class<? extends Operation<T, ?>> operationClass) {

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

    private record ConditionRegistration<T>(Condition<T, ?> instance, Class<? extends Condition<T, ?>> conditionClass,
                                            Predicate<T> predicate, String expression) {

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
