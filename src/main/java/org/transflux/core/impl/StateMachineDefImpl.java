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
import org.transflux.core.ContextScope;
import org.transflux.core.Identifiable;
import org.transflux.core.StateMachine;
import org.transflux.core.StateMachineDef;
import org.transflux.core.condition.Condition;
import org.transflux.core.condition.ConditionDescriptor;
import org.transflux.core.exception.TransfluxValidationException;
import org.transflux.core.operation.CompositeOperationDef;
import org.transflux.core.operation.ContextMapper;
import org.transflux.core.operation.MapperDef;
import org.transflux.core.operation.Operation;
import org.transflux.core.operation.SimpleOperationDef;
import org.transflux.core.operation.Step;
import org.transflux.core.operation.StepDef;
import org.transflux.core.state.StateApplier;
import org.transflux.core.state.StateDef;
import org.transflux.core.state.StateListener;
import org.transflux.core.state.StateListenerDef;
import org.transflux.core.state.StateResolver;
import org.transflux.core.transition.TransitionDef;
import org.transflux.core.transition.TransitionListener;
import org.transflux.core.transition.TransitionListenerDef;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.transflux.core.Preconditions.requireNotBlank;
import static org.transflux.core.Preconditions.requireNotNull;
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

    /**
     * State listeners attached to every state rather than to one. Kept in declaration order; the
     * per-state listeners of whichever state is being entered or left run ahead of these.
     */
    private final List<StateListenerDefImpl<T>> globalEntryListeners = new ArrayList<>();
    private final List<StateListenerDefImpl<T>> globalExitListeners = new ArrayList<>();

    /**
     * Transition listeners attached to every transition rather than to one. Kept in declaration
     * order; the per-transition listeners of whichever transition is executing run ahead of these.
     * They span transitions with differing context types and so are typed against {@link Object}.
     */
    private final List<TransitionListenerDefImpl<T, Object>> globalStartListeners = new ArrayList<>();
    private final List<TransitionListenerDefImpl<T, Object>> globalCompleteListeners = new ArrayList<>();
    private final List<TransitionListenerDefImpl<T, Object>> globalErrorListeners = new ArrayList<>();

    /**
     * Listener ids claimed so far. Listeners form one state-machine-wide namespace across both
     * kinds — they are not reachable through the component registry — so state and transition
     * listeners, per-owner and global alike, are checked against this one set. State listeners and
     * the global registrations claim eagerly; a transition's own listeners are claimed when the
     * definition is built, because a transition def holds no reference back to this def.
     */
    private final Set<String> listenerIds = new HashSet<>();

    /** Creates an empty definition. */
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
    public StateMachineDef<T> step(Identifiable stepIdentifiable, Step<T, ?> step) {
        requireNotNull(stepIdentifiable, "Step identifiable");
        return step(stepIdentifiable.getId(), step);
    }

    @Override
    public StateMachineDef<T> step(String id, Class<? extends Step<T, ?>> stepClass) {
        requireNotBlank(id, "Step ID");
        requireNotNull(stepClass, "Step class");
        registerStepClass(id, stepClass);
        return this;
    }

    @Override
    public StateMachineDef<T> step(Identifiable stepIdentifiable, Class<? extends Step<T, ?>> stepClass) {
        requireNotNull(stepIdentifiable, "Step identifiable");
        return step(stepIdentifiable.getId(), stepClass);
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
    public <C> StateMachineDef<T> step(Identifiable stepIdentifiable, Class<C> contextType, Step<T, C> step) {
        requireNotNull(stepIdentifiable, "Step identifiable");
        return step(stepIdentifiable.getId(), contextType, step);
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

    @Override
    public <C> StateMachineDef<T> step(Identifiable stepIdentifiable, Class<C> contextType, Class<? extends Step<T, C>> stepClass) {
        requireNotNull(stepIdentifiable, "Step identifiable");
        return step(stepIdentifiable.getId(), contextType, stepClass);
    }

    @Override
    public <C> StateMachineDef<T> step(String id, Class<C> contextType, Consumer<StepDef<T, C>> configurer) {
        requireNotBlank(id, "Step ID");
        requireNotNull(contextType, "Context type");
        requireNotNull(configurer, "Step configurer");
        StepDefImpl<T, C> def = new StepDefImpl<>(id, contextType);
        ConfigurableDefImpl.runConfigurer(def, configurer);
        registerStepDef(def);
        tagContextType(id, contextType);
        return this;
    }

    @Override
    public <C> StateMachineDef<T> step(Identifiable stepIdentifiable, Class<C> contextType, Consumer<StepDef<T, C>> configurer) {
        requireNotNull(stepIdentifiable, "Step identifiable");
        return step(stepIdentifiable.getId(), contextType, configurer);
    }

    private void registerStepDef(StepDefImpl<T, ?> def) {
        String id = def.getId();
        if (stepRegistrations.containsKey(id)) {
            throw new TransfluxValidationException("Step ID '" + id + "' is already registered");
        }
        checkIdNotRegisteredAsOperation(id);
        stepRegistrations.put(id, StepRegistration.ofDef(def));
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
     * @param rootRegistry the state-machine's root registry — the parent of every composite scope
     * @param conditionRegistry the resolved SM-wide condition registry
     */
    void bindCompositeScopes(RegistryImpl<T> rootRegistry,
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
            claimInlineConditions(canonical, td);
            OperationDefImpl<T, ?, ?> op = td.getOperationDef();
            if (op != null) {
                op.bindScope(rootRegistry, canonical, conditionRegistry);
            }
        }

        for (CompositeOperationDefImpl<T, ?> composite : smCompositeOperations.values()) {
            composite.bindScope(rootRegistry, canonical, conditionRegistry);
        }
    }

    /**
     * Walks every known composite operation def — both transition-attached top-level composites
     * and SM-level registered composites — and returns the id of the first composite whose
     * <em>local</em> scope registry contains an entry under {@code id}, excluding the composite
     * that originated the search. Used by {@link ActionRef} resolution to enrich
     * "unknown id" diagnostics when an id exists inline in a sibling composite's subtree.
     *
     * <p>Returns {@link Optional#empty()} when no matching sibling registration exists.
     */
    Optional<String> findInlineSiblingScope(String id, String excludingCompositeId) {
        for (TransitionDefImpl<T, ?> td : transitionsById.values()) {
            OperationDefImpl<T, ?, ?> op = td.getOperationDef();
            if (op != null) {
                Optional<String> hit = op.scanScopeFor(id, excludingCompositeId);
                if (hit.isPresent()) {
                    return hit;
                }
            }
        }

        for (CompositeOperationDefImpl<T, ?> composite : smCompositeOperations.values()) {
            Optional<String> hit = composite.scanScopeFor(id, excludingCompositeId);
            if (hit.isPresent()) {
                return hit;
            }
        }

        return Optional.empty();
    }

    private static <T> Object payloadOf(StepRegistration<T> reg) {
        if (reg.def != null) {
            return reg.def;
        }
        return reg.instance != null ? reg.instance : reg.stepClass;
    }

    private static <T> Object payloadOf(OperationRegistration<T> reg) {
        if (reg.def != null) {
            return reg.def;
        }
        return reg.instance != null ? reg.instance : reg.operationClass;
    }

    /**
     * Flattens the scope registry of every composite operation declared on this state-machine
     * def. Called after every component has been bound into its appropriate registry so each
     * scope's {@link Registry#resolve(String)} becomes a single local-map lookup.
     */
    void flattenCompositeScopes() {
        for (TransitionDefImpl<T, ?> td : transitionsById.values()) {
            OperationDefImpl<T, ?, ?> op = td.getOperationDef();
            if (op != null) {
                op.flattenScope();
            }
        }

        for (CompositeOperationDefImpl<T, ?> composite : smCompositeOperations.values()) {
            composite.flattenScope();
        }
    }

    /**
     * Records the canonical payload for {@code id} in the per-build global table. Idempotent
     * for an existing identical payload (same instance reference, or an equal {@link Class} or
     * {@link String} value) — mirrors the idempotency rules of the SM-level
     * {@code registerStepInstance} / {@code registerStepClass} pair and friends. A different
     * payload under the same id raises {@link TransfluxValidationException}, enforcing SM-wide id
     * uniqueness.
     */
    static void claimCanonical(Map<String, Object> canonical, String id, Object payload, String kind) {
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

        if (existing instanceof String && payload instanceof String && existing.equals(payload)) {
            return;
        }

        throw new TransfluxValidationException(
            kind + " id '" + id + "' is already registered with payload '"
                + payloadClassName(existing) + "'; cannot re-register with '"
                + payloadClassName(payload) + "'.");
    }

    /**
     * Claims every inline condition id a transition carries — its own pre- and post-conditions
     * plus those of the triggers attached to it — in the per-build table.
     *
     * @param canonical the per-build canonical payload table
     * @param td the transition to walk
     *
     * @throws TransfluxValidationException if any id is already held by a different payload
     */
    private void claimInlineConditions(Map<String, Object> canonical, TransitionDefImpl<T, ?> td) {
        for (ConditionDescriptor descriptor : td.getPreConditionDescriptors()) {
            claimInlineCondition(canonical, descriptor);
        }
        for (ConditionDescriptor descriptor : td.getPostConditionDescriptors()) {
            claimInlineCondition(canonical, descriptor);
        }
        for (ManualTriggerDefImpl<T, ?> mt : td.getManualTriggers()) {
            for (ConditionDescriptor descriptor : mt.getPreConditionDescriptors()) {
                claimInlineCondition(canonical, descriptor);
            }
        }
        for (DataTriggerDefImpl<T, ?> dt : td.getDataTriggers()) {
            claimInlineCondition(canonical, dt.getGateDescriptor());
        }
    }

    /**
     * Claims the id of an inline condition descriptor in the per-build table, so a condition
     * declared inline competes for its id with every other component the same way a step or an
     * operation does.
     * <p>
     * Reference descriptors are skipped: they name an existing registration rather than declaring
     * one. Expression descriptors with no explicit id are skipped too, since their id is derived
     * from the expression and its descriptor path and so cannot collide.
     *
     * @param canonical the per-build canonical payload table
     * @param descriptor the descriptor to claim; may be {@code null}
     *
     * @throws TransfluxValidationException if the id is already held by a different payload
     */
    static void claimInlineCondition(Map<String, Object> canonical, ConditionDescriptor descriptor) {
        if (descriptor == null || descriptor.id() == null) {
            return;
        }

        Object payload;
        if (descriptor instanceof ConditionDescriptor.InstanceBased instanceBased) {
            payload = instanceBased.condition();
        } else if (descriptor instanceof ConditionDescriptor.ClassBased classBased) {
            payload = classBased.conditionClass();
        } else if (descriptor instanceof ConditionDescriptor.PredicateBased predicateBased) {
            payload = predicateBased.predicate();
        } else if (descriptor instanceof ConditionDescriptor.ExpressionBased expressionBased) {
            payload = expressionBased.expression();
        } else {
            return;
        }

        claimCanonical(canonical, descriptor.id(), payload, "Condition");
    }

    private static String payloadClassName(Object payload) {
        if (payload instanceof Class<?> cls) {
            return cls.getName();
        }
        return payload.getClass().getName();
    }

    @Override
    public StateMachineDef<T> condition(String id, Condition<T, ?> condition) {
        requireNotBlank(id, "Condition ID");
        requireNotNull(condition, "Condition");
        registerConditionInstance(id, condition);
        return this;
    }

    @Override
    public StateMachineDef<T> condition(Identifiable conditionIdentifiable, Condition<T, ?> condition) {
        requireNotNull(conditionIdentifiable, "Condition identifiable");
        return condition(conditionIdentifiable.getId(), condition);
    }

    @Override
    public StateMachineDef<T> condition(String id, Class<? extends Condition<T, ?>> conditionClass) {
        requireNotBlank(id, "Condition ID");
        requireNotNull(conditionClass, "Condition class");
        registerConditionClass(id, conditionClass);
        return this;
    }

    @Override
    public StateMachineDef<T> condition(Identifiable conditionIdentifiable, Class<? extends Condition<T, ?>> conditionClass) {
        requireNotNull(conditionIdentifiable, "Condition identifiable");
        return condition(conditionIdentifiable.getId(), conditionClass);
    }

    @Override
    public StateMachineDef<T> condition(String id, BiPredicate<T, ?> predicate) {
        requireNotBlank(id, "Condition ID");
        requireNotNull(predicate, "Predicate");
        registerConditionPredicate(id, predicate);
        return this;
    }

    @Override
    public StateMachineDef<T> condition(Identifiable conditionIdentifiable, BiPredicate<T, ?> predicate) {
        requireNotNull(conditionIdentifiable, "Condition identifiable");
        return condition(conditionIdentifiable.getId(), predicate);
    }

    @Override
    public StateMachineDef<T> condition(String id, Predicate<T> predicate) {
        requireNotNull(predicate, "Predicate");
        return condition(id, adaptEntityPredicate(predicate));
    }

    @Override
    public StateMachineDef<T> condition(Identifiable conditionIdentifiable, Predicate<T> predicate) {
        requireNotNull(conditionIdentifiable, "Condition identifiable");
        return condition(conditionIdentifiable.getId(), predicate);
    }

    @Override
    public StateMachineDef<T> condition(String id, String spelExpression) {
        requireNotBlank(id, "Condition ID");
        requireNotBlank(spelExpression, "SpEL expression");
        registerConditionExpression(id, spelExpression);
        return this;
    }

    @Override
    public StateMachineDef<T> condition(Identifiable conditionIdentifiable, String spelExpression) {
        requireNotNull(conditionIdentifiable, "Condition identifiable");
        return condition(conditionIdentifiable.getId(), spelExpression);
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
    public <C> StateMachineDef<T> condition(Identifiable conditionIdentifiable, Class<C> contextType, Condition<T, C> condition) {
        requireNotNull(conditionIdentifiable, "Condition identifiable");
        return condition(conditionIdentifiable.getId(), contextType, condition);
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
    public <C> StateMachineDef<T> condition(Identifiable conditionIdentifiable, Class<C> contextType, Class<? extends Condition<T, C>> conditionClass) {
        requireNotNull(conditionIdentifiable, "Condition identifiable");
        return condition(conditionIdentifiable.getId(), contextType, conditionClass);
    }

    @Override
    public <C> StateMachineDef<T> conditionPredicate(String id, Class<C> contextType, BiPredicate<T, C> predicate) {
        requireNotBlank(id, "Condition ID");
        requireNotNull(contextType, "Context type");
        requireNotNull(predicate, "Predicate");
        registerConditionPredicate(id, predicate);
        tagContextType(id, contextType);
        return this;
    }

    @Override
    public <C> StateMachineDef<T> conditionPredicate(Identifiable conditionIdentifiable, Class<C> contextType, BiPredicate<T, C> predicate) {
        requireNotNull(conditionIdentifiable, "Condition identifiable");
        return conditionPredicate(conditionIdentifiable.getId(), contextType, predicate);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <C> StateMachineDef<T> conditionPredicate(String id, Class<C> contextType, Predicate<T> predicate) {
        requireNotNull(predicate, "Predicate");
        BiPredicate<T, C> adapted = (BiPredicate<T, C>) adaptEntityPredicate(predicate);
        return conditionPredicate(id, contextType, adapted);
    }

    @Override
    public <C> StateMachineDef<T> conditionPredicate(Identifiable conditionIdentifiable, Class<C> contextType, Predicate<T> predicate) {
        requireNotNull(conditionIdentifiable, "Condition identifiable");
        return conditionPredicate(conditionIdentifiable.getId(), contextType, predicate);
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
    public <C> StateMachineDef<T> conditionExpression(Identifiable conditionIdentifiable, Class<C> contextType, String spelExpression) {
        requireNotNull(conditionIdentifiable, "Condition identifiable");
        return conditionExpression(conditionIdentifiable.getId(), contextType, spelExpression);
    }

    @Override
    public <C> StateMachineDef<T> compositeOperation(String id, Class<C> contextType, Consumer<CompositeOperationDef<T, C>> configurer) {
        registerScopedCompositeOperation(id, configurer, contextType);
        return this;
    }

    @Override
    public <C> StateMachineDef<T> compositeOperation(Identifiable operationIdentifiable, Class<C> contextType, Consumer<CompositeOperationDef<T, C>> configurer) {
        requireNotNull(operationIdentifiable, "Operation identifiable");
        return compositeOperation(operationIdentifiable.getId(), contextType, configurer);
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
    public <C> StateMachineDef<T> operation(Identifiable operationIdentifiable, Class<C> contextType, Operation<T, C> operation) {
        requireNotNull(operationIdentifiable, "Operation identifiable");
        return operation(operationIdentifiable.getId(), contextType, operation);
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
    public <C> StateMachineDef<T> operation(Identifiable operationIdentifiable, Class<C> contextType, Class<? extends Operation<T, C>> operationClass) {
        requireNotNull(operationIdentifiable, "Operation identifiable");
        return operation(operationIdentifiable.getId(), contextType, operationClass);
    }

    @Override
    public <C> StateMachineDef<T> simpleOperation(String id, Class<C> contextType, Consumer<SimpleOperationDef<T, C>> configurer) {
        requireNotBlank(id, "Operation ID");
        requireNotNull(contextType, "Context type");
        requireNotNull(configurer, "Simple operation configurer");
        SimpleOperationDefImpl<T, C> def = new SimpleOperationDefImpl<>(id);
        ConfigurableDefImpl.runConfigurer(def, configurer);
        registerOperationDef(def);
        tagContextType(id, contextType);
        return this;
    }

    @Override
    public <C> StateMachineDef<T> simpleOperation(Identifiable operationIdentifiable, Class<C> contextType, Consumer<SimpleOperationDef<T, C>> configurer) {
        requireNotNull(operationIdentifiable, "Operation identifiable");
        return simpleOperation(operationIdentifiable.getId(), contextType, configurer);
    }

    private void registerOperationDef(SimpleOperationDefImpl<T, ?> def) {
        String id = def.getId();
        if (operationRegistrations.containsKey(id)) {
            throw new TransfluxValidationException("Operation ID '" + id + "' is already registered");
        }
        checkIdNotRegisteredAsStep(id);
        operationRegistrations.put(id, OperationRegistration.ofDef(def));
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
    public <P, N> StateMachineDef<T> mapper(Identifiable mapperIdentifiable,
                                            Class<P> parentType,
                                            Class<N> childType,
                                            ContextMapper<P, N> mapper) {
        requireNotNull(mapperIdentifiable, "Mapper identifiable");
        return mapper(mapperIdentifiable.getId(), parentType, childType, mapper);
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
    public <P, N> StateMachineDef<T> mapper(Identifiable mapperIdentifiable,
                                            Class<P> parentType,
                                            Class<N> childType,
                                            Class<? extends ContextMapper<P, N>> mapperClass) {
        requireNotNull(mapperIdentifiable, "Mapper identifiable");
        return mapper(mapperIdentifiable.getId(), parentType, childType, mapperClass);
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

    @Override
    public <P, N> StateMachineDef<T> mapper(Identifiable mapperIdentifiable,
                                            Class<P> parentType,
                                            Class<N> childType,
                                            Function<P, N> mapTo) {
        requireNotNull(mapperIdentifiable, "Mapper identifiable");
        return mapper(mapperIdentifiable.getId(), parentType, childType, mapTo);
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
     * @param id the mapper id
     *
     * @return the registered mapper def, or {@code null}
     */
    MapperDef<?, ?> getMapperDef(String id) {
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

    private void registerConditionPredicate(String id, BiPredicate<T, ?> predicate) {
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

    private static <T> BiPredicate<T, Object> adaptEntityPredicate(Predicate<T> predicate) {
        return (entity, context) -> predicate.test(entity);
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
     */
    Map<String, BoundCondition<T, ?>> buildBoundConditions() {
        Map<String, BoundCondition<T, ?>> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, ConditionRegistration<T>> e : conditionRegistrations.entrySet()) {
            resolved.put(e.getKey(), e.getValue().toBoundCondition(e.getKey()));
        }

        return Collections.unmodifiableMap(resolved);
    }

    /**
     * Resolves the SM-level step registrations into {@link BoundStep} instances. Composite-local
     * inline steps and conditionals are bound into their owning composite's scope by
     * {@link #bindCompositeScopes(RegistryImpl, Map)} and are not included
     * here.
     *
     * @return an unmodifiable map of SM-level step id to bound step
     */
    Map<String, BoundStep<T, ?>> buildBoundSteps() {
        Map<String, BoundStep<T, ?>> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, StepRegistration<T>> e : stepRegistrations.entrySet()) {
            resolved.put(e.getKey(), e.getValue().toBoundStep(e.getKey()));
        }
        return Collections.unmodifiableMap(resolved);
    }

    /**
     * Resolves the operation registrations into {@link BoundOperation} instances and surfaces
     * each one to the supplied callback. Framework-internal.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    void buildBoundOperationsIncrementally(StateMachineImpl<T> stateMachine,
                                           Consumer<BoundOperation<T, ?>> afterBuild) {
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, OperationRegistration<T>> e : operationRegistrations.entrySet()) {
            BoundOperation<T, ?> bo = e.getValue().toBoundOperation(e.getKey(), stateMachine);
            seen.add(e.getKey());
            afterBuild.accept(bo);
        }
        for (Map.Entry<String, CompositeOperationDefImpl<T, ?>> e : smCompositeOperations.entrySet()) {
            if (seen.contains(e.getKey())) {
                throw new TransfluxValidationException(
                    "Operation ID '" + e.getKey() + "' is already registered");
            }
            CompositeOperationDefImpl raw = e.getValue();
            BoundOperation<T, ?> bo = raw.buildBound(stateMachine);
            afterBuild.accept(bo);
        }
    }

    @Override
    public <C> StateMachineDef<T> forContext(Class<C> contextType, Consumer<ContextScope<T, C>> configurer) {
        requireNotNull(contextType, "Context type");
        requireNotNull(configurer, "forContext configurer");
        ContextScopeImpl<T, C> scope = new ContextScopeImpl<>(this, contextType);
        ConfigurableDefImpl.runConfigurer(scope, configurer);
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

    Class<?> getComponentContextType(String id) {
        return componentContextTypes.get(id);
    }

    Class<?> componentContextTypeOrDefault(String id) {
        return componentContextTypes.getOrDefault(id, Object.class);
    }

    Map<String, MapperDefImpl<?, ?>> getMapperRegistrations() {
        return mapperRegistrations;
    }

    CompositeOperationDefImpl<T, ?> getSmCompositeOperation(String id) {
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

    <C> void registerScopedCondition(String id, BiPredicate<T, C> predicate, Class<C> contextType) {
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
        ConfigurableDefImpl.runConfigurer(composite, configurer);
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
        ConfigurableDefImpl.runConfigurer(stateDef, configurer);
        return this;
    }

    @Override
    public StateMachineDef<T> state(Identifiable stateIdentifiable, Consumer<StateDef<T>> configurer) {
        requireNotNull(stateIdentifiable, "State identifiable");
        return state(stateIdentifiable.getId(), configurer);
    }

    @Override
    public StateMachineDef<T> onAnyStateEntry(String listenerId, StateListener<T> listener) {
        requireNotBlank(listenerId, "State listener ID");
        requireNotNull(listener, "State listener");
        return onAnyStateEntry(listenerId, l -> l.using(listener));
    }

    @Override
    public StateMachineDef<T> onAnyStateEntry(Identifiable listenerIdentifiable, StateListener<T> listener) {
        requireNotNull(listenerIdentifiable, "State listener identifiable");
        return onAnyStateEntry(listenerIdentifiable.getId(), listener);
    }

    @Override
    public StateMachineDef<T> onAnyStateEntry(String listenerId, Class<? extends StateListener<T>> listenerClass) {
        requireNotBlank(listenerId, "State listener ID");
        requireNotNull(listenerClass, "State listener class");
        return onAnyStateEntry(listenerId, l -> l.using(listenerClass));
    }

    @Override
    public StateMachineDef<T> onAnyStateEntry(Identifiable listenerIdentifiable,
                                              Class<? extends StateListener<T>> listenerClass) {
        requireNotNull(listenerIdentifiable, "State listener identifiable");
        return onAnyStateEntry(listenerIdentifiable.getId(), listenerClass);
    }

    @Override
    public StateMachineDef<T> onAnyStateEntry(String listenerId, Consumer<StateListenerDef<T>> configurer) {
        globalEntryListeners.add(declareStateListener(listenerId, configurer));
        return this;
    }

    @Override
    public StateMachineDef<T> onAnyStateEntry(Identifiable listenerIdentifiable,
                                              Consumer<StateListenerDef<T>> configurer) {
        requireNotNull(listenerIdentifiable, "State listener identifiable");
        return onAnyStateEntry(listenerIdentifiable.getId(), configurer);
    }

    @Override
    public StateMachineDef<T> onAnyStateExit(String listenerId, StateListener<T> listener) {
        requireNotBlank(listenerId, "State listener ID");
        requireNotNull(listener, "State listener");
        return onAnyStateExit(listenerId, l -> l.using(listener));
    }

    @Override
    public StateMachineDef<T> onAnyStateExit(Identifiable listenerIdentifiable, StateListener<T> listener) {
        requireNotNull(listenerIdentifiable, "State listener identifiable");
        return onAnyStateExit(listenerIdentifiable.getId(), listener);
    }

    @Override
    public StateMachineDef<T> onAnyStateExit(String listenerId, Class<? extends StateListener<T>> listenerClass) {
        requireNotBlank(listenerId, "State listener ID");
        requireNotNull(listenerClass, "State listener class");
        return onAnyStateExit(listenerId, l -> l.using(listenerClass));
    }

    @Override
    public StateMachineDef<T> onAnyStateExit(Identifiable listenerIdentifiable,
                                             Class<? extends StateListener<T>> listenerClass) {
        requireNotNull(listenerIdentifiable, "State listener identifiable");
        return onAnyStateExit(listenerIdentifiable.getId(), listenerClass);
    }

    @Override
    public StateMachineDef<T> onAnyStateExit(String listenerId, Consumer<StateListenerDef<T>> configurer) {
        globalExitListeners.add(declareStateListener(listenerId, configurer));
        return this;
    }

    @Override
    public StateMachineDef<T> onAnyStateExit(Identifiable listenerIdentifiable,
                                             Consumer<StateListenerDef<T>> configurer) {
        requireNotNull(listenerIdentifiable, "State listener identifiable");
        return onAnyStateExit(listenerIdentifiable.getId(), configurer);
    }

    /**
     * Returns the state listeners notified on entry to every state, in declaration order.
     *
     * @return the live global entry-listener list
     */
    List<StateListenerDefImpl<T>> getGlobalEntryListeners() {
        return globalEntryListeners;
    }

    /**
     * Returns the state listeners notified on exit from every state, in declaration order.
     *
     * @return the live global exit-listener list
     */
    List<StateListenerDefImpl<T>> getGlobalExitListeners() {
        return globalExitListeners;
    }

    @Override
    public StateMachineDef<T> onAnyTransitionStart(String listenerId, TransitionListener<T, Object> listener) {
        requireNotBlank(listenerId, "Transition listener ID");
        requireNotNull(listener, "Transition listener");
        return onAnyTransitionStart(listenerId, l -> l.using(listener));
    }

    @Override
    public StateMachineDef<T> onAnyTransitionStart(Identifiable listenerIdentifiable,
                                                   TransitionListener<T, Object> listener) {
        requireNotNull(listenerIdentifiable, "Transition listener identifiable");
        return onAnyTransitionStart(listenerIdentifiable.getId(), listener);
    }

    @Override
    public StateMachineDef<T> onAnyTransitionStart(String listenerId,
                                                   Class<? extends TransitionListener<T, Object>> listenerClass) {
        requireNotBlank(listenerId, "Transition listener ID");
        requireNotNull(listenerClass, "Transition listener class");
        return onAnyTransitionStart(listenerId, l -> l.using(listenerClass));
    }

    @Override
    public StateMachineDef<T> onAnyTransitionStart(Identifiable listenerIdentifiable,
                                                   Class<? extends TransitionListener<T, Object>> listenerClass) {
        requireNotNull(listenerIdentifiable, "Transition listener identifiable");
        return onAnyTransitionStart(listenerIdentifiable.getId(), listenerClass);
    }

    @Override
    public StateMachineDef<T> onAnyTransitionStart(String listenerId,
                                                   Consumer<TransitionListenerDef<T, Object>> configurer) {
        globalStartListeners.add(declareTransitionListener(listenerId, configurer));
        return this;
    }

    @Override
    public StateMachineDef<T> onAnyTransitionStart(Identifiable listenerIdentifiable,
                                                   Consumer<TransitionListenerDef<T, Object>> configurer) {
        requireNotNull(listenerIdentifiable, "Transition listener identifiable");
        return onAnyTransitionStart(listenerIdentifiable.getId(), configurer);
    }

    @Override
    public StateMachineDef<T> onAnyTransitionComplete(String listenerId, TransitionListener<T, Object> listener) {
        requireNotBlank(listenerId, "Transition listener ID");
        requireNotNull(listener, "Transition listener");
        return onAnyTransitionComplete(listenerId, l -> l.using(listener));
    }

    @Override
    public StateMachineDef<T> onAnyTransitionComplete(Identifiable listenerIdentifiable,
                                                      TransitionListener<T, Object> listener) {
        requireNotNull(listenerIdentifiable, "Transition listener identifiable");
        return onAnyTransitionComplete(listenerIdentifiable.getId(), listener);
    }

    @Override
    public StateMachineDef<T> onAnyTransitionComplete(String listenerId,
                                                      Class<? extends TransitionListener<T, Object>> listenerClass) {
        requireNotBlank(listenerId, "Transition listener ID");
        requireNotNull(listenerClass, "Transition listener class");
        return onAnyTransitionComplete(listenerId, l -> l.using(listenerClass));
    }

    @Override
    public StateMachineDef<T> onAnyTransitionComplete(Identifiable listenerIdentifiable,
                                                      Class<? extends TransitionListener<T, Object>> listenerClass) {
        requireNotNull(listenerIdentifiable, "Transition listener identifiable");
        return onAnyTransitionComplete(listenerIdentifiable.getId(), listenerClass);
    }

    @Override
    public StateMachineDef<T> onAnyTransitionComplete(String listenerId,
                                                      Consumer<TransitionListenerDef<T, Object>> configurer) {
        globalCompleteListeners.add(declareTransitionListener(listenerId, configurer));
        return this;
    }

    @Override
    public StateMachineDef<T> onAnyTransitionComplete(Identifiable listenerIdentifiable,
                                                      Consumer<TransitionListenerDef<T, Object>> configurer) {
        requireNotNull(listenerIdentifiable, "Transition listener identifiable");
        return onAnyTransitionComplete(listenerIdentifiable.getId(), configurer);
    }

    @Override
    public StateMachineDef<T> onAnyTransitionError(String listenerId, TransitionListener<T, Object> listener) {
        requireNotBlank(listenerId, "Transition listener ID");
        requireNotNull(listener, "Transition listener");
        return onAnyTransitionError(listenerId, l -> l.using(listener));
    }

    @Override
    public StateMachineDef<T> onAnyTransitionError(Identifiable listenerIdentifiable,
                                                   TransitionListener<T, Object> listener) {
        requireNotNull(listenerIdentifiable, "Transition listener identifiable");
        return onAnyTransitionError(listenerIdentifiable.getId(), listener);
    }

    @Override
    public StateMachineDef<T> onAnyTransitionError(String listenerId,
                                                   Class<? extends TransitionListener<T, Object>> listenerClass) {
        requireNotBlank(listenerId, "Transition listener ID");
        requireNotNull(listenerClass, "Transition listener class");
        return onAnyTransitionError(listenerId, l -> l.using(listenerClass));
    }

    @Override
    public StateMachineDef<T> onAnyTransitionError(Identifiable listenerIdentifiable,
                                                   Class<? extends TransitionListener<T, Object>> listenerClass) {
        requireNotNull(listenerIdentifiable, "Transition listener identifiable");
        return onAnyTransitionError(listenerIdentifiable.getId(), listenerClass);
    }

    @Override
    public StateMachineDef<T> onAnyTransitionError(String listenerId,
                                                   Consumer<TransitionListenerDef<T, Object>> configurer) {
        globalErrorListeners.add(declareTransitionListener(listenerId, configurer));
        return this;
    }

    @Override
    public StateMachineDef<T> onAnyTransitionError(Identifiable listenerIdentifiable,
                                                   Consumer<TransitionListenerDef<T, Object>> configurer) {
        requireNotNull(listenerIdentifiable, "Transition listener identifiable");
        return onAnyTransitionError(listenerIdentifiable.getId(), configurer);
    }

    /**
     * Returns the listeners notified when any transition starts, in declaration order.
     *
     * @return the live global start-listener list
     */
    List<TransitionListenerDefImpl<T, Object>> getGlobalStartListeners() {
        return globalStartListeners;
    }

    /**
     * Returns the listeners notified when any transition completes, in declaration order.
     *
     * @return the live global completion-listener list
     */
    List<TransitionListenerDefImpl<T, Object>> getGlobalCompleteListeners() {
        return globalCompleteListeners;
    }

    /**
     * Returns the listeners notified when any transition fails, in declaration order.
     *
     * @return the live global error-listener list
     */
    List<TransitionListenerDefImpl<T, Object>> getGlobalErrorListeners() {
        return globalErrorListeners;
    }

    /**
     * Reserves a listener id in the state-machine-wide listener namespace shared by state and
     * transition listeners.
     *
     * @param listenerId the id to claim; never {@code null} or blank
     *
     * @throws TransfluxValidationException if the id is blank or already claimed
     */
    void claimListenerId(String listenerId) {
        requireNotBlank(listenerId, "Listener ID");
        if (!listenerIds.add(listenerId)) {
            throw new TransfluxValidationException(
                "Listener ID '" + listenerId + "' is already registered");
        }
    }

    /**
     * Checks every transition's own listener ids against the shared namespace. Runs per build over
     * a throwaway copy of the eagerly-claimed ids, so building the same definition twice does not
     * report the second build's transition listeners as duplicates.
     */
    private void checkTransitionListenerIds() {
        Set<String> claimed = new HashSet<>(listenerIds);
        for (TransitionDefImpl<T, ?> td : transitionsById.values()) {
            claimTransitionListenerIds(td.getStartListeners(), td.getId(), "onStart", claimed);
            claimTransitionListenerIds(td.getCompleteListeners(), td.getId(), "onComplete", claimed);
            claimTransitionListenerIds(td.getErrorListeners(), td.getId(), "onError", claimed);
        }
    }

    /**
     * Claims one hook's listener ids, naming the owning transition and hook on a collision. A
     * transition's listeners are claimed at build time rather than at declaration, so the stack
     * trace points at {@code build()} and cannot locate the duplicate on its own.
     */
    private void claimTransitionListenerIds(List<? extends TransitionListenerDefImpl<T, ?>> listeners,
                                            String transitionId, String hook, Set<String> claimed) {
        for (TransitionListenerDefImpl<T, ?> ld : listeners) {
            if (!claimed.add(ld.getId())) {
                throw new TransfluxValidationException(
                    "Listener ID '" + ld.getId() + "' is already registered (declared on transition '"
                        + transitionId + "' via " + hook + ")");
            }
        }
    }

    private StateListenerDefImpl<T> declareStateListener(String listenerId,
                                                         Consumer<StateListenerDef<T>> configurer) {
        requireNotBlank(listenerId, "State listener ID");
        requireNotNull(configurer, "State listener configurer");
        StateListenerDefImpl<T> listenerDef = new StateListenerDefImpl<>(listenerId);
        // Claimed only once the configurer has returned, so a configurer that throws leaves the id
        // free for the caller's corrected retry.
        ConfigurableDefImpl.runConfigurer(listenerDef, configurer);
        claimListenerId(listenerId);
        return listenerDef;
    }

    private TransitionListenerDefImpl<T, Object> declareTransitionListener(
            String listenerId, Consumer<TransitionListenerDef<T, Object>> configurer) {
        requireNotBlank(listenerId, "Transition listener ID");
        requireNotNull(configurer, "Transition listener configurer");
        TransitionListenerDefImpl<T, Object> listenerDef = new TransitionListenerDefImpl<>(listenerId);
        // Claimed only once the configurer has returned, so a configurer that throws leaves the id
        // free for the caller's corrected retry.
        ConfigurableDefImpl.runConfigurer(listenerDef, configurer);
        claimListenerId(listenerId);
        return listenerDef;
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
     * @param sourceStateId the ID of the source state
     * @param targetStateId the ID of the target state
     * @param transitionId the unique identifier for the transition
     *
     * @return the newly registered transition def
     */
    TransitionDefImpl<T, Object> registerTransition(String sourceStateId, String targetStateId,
                                                           String transitionId) {
        return registerTransition(sourceStateId, targetStateId, transitionId, Object.class);
    }

    /**
     * Registers a transition between two states tagged with the supplied context type.
     *
     * @param sourceStateId the ID of the source state
     * @param targetStateId the ID of the target state
     * @param transitionId the unique identifier for the transition
     * @param contextType the transition's context class; never {@code null}
     * @param <C> the context type
     *
     * @return the newly registered transition def
     */
    <C> TransitionDefImpl<T, C> registerTransition(String sourceStateId, String targetStateId,
                                                          String transitionId, Class<C> contextType) {
        requireNotBlank(sourceStateId, "Source state ID");
        requireNotBlank(targetStateId, "Target state ID");
        requireNotBlank(transitionId, "Transition ID");
        requireNotNull(contextType, "Context type");

        if (transitionsById.containsKey(transitionId)) {
            throw new TransfluxValidationException("Transition ID " + transitionId + " already defined");
        }

        TransitionDefImpl<T, C> def = new TransitionDefImpl<>(transitionId, sourceStateId, targetStateId, contextType);
        transitionsById.put(transitionId, def);
        return def;
    }

    @Override
    public StateMachine<T> build() {
        validateContextCompatibilityAndCycles();
        StateMachineImpl<T> stateMachine = new StateMachineImpl<>(this);
        validateComponents(stateMachine.getComponentRegistry());
        return stateMachine;
    }

    /**
     * Runs {@link Component#validate()} over every registered component, once, after the registry
     * chain has been built and flattened. Validating here rather than at registration time means a
     * component's rules can rely on the whole definition being settled.
     *
     * <p>The walk covers the root registry <em>and</em> every composite's scope registry, because
     * {@link Registry#ids()} is local-only and inline composite members never appear in the root.
     * Ids are unique state-machine-wide, so validating each id once is enough — a flattened scope
     * repeats its ancestors' entries under the ids they already carry.
     *
     * @param rootRegistry the flattened root registry
     *
     * @throws TransfluxValidationException if any component rejects itself; the variant's own
     *         message names the offending component
     */
    void validateComponents(Registry<T> rootRegistry) {
        Set<String> validated = new HashSet<>();
        validateScope(rootRegistry, validated);

        for (TransitionDefImpl<T, ?> td : transitionsById.values()) {
            OperationDefImpl<T, ?, ?> op = td.getOperationDef();
            if (op != null) {
                validateScope(op.getScopeRegistry(), validated);
            }
        }

        for (CompositeOperationDefImpl<T, ?> composite : smCompositeOperations.values()) {
            validateScope(composite.getScopeRegistry(), validated);
        }
    }

    private void validateScope(Registry<T> registry, Set<String> validated) {
        if (registry == null) {
            return;
        }
        for (String id : registry.ids()) {
            if (validated.add(id)) {
                registry.get(id).orElseThrow().validate();
            }
        }
    }

    private void validateContextCompatibilityAndCycles() {
        checkTransitionListenerIds();
        for (TransitionDefImpl<T, ?> td : transitionsById.values()) {
            Class<?> transitionContext = td.getContextType();
            OperationDefImpl<T, ?, ?> op = td.getOperationDef();
            if (op != null) {
                op.checkRefs(transitionContext, "transition '" + td.getId() + "'", this);
            }
            checkConditionRefs(td);
        }
        for (Map.Entry<String, CompositeOperationDefImpl<T, ?>> e : smCompositeOperations.entrySet()) {
            Class<?> scopeContext = componentContextTypes.get(e.getKey());
            e.getValue().checkRefs(scopeContext, "SM-level composite '" + e.getKey() + "'", this);
        }
        detectCompositeCycles();
    }

    /**
     * Validates every by-id condition reference a transition carries — its own pre- and
     * post-conditions plus those of the triggers attached to it — against the context the
     * referencing site runs under.
     *
     * @param td the transition to walk
     *
     * @throws TransfluxValidationException if a referenced condition declares an incompatible
     *         context type
     */
    private void checkConditionRefs(TransitionDefImpl<T, ?> td) {
        Class<?> context = td.getContextType() != null ? td.getContextType() : Object.class;
        String label = "transition '" + td.getId() + "'";

        checkConditionRefs(td.getPreConditionDescriptors(), context, label, "pre-condition");
        checkConditionRefs(td.getPostConditionDescriptors(), context, label, "post-condition");

        for (ManualTriggerDefImpl<T, ?> mt : td.getManualTriggers()) {
            checkConditionRefs(mt.getPreConditionDescriptors(), context,
                "manual trigger '" + mt.getId() + "'", "pre-condition");
        }
        for (DataTriggerDefImpl<T, ?> dt : td.getDataTriggers()) {
            checkConditionRef(dt.getGateDescriptor(), context,
                "data trigger '" + dt.getId() + "'", "gate condition");
        }
    }

    private void checkConditionRefs(List<ConditionDescriptor> descriptors, Class<?> scopeContext,
                                    String scopeLabel, String kind) {
        for (ConditionDescriptor descriptor : descriptors) {
            checkConditionRef(descriptor, scopeContext, scopeLabel, kind);
        }
    }

    /**
     * Rejects a reference to a registered condition whose declared context type cannot accept the
     * referencing site's context. Only the reference form is checkable — the inline forms are typed
     * against the referencing def's own context by the compiler, and expressions are dynamic.
     * Conditions registered through the untyped overloads carry no declared type and are skipped.
     */
    private void checkConditionRef(ConditionDescriptor descriptor, Class<?> scopeContext,
                                   String scopeLabel, String kind) {
        if (!(descriptor instanceof ConditionDescriptor.Reference ref)) {
            return;
        }
        Class<?> componentContext = componentContextTypes.get(ref.id());
        if (componentContext == null
            || componentContext == Object.class
            || componentContext.isAssignableFrom(scopeContext)) {
            return;
        }
        throw new TransfluxValidationException(
            "Context type mismatch: " + scopeLabel + " (context " + scopeContext.getName()
                + ") references " + kind + " '" + ref.id() + "' declared for context "
                + componentContext.getName() + "; conditions take no mapper — register the condition"
                + " against a compatible context or declare it inline on " + scopeLabel);
    }

    private void detectCompositeCycles() {
        Set<String> visited = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        for (String id : smCompositeOperations.keySet()) {
            if (!visited.contains(id)) {
                dfsComposite(id, visited, stack);
            }
        }
    }

    private void dfsComposite(String id, Set<String> visited, Deque<String> stack) {
        if (stack.contains(id)) {
            List<String> path = new ArrayList<>(stack);
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

    /** @return the bound entity class */
    public Class<T> getEntityType() {
        return entityType;
    }

    /** @return the state machine name, or {@code null} if unset */
    public String getName() {
        return name;
    }

    /** @return the state machine description, or {@code null} if unset */
    public String getDescription() {
        return description;
    }

    /** @return the state machine version, or {@code null} if unset */
    public String getVersion() {
        return version;
    }

    /** @return the state resolver, or {@code null} if unset */
    public StateResolver<T> getStateResolver() {
        return stateResolver;
    }

    /** @return the state applier, or {@code null} if unset */
    public StateApplier<T> getStateApplier() {
        return stateApplier;
    }

    Map<String, StateDefImpl<T>> getStates() {
        return states;
    }

    Map<String, TransitionDefImpl<T, ?>> getTransitionsById() {
        return transitionsById;
    }

    private record StepRegistration<T>(Step<T, ?> instance, Class<? extends Step<T, ?>> stepClass,
                                       StepDefImpl<T, ?> def) {

        static <T> StepRegistration<T> ofInstance(Step<T, ?> instance) {
                return new StepRegistration<>(instance, null, null);
            }

            static <T> StepRegistration<T> ofClass(Class<? extends Step<T, ?>> stepClass) {
                return new StepRegistration<>(null, stepClass, null);
            }

            static <T> StepRegistration<T> ofDef(StepDefImpl<T, ?> def) {
                return new StepRegistration<>(null, null, def);
            }

            @SuppressWarnings({"unchecked", "rawtypes"})
            BoundStep<T, ?> toBoundStep(String id) {
                if (def != null) {
                    return def.buildBoundStep();
                }
                Step<T, ?> resolved = InstanceOrClassSource.resolve(instance, (Class) stepClass, "Step");
                return BoundStep.of(id, (Step) resolved);
            }
        }

    private record OperationRegistration<T>(Operation<T, ?> instance, Class<? extends Operation<T, ?>> operationClass,
                                            SimpleOperationDefImpl<T, ?> def) {

        static <T> OperationRegistration<T> ofInstance(Operation<T, ?> instance) {
                return new OperationRegistration<>(instance, null, null);
            }

            static <T> OperationRegistration<T> ofClass(Class<? extends Operation<T, ?>> operationClass) {
                return new OperationRegistration<>(null, operationClass, null);
            }

            static <T> OperationRegistration<T> ofDef(SimpleOperationDefImpl<T, ?> def) {
                return new OperationRegistration<>(null, null, def);
            }

            @SuppressWarnings({"unchecked", "rawtypes"})
            BoundOperation<T, ?> toBoundOperation(String id, StateMachineImpl<T> stateMachine) {
                if (def != null) {
                    return def.buildBound(stateMachine);
                }
                Operation<T, ?> resolved = InstanceOrClassSource.resolve(instance, (Class) operationClass, "Operation");
                return BoundOperation.of(id, (Operation) resolved);
            }
        }

    private record ConditionRegistration<T>(Condition<T, ?> instance, Class<? extends Condition<T, ?>> conditionClass,
                                            BiPredicate<T, ?> predicate, String expression) {

        static <T> ConditionRegistration<T> ofInstance(Condition<T, ?> instance) {
                return new ConditionRegistration<>(instance, null, null, null);
            }

            static <T> ConditionRegistration<T> ofClass(Class<? extends Condition<T, ?>> conditionClass) {
                return new ConditionRegistration<>(null, conditionClass, null, null);
            }

            static <T> ConditionRegistration<T> ofPredicate(BiPredicate<T, ?> predicate) {
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
                    return BoundCondition.of(id, (Condition) InstanceOrClassSource.resolve(null, (Class) conditionClass, "Condition"));
                }
                if (predicate != null) {
                    BiPredicate<T, Object> p = (BiPredicate<T, Object>) predicate;
                    Condition<T, Object> adapted = (entity, ctx, transition) -> p.test(entity, ctx);
                    return BoundCondition.of(id, (Condition) adapted);
                }
                return BoundCondition.fromExpression(id, expression);
            }
        }

    TransitionDef<T, ?> getTransition(String transitionId) {
        var td = transitionsById.get(transitionId);
        if (td == null) {
            throw new TransfluxValidationException("Transition '" + transitionId + "' not found");
        }
        return td;
    }
}
