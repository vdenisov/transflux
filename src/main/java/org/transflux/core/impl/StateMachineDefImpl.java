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

import org.transflux.core.ContextScope;
import org.transflux.core.Identifiable;
import org.transflux.core.StateMachine;
import org.transflux.core.StateMachineDef;
import org.transflux.core.action.Action;
import org.transflux.core.action.ActionKind;
import org.transflux.core.action.ActionListener;
import org.transflux.core.action.ActionListenerDef;
import org.transflux.core.action.ContextMapper;
import org.transflux.core.action.ForkRejectionPolicy;
import org.transflux.core.action.MapperDef;
import org.transflux.core.action.ConditionalOperationDef;
import org.transflux.core.action.OperationDef;
import org.transflux.core.action.StepDef;
import org.transflux.core.condition.Condition;
import org.transflux.core.condition.ConditionDescriptor;
import org.transflux.core.exception.TransfluxValidationException;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
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
    private Class<T> entityType;
    private String name;
    private String description;
    private String version;

    private StateResolver<T> stateResolver;
    private StateApplier<T> stateApplier;

    private final Map<String, StateDefImpl<T>> states = new LinkedHashMap<>();

    /**
     * SM-level imperative actions, whichever verb declared them. There is one map because there
     * is one id namespace; keeping two was only ever a way to tell the kinds apart inside it.
     */
    private final Map<String, ActionRegistration<T>> actionRegistrations = new LinkedHashMap<>();

    private final Map<String, ConditionRegistration<T>> conditionRegistrations = new LinkedHashMap<>();

    private final Map<String, ActionDefImpl<T, ?, ?>> smCompositeOperations = new LinkedHashMap<>();

    private final Map<String, MapperDefImpl<?, ?>> mapperRegistrations = new LinkedHashMap<>();

    private final Map<String, Class<?>> componentContextTypes = new LinkedHashMap<>();

    private final Map<String, TransitionDefImpl<T, ?>> transitionsById = new LinkedHashMap<>();

    private ExecutorService asyncExecutor;

    private AsyncPoolSpec asyncPoolSpec;

    private ForkRejectionPolicy forkRejectionPolicy;

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
     * Action listeners attached to every action rather than to one. Kept in declaration order; the
     * listeners of whichever action is running run ahead of these. They span actions declared
     * against differing context types and so are typed against {@link Object}.
     */
    private final List<ActionListenerDefImpl<T, Object>> globalActionStartListeners = new ArrayList<>();
    private final List<ActionListenerDefImpl<T, Object>> globalActionCompleteListeners = new ArrayList<>();
    private final List<ActionListenerDefImpl<T, Object>> globalActionErrorListeners = new ArrayList<>();

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
        warnIfSet(this.name, name, "Name", Loggers.BUILD_VALIDATION);

        this.name = name;
        return this;
    }

    @Override
    public StateMachineDef<T> withDescription(String description) {
        warnIfSet(this.description, description, "Description", Loggers.BUILD_VALIDATION);

        this.description = description;
        return this;
    }

    @Override
    public StateMachineDef<T> withVersion(String version) {
        warnIfSet(this.version, version, "Version", Loggers.BUILD_VALIDATION);

        this.version = version;
        return this;
    }

    @Override
    public StateMachineDef<T> withStateResolver(StateResolver<T> stateResolver) {
        requireNotNull(stateResolver, "State resolver");

        if (this.stateResolver != null) {
            Loggers.BUILD_VALIDATION.warn("State resolver overwritten, current={}, incoming={}",
                                          this.stateResolver.getClass().getName(),
                                          stateResolver.getClass().getName());
        }

        this.stateResolver = stateResolver;
        return this;
    }

    @Override
    public StateMachineDef<T> withAsyncExecutor(ExecutorService executor) {
        requireNotNull(executor, "Async executor");
        warnIfAsyncTargetSet();

        this.asyncPoolSpec = null;
        this.asyncExecutor = executor;
        return this;
    }

    @Override
    public StateMachineDef<T> withAsyncPool(int threads, int queueCapacity) {
        return applyPoolSpec(new AsyncPoolSpec(threads, queueCapacity, null));
    }

    @Override
    public StateMachineDef<T> withAsyncPool(int threads, int queueCapacity, ThreadFactory threadFactory) {
        requireNotNull(threadFactory, "Async thread factory");
        return applyPoolSpec(new AsyncPoolSpec(threads, queueCapacity, threadFactory));
    }

    @Override
    public StateMachineDef<T> withForkRejectionPolicy(ForkRejectionPolicy policy) {
        requireNotNull(policy, "Fork rejection policy");
        ValidationUtils.warnIfSet(this.forkRejectionPolicy != null, "Fork rejection policy",
                                  "StateMachineDef", Loggers.BUILD_VALIDATION);

        this.forkRejectionPolicy = policy;
        return this;
    }

    @Override
    public StateMachineDef<T> step(String id, Action<T, ?> step) {
        requireNotBlank(id, "Step ID");
        requireNotNull(step, "Step");
        registerStepInstance(id, step);
        return this;
    }

    @Override
    public StateMachineDef<T> step(Identifiable stepIdentifiable, Action<T, ?> step) {
        requireNotNull(stepIdentifiable, "Step identifiable");
        return step(stepIdentifiable.getId(), step);
    }

    @Override
    public StateMachineDef<T> step(String id, Class<? extends Action<T, ?>> stepClass) {
        requireNotBlank(id, "Step ID");
        requireNotNull(stepClass, "Step class");
        registerStepClass(id, stepClass);
        return this;
    }

    @Override
    public StateMachineDef<T> step(Identifiable stepIdentifiable, Class<? extends Action<T, ?>> stepClass) {
        requireNotNull(stepIdentifiable, "Step identifiable");
        return step(stepIdentifiable.getId(), stepClass);
    }

    @Override
    public StateMachineDef<T> step(String id, Consumer<StepDef<T, Object>> configurer) {
        requireNotBlank(id, "Step ID");
        requireNotNull(configurer, "Step configurer");
        StepDefImpl<T, Object> def = new StepDefImpl<>(id);
        ConfigurableDefImpl.runConfigurer(def, configurer);
        registerStepDef(def);
        return this;
    }

    @Override
    public StateMachineDef<T> step(Identifiable stepIdentifiable, Consumer<StepDef<T, Object>> configurer) {
        requireNotNull(stepIdentifiable, "Step identifiable");
        return step(stepIdentifiable.getId(), configurer);
    }

    @Override
    public <C> StateMachineDef<T> step(String id, Class<C> contextType, Action<T, C> step) {
        requireNotBlank(id, "Step ID");
        requireNotNull(contextType, "Context type");
        requireNotNull(step, "Step");
        registerStepInstance(id, step);
        tagContextType(id, contextType);
        return this;
    }

    @Override
    public <C> StateMachineDef<T> step(Identifiable stepIdentifiable, Class<C> contextType, Action<T, C> step) {
        requireNotNull(stepIdentifiable, "Step identifiable");
        return step(stepIdentifiable.getId(), contextType, step);
    }

    @Override
    public <C> StateMachineDef<T> step(String id, Class<C> contextType, Class<? extends Action<T, C>> stepClass) {
        requireNotBlank(id, "Step ID");
        requireNotNull(contextType, "Context type");
        requireNotNull(stepClass, "Step class");
        registerStepClass(id, stepClass);
        tagContextType(id, contextType);
        return this;
    }

    @Override
    public <C> StateMachineDef<T> step(Identifiable stepIdentifiable, Class<C> contextType, Class<? extends Action<T, C>> stepClass) {
        requireNotNull(stepIdentifiable, "Step identifiable");
        return step(stepIdentifiable.getId(), contextType, stepClass);
    }

    @Override
    public <C> StateMachineDef<T> step(String id, Class<C> contextType, Consumer<StepDef<T, C>> configurer) {
        requireNotBlank(id, "Step ID");
        requireNotNull(contextType, "Context type");
        requireNotNull(configurer, "Step configurer");
        registerScopedStep(id, configurer, contextType);
        return this;
    }

    @Override
    public <C> StateMachineDef<T> step(Identifiable stepIdentifiable, Class<C> contextType, Consumer<StepDef<T, C>> configurer) {
        requireNotNull(stepIdentifiable, "Step identifiable");
        return step(stepIdentifiable.getId(), contextType, configurer);
    }

    private void registerStepDef(StepDefImpl<T, ?> def) {
        String id = def.getId();
        if (actionRegistrations.containsKey(id)) {
            throw new TransfluxValidationException("Action ID '" + id + "' is already registered");
        }
        checkIdNotRegisteredAsContainer(id);
        actionRegistrations.put(id, ActionRegistration.ofDef(def));
    }

    private void registerStepInstance(String id, Action<T, ?> action) {
        ActionRegistration<T> existing = actionRegistrations.get(id);
        if (existing == null) {
            checkIdNotRegisteredAsContainer(id);
            actionRegistrations.put(id, ActionRegistration.ofInstance(action));
            return;
        }

        if (existing.instance != null && existing.instance == action) {
            return;
        }

        throw new TransfluxValidationException("Action ID '" + id + "' is already registered");
    }

    private void registerStepClass(String id, Class<? extends Action<T, ?>> actionClass) {
        ActionRegistration<T> existing = actionRegistrations.get(id);
        if (existing == null) {
            checkIdNotRegisteredAsContainer(id);
            actionRegistrations.put(id, ActionRegistration.ofClass(actionClass));
            return;
        }

        if (existing.actionClass != null && existing.actionClass.equals(actionClass)) {
            return;
        }

        throw new TransfluxValidationException("Action ID '" + id + "' is already registered");
    }

    /**
     * Imperative actions and declarative containers live in separate maps because the build
     * resolves them at different points, but they share one id namespace, so a collision across
     * the two is still a collision.
     */
    private void checkIdNotRegisteredAsContainer(String id) {
        if (smCompositeOperations.containsKey(id)) {
            throw new TransfluxValidationException(
                "ID '" + id + "' is already registered as an operation");
        }
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

        for (Map.Entry<String, ActionRegistration<T>> e : actionRegistrations.entrySet()) {
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
            ActionDefImpl<T, ?, ?> op = td.getActionDef();
            if (op != null) {
                op.bindScope(rootRegistry, canonical, conditionRegistry);
            }
        }

        for (ActionDefImpl<T, ?, ?> composite : smCompositeOperations.values()) {
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
            ActionDefImpl<T, ?, ?> op = td.getActionDef();
            if (op != null) {
                Optional<String> hit = op.scanScopeFor(id, excludingCompositeId);
                if (hit.isPresent()) {
                    return hit;
                }
            }
        }

        for (ActionDefImpl<T, ?, ?> composite : smCompositeOperations.values()) {
            Optional<String> hit = composite.scanScopeFor(id, excludingCompositeId);
            if (hit.isPresent()) {
                return hit;
            }
        }

        return Optional.empty();
    }

    private static <T> Object payloadOf(ActionRegistration<T> reg) {
        if (reg.def != null) {
            return reg.def;
        }
        return reg.instance != null ? reg.instance : reg.actionClass;
    }

    /**
     * Flattens the scope registry of every composite operation declared on this state-machine
     * def. Called after every component has been bound into its appropriate registry so each
     * scope's {@link Registry#resolve(String)} becomes a single local-map lookup.
     */
    void flattenCompositeScopes() {
        for (TransitionDefImpl<T, ?> td : transitionsById.values()) {
            ActionDefImpl<T, ?, ?> op = td.getActionDef();
            if (op != null) {
                op.flattenScope();
            }
        }

        for (ActionDefImpl<T, ?, ?> composite : smCompositeOperations.values()) {
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
    public <C> StateMachineDef<T> operation(String id, Class<C> contextType, Consumer<OperationDef<T, C>> configurer) {
        registerScopedCompositeOperation(id, configurer, contextType);
        return this;
    }

    @Override
    public <C> StateMachineDef<T> conditional(String id, Class<C> contextType,
                                              Consumer<ConditionalOperationDef<T, C>> configurer) {
        registerScopedConditional(id, configurer, contextType);
        return this;
    }

    @Override
    public <C> StateMachineDef<T> operation(Identifiable operationIdentifiable, Class<C> contextType, Consumer<OperationDef<T, C>> configurer) {
        requireNotNull(operationIdentifiable, "Operation identifiable");
        return operation(operationIdentifiable.getId(), contextType, configurer);
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
        registerMapper(configuredMapper(id, parentType, childType, d -> d.using(mapper)));
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
        registerMapper(configuredMapper(id, parentType, childType, d -> d.using(mapperClass)));
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
    public <P, N> StateMachineDef<T> mapperDef(String id,
                                               Class<P> parentType,
                                               Class<N> childType,
                                               Consumer<MapperDef<P, N>> configurer) {
        requireNotBlank(id, "Mapper ID");
        requireNotNull(parentType, "Mapper parent type");
        requireNotNull(childType, "Mapper child type");
        requireNotNull(configurer, "Mapper configurer");
        registerMapper(configuredMapper(id, parentType, childType, configurer));
        return this;
    }

    @Override
    public <P, N> StateMachineDef<T> mapperDef(Identifiable mapperIdentifiable,
                                               Class<P> parentType,
                                               Class<N> childType,
                                               Consumer<MapperDef<P, N>> configurer) {
        requireNotNull(mapperIdentifiable, "Mapper identifiable");
        return mapperDef(mapperIdentifiable.getId(), parentType, childType, configurer);
    }

    private static <P, N> MapperDefImpl<P, N> configuredMapper(String id, Class<P> parentType,
                                                               Class<N> childType,
                                                               Consumer<MapperDef<P, N>> configurer) {
        MapperDefImpl<P, N> def = new MapperDefImpl<>(id, parentType, childType);
        ConfigurableDefImpl.runConfigurer(def, configurer);
        return def;
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
     * Resolves the SM-level step registrations into {@link BoundAction} instances. Composite-local
     * inline steps and conditionals are bound into their owning composite's scope by
     * {@link #bindCompositeScopes(RegistryImpl, Map)} and are not included
     * here.
     *
     * @return an unmodifiable map of SM-level step id to bound step
     */
    Map<String, BoundAction<T, ?>> buildBoundActions() {
        Map<String, BoundAction<T, ?>> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, ActionRegistration<T>> e : actionRegistrations.entrySet()) {
            resolved.put(e.getKey(), e.getValue().toBoundAction(e.getKey()));
        }
        return Collections.unmodifiableMap(resolved);
    }

    /**
     * Resolves the SM-level declarative containers into {@link BoundAction} instances and
     * surfaces each one to the supplied callback. The members each one will iterate are installed
     * later, by {@link #bindDeferredMembers}. Framework-internal.
     */
    void buildBoundOperations(Consumer<BoundAction<T, ?>> afterBuild) {
        for (Map.Entry<String, ActionDefImpl<T, ?, ?>> e : smCompositeOperations.entrySet()) {
            if (actionRegistrations.containsKey(e.getKey())) {
                throw new TransfluxValidationException(
                    "Operation ID '" + e.getKey() + "' is already registered");
            }
            afterBuild.accept(e.getValue().buildBound());
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

    /**
     * Records a pool spec, which competes with a host-supplied executor for the same slot: where
     * forked members run. The later declaration wins, as it does for every other def-side setter.
     *
     * @param spec the sizing to build from
     *
     * @return this def for chaining
     */
    private StateMachineDef<T> applyPoolSpec(AsyncPoolSpec spec) {
        warnIfAsyncTargetSet();

        this.asyncExecutor = null;
        this.asyncPoolSpec = spec;
        return this;
    }

    private void warnIfAsyncTargetSet() {
        ValidationUtils.warnIfSet(asyncExecutor != null || asyncPoolSpec != null,
                                  "Async executor", "StateMachineDef", Loggers.BUILD_VALIDATION);
    }

    Map<String, MapperDefImpl<?, ?>> getMapperRegistrations() {
        return mapperRegistrations;
    }

    /**
     * Returns the executor the host supplied for forked members.
     *
     * @return the executor, or {@code null} when the state machine should build its own
     */
    ExecutorService getAsyncExecutor() {
        return asyncExecutor;
    }

    /**
     * Returns the pool sizing to build from, which is the default unless the host said otherwise.
     *
     * @return the pool spec; never {@code null}
     */
    AsyncPoolSpec getAsyncPoolSpec() {
        return asyncPoolSpec != null ? asyncPoolSpec : AsyncPoolSpec.DEFAULT;
    }

    /**
     * Returns what to do when a submission is refused.
     *
     * @return the policy; never {@code null}
     */
    ForkRejectionPolicy getForkRejectionPolicy() {
        return forkRejectionPolicy != null ? forkRejectionPolicy : ForkRejectionPolicy.DROP;
    }

    /**
     * Reports whether anything in this definition forks, which is what decides whether the state
     * machine builds a pool at all.
     * <p>
     * The roots are exactly two - a container registered at SM level, or one attached to a
     * transition - since every other container is declared inside one of them. Each root is then
     * walked in full: {@link OperationDefImpl#declaresFork()} descends through any conditional's
     * branches and any container declared in place, since a member forks through the same path
     * wherever it sits.
     *
     * @return whether any container declares a forked member
     */
    boolean definitionForks() {
        for (ActionDefImpl<T, ?, ?> composite : smCompositeOperations.values()) {
            if (composite.declaresFork()) {
                return true;
            }
        }
        for (TransitionDefImpl<T, ?> td : transitionsById.values()) {
            ActionDefImpl<T, ?, ?> op = td.getActionDef();
            if (op != null && op.declaresFork()) {
                return true;
            }
        }
        return false;
    }

    ActionDefImpl<T, ?, ?> getSmCompositeOperation(String id) {
        return smCompositeOperations.get(id);
    }

    <C> void registerScopedStep(String id, Action<T, C> step, Class<C> contextType) {
        registerStepInstance(id, step);
        tagContextType(id, contextType);
    }

    <C> void registerScopedStep(String id, Class<? extends Action<T, C>> stepClass, Class<C> contextType) {
        registerStepClass(id, stepClass);
        tagContextType(id, contextType);
    }

    <C> void registerScopedStep(String id, Consumer<StepDef<T, C>> configurer, Class<C> contextType) {
        StepDefImpl<T, C> def = new StepDefImpl<>(id, contextType);
        ConfigurableDefImpl.runConfigurer(def, configurer);
        registerStepDef(def);
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

    <C> void registerScopedCompositeOperation(String id,
                                              Consumer<OperationDef<T, C>> configurer,
                                              Class<C> contextType) {
        requireNotBlank(id, "Composite operation ID");
        requireNotNull(contextType, "Context type");
        requireNotNull(configurer, "Composite operation configurer");
        claimSmLevelActionId(id);

        OperationDefImpl<T, C> composite = new OperationDefImpl<>(id);
        ConfigurableDefImpl.runConfigurer(composite, configurer);
        smCompositeOperations.put(id, composite);
        tagContextType(id, contextType);
    }

    /**
     * Registers a conditional at state-machine level, so it can be referenced by id like any other
     * declarative action. It shares the container's namespace and the container's build passes -
     * a conditional is an action, and being registered is a property of the declaration site
     * rather than of the form.
     *
     * @param id the conditional's id
     * @param configurer callback declaring the branches
     * @param contextType the context the conditional runs against
     * @param <C> the conditional's context type
     */
    <C> void registerScopedConditional(String id,
                                       Consumer<ConditionalOperationDef<T, C>> configurer,
                                       Class<C> contextType) {
        requireNotBlank(id, "Conditional operation ID");
        requireNotNull(contextType, "Context type");
        requireNotNull(configurer, "Conditional operation configurer");
        claimSmLevelActionId(id);

        ConditionalOperationDefImpl<T, C> conditional = new ConditionalOperationDefImpl<>(id);
        ConfigurableDefImpl.runConfigurer(conditional, configurer);
        smCompositeOperations.put(id, conditional);
        tagContextType(id, contextType);
    }

    private void claimSmLevelActionId(String id) {
        if (smCompositeOperations.containsKey(id)) {
            throw new TransfluxValidationException(
                "Composite operation id '" + id + "' is already registered at SM level");
        }
        if (actionRegistrations.containsKey(id) || conditionRegistrations.containsKey(id)) {
            throw new TransfluxValidationException(
                "Component id '" + id + "' is already registered");
        }
    }

    @Override
    public StateMachineDef<T> withStateApplier(StateApplier<T> stateApplier) {
        requireNotNull(stateApplier, "State applier");

        if (this.stateApplier != null) {
            Loggers.BUILD_VALIDATION.warn("State applier overwritten, current={}, incoming={}",
                                          this.stateApplier.getClass().getName(),
                                          stateApplier.getClass().getName());
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

    @Override
    public StateMachineDef<T> onAnyActionStart(String listenerId, ActionListener<T, Object> listener) {
        requireNotBlank(listenerId, "Action listener ID");
        requireNotNull(listener, "Action listener");
        return onAnyActionStart(listenerId, l -> l.using(listener));
    }

    @Override
    public StateMachineDef<T> onAnyActionStart(Identifiable listenerIdentifiable,
                                               ActionListener<T, Object> listener) {
        requireNotNull(listenerIdentifiable, "Action listener identifiable");
        return onAnyActionStart(listenerIdentifiable.getId(), listener);
    }

    @Override
    public StateMachineDef<T> onAnyActionStart(String listenerId,
                                               Class<? extends ActionListener<T, Object>> listenerClass) {
        requireNotBlank(listenerId, "Action listener ID");
        requireNotNull(listenerClass, "Action listener class");
        return onAnyActionStart(listenerId, l -> l.using(listenerClass));
    }

    @Override
    public StateMachineDef<T> onAnyActionStart(Identifiable listenerIdentifiable,
                                               Class<? extends ActionListener<T, Object>> listenerClass) {
        requireNotNull(listenerIdentifiable, "Action listener identifiable");
        return onAnyActionStart(listenerIdentifiable.getId(), listenerClass);
    }

    @Override
    public StateMachineDef<T> onAnyActionStart(String listenerId,
                                               Consumer<ActionListenerDef<T, Object>> configurer) {
        globalActionStartListeners.add(declareActionListener(listenerId, configurer));
        return this;
    }

    @Override
    public StateMachineDef<T> onAnyActionStart(Identifiable listenerIdentifiable,
                                               Consumer<ActionListenerDef<T, Object>> configurer) {
        requireNotNull(listenerIdentifiable, "Action listener identifiable");
        return onAnyActionStart(listenerIdentifiable.getId(), configurer);
    }

    @Override
    public StateMachineDef<T> onAnyActionComplete(String listenerId, ActionListener<T, Object> listener) {
        requireNotBlank(listenerId, "Action listener ID");
        requireNotNull(listener, "Action listener");
        return onAnyActionComplete(listenerId, l -> l.using(listener));
    }

    @Override
    public StateMachineDef<T> onAnyActionComplete(Identifiable listenerIdentifiable,
                                                  ActionListener<T, Object> listener) {
        requireNotNull(listenerIdentifiable, "Action listener identifiable");
        return onAnyActionComplete(listenerIdentifiable.getId(), listener);
    }

    @Override
    public StateMachineDef<T> onAnyActionComplete(String listenerId,
                                                  Class<? extends ActionListener<T, Object>> listenerClass) {
        requireNotBlank(listenerId, "Action listener ID");
        requireNotNull(listenerClass, "Action listener class");
        return onAnyActionComplete(listenerId, l -> l.using(listenerClass));
    }

    @Override
    public StateMachineDef<T> onAnyActionComplete(Identifiable listenerIdentifiable,
                                                  Class<? extends ActionListener<T, Object>> listenerClass) {
        requireNotNull(listenerIdentifiable, "Action listener identifiable");
        return onAnyActionComplete(listenerIdentifiable.getId(), listenerClass);
    }

    @Override
    public StateMachineDef<T> onAnyActionComplete(String listenerId,
                                                  Consumer<ActionListenerDef<T, Object>> configurer) {
        globalActionCompleteListeners.add(declareActionListener(listenerId, configurer));
        return this;
    }

    @Override
    public StateMachineDef<T> onAnyActionComplete(Identifiable listenerIdentifiable,
                                                  Consumer<ActionListenerDef<T, Object>> configurer) {
        requireNotNull(listenerIdentifiable, "Action listener identifiable");
        return onAnyActionComplete(listenerIdentifiable.getId(), configurer);
    }

    @Override
    public StateMachineDef<T> onAnyActionError(String listenerId, ActionListener<T, Object> listener) {
        requireNotBlank(listenerId, "Action listener ID");
        requireNotNull(listener, "Action listener");
        return onAnyActionError(listenerId, l -> l.using(listener));
    }

    @Override
    public StateMachineDef<T> onAnyActionError(Identifiable listenerIdentifiable,
                                               ActionListener<T, Object> listener) {
        requireNotNull(listenerIdentifiable, "Action listener identifiable");
        return onAnyActionError(listenerIdentifiable.getId(), listener);
    }

    @Override
    public StateMachineDef<T> onAnyActionError(String listenerId,
                                               Class<? extends ActionListener<T, Object>> listenerClass) {
        requireNotBlank(listenerId, "Action listener ID");
        requireNotNull(listenerClass, "Action listener class");
        return onAnyActionError(listenerId, l -> l.using(listenerClass));
    }

    @Override
    public StateMachineDef<T> onAnyActionError(Identifiable listenerIdentifiable,
                                               Class<? extends ActionListener<T, Object>> listenerClass) {
        requireNotNull(listenerIdentifiable, "Action listener identifiable");
        return onAnyActionError(listenerIdentifiable.getId(), listenerClass);
    }

    @Override
    public StateMachineDef<T> onAnyActionError(String listenerId,
                                               Consumer<ActionListenerDef<T, Object>> configurer) {
        globalActionErrorListeners.add(declareActionListener(listenerId, configurer));
        return this;
    }

    @Override
    public StateMachineDef<T> onAnyActionError(Identifiable listenerIdentifiable,
                                               Consumer<ActionListenerDef<T, Object>> configurer) {
        requireNotNull(listenerIdentifiable, "Action listener identifiable");
        return onAnyActionError(listenerIdentifiable.getId(), configurer);
    }

    /**
     * Returns the listeners notified when any action starts, in declaration order.
     *
     * @return the live global action-start listener list
     */
    List<ActionListenerDefImpl<T, Object>> getGlobalActionStartListeners() {
        return globalActionStartListeners;
    }

    /**
     * Returns the listeners notified when any action completes, in declaration order.
     *
     * @return the live global action-complete listener list
     */
    List<ActionListenerDefImpl<T, Object>> getGlobalActionCompleteListeners() {
        return globalActionCompleteListeners;
    }

    /**
     * Returns the listeners notified when any action fails, in declaration order.
     *
     * @return the live global action-error listener list
     */
    List<ActionListenerDefImpl<T, Object>> getGlobalActionErrorListeners() {
        return globalActionErrorListeners;
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
     * Checks the listener ids owned by transitions and by actions against the shared namespace.
     * Runs per build over a throwaway copy of the eagerly-claimed ids, so building the same
     * definition twice does not report the second build's own listeners as duplicates.
     */
    private void checkOwnedListenerIds() {
        Set<String> claimed = new HashSet<>(listenerIds);

        for (TransitionDefImpl<T, ?> td : transitionsById.values()) {
            claimTransitionListenerIds(td.getStartListeners(), td.getId(), "onStart", claimed);
            claimTransitionListenerIds(td.getCompleteListeners(), td.getId(), "onComplete", claimed);
            claimTransitionListenerIds(td.getErrorListeners(), td.getId(), "onError", claimed);
        }

        BiConsumer<String, String> actionListenerIds = (listenerId, ownerLabel) -> {
            if (!claimed.add(listenerId)) {
                throw new TransfluxValidationException(
                    "Listener ID '" + listenerId + "' is already registered (declared on "
                        + ownerLabel + ")");
            }
        };

        for (ActionRegistration<T> registration : actionRegistrations.values()) {
            if (registration.def() != null) {
                registration.def().collectListenerIds(actionListenerIds);
            }
        }
        for (ActionDefImpl<T, ?, ?> composite : smCompositeOperations.values()) {
            composite.collectListenerIds(actionListenerIds);
        }
        for (TransitionDefImpl<T, ?> td : transitionsById.values()) {
            ActionDefImpl<T, ?, ?> actionDef = td.getActionDef();
            if (actionDef != null) {
                actionDef.collectListenerIds(actionListenerIds);
            }
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

    private ActionListenerDefImpl<T, Object> declareActionListener(
            String listenerId, Consumer<ActionListenerDef<T, Object>> configurer) {
        requireNotBlank(listenerId, "Action listener ID");
        requireNotNull(configurer, "Action listener configurer");
        ActionListenerDefImpl<T, Object> listenerDef = new ActionListenerDefImpl<>(listenerId);
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
        // The three phase boundaries, reported so that "why did my definition build into *that*" has
        // somewhere to start. Each phase names itself before running, so a throw is attributable to
        // the phase whose line was last emitted — which is why all three go to one logger rather than
        // to the logger of the phase they announce: split across leaves, the attribution would hold
        // only for a host who enabled every one of them.
        Loggers.BUILD_LIFECYCLE.debug("Validating context compatibility and cycles");
        validateContextCompatibilityAndCycles();

        Loggers.BUILD_LIFECYCLE.debug("Populating registries and binding components");
        StateMachineImpl<T> stateMachine = new StateMachineImpl<>(this);

        Loggers.BUILD_LIFECYCLE.debug("Validating registered components");
        validateComponents(stateMachine.getComponentRegistry());

        // The one build-time INFO: rare, and the only report a host gets that its definition
        // resolved into the shape it expected. The component count is the root registry's, matching
        // the name the binding pass uses. A generation counter belongs here too once definition
        // replacement lands.
        Loggers.BUILD_LIFECYCLE.info("State machine built, states={}, transitions={}, triggers={}, rootComponents={}",
                                     stateMachine.stateCount(), stateMachine.transitionCount(),
                                     stateMachine.triggerCount(), stateMachine.componentCount());

        return stateMachine;
    }

    /**
     * Names a state-machine-level registration as a position in the definition tree. It uses the
     * def's own label, so a registered conditional reads as one rather than as a "composite" -
     * the same phrasing the transition-attached path produces for the same def.
     */
    private static String smLevelLabel(ActionDefImpl<?, ?, ?> def) {
        return "SM-level " + def.defLabel();
    }

    /**
     * Names an action attached to a transition as a position in the definition tree. The
     * transition is the root and the action is nested beneath it, so the label names both - an
     * SM-level container, being a root itself, needs no such composition.
     */
    private static String attachedActionLabel(TransitionDefImpl<?, ?> td, ActionDefImpl<?, ?, ?> op) {
        return "transition '" + td.getId() + "' > " + op.defLabel();
    }

    /**
     * Resolves every declared member - a container's own, and those inside any conditional it
     * holds - and installs the bound members on the executors that iterate them.
     * <p>
     * Members cannot bind during {@link OperationDefImpl#buildBound}, because a container's or a
     * conditional's bound action is registered <em>into</em> the very scope its own members
     * resolve against: a sibling may name it by id, and such a reference captures the bound
     * action by value. Binding here, once every scope is populated and every container is
     * registered, also frees a member to reference a container declared after its own.
     *
     * @param stateMachine the state machine under construction
     *
     * @throws TransfluxValidationException if a member names an id that no action in scope carries
     */
    void bindDeferredMembers(StateMachineImpl<T> stateMachine) {
        for (TransitionDefImpl<T, ?> td : transitionsById.values()) {
            ActionDefImpl<T, ?, ?> op = td.getActionDef();
            if (op != null) {
                op.bindMembers(stateMachine, attachedActionLabel(td, op));
            }
        }

        for (Map.Entry<String, ActionDefImpl<T, ?, ?>> e : smCompositeOperations.entrySet()) {
            e.getValue().bindMembers(stateMachine, smLevelLabel(e.getValue()));
        }
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
            ActionDefImpl<T, ?, ?> op = td.getActionDef();
            if (op != null) {
                op.collectScopes(scope -> validateScope(scope, validated));
            }
        }

        for (ActionDefImpl<T, ?, ?> composite : smCompositeOperations.values()) {
            composite.collectScopes(scope -> validateScope(scope, validated));
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
        checkOwnedListenerIds();
        for (TransitionDefImpl<T, ?> td : transitionsById.values()) {
            Class<?> transitionContext = td.getContextType();
            ActionDefImpl<T, ?, ?> op = td.getActionDef();
            if (op != null) {
                op.checkRefs(transitionContext, attachedActionLabel(td, op), this);
            }
            checkConditionRefs(td);
        }
        for (Map.Entry<String, ActionDefImpl<T, ?, ?>> e : smCompositeOperations.entrySet()) {
            Class<?> scopeContext = componentContextTypes.get(e.getKey());
            e.getValue().checkRefs(scopeContext, smLevelLabel(e.getValue()), this);
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
        Map<String, List<String>> nodes = collectCycleNodes();
        Set<String> visited = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        for (String id : nodes.keySet()) {
            if (!visited.contains(id)) {
                dfsComposite(id, nodes, visited, stack);
            }
        }
    }

    /**
     * Collects every action a by-id reference can reach, keyed by the id that reaches it, with the
     * ids it reaches in turn as its outgoing edges.
     * <p>
     * A container declared in place and a conditional are nodes in their own right, not merely
     * members of one: each is registered under its id in the enclosing scope, so a sibling - or
     * one of its own descendants - can name it, and a self-reference resolves and then recurses
     * without bound at execution. Rooting only at state-machine level left that edge invisible.
     * <p>
     * <b>Only an id that resolves becomes a node.</b> A container attached to a transition is
     * registered in no registry, so nothing can name it and it can never lie on a cycle; entering
     * it as a node would let its id shadow a state-machine-level container's and answer for edges
     * that are not that container's, which both rejects sound definitions and hides real cycles.
     * Its descendants are registered in its scope, so they are nodes and are rooted like any
     * other - a cycle buried under a transition-attached container is still found.
     * <p>
     * Registered containers go in first, and the rest through {@code putIfAbsent}: this pass runs
     * before ids are claimed, so a nested id colliding with a state-machine-level one is still
     * possible here, and a real container must not be shadowed by it and reported as a cycle
     * before the collision itself is reported.
     * <p>
     * The edge lists stay over-approximate in the way this detector already is: it does not reason
     * about which branch of a conditional is selectable, just as it does not reason about whether
     * a container is ever reached.
     * <p>
     * Two consequences of that over-approximation are known and accepted, because both only affect
     * <em>which</em> message a rejected definition gets, never whether a sound one is accepted.
     * An action's edges include its whole subtree's, so a cycle closed from inside a nested
     * position is attributed to the outermost one and the reported path is shorter than the real
     * chain. And the walk does not model lexical visibility, so a reference to an id the referrer
     * cannot actually see can be reported as a cycle rather than as an unknown id - this pass runs
     * before any scope exists, so it has nothing to check visibility against. Naming the real
     * fault in both cases needs structural parent-to-child edges and a visibility-aware walk;
     * neither is worth it while the outcome is a failed build either way.
     *
     * @return each node's outgoing ids, in declaration order
     */
    private Map<String, List<String>> collectCycleNodes() {
        Map<String, List<String>> nodes = new LinkedHashMap<>();
        for (Map.Entry<String, ActionDefImpl<T, ?, ?>> e : smCompositeOperations.entrySet()) {
            nodes.put(e.getKey(), e.getValue().ownByIdReferenceIds());
        }

        BiConsumer<String, List<String>> sink = nodes::putIfAbsent;
        for (ActionDefImpl<T, ?, ?> composite : smCompositeOperations.values()) {
            composite.collectNestedCycleNodes(sink);
        }

        for (TransitionDefImpl<T, ?> td : transitionsById.values()) {
            ActionDefImpl<T, ?, ?> op = td.getActionDef();
            if (op != null) {
                op.collectNestedCycleNodes(sink);
            }
        }

        return nodes;
    }

    private void dfsComposite(String id, Map<String, List<String>> nodes, Set<String> visited,
                              Deque<String> stack) {
        if (stack.contains(id)) {
            // The stack is LIFO, so it iterates most-recent-first; reverse it to walk the path the
            // way the definition reads, then keep everything from the repeated id onwards.
            List<String> path = new ArrayList<>(stack);
            Collections.reverse(path);
            path.add(id);
            throw new TransfluxValidationException(
                "Composite operation cycle detected: "
                    + String.join(" -> ", path.subList(path.indexOf(id), path.size())));
        }
        if (visited.contains(id)) {
            return;
        }
        List<String> edges = nodes.get(id);
        if (edges == null) {
            return;
        }
        stack.push(id);
        for (String refId : edges) {
            if (nodes.containsKey(refId)) {
                dfsComposite(refId, nodes, visited, stack);
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

    private record ActionRegistration<T>(Action<T, ?> instance, Class<? extends Action<T, ?>> actionClass,
                                         StepDefImpl<T, ?> def) {

        static <T> ActionRegistration<T> ofInstance(Action<T, ?> instance) {
            return new ActionRegistration<>(instance, null, null);
        }

        static <T> ActionRegistration<T> ofClass(Class<? extends Action<T, ?>> actionClass) {
            return new ActionRegistration<>(null, actionClass, null);
        }

        static <T> ActionRegistration<T> ofDef(StepDefImpl<T, ?> def) {
            return new ActionRegistration<>(null, null, def);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        BoundAction<T, ?> toBoundAction(String id) {
            if (def != null) {
                return def.buildBoundAction();
            }
            Action<T, ?> resolved = InstanceOrClassSource.resolve(instance, (Class) actionClass, "Step");
            return BoundAction.of(id, (Action) resolved, ActionKind.STEP);
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
