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
import org.transflux.core.StateMachine;
import org.transflux.core.StateMachineDef;
import org.transflux.core.action.Action;
import org.transflux.core.action.ActionKind;
import org.transflux.core.action.ActionListener;
import org.transflux.core.action.ActionListenerDef;
import org.transflux.core.action.ContextMapper;
import org.transflux.core.action.ActionPhase;
import org.transflux.core.action.AsyncRejectionPolicy;
import org.transflux.core.action.MapperDef;
import org.transflux.core.action.ChoiceDef;
import org.transflux.core.action.OperationDef;
import org.transflux.core.action.StepDef;
import org.transflux.core.condition.Condition;
import org.transflux.core.condition.ConditionDescriptor;
import org.transflux.core.exception.TransfluxValidationException;
import org.transflux.core.logging.ExecutionLogging;
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
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.function.BiFunction;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import org.transflux.core.trigger.DataTriggerDef;
import org.transflux.core.trigger.EventTriggerDef;
import org.transflux.core.trigger.ManualTriggerDef;
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
    private String id;
    private String name;
    private String description;
    private String version;

    private StateResolver<? super T> stateResolver;
    private StateApplier<? super T> stateApplier;

    private final Map<String, StateDefImpl<T>> states = new LinkedHashMap<>();

    /**
     * SM-level imperative actions, whichever verb declared them. There is one map because there
     * is one id namespace; keeping two was only ever a way to tell the kinds apart inside it.
     */
    private final Map<String, ActionRegistration<T>> actionRegistrations = new LinkedHashMap<>();

    private final Map<String, ConditionRegistration<T>> conditionRegistrations = new LinkedHashMap<>();

    private final Map<String, ActionDefImpl<T, ?, ?>> smCompositeOperations = new LinkedHashMap<>();

    /**
     * Resolves listener references for the build in progress. Per build rather than per definition:
     * it caches one bound record per registration, and a second build must not hand out the first
     * build's.
     * <p>
     * Held on the definition, so one definition builds one machine at a time. Building the same
     * definition twice is supported and is what the clearing in {@code build()} is for; building it
     * twice <em>concurrently</em> is not, in line with §2.1.2 leaving concurrency to the host - a
     * def is mutable throughout its life and was never safe to share across threads.
     */
    private ListenerRegistrations<T> listenerBinder;

    /** Registered listeners, per category, sharing the state-machine-wide listener namespace. */
    private final Map<String, StateListenerDefImpl<T>> stateListenerRegistrations = new LinkedHashMap<>();
    private final Map<String, TransitionListenerDefImpl<T, ?>> transitionListenerRegistrations =
        new LinkedHashMap<>();
    private final Map<String, ActionListenerDefImpl<T, ?>> actionListenerRegistrations = new LinkedHashMap<>();

    /** Registered triggers, in their own state-machine-wide namespace. */
    private final Map<String, TriggerDefImpl<T, ?, ?>> triggerRegistrations = new LinkedHashMap<>();

    private final Map<String, MapperDefImpl<?, ?>> mapperRegistrations = new LinkedHashMap<>();

    /**
     * Every registered action, condition and mapper id, claimed as it is registered. The kinds are
     * kept in maps of their own, which the build reads at different points, but they share this one
     * namespace; each build copies it and claims the inline declarations on top.
     */
    private final Map<String, CanonicalClaim> componentIds = new LinkedHashMap<>();

    private final Map<String, Class<?>> componentContextTypes = new LinkedHashMap<>();

    /**
     * The context each inline-declared action runs against, keyed by the scope that declares it
     * and then by id, collected per build. Inline ids never reach {@link #componentContextTypes},
     * which only registrations write, so a by-id reference to one has nothing else to be checked
     * against.
     * <p>
     * Keyed by scope because that is what inline visibility is: an id is answerable only from a
     * position that can resolve it, and resolution walks a scope chain. A flat map would have to
     * answer for an id the referencing position cannot see, and answering on context there
     * produces advice - "supply a mapper" - that cannot make the reference resolve.
     */
    private final Map<String, Map<String, Class<?>>> inlineMemberContexts = new LinkedHashMap<>();

    private final Map<String, TransitionDefImpl<T, ?>> transitionsById = new LinkedHashMap<>();

    private ExecutorService asyncExecutor;

    private AsyncPoolSpec asyncPoolSpec;

    private AsyncRejectionPolicy asyncRejectionPolicy;


    /**
     * State listeners attached to every state rather than to one. Kept in declaration order; the
     * per-state listeners of whichever state is being entered or left run ahead of these.
     */
    private final List<ListenerEntry<StateListenerDefImpl<T>>> globalEntryListeners = new ArrayList<>();
    private final List<ListenerEntry<StateListenerDefImpl<T>>> globalExitListeners = new ArrayList<>();

    /**
     * Transition listeners attached to every transition rather than to one. Kept in declaration
     * order; the per-transition listeners of whichever transition is executing run ahead of these.
     * They span transitions with differing context types and so are typed against {@link Object}.
     */
    private final List<ListenerEntry<TransitionListenerDefImpl<T, Object>>> globalStartListeners = new ArrayList<>();
    private final List<ListenerEntry<TransitionListenerDefImpl<T, Object>>> globalCompleteListeners = new ArrayList<>();
    private final List<ListenerEntry<TransitionListenerDefImpl<T, Object>>> globalErrorListeners = new ArrayList<>();

    /**
     * Action listeners attached to every action rather than to one. Kept in declaration order; the
     * listeners of whichever action is running run ahead of these. They span actions declared
     * against differing context types and so are typed against {@link Object}.
     */
    private final List<ListenerEntry<ActionListenerDefImpl<T, Object>>> globalActionStartListeners = new ArrayList<>();
    private final List<ListenerEntry<ActionListenerDefImpl<T, Object>>> globalActionCompleteListeners = new ArrayList<>();
    private final List<ListenerEntry<ActionListenerDefImpl<T, Object>>> globalActionErrorListeners = new ArrayList<>();

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
    public StateMachineDef<T> withId(String id) {
        warnIfSet(this.id, id, "Id", Loggers.BUILD_VALIDATION);

        this.id = id;
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
    public StateMachineDef<T> withStateResolver(StateResolver<? super T> stateResolver) {
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
    public StateMachineDef<T> withAsyncPool() {
        return applyPoolSpec(AsyncPoolSpec.defaults());
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
    public StateMachineDef<T> withAsyncRejectionPolicy(AsyncRejectionPolicy policy) {
        requireNotNull(policy, "Async rejection policy");
        ValidationUtils.warnIfSet(this.asyncRejectionPolicy != null, "Async rejection policy",
                                  "StateMachineDef", Loggers.BUILD_VALIDATION);

        this.asyncRejectionPolicy = policy;
        return this;
    }

    @Override
    public StateMachineDef<T> step(String id, Action<? super T, ?> step) {
        requireNotBlank(id, "Step ID");
        requireNotNull(step, "Step");
        registerStepInstance(id, step);
        return this;
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
    public <C> StateMachineDef<T> step(String id, Class<C> contextType, Action<? super T, C> step) {
        requireNotBlank(id, "Step ID");
        requireNotNull(contextType, "Context type");
        requireNotNull(step, "Step");
        registerStepInstance(id, step);
        tagContextType(id, contextType);
        return this;
    }

    @Override
    public <C> StateMachineDef<T> step(String id, Class<C> contextType, Consumer<StepDef<T, C>> configurer) {
        requireNotBlank(id, "Step ID");
        requireNotNull(contextType, "Context type");
        requireNotNull(configurer, "Step configurer");
        registerScopedStep(id, configurer, contextType);
        return this;
    }

    private void registerStepDef(StepDefImpl<T, ?> def) {
        claimCanonical(componentIds, def.getId(), def, "Step");
        actionRegistrations.put(def.getId(), ActionRegistration.ofDef(def));
    }

    private void registerStepInstance(String id, Action<? super T, ?> action) {
        // The same instance registered again is one registration; the claim says so.
        claimCanonical(componentIds, id, action, "Step");
        actionRegistrations.putIfAbsent(id, ActionRegistration.ofInstance(action));
    }

    /**
     * Wires a {@link RegistryImpl} scope onto every composite operation declared on this
     * state-machine def — both the SM-level composites and those embedded in transitions — and
     * populates each scope with the composite's inline-declared members (steps, operations) and
     * the bound steps for the choices it owns. Each scope's parent is the supplied root
     * registry, so by-id refs from inside a composite first check the composite-local entries
     * and fall back to root.
     *
     * <p>This pass also enforces SM-wide id uniqueness for inline declarations: each is claimed in
     * a copy of the registrations' id table, and a collision anywhere fails the build with a
     * {@link TransfluxValidationException}.
     *
     * @param rootRegistry the state-machine's root registry — the parent of every composite scope
     * @param conditionRegistry the resolved SM-wide condition registry
     */
    void bindCompositeScopes(RegistryImpl<T> rootRegistry,
                                    Map<String, BoundCondition<T, ?>> conditionRegistry) {
        // Registrations claimed their ids as they were made; inline declarations claim theirs here.
        Map<String, CanonicalClaim> canonical = new HashMap<>(componentIds);

        claimRegisteredTriggerConditions(canonical);

        for (TransitionDefImpl<T, ?> td : transitionsById.values()) {
            claimInlineConditions(canonical, td);
            ActionDefImpl<T, ?, ?> op = td.getActionDef();
            if (op != null) {
                // The body declares no context of its own, so it runs against the transition's -
                // the same seeding checkRefs uses for this position.
                op.bindScope(rootRegistry, canonical, conditionRegistry, td.getContextType());
            }
        }

        for (ActionDefImpl<T, ?, ?> composite : smCompositeOperations.values()) {
            composite.bindScope(rootRegistry, canonical, conditionRegistry, null);
        }
    }

    /**
     * Walks every known composite operation def — both transition-attached top-level composites
     * and SM-level registered composites — and returns the id of the first composite whose
     * <em>local</em> scope registry contains an entry under {@code id}, excluding the composite
     * that originated the search. Used by {@link ActionRef} resolution to enrich "unknown id"
     * diagnostics with the composite that does hold the id.
     *
     * <p>The walk is transitive, so the holder it names may be nested beneath the failing
     * position rather than beside it. The caller must not assume either.
     *
     * <p>Returns {@link Optional#empty()} when nothing holds the id inline.
     */
    Optional<String> findInlineScopeHolding(String id, String excludingCompositeId) {
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
     * Claims {@code id} in an id table - the definition's registrations, or a build's copy of them
     * that inline declarations extend. The same payload claimed again - the same instance, or an
     * equal expression - is one declaration seen twice.
     *
     * @param canonical the table
     * @param id the id
     * @param payload what is declared under it
     * @param kind the declaring kind, capitalised as it leads the message
     *
     * @throws TransfluxValidationException if the id is taken by anything else
     */
    static void claimCanonical(Map<String, CanonicalClaim> canonical, String id, Object payload, String kind) {
        CanonicalClaim existing = canonical.get(id);

        if (existing == null) {
            canonical.put(id, new CanonicalClaim(payload, kind));
            return;
        }

        if (existing.payload() == payload) {
            return;
        }

        if (existing.payload() instanceof String && payload instanceof String && existing.payload().equals(payload)) {
            return;
        }

        String clash = existing.kind().equals(kind)
            ? "is already registered by another " + kind.toLowerCase(Locale.ROOT)
            : "is already registered as " + article(existing.kind()) + " " + existing.kind().toLowerCase(Locale.ROOT);
        // One declaration referenced from both places helps within a kind or between actions, not across kinds.
        String reference = existing.kind().equals(kind) || isAction(existing.kind()) && isAction(kind)
            ? ", or declare it once and reference it by id"
            : "";

        throw new TransfluxValidationException(kind + " id '" + id + "' " + clash + ". Ids are unique across the"
            + " state machine wherever they are declared, so give one of them another id" + reference + ".");
    }

    private static boolean isAction(String kind) {
        return kind.equals("Step") || kind.equals("Operation") || kind.equals("Choice");
    }

    private static String article(String kind) {
        return "AEIOU".indexOf(kind.charAt(0)) >= 0 ? "an" : "a";
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
    /**
     * Claims the inline condition ids a registered trigger declares, which no transition walk
     * reaches: a registration is not owned by one.
     *
     * @param canonical the per-build id table
     */
    private void claimRegisteredTriggerConditions(Map<String, CanonicalClaim> canonical) {
        for (TriggerDefImpl<T, ?, ?> registered : triggerRegistrations.values()) {
            if (registered instanceof ManualTriggerDefImpl<?, ?> manual) {
                for (ConditionDescriptor descriptor : manual.getPreConditionDescriptors()) {
                    claimInlineCondition(canonical, descriptor);
                }
            } else if (registered instanceof DataTriggerDefImpl<?, ?> data) {
                claimInlineCondition(canonical, data.getGateDescriptor());
            }
        }
    }

    private void claimInlineConditions(Map<String, CanonicalClaim> canonical, TransitionDefImpl<T, ?> td) {
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
    static void claimInlineCondition(Map<String, CanonicalClaim> canonical, ConditionDescriptor descriptor) {
        if (descriptor == null || descriptor.id() == null) {
            return;
        }

        Object payload;
        if (descriptor instanceof ConditionDescriptor.InstanceBased instanceBased) {
            payload = instanceBased.condition();
        } else if (descriptor instanceof ConditionDescriptor.PredicateBased predicateBased) {
            payload = predicateBased.predicate();
        } else if (descriptor instanceof ConditionDescriptor.ExpressionBased expressionBased) {
            payload = expressionBased.expression();
        } else {
            return;
        }

        claimCanonical(canonical, descriptor.id(), payload, "Condition");
    }

    @Override
    public StateMachineDef<T> condition(String id, Condition<? super T, ?> condition) {
        requireNotBlank(id, "Condition ID");
        requireNotNull(condition, "Condition");
        registerConditionInstance(id, condition);
        return this;
    }

    @Override
    public StateMachineDef<T> condition(String id, BiPredicate<? super T, ?> predicate) {
        requireNotBlank(id, "Condition ID");
        requireNotNull(predicate, "Predicate");
        registerConditionPredicate(id, predicate);
        return this;
    }

    @Override
    public StateMachineDef<T> condition(String id, Predicate<? super T> predicate) {
        requireNotNull(predicate, "Predicate");
        return condition(id, adaptEntityPredicate(predicate));
    }

    @Override
    public StateMachineDef<T> condition(String id, String spelExpression) {
        requireNotBlank(id, "Condition ID");
        requireNotBlank(spelExpression, "SpEL expression");
        registerConditionExpression(id, spelExpression);
        return this;
    }

    @Override
    public <C> StateMachineDef<T> condition(String id, Class<C> contextType, Condition<? super T, C> condition) {
        requireNotBlank(id, "Condition ID");
        requireNotNull(contextType, "Context type");
        requireNotNull(condition, "Condition");
        registerConditionInstance(id, condition);
        tagContextType(id, contextType);
        return this;
    }

    @Override
    public <C> StateMachineDef<T> condition(String id, Class<C> contextType, BiPredicate<? super T, C> predicate) {
        requireNotBlank(id, "Condition ID");
        requireNotNull(contextType, "Context type");
        requireNotNull(predicate, "Predicate");
        registerConditionPredicate(id, predicate);
        tagContextType(id, contextType);
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <C> StateMachineDef<T> condition(String id, Class<C> contextType, Predicate<? super T> predicate) {
        requireNotNull(predicate, "Predicate");
        BiPredicate<? super T, C> adapted = (BiPredicate<? super T, C>) adaptEntityPredicate(predicate);
        return condition(id, contextType, adapted);
    }

    @Override
    public <C> StateMachineDef<T> condition(String id, Class<C> contextType, String spelExpression) {
        requireNotBlank(id, "Condition ID");
        requireNotNull(contextType, "Context type");
        requireNotBlank(spelExpression, "SpEL expression");
        registerConditionExpression(id, spelExpression);
        tagContextType(id, contextType);
        return this;
    }

    @Override
    public <C> StateMachineDef<T> operation(String id, Class<C> contextType, Consumer<OperationDef<T, C>> configurer) {
        registerScopedCompositeOperation(id, configurer, contextType);
        return this;
    }

    @Override
    public <C> StateMachineDef<T> choice(String id, Class<C> contextType, Consumer<ChoiceDef<T, C>> configurer) {
        registerScopedChoice(id, configurer, contextType);
        return this;
    }

    @Override
    public StateMachineDef<T> manualTrigger(String id, Consumer<ManualTriggerDef<T, Object>> configurer) {
        return manualTrigger(id, Object.class, configurer);
    }

    @Override
    public <C> StateMachineDef<T> manualTrigger(String id, Class<C> contextType,
                                     Consumer<ManualTriggerDef<T, C>> configurer) {
        registerTrigger(id, contextType, configurer, ManualTriggerDefImpl::new, "manual trigger");
        return this;
    }

    @Override
    public StateMachineDef<T> eventTrigger(String id, Consumer<EventTriggerDef<T, Object>> configurer) {
        return eventTrigger(id, Object.class, configurer);
    }

    @Override
    public <C> StateMachineDef<T> eventTrigger(String id, Class<C> contextType,
                                     Consumer<EventTriggerDef<T, C>> configurer) {
        registerTrigger(id, contextType, configurer, EventTriggerDefImpl::new, "event trigger");
        return this;
    }

    @Override
    public StateMachineDef<T> dataTrigger(String id, Consumer<DataTriggerDef<T, Object>> configurer) {
        return dataTrigger(id, Object.class, configurer);
    }

    @Override
    public <C> StateMachineDef<T> dataTrigger(String id, Class<C> contextType,
                                     Consumer<DataTriggerDef<T, C>> configurer) {
        registerTrigger(id, contextType, configurer, DataTriggerDefImpl::new, "data trigger");
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
        registerMapper(configuredMapper(id, parentType, childType, d -> d.using(mapper)));
        return this;
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

    private static <P, N> MapperDefImpl<P, N> configuredMapper(String id, Class<P> parentType,
                                                               Class<N> childType,
                                                               Consumer<MapperDef<P, N>> configurer) {
        MapperDefImpl<P, N> def = new MapperDefImpl<>(id, parentType, childType);
        ConfigurableDefImpl.runConfigurer(def, configurer);
        return def;
    }

    private void registerMapper(MapperDefImpl<?, ?> def) {
        claimCanonical(componentIds, def.getId(), def, "Mapper");
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

    private void registerConditionInstance(String id, Condition<? super T, ?> condition) {
        ConditionRegistration<T> existing = conditionRegistrations.get(id);
        if (existing != null && existing.instance != null && existing.instance == condition) {
            return;
        }
        claimCondition(id, ConditionRegistration.ofInstance(condition));
    }

    private void registerConditionPredicate(String id, BiPredicate<? super T, ?> predicate) {
        ConditionRegistration<T> existing = conditionRegistrations.get(id);
        if (existing != null && existing.predicate != null && existing.predicate == predicate) {
            return;
        }
        claimCondition(id, ConditionRegistration.ofPredicate(predicate));
    }

    private static <T> BiPredicate<T, Object> adaptEntityPredicate(Predicate<? super T> predicate) {
        return (entity, context) -> predicate.test(entity);
    }

    private void registerConditionExpression(String id, String expression) {
        SpelConditionEvaluator.shared().validate(expression);
        ConditionRegistration<T> existing = conditionRegistrations.get(id);
        if (existing != null && existing.expression != null && existing.expression.equals(expression)) {
            return;
        }
        claimCondition(id, ConditionRegistration.ofExpression(expression));
    }

    /**
     * Claims a condition registration's id and records it. The table holds the registration record
     * rather than what it wraps, so an inline condition never matches it: the inline copy binds on
     * its own, possibly against another context, and one id would then name two conditions.
     *
     * @param id the condition's id
     * @param registration what is registered under it
     *
     * @throws TransfluxValidationException if the id is already taken
     */
    private void claimCondition(String id, ConditionRegistration<T> registration) {
        claimCanonical(componentIds, id, registration, "Condition");
        conditionRegistrations.put(id, registration);
    }

    /**
     * Resolves the condition registrations into {@link BoundCondition} instances. Called from
     * {@link StateMachineSnapshot} during state machine construction.
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
     * inline steps and choices are bound into their owning composite's scope by
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

    /**
     * Reports the context a by-id reference's callee runs against, as seen from a position whose
     * enclosing scopes are {@code visibleScopes}.
     * <p>
     * The scopes are walked innermost first, so the entry found is the one the reference will
     * actually resolve to - the same order {@code Registry} follows through its parent chain.
     *
     * @param id the referenced id
     * @param visibleScopes the ids of the scopes the referencing position can resolve through,
     *                      innermost first
     *
     * @return the callee's context, or {@code Object} when nothing here can answer - an untyped
     *         registration, or an inline declaration the referencing position cannot see
     */
    Class<?> componentContextTypeOrDefault(String id, Collection<String> visibleScopes) {
        Class<?> registered = componentContextTypes.get(id);
        if (registered != null) {
            return registered;
        }
        for (String scope : visibleScopes) {
            Class<?> declared = inlineMemberContexts.getOrDefault(scope, Map.of()).get(id);
            if (declared != null) {
                return declared;
            }
        }
        return Object.class;
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
        return asyncPoolSpec != null ? asyncPoolSpec : AsyncPoolSpec.defaults();
    }

    /**
     * Reports whether the host asked for a pool of its own sizing, which is the second reason to
     * build one.
     * <p>
     * {@link #definitionForks()} only sees members a sequence declared, so a state machine whose
     * only forks are written inside action bodies would otherwise build no executor at all.
     * Declaring a pool is how such a definition says it forks.
     *
     * @return whether any {@code withAsyncPool} form was called
     */
    boolean declaresAsyncPool() {
        return asyncPoolSpec != null;
    }

    /**
     * Reports whether anything in this definition asks to wait for capacity, which decides whether
     * the pool is built with a fair queue.
     * <p>
     * Like every other definition-time walk this sees declared positions only, so a {@code BLOCK}
     * chosen inside an action body waits on an unfair queue. That costs ordering under contention,
     * never correctness.
     *
     * @return {@code true} if the machine default or any action declares
     *         {@link AsyncRejectionPolicy#BLOCK}
     */
    boolean declaresBlockingRejection() {
        if (getAsyncRejectionPolicy() == AsyncRejectionPolicy.BLOCK) {
            return true;
        }

        boolean[] blocks = {false};
        visitActionDefs(def -> blocks[0] |= def.getAsyncRejectionPolicy() == AsyncRejectionPolicy.BLOCK);
        visitListenerDefs(listener -> blocks[0] |= listener.getAsync() == AsyncRejectionPolicy.BLOCK);
        return blocks[0] || anyMember(member -> member.policy() == AsyncRejectionPolicy.BLOCK);
    }

    /**
     * Returns what to do when a submission is refused.
     *
     * @return the policy; never {@code null}
     */
    AsyncRejectionPolicy getAsyncRejectionPolicy() {
        return asyncRejectionPolicy != null ? asyncRejectionPolicy : AsyncRejectionPolicy.DROP;
    }

    /**
     * Reports whether anything in this definition forks, which is what decides whether the state
     * machine builds a pool at all.
     * <p>
     * The roots are exactly two - a container registered at SM level, or a transition's body -
     * since every other container is declared inside one of them. Each root is then
     * walked in full: {@link OperationDefImpl#declaresFork()} descends through any choice's
     * branches and any container declared in place, since a member forks through the same path
     * wherever it sits.
     *
     * @return whether any container declares a forked member
     */
    boolean definitionForks() {
        return anyMember(ActionSequenceSink.DeclaredMember::forked);
    }

    /**
     * Reports whether any listener in this definition runs async, which is the third reason to
     * build a pool.
     *
     * @return whether any listener declared {@code withAsync}
     */
    boolean declaresAsyncListener() {
        boolean[] async = {false};
        visitListenerDefs(listener -> async[0] |= listener.getAsync() != null);
        return async[0];
    }

    /**
     * Reports whether any declared member in this definition satisfies the test. The roots are
     * exactly two - an SM-level container, or a transition's body - since every other sequence is
     * declared inside one of them; each root walks its own subtree.
     */
    private boolean anyMember(Predicate<ActionSequenceSink.DeclaredMember<?, ?>> test) {
        for (ActionDefImpl<T, ?, ?> composite : smCompositeOperations.values()) {
            if (composite.anyMember(test)) {
                return true;
            }
        }
        for (TransitionDefImpl<T, ?> td : transitionsById.values()) {
            ActionDefImpl<T, ?, ?> op = td.getActionDef();
            if (op != null && op.anyMember(test)) {
                return true;
            }
        }
        return false;
    }

    ActionDefImpl<T, ?, ?> getSmCompositeOperation(String id) {
        return smCompositeOperations.get(id);
    }

    <C> void registerScopedStep(String id, Action<? super T, C> step, Class<C> contextType) {
        registerStepInstance(id, step);
        tagContextType(id, contextType);
    }

    <C> void registerScopedStep(String id, Consumer<StepDef<T, C>> configurer, Class<C> contextType) {
        StepDefImpl<T, C> def = new StepDefImpl<>(id, contextType);
        ConfigurableDefImpl.runConfigurer(def, configurer);
        registerStepDef(def);
        tagContextType(id, contextType);
    }

    <C> void registerScopedCondition(String id, Condition<? super T, C> condition, Class<C> contextType) {
        registerConditionInstance(id, condition);
        tagContextType(id, contextType);
    }

    <C> void registerScopedCondition(String id, BiPredicate<? super T, C> predicate, Class<C> contextType) {
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
        OperationDefImpl<T, C> composite = new OperationDefImpl<>(id, contextType);
        ConfigurableDefImpl.runConfigurer(composite, configurer);
        // Claimed once the configurer returned, so one that throws leaves the id free for a retry.
        claimCanonical(componentIds, id, composite, "Operation");
        smCompositeOperations.put(id, composite);
        tagContextType(id, contextType);
    }

    @Override
    public StateMachineDef<T> stateListener(String id, StateListener<? super T> listener) {
        requireNotNull(listener, "State listener");
        return stateListener(id, l -> l.using(listener));
    }

    @Override
    public StateMachineDef<T> stateListener(String id, Consumer<StateListenerDef<T>> configurer) {
        StateListenerDefImpl<T> def = new StateListenerDefImpl<>(requireListenerId(id));
        registerListener(id, def, configurer, "State listener configurer", stateListenerRegistrations);
        return this;
    }

    @Override
    public StateMachineDef<T> transitionListener(String id, TransitionListener<? super T, Object> listener) {
        requireNotNull(listener, "Transition listener");
        return transitionListener(id, Object.class, l -> l.using(listener));
    }

    @Override
    public <C> StateMachineDef<T> transitionListener(String id, Class<C> contextType,
                                                     TransitionListener<? super T, C> listener) {
        requireNotNull(listener, "Transition listener");
        return transitionListener(id, contextType, l -> l.using(listener));
    }

    @Override
    public StateMachineDef<T> transitionListener(String id,
                                                 Consumer<TransitionListenerDef<T, Object>> configurer) {
        return transitionListener(id, Object.class, configurer);
    }

    @Override
    public <C> StateMachineDef<T> transitionListener(String id, Class<C> contextType,
                                                     Consumer<TransitionListenerDef<T, C>> configurer) {
        requireNotNull(contextType, "Listener context type");
        TransitionListenerDefImpl<T, C> def = new TransitionListenerDefImpl<>(requireListenerId(id), contextType);
        registerListener(id, def, configurer, "Transition listener configurer", transitionListenerRegistrations);
        return this;
    }

    @Override
    public StateMachineDef<T> actionListener(String id, ActionListener<? super T, Object> listener) {
        requireNotNull(listener, "Action listener");
        return actionListener(id, Object.class, l -> l.using(listener));
    }

    @Override
    public <C> StateMachineDef<T> actionListener(String id, Class<C> contextType,
                                                 ActionListener<? super T, C> listener) {
        requireNotNull(listener, "Action listener");
        return actionListener(id, contextType, l -> l.using(listener));
    }

    @Override
    public StateMachineDef<T> actionListener(String id, Consumer<ActionListenerDef<T, Object>> configurer) {
        return actionListener(id, Object.class, configurer);
    }

    @Override
    public <C> StateMachineDef<T> actionListener(String id, Class<C> contextType,
                                                 Consumer<ActionListenerDef<T, C>> configurer) {
        requireNotNull(contextType, "Listener context type");
        ActionListenerDefImpl<T, C> def = new ActionListenerDefImpl<>(requireListenerId(id), contextType);
        registerListener(id, def, configurer, "Action listener configurer", actionListenerRegistrations);
        return this;
    }

    private static String requireListenerId(String id) {
        requireNotBlank(id, "Listener ID");
        return id;
    }

    /**
     * Runs a registration's configurer, then claims its id and files it.
     * <p>
     * In that order, for the reason the inline path gives: a configurer that throws leaves the id
     * free for the caller's corrected retry. The listener has to have one by the time this returns
     * - a registration is what an owner attaches by id, so discovering it declared nothing only
     * when something attaches it would report the failure at the wrong place, and never at all for
     * one nothing attaches.
     *
     * @param id the registration's id
     * @param def the freshly-constructed def
     * @param configurer the caller's configurer
     * @param label names the configurer argument in a rejection
     * @param registrations the category's registration map
     * @param <D> the category's def type
     * @param <X> the def type the caller's configurer sees
     */
    private <D extends ListenerDefImpl<?>, X> void registerListener(
            String id, D def, Consumer<X> configurer, String label, Map<String, ? super D> registrations) {
        requireNotNull(configurer, label);
        ConfigurableDefImpl.runConfigurer(def, (Consumer) configurer);
        def.requireListenerDeclared();
        claimListenerId(id);
        registrations.put(id, def);
    }

    /**
     * Returns the resolver binding this build's listener references.
     *
     * @return the binder; never {@code null} while a build is running
     */
    ListenerRegistrations<T> listenerBinder() {
        return listenerBinder;
    }

    Map<String, StateListenerDefImpl<T>> getStateListenerRegistrations() {
        return stateListenerRegistrations;
    }

    Map<String, TransitionListenerDefImpl<T, ?>> getTransitionListenerRegistrations() {
        return transitionListenerRegistrations;
    }

    Map<String, ActionListenerDefImpl<T, ?>> getActionListenerRegistrations() {
        return actionListenerRegistrations;
    }

    /**
     * Registers a trigger of any kind under its own namespace, running the configurer against a
     * freshly-constructed def that carries the declared context rather than a transition's.
     *
     * @param id the trigger id
     * @param contextType the context the trigger was declared against
     * @param configurer the caller's configurer
     * @param factory builds the def for this kind
     * @param kind the kind's label, for the rejection message
     * @param <C> the trigger's context type
     * @param <D> the def type this kind exposes
     */
    <C, D> void registerTrigger(String id, Class<C> contextType, Consumer<D> configurer,
                                BiFunction<String, Class<C>, TriggerDefImpl<T, C, ?>> factory,
                                String kind) {
        requireNotBlank(id, "Trigger ID");
        requireNotNull(contextType, "Trigger context type");
        requireNotNull(configurer, "Trigger configurer");

        TriggerDefImpl<T, C, ?> existing = (TriggerDefImpl<T, C, ?>) triggerRegistrations.get(id);
        if (existing != null) {
            throw new TransfluxValidationException(
                "Trigger id '" + id + "' is already registered as a " + existing.defLabel()
                    + "; ids are unique across this state machine's triggers");
        }

        TriggerDefImpl<T, C, ?> def = factory.apply(id, contextType);
        ConfigurableDefImpl.runConfigurer(def, (Consumer) configurer);
        triggerRegistrations.put(id, def);
        Loggers.BUILD_REGISTRY.debug("Trigger registered, triggerId={}, kind={}, contextType={}",
                                     new Object[] {id, kind, contextType.getName()});
    }

    /**
     * Returns the registered triggers, keyed by id, in declaration order.
     *
     * @return the trigger registrations; never {@code null}
     */
    Map<String, TriggerDefImpl<T, ?, ?>> getTriggerRegistrations() {
        return triggerRegistrations;
    }

    /**
     * Registers a choice at state-machine level, so it can be referenced by id like any other
     * declarative action. It shares the container's namespace and the container's build passes -
     * a choice is an action, and being registered is a property of the declaration site
     * rather than of the form.
     *
     * @param id the choice's id
     * @param configurer callback declaring the branches
     * @param contextType the context the choice runs against
     * @param <C> the choice's context type
     */
    <C> void registerScopedChoice(String id,
                                  Consumer<ChoiceDef<T, C>> configurer,
                                  Class<C> contextType) {
        requireNotBlank(id, "Choice ID");
        requireNotNull(contextType, "Context type");
        requireNotNull(configurer, "Choice configurer");
        ChoiceDefImpl<T, C> choice = new ChoiceDefImpl<>(id, contextType);
        ConfigurableDefImpl.runConfigurer(choice, configurer);
        // Claimed once the configurer returned, so one that throws leaves the id free for a retry.
        claimCanonical(componentIds, id, choice, "Choice");
        smCompositeOperations.put(id, choice);
        tagContextType(id, contextType);
    }


    @Override
    public StateMachineDef<T> withStateApplier(StateApplier<? super T> stateApplier) {
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
    public StateMachineDef<T> state(String stateId) {
        requireNotBlank(stateId, "State ID");
        registerState(stateId);
        return this;
    }

    @Override
    public StateMachineDef<T> transition(String transitionId, String sourceStateId, String targetStateId,
                                         Consumer<TransitionDef<T, Object>> configurer) {
        return transition(transitionId, sourceStateId, targetStateId, Object.class, configurer);
    }

    @Override
    public <C> StateMachineDef<T> transition(String transitionId, String sourceStateId, String targetStateId,
                                             Class<C> contextType, Consumer<TransitionDef<T, C>> configurer) {
        requireNotNull(configurer, "Transition configurer");
        TransitionDefImpl<T, C> td = registerTransition(sourceStateId, targetStateId, transitionId, contextType);
        ConfigurableDefImpl.runConfigurer(td, configurer);
        return this;
    }

    @Override
    public StateMachineDef<T> withExecutionLogging() {
        return withExecutionLogging(ExecutionLogging.defaults());
    }

    @Override
    public StateMachineDef<T> withExecutionLogging(ExecutionLogging<? super T> logging) {
        requireNotNull(logging, "Execution logging");
        return onAnyStateEntry(ExecutionLogging.STATE_ENTRY_LISTENER_ID, logging.<T>stateListener())
            .onAnyStateExit(ExecutionLogging.STATE_EXIT_LISTENER_ID, logging.<T>stateListener())
            .onAnyTransitionStart(ExecutionLogging.TRANSITION_START_LISTENER_ID,
                                  logging.<T, Object>transitionListener())
            .onAnyTransitionComplete(ExecutionLogging.TRANSITION_COMPLETE_LISTENER_ID,
                                     logging.<T, Object>transitionListener())
            .onAnyTransitionError(ExecutionLogging.TRANSITION_ERROR_LISTENER_ID,
                                  logging.<T, Object>transitionListener())
            .onAnyActionStart(ExecutionLogging.ACTION_START_LISTENER_ID,
                              logging.<T, Object>actionListener())
            .onAnyActionComplete(ExecutionLogging.ACTION_COMPLETE_LISTENER_ID,
                                 logging.<T, Object>actionListener())
            .onAnyActionError(ExecutionLogging.ACTION_ERROR_LISTENER_ID,
                              logging.<T, Object>actionListener());
    }

    @Override
    public StateMachineDef<T> onAnyStateEntry(String listenerId) {
        requireNotBlank(listenerId, "State listener ID");
        globalEntryListeners.add(ListenerEntry.reference(listenerId));
        return this;
    }

    @Override
    public StateMachineDef<T> onAnyStateEntry(String listenerId, StateListener<? super T> listener) {
        requireNotBlank(listenerId, "State listener ID");
        requireNotNull(listener, "State listener");
        return onAnyStateEntry(listenerId, l -> l.using(listener));
    }

    @Override
    public StateMachineDef<T> onAnyStateEntry(String listenerId, Consumer<StateListenerDef<T>> configurer) {
        globalEntryListeners.add(declareStateListener(listenerId, configurer));
        return this;
    }

    @Override
    public StateMachineDef<T> onAnyStateExit(String listenerId) {
        requireNotBlank(listenerId, "State listener ID");
        globalExitListeners.add(ListenerEntry.reference(listenerId));
        return this;
    }

    @Override
    public StateMachineDef<T> onAnyStateExit(String listenerId, StateListener<? super T> listener) {
        requireNotBlank(listenerId, "State listener ID");
        requireNotNull(listener, "State listener");
        return onAnyStateExit(listenerId, l -> l.using(listener));
    }

    @Override
    public StateMachineDef<T> onAnyStateExit(String listenerId, Consumer<StateListenerDef<T>> configurer) {
        globalExitListeners.add(declareStateListener(listenerId, configurer));
        return this;
    }

    /**
     * Returns the state listeners notified on entry to every state, in declaration order.
     *
     * @return the live global entry-listener list
     */
    List<ListenerEntry<StateListenerDefImpl<T>>> getGlobalEntryListeners() {
        return globalEntryListeners;
    }

    /**
     * Returns the state listeners notified on exit from every state, in declaration order.
     *
     * @return the live global exit-listener list
     */
    List<ListenerEntry<StateListenerDefImpl<T>>> getGlobalExitListeners() {
        return globalExitListeners;
    }

    @Override
    public StateMachineDef<T> onAnyTransitionStart(String listenerId) {
        requireNotBlank(listenerId, "Transition listener ID");
        globalStartListeners.add(ListenerEntry.reference(listenerId));
        return this;
    }

    @Override
    public StateMachineDef<T> onAnyTransitionStart(String listenerId, TransitionListener<? super T, Object> listener) {
        requireNotBlank(listenerId, "Transition listener ID");
        requireNotNull(listener, "Transition listener");
        return onAnyTransitionStart(listenerId, l -> l.using(listener));
    }

    @Override
    public StateMachineDef<T> onAnyTransitionStart(String listenerId,
                                                   Consumer<TransitionListenerDef<T, Object>> configurer) {
        globalStartListeners.add(declareTransitionListener(listenerId, configurer));
        return this;
    }

    @Override
    public StateMachineDef<T> onAnyTransitionComplete(String listenerId) {
        requireNotBlank(listenerId, "Transition listener ID");
        globalCompleteListeners.add(ListenerEntry.reference(listenerId));
        return this;
    }

    @Override
    public StateMachineDef<T> onAnyTransitionComplete(String listenerId, TransitionListener<? super T, Object> listener) {
        requireNotBlank(listenerId, "Transition listener ID");
        requireNotNull(listener, "Transition listener");
        return onAnyTransitionComplete(listenerId, l -> l.using(listener));
    }

    @Override
    public StateMachineDef<T> onAnyTransitionComplete(String listenerId,
                                                      Consumer<TransitionListenerDef<T, Object>> configurer) {
        globalCompleteListeners.add(declareTransitionListener(listenerId, configurer));
        return this;
    }

    @Override
    public StateMachineDef<T> onAnyTransitionError(String listenerId) {
        requireNotBlank(listenerId, "Transition listener ID");
        globalErrorListeners.add(ListenerEntry.reference(listenerId));
        return this;
    }

    @Override
    public StateMachineDef<T> onAnyTransitionError(String listenerId, TransitionListener<? super T, Object> listener) {
        requireNotBlank(listenerId, "Transition listener ID");
        requireNotNull(listener, "Transition listener");
        return onAnyTransitionError(listenerId, l -> l.using(listener));
    }

    @Override
    public StateMachineDef<T> onAnyTransitionError(String listenerId,
                                                   Consumer<TransitionListenerDef<T, Object>> configurer) {
        globalErrorListeners.add(declareTransitionListener(listenerId, configurer));
        return this;
    }

    @Override
    public StateMachineDef<T> onAnyActionStart(String listenerId) {
        requireNotBlank(listenerId, "Action listener ID");
        globalActionStartListeners.add(ListenerEntry.reference(listenerId));
        return this;
    }

    @Override
    public StateMachineDef<T> onAnyActionStart(String listenerId, ActionListener<? super T, Object> listener) {
        requireNotBlank(listenerId, "Action listener ID");
        requireNotNull(listener, "Action listener");
        return onAnyActionStart(listenerId, l -> l.using(listener));
    }

    @Override
    public StateMachineDef<T> onAnyActionStart(String listenerId,
                                               Consumer<ActionListenerDef<T, Object>> configurer) {
        globalActionStartListeners.add(declareActionListener(listenerId, configurer));
        return this;
    }

    @Override
    public StateMachineDef<T> onAnyActionComplete(String listenerId) {
        requireNotBlank(listenerId, "Action listener ID");
        globalActionCompleteListeners.add(ListenerEntry.reference(listenerId));
        return this;
    }

    @Override
    public StateMachineDef<T> onAnyActionComplete(String listenerId, ActionListener<? super T, Object> listener) {
        requireNotBlank(listenerId, "Action listener ID");
        requireNotNull(listener, "Action listener");
        return onAnyActionComplete(listenerId, l -> l.using(listener));
    }

    @Override
    public StateMachineDef<T> onAnyActionComplete(String listenerId,
                                                  Consumer<ActionListenerDef<T, Object>> configurer) {
        globalActionCompleteListeners.add(declareActionListener(listenerId, configurer));
        return this;
    }

    @Override
    public StateMachineDef<T> onAnyActionError(String listenerId) {
        requireNotBlank(listenerId, "Action listener ID");
        globalActionErrorListeners.add(ListenerEntry.reference(listenerId));
        return this;
    }

    @Override
    public StateMachineDef<T> onAnyActionError(String listenerId, ActionListener<? super T, Object> listener) {
        requireNotBlank(listenerId, "Action listener ID");
        requireNotNull(listener, "Action listener");
        return onAnyActionError(listenerId, l -> l.using(listener));
    }

    @Override
    public StateMachineDef<T> onAnyActionError(String listenerId,
                                               Consumer<ActionListenerDef<T, Object>> configurer) {
        globalActionErrorListeners.add(declareActionListener(listenerId, configurer));
        return this;
    }

    /**
     * Returns the listeners notified when any action starts, in declaration order.
     *
     * @return the live global action-start listener list
     */
    List<ListenerEntry<ActionListenerDefImpl<T, Object>>> getGlobalActionStartListeners() {
        return globalActionStartListeners;
    }

    /**
     * Returns the listeners notified when any action completes, in declaration order.
     *
     * @return the live global action-complete listener list
     */
    List<ListenerEntry<ActionListenerDefImpl<T, Object>>> getGlobalActionCompleteListeners() {
        return globalActionCompleteListeners;
    }

    /**
     * Returns the listeners notified when any action fails, in declaration order.
     *
     * @return the live global action-error listener list
     */
    List<ListenerEntry<ActionListenerDefImpl<T, Object>>> getGlobalActionErrorListeners() {
        return globalActionErrorListeners;
    }

    /**
     * Returns the listeners notified when any transition starts, in declaration order.
     *
     * @return the live global start-listener list
     */
    List<ListenerEntry<TransitionListenerDefImpl<T, Object>>> getGlobalStartListeners() {
        return globalStartListeners;
    }

    /**
     * Returns the listeners notified when any transition completes, in declaration order.
     *
     * @return the live global completion-listener list
     */
    List<ListenerEntry<TransitionListenerDefImpl<T, Object>>> getGlobalCompleteListeners() {
        return globalCompleteListeners;
    }

    /**
     * Returns the listeners notified when any transition fails, in declaration order.
     *
     * @return the live global error-listener list
     */
    List<ListenerEntry<TransitionListenerDefImpl<T, Object>>> getGlobalErrorListeners() {
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
        // Where each id was declared, for a message naming both; null for one claimed at declaration.
        Map<String, String> claimed = new HashMap<>();
        listenerIds.forEach(id -> claimed.put(id, null));

        for (TransitionDefImpl<T, ?> td : transitionsById.values()) {
            claimTransitionListenerIds(ListenerRegistrations.declaredOf(td.getStartListeners()), td.getId(), "onStart", claimed);
            claimTransitionListenerIds(ListenerRegistrations.declaredOf(td.getCompleteListeners()), td.getId(), "onComplete", claimed);
            claimTransitionListenerIds(ListenerRegistrations.declaredOf(td.getErrorListeners()), td.getId(), "onError", claimed);
        }

        BiConsumer<String, String> actionListenerIds =
            (listenerId, ownerLabel) -> claimOwnedListenerId(claimed, listenerId, ownerLabel);

        visitActionDefs(def -> def.emitOwnListenerIds(actionListenerIds));
    }

    /**
     * Rejects a deny-list entry that names no global listener of its owner's own category.
     * <p>
     * A typo in a deny-list silently protects nothing, which is the one failure mode this feature
     * cannot absorb - hence an error rather than a warning. The category follows the owner, so an
     * id naming a global of another category is rejected too, as is one naming a listener attached
     * to an owner: those are never suppressed, because attaching a listener to an owner is the
     * consent.
     *
     * @throws TransfluxValidationException if a disabled id names no such global listener
     */
    private void checkGlobalListenerDisables() {
        Set<String> stateGlobals = listenerIdsOf(globalEntryListeners, globalExitListeners);
        Set<String> transitionGlobals =
            listenerIdsOf(globalStartListeners, globalCompleteListeners, globalErrorListeners);
        Set<String> actionGlobals = listenerIdsOf(globalActionStartListeners,
                                                  globalActionCompleteListeners,
                                                  globalActionErrorListeners);

        for (StateDefImpl<T> sd : states.values()) {
            checkDisabledIds(sd.getDisabledGlobals(), stateGlobals, sd.defLabel(), "state",
                             "onAnyStateEntry / onAnyStateExit");
        }
        for (TransitionDefImpl<T, ?> td : transitionsById.values()) {
            checkDisabledIds(td.getDisabledGlobals(), transitionGlobals, td.defLabel(), "transition",
                             "onAnyTransitionStart / onAnyTransitionComplete / onAnyTransitionError");
        }
        visitActionDefs(def -> checkDisabledIds(def.getDisabledGlobals(), actionGlobals,
                                                def.defLabel(), "action",
                                                "onAnyActionStart / onAnyActionComplete / onAnyActionError"));
    }

    @SafeVarargs
    private static Set<String> listenerIdsOf(List<? extends ListenerEntry<?>>... groups) {
        Set<String> ids = new HashSet<>();
        for (List<? extends ListenerEntry<?>> group : groups) {
            for (ListenerEntry<?> entry : group) {
                // A global attached by id is a global of that category too, so a deny-list may
                // name it - the id is what the hook carries either way.
                ids.add(entry.id());
            }
        }

        return ids;
    }

    private static void checkDisabledIds(GlobalListenerDisables disabled, Set<String> globals,
                                         String ownerLabel, String category, String registrations) {
        for (String listenerId : disabled.ids()) {
            if (!globals.contains(listenerId)) {
                throw new TransfluxValidationException(
                    "Listener ID '" + listenerId + "' is disabled on " + ownerLabel
                        + ", but no global " + category + " listener is registered under it."
                        + " disableGlobalListener names one registered through " + registrations
                        + "; an owner's own listeners are always notified");
            }
        }
    }

    /**
     * Visits every listener def in this definition, of all three categories, owned and global.
     *
     * @param visitor what to apply to each
     */
    private void visitListenerDefs(Consumer<ListenerDefImpl<?>> visitor) {
        ListenerRegistrations.declaredOf(globalEntryListeners).forEach(visitor);
        ListenerRegistrations.declaredOf(globalExitListeners).forEach(visitor);
        ListenerRegistrations.declaredOf(globalStartListeners).forEach(visitor);
        ListenerRegistrations.declaredOf(globalCompleteListeners).forEach(visitor);
        ListenerRegistrations.declaredOf(globalErrorListeners).forEach(visitor);
        ListenerRegistrations.declaredOf(globalActionStartListeners).forEach(visitor);
        ListenerRegistrations.declaredOf(globalActionCompleteListeners).forEach(visitor);
        ListenerRegistrations.declaredOf(globalActionErrorListeners).forEach(visitor);
        // A registration is visited once here rather than once per attachment: an unattached one
        // still declares what it declares, and a doubly-attached one must not count twice.
        stateListenerRegistrations.values().forEach(visitor);
        transitionListenerRegistrations.values().forEach(visitor);
        actionListenerRegistrations.values().forEach(visitor);
        for (StateDefImpl<T> sd : states.values()) {
            ListenerRegistrations.declaredOf(sd.getEntryListeners()).forEach(visitor);
            ListenerRegistrations.declaredOf(sd.getExitListeners()).forEach(visitor);
        }
        for (TransitionDefImpl<T, ?> td : transitionsById.values()) {
            ListenerRegistrations.declaredOf(td.getStartListeners()).forEach(visitor);
            ListenerRegistrations.declaredOf(td.getCompleteListeners()).forEach(visitor);
            ListenerRegistrations.declaredOf(td.getErrorListeners()).forEach(visitor);
        }
        visitActionDefs(def -> {
            for (ActionPhase phase : ActionPhase.values()) {
                ListenerRegistrations.declaredOf(def.getListeners(phase)).forEach(visitor);
            }
        });
    }

    /**
     * Visits every action def in this definition: registered steps and containers, transition
     * bodies, and every action declared inline beneath any of them, at any depth.
     * <p>
     * The three roots are what a def is reachable through - a registration, an SM-level container,
     * or a transition's body - and the recursion beneath each is the def's own. Registered steps
     * are a root of their own because a step declares no members and so is reachable nowhere else.
     * A bare {@link org.transflux.core.action.Action} instance has no def and is correctly outside
     * this walk.
     *
     * @param visitor receives each def exactly once
     */
    void visitActionDefs(Consumer<ActionDefImpl<?, ?, ?>> visitor) {
        for (ActionRegistration<T> registration : actionRegistrations.values()) {
            if (registration.def() != null) {
                registration.def().visitDefs(visitor);
            }
        }
        for (ActionDefImpl<T, ?, ?> composite : smCompositeOperations.values()) {
            composite.visitDefs(visitor);
        }
        for (TransitionDefImpl<T, ?> td : transitionsById.values()) {
            ActionDefImpl<T, ?, ?> actionDef = td.getActionDef();
            if (actionDef != null) {
                actionDef.visitDefs(visitor);
            }
        }
    }

    /**
     * Rejects {@link AsyncRejectionPolicy#BLOCK} where nothing can be waited on.
     * <p>
     * Waiting for capacity happens inside the rejection handler the framework installs on a pool
     * it builds itself, which is the only place a refusal can still be turned into an enqueue. A
     * host-supplied executor is not the framework's to reconfigure, so against one the declaration
     * is a contradiction rather than a preference, and it is reported at build rather than
     * degrading silently at the first saturated queue.
     * <p>
     * A def declaring it is rejected whether or not it is ever forked. What the declaration says
     * about this state machine is wrong either way, and proving a def is never forked would cost a
     * second walk to answer a question the author already got wrong.
     * <p>
     * This covers every declarative position - the machine default, every def, every by-id fork.
     * A policy chosen from inside a Java body at runtime is not visible here and is refused at the
     * submission instead.
     */
    private static final String DECLARES_HOST_EXECUTOR =
        "this definition declares a host-supplied executor";

    private void checkBlockingIsPossible() {
        if (asyncExecutor == null) {
            return;
        }

        if (asyncRejectionPolicy == AsyncRejectionPolicy.BLOCK) {
            throw new TransfluxValidationException(
                blockUnavailable("StateMachineDef", DECLARES_HOST_EXECUTOR));
        }
        visitActionDefs(def -> {
            if (def.getAsyncRejectionPolicy() == AsyncRejectionPolicy.BLOCK) {
                throw new TransfluxValidationException(
                    blockUnavailable(def.defLabel(), DECLARES_HOST_EXECUTOR));
            }
        });
        visitListenerDefs(listener -> {
            if (listener.getAsync() == AsyncRejectionPolicy.BLOCK) {
                throw new TransfluxValidationException(
                    blockUnavailable(listener.defLabel(), DECLARES_HOST_EXECUTOR));
            }
        });
        anyMember(member -> {
            if (member.policy() == AsyncRejectionPolicy.BLOCK) {
                throw new TransfluxValidationException(
                    blockUnavailable("a fork of action '" + member.ref().id() + "'",
                                     DECLARES_HOST_EXECUTOR));
            }
            return false;
        });
    }

    /**
     * The message a {@code BLOCK} declaration gets when nothing can honour it. Shared with the
     * runtime refusal, which answers for the one declaration site the build cannot see - a policy
     * chosen inside an action body.
     *
     * @param ownerLabel what declared the policy
     *
     * @return the rejection message
     */
    static String blockUnavailable(String ownerLabel) {
        return blockUnavailable(ownerLabel, "this state machine runs on a host-supplied executor");
    }

    /**
     * The same message with the reason spelled out by the caller. The build check speaks about what
     * the definition <i>declares</i>, because under handle ownership a definition's own executor is
     * not necessarily the one that would run its forks; the runtime refusals speak about the
     * executor actually in force.
     *
     * @param ownerLabel what declared the policy
     * @param because why nothing can honour it
     *
     * @return the rejection message
     */
    static String blockUnavailable(String ownerLabel, String because) {
        return "Async rejection policy BLOCK is declared on " + ownerLabel + ", but " + because
            + "; waiting for capacity needs the rejection handler the framework installs on a pool"
            + " it builds itself, so either drop withAsyncExecutor(...) and size the pool with"
            + " withAsyncPool(...), or choose another policy";
    }

    /**
     * Claims one hook's listener ids, naming the owning transition and hook on a collision. A
     * transition's listeners are claimed at build time rather than at declaration, so the stack
     * trace points at {@code build()} and cannot locate the duplicate on its own.
     */
    private void claimTransitionListenerIds(List<? extends TransitionListenerDefImpl<T, ?>> listeners,
                                            String transitionId, String hook, Map<String, String> claimed) {
        for (TransitionListenerDefImpl<T, ?> ld : listeners) {
            claimOwnedListenerId(claimed, ld.getId(), "transition '" + transitionId + "' via " + hook);
        }
    }

    /**
     * Claims a listener id declared on an owner, naming both declarations when both are known.
     *
     * @param claimed where each claimed id was declared; {@code null} for one claimed at declaration
     * @param listenerId the id
     * @param site where it is declared, such as {@code transition 't' via onStart}
     *
     * @throws TransfluxValidationException if the id is already claimed
     */
    private static void claimOwnedListenerId(Map<String, String> claimed, String listenerId, String site) {
        if (!claimed.containsKey(listenerId)) {
            claimed.put(listenerId, site);
            return;
        }
        String first = claimed.get(listenerId);
        throw new TransfluxValidationException(first == null
            ? "Listener ID '" + listenerId + "', declared on " + site + ", is already registered"
            : "Listener ID '" + listenerId + "' is declared on " + first + " and on " + site
                + "; listener ids are unique across the state machine");
    }

    private ListenerEntry<StateListenerDefImpl<T>> declareStateListener(String listenerId,
                                                         Consumer<StateListenerDef<T>> configurer) {
        requireNotBlank(listenerId, "State listener ID");
        requireNotNull(configurer, "State listener configurer");
        StateListenerDefImpl<T> listenerDef = new StateListenerDefImpl<>(listenerId);
        // Claimed only once the configurer has returned, so a configurer that throws leaves the id
        // free for the caller's corrected retry.
        ConfigurableDefImpl.runConfigurer(listenerDef, configurer);
        claimListenerId(listenerId);
        return ListenerEntry.declared(listenerId, listenerDef);
    }

    private ListenerEntry<TransitionListenerDefImpl<T, Object>> declareTransitionListener(
            String listenerId, Consumer<TransitionListenerDef<T, Object>> configurer) {
        requireNotBlank(listenerId, "Transition listener ID");
        requireNotNull(configurer, "Transition listener configurer");
        TransitionListenerDefImpl<T, Object> listenerDef = new TransitionListenerDefImpl<>(listenerId);
        // Claimed only once the configurer has returned, so a configurer that throws leaves the id
        // free for the caller's corrected retry.
        ConfigurableDefImpl.runConfigurer(listenerDef, configurer);
        claimListenerId(listenerId);
        return ListenerEntry.declared(listenerId, listenerDef);
    }

    private ListenerEntry<ActionListenerDefImpl<T, Object>> declareActionListener(
            String listenerId, Consumer<ActionListenerDef<T, Object>> configurer) {
        requireNotBlank(listenerId, "Action listener ID");
        requireNotNull(configurer, "Action listener configurer");
        ActionListenerDefImpl<T, Object> listenerDef = new ActionListenerDefImpl<>(listenerId);
        // Claimed only once the configurer has returned, so a configurer that throws leaves the id
        // free for the caller's corrected retry.
        ConfigurableDefImpl.runConfigurer(listenerDef, configurer);
        claimListenerId(listenerId);
        return ListenerEntry.declared(listenerId, listenerDef);
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
        requireEntityType();

        StateMachineImpl<T> handle = new StateMachineImpl<>(entityType);
        handle.install(buildSnapshot(handle, 1L));
        return handle;
    }

    /**
     * Runs the build pipeline and produces one snapshot for {@code handle}. Shared by
     * {@link #build()} and by definition replacement, so both go through the same validation, the
     * same binding and the same completion line.
     *
     * @param handle the state machine the snapshot will run under; never {@code null}
     * @param generation the generation number the snapshot will be installed as
     *
     * @return the built snapshot
     *
     * @throws TransfluxValidationException if the definition is incomplete or inconsistent
     */
    StateMachineSnapshot<T> buildSnapshot(StateMachineImpl<T> handle, long generation) {
        // The three phase boundaries, reported so that "why did my definition build into *that*" has
        // somewhere to start. Each phase names itself before running, so a throw is attributable to
        // the phase whose line was last emitted — which is why all three go to one logger rather than
        // to the logger of the phase they announce: split across leaves, the attribution would hold
        // only for a host who enabled every one of them.
        Loggers.BUILD_LIFECYCLE.debug("Validating context compatibility and cycles");
        validateContextCompatibilityAndCycles();

        Loggers.BUILD_LIFECYCLE.debug("Populating registries and binding components");
        listenerBinder = new ListenerRegistrations<>(this);
        visitActionDefs(actionDef -> ((ActionDefImpl) actionDef).setListenerBinder(listenerBinder));
        StateMachineSnapshot<T> snapshot;
        try {
            snapshot = new StateMachineSnapshot<>(this, handle);
        } finally {
            listenerBinder = null;
            visitActionDefs(actionDef -> ((ActionDefImpl) actionDef).setListenerBinder(null));
        }

        Loggers.BUILD_LIFECYCLE.debug("Validating registered components");
        validateComponents(snapshot.getComponentRegistry());

        // The executor comes last, so a definition that fails validation never leaves a pool behind.
        handle.adoptExecutorIfNeeded(this);

        // The one build-time INFO: rare, and the only report a host gets that its definition
        // resolved into the shape it expected. The component count is the root registry's, matching
        // the name the binding pass uses.
        Loggers.BUILD_LIFECYCLE.info(
            "State machine built, id={}, version={}, generation={}, states={}, transitions={},"
                + " triggers={}, rootComponents={}",
            new Object[] {id, version, generation, snapshot.stateCount(), snapshot.transitionCount(),
                          snapshot.triggerCount(), snapshot.componentCount()});

        return snapshot;
    }

    /**
     * Names a transition's body as a position in the definition tree. The body labels itself as
     * the transition, so this is its own label and not a composition - it is a root, exactly as an
     * SM-level container is.
     */
    private static String bodyLabel(ActionDefImpl<?, ?, ?> body) {
        return body.defLabel();
    }

    /**
     * Resolves every declared member - a container's own, and those inside any choice it
     * holds - and installs the bound members on the executors that iterate them.
     * <p>
     * Members cannot bind during {@link OperationDefImpl#buildBound}, because a container's or a
     * choice's bound action is registered <em>into</em> the very scope its own members
     * resolve against: a sibling may name it by id, and such a reference captures the bound
     * action by value. Binding here, once every scope is populated and every container is
     * registered, also frees a member to reference a container declared after its own.
     *
     * @param stateMachine the state machine under construction
     *
     * @throws TransfluxValidationException if a member names an id that no action in scope carries
     */
    void bindDeferredMembers(StateMachineSnapshot<T> stateMachine) {
        for (TransitionDefImpl<T, ?> td : transitionsById.values()) {
            ActionDefImpl<T, ?, ?> op = td.getActionDef();
            if (op != null) {
                op.bindMembers(stateMachine, bodyLabel(op));
            }
        }

        for (Map.Entry<String, ActionDefImpl<T, ?, ?>> e : smCompositeOperations.entrySet()) {
            e.getValue().bindMembers(stateMachine, e.getValue().defLabel());
        }
    }

    /**
     * Records the context every inline-declared action runs against, over the whole definition,
     * before any reference is checked.
     * <p>
     * It is a pass of its own for the same reason member binding is: a reference may name an id
     * declared after it, or one declared in an enclosing scope, so nothing can be checked until
     * every declaration has been seen. The two roots are the same ones {@link #checkRefs} walks -
     * an action attached to a transition, and one registered at state-machine level - and each
     * seeds the walk with the context that position runs against.
     *
     * <p>Two declarations in one scope may still claim one id here: this pass runs before ids are
     * claimed, so a duplicate is possible and is a definition error in its own right. The first
     * wins, which leaves the duplicate to be reported as a duplicate rather than surfacing as a
     * context mismatch blaming whichever declaration the walk happened to reach second.
     */
    private void collectInlineMemberContexts() {
        inlineMemberContexts.clear();

        InlineContextSink sink = (id, context, scope) -> inlineMemberContexts
            .computeIfAbsent(scope, k -> new LinkedHashMap<>())
            .putIfAbsent(id, context);

        for (TransitionDefImpl<T, ?> td : transitionsById.values()) {
            ActionDefImpl<T, ?, ?> op = td.getActionDef();
            if (op != null) {
                op.collectMemberContexts(td.getContextType(), sink);
            }
        }

        for (Map.Entry<String, ActionDefImpl<T, ?, ?>> e : smCompositeOperations.entrySet()) {
            e.getValue().collectMemberContexts(componentContextTypes.get(e.getKey()), sink);
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
        checkTransitionEndpoints();
        checkOwnedListenerIds();
        checkGlobalListenerDisables();
        checkBlockingIsPossible();
        checkTriggerAttachments();
        collectInlineMemberContexts();
        checkListenerAttachments();
        for (TransitionDefImpl<T, ?> td : transitionsById.values()) {
            Class<?> transitionContext = td.getContextType();
            ActionDefImpl<T, ?, ?> op = td.getActionDef();
            if (op != null) {
                // The body names no context of its own, so the transition is what declares one.
                op.checkRefs(transitionContext, bodyLabel(op), bodyLabel(op), List.of(), this);
            }
            checkConditionRefs(td);
        }
        checkRegisteredTriggerConditionRefs();
        for (Map.Entry<String, ActionDefImpl<T, ?, ?>> e : smCompositeOperations.entrySet()) {
            Class<?> scopeContext = componentContextTypes.get(e.getKey());
            e.getValue().checkRefs(scopeContext, e.getValue().defLabel(),
                                   e.getValue().defLabel(), List.of(), this);
        }
        detectCompositeCycles();
    }

    private void checkTransitionEndpoints() {
        for (TransitionDefImpl<T, ?> td : transitionsById.values()) {
            if (!states.containsKey(td.getSourceStateId())) {
                throw new TransfluxValidationException("Transition '" + td.getId() + "' leaves state '"
                    + td.getSourceStateId() + "', which is not declared");
            }
            if (!states.containsKey(td.getTargetStateId())) {
                throw new TransfluxValidationException("Transition '" + td.getId() + "' targets state '"
                    + td.getTargetStateId() + "', which is not declared");
            }
        }
    }

    /**
     * Validates every one-argument listener hook: that the id resolves, that it resolves within
     * its own category, and that the listener's context accepts the owner attaching it.
     * <p>
     * Resolution is owner-first - a reference reaches anything the same owner declared at any of
     * its hooks, in either order - and then the registrations of that hook's category. Everything
     * else gets a message naming what was actually found, because "unknown id" would be wrong: a
     * listener declared on another owner exists, and so does one of another category.
     *
     * @throws TransfluxValidationException on the first reference that breaks one of those
     */
    private void checkListenerAttachments() {
        Map<String, String> declaredElsewhere = new HashMap<>();
        for (StateDefImpl<T> sd : states.values()) {
            collectOwnerDeclarations(declaredElsewhere, "state '" + sd.getId() + "'",
                                     sd.getEntryListeners(), sd.getExitListeners());
        }
        for (TransitionDefImpl<T, ?> td : transitionsById.values()) {
            collectOwnerDeclarations(declaredElsewhere, "transition '" + td.getId() + "'",
                                     td.getStartListeners(), td.getCompleteListeners(),
                                     td.getErrorListeners());
        }
        collectOwnerDeclarations(declaredElsewhere, "the state machine",
                                 globalEntryListeners, globalExitListeners, globalStartListeners,
                                 globalCompleteListeners, globalErrorListeners,
                                 globalActionStartListeners, globalActionCompleteListeners,
                                 globalActionErrorListeners);
        visitActionDefs(ad -> collectOwnerDeclarations(declaredElsewhere, ad.defLabel(),
                                                       ad.getListeners(ActionPhase.START),
                                                       ad.getListeners(ActionPhase.COMPLETE),
                                                       ad.getListeners(ActionPhase.ERROR)));

        for (StateDefImpl<T> sd : states.values()) {
            checkReferences("state '" + sd.getId() + "'", Object.class, declaredElsewhere,
                            stateListenerRegistrations, "state",
                            ListenerRegistrations.ownIds(sd.getEntryListeners(), sd.getExitListeners()),
                            sd.getEntryListeners(), sd.getExitListeners());
        }
        for (TransitionDefImpl<T, ?> td : transitionsById.values()) {
            Class<?> context = td.getContextType() == null ? Object.class : td.getContextType();
            checkReferences("transition '" + td.getId() + "'", context, declaredElsewhere,
                            transitionListenerRegistrations, "transition",
                            ListenerRegistrations.ownIds(td.getStartListeners(),
                                                         td.getCompleteListeners(),
                                                         td.getErrorListeners()),
                            td.getStartListeners(), td.getCompleteListeners(), td.getErrorListeners());
        }
        visitActionDefs(ad -> checkReferences(ad.defLabel(), actionContext(ad), declaredElsewhere,
                                              actionListenerRegistrations, "action",
                                              ListenerRegistrations.ownIds(
                                                  ad.getListeners(ActionPhase.START),
                                                  ad.getListeners(ActionPhase.COMPLETE),
                                                  ad.getListeners(ActionPhase.ERROR)),
                                              ad.getListeners(ActionPhase.START),
                                              ad.getListeners(ActionPhase.COMPLETE),
                                              ad.getListeners(ActionPhase.ERROR)));

        checkGlobalReferences(declaredElsewhere);
    }

    /**
     * The context an action actually runs against, which is not what {@code getContextType()}
     * reports: that answers with the {@code Object} sentinel for a declaration naming no context,
     * where the action in fact inherits the enclosing one. A registration is tagged, and an inline
     * declaration was resolved by {@code collectInlineMemberContexts}; anything neither pass saw
     * genuinely runs against {@code Object}.
     *
     * @param ad the action def
     *
     * @return the context to check its listener attachments against
     */
    private Class<?> actionContext(ActionDefImpl<?, ?, ?> ad) {
        if (ad.declaredContext() != null) {
            return ad.declaredContext();
        }

        Class<?> tagged = componentContextTypes.get(ad.getId());
        if (tagged != null) {
            return tagged;
        }

        for (Map<String, Class<?>> scope : inlineMemberContexts.values()) {
            Class<?> inline = scope.get(ad.getId());
            if (inline != null) {
                return inline;
            }
        }

        return Object.class;
    }

    @SafeVarargs
    private void collectOwnerDeclarations(Map<String, String> declaredElsewhere, String owner,
                                          List<? extends ListenerEntry<?>>... hooks) {
        for (List<? extends ListenerEntry<?>> hook : hooks) {
            for (ListenerEntry<?> entry : hook) {
                if (!entry.isReference()) {
                    declaredElsewhere.put(entry.id(), owner);
                }
            }
        }
    }

    /**
     * Checks one owner's references, where {@code ownIds} is what that owner itself declared and
     * is reached before any registration.
     *
     * @param ownerLabel names the owner in a rejection
     * @param ownerContext the context the owner runs against
     * @param declaredElsewhere every inline declaration in the definition, by owner
     * @param registrations the registrations of this hook's category
     * @param category the category name, for the rejection
     * @param ownIds the ids this owner declared at any of its hooks
     * @param hooks the owner's hook lists
     */
    @SafeVarargs
    private void checkReferences(
            String ownerLabel, Class<?> ownerContext, Map<String, String> declaredElsewhere,
            Map<String, ? extends ListenerDefImpl<?>> registrations, String category,
            Set<String> ownIds, List<? extends ListenerEntry<?>>... hooks) {
        for (List<? extends ListenerEntry<?>> hook : hooks) {
            for (ListenerEntry<?> entry : hook) {
                if (!entry.isReference() || ownIds.contains(entry.id())) {
                    continue;
                }

                ListenerDefImpl<?> registered = registrations.get(entry.id());
                if (registered == null) {
                    throw new TransfluxValidationException(unresolvedListener(
                        ownerLabel, entry.id(), category, declaredElsewhere));
                }

                checkListenerContext(ownerLabel, ownerContext, registered);
            }
        }
    }

    /**
     * Builds the message for a reference that did not resolve, naming what was found instead: a
     * listener of another category, one declared on another owner, or nothing at all.
     */
    private String unresolvedListener(String ownerLabel, String id, String category,
                                      Map<String, String> declaredElsewhere) {
        String head = ownerLabel + " attaches listener '" + id + "', which ";
        if (otherCategoryRegistrations(category).containsKey(id)) {
            return head + "is registered in another category; a hook only reaches listeners of its own";
        }

        String owner = declaredElsewhere.get(id);
        if (owner != null) {
            return head + "is declared on " + owner
                + "; a listener declared in place is visible to its own owner alone, so register it"
                + " to attach it elsewhere";
        }

        return head + "is not a registered " + category + " listener";
    }

    private static String capitalize(String label) {
        return Character.toUpperCase(label.charAt(0)) + label.substring(1);
    }

    private Map<String, ? extends ListenerDefImpl<?>> otherCategoryRegistrations(String category) {
        Map<String, ListenerDefImpl<?>> others = new HashMap<>();
        if (!"state".equals(category)) {
            others.putAll(stateListenerRegistrations);
        }
        if (!"transition".equals(category)) {
            others.putAll(transitionListenerRegistrations);
        }
        if (!"action".equals(category)) {
            others.putAll(actionListenerRegistrations);
        }

        return others;
    }

    private void checkListenerContext(String ownerLabel, Class<?> ownerContext,
                                      ListenerDefImpl<?> registered) {
        Class<?> declared = registered.getContextType();
        Class<?> owner = ownerContext == null ? Object.class : ownerContext;
        if (declared == Object.class || declared.isAssignableFrom(owner)) {
            return;
        }

        throw new TransfluxValidationException(
            "Context type mismatch: " + ownerLabel + " (context " + owner.getName() + ") attaches "
                + registered.defLabel() + " declared for context " + declared.getName());
    }

    /**
     * The eight state-machine-wide hooks are one owner, and they take {@code Object}: a hook
     * spanning every transition, or every action, cannot promise one context, so a registration
     * typed to anything narrower is refused here rather than at whichever owner it would break on.
     */
    private void checkGlobalReferences(Map<String, String> declaredElsewhere) {
        checkReferences("the state machine", Object.class, declaredElsewhere, stateListenerRegistrations,
                        "state", ListenerRegistrations.ownIds(globalEntryListeners, globalExitListeners),
                        globalEntryListeners, globalExitListeners);
        checkReferences("the state machine", Object.class, declaredElsewhere, transitionListenerRegistrations,
                        "transition", ListenerRegistrations.ownIds(globalStartListeners,
                                                                   globalCompleteListeners,
                                                                   globalErrorListeners),
                        globalStartListeners, globalCompleteListeners, globalErrorListeners);
        checkReferences("the state machine", Object.class, declaredElsewhere, actionListenerRegistrations,
                        "action", ListenerRegistrations.ownIds(globalActionStartListeners,
                                                               globalActionCompleteListeners,
                                                               globalActionErrorListeners),
                        globalActionStartListeners, globalActionCompleteListeners,
                        globalActionErrorListeners);
    }

    /**
     * Validates every {@code addTrigger(id)} attachment: that the id names a registration, that
     * the registration's context accepts the attaching transition's, and that no manual trigger
     * ends up on two transitions leaving one state.
     * <p>
     * A trigger declared in place gets its own message rather than "unknown": it exists, it is
     * simply visible to the transition that declared it and to nothing else.
     *
     * @throws TransfluxValidationException on the first attachment that breaks one of those
     */
    private void checkTriggerAttachments() {
        Map<String, String> declaredInline = new HashMap<>();
        for (TransitionDefImpl<T, ?> td : transitionsById.values()) {
            for (TriggerDefImpl<T, ?, ?> declared : List.copyOf(td.getManualTriggers())) {
                declaredInline.put(declared.getId(), td.getId());
            }
            td.getEventTriggers().forEach(t -> declaredInline.put(t.getId(), td.getId()));
            td.getDataTriggers().forEach(t -> declaredInline.put(t.getId(), td.getId()));
        }

        Map<String, Map<String, String>> attachedBySource = new HashMap<>();
        for (TransitionDefImpl<T, ?> td : transitionsById.values()) {
            Set<String> attachedHere = new HashSet<>();
            for (String ref : td.getTriggerRefs()) {
                if (!attachedHere.add(ref)) {
                    throw new TransfluxValidationException(
                        "Transition '" + td.getId() + "' attaches trigger '" + ref
                            + "' more than once; attaching is not additive");
                }

                TriggerDefImpl<T, ?, ?> registered = triggerRegistrations.get(ref);
                if (registered == null) {
                    String owner = declaredInline.get(ref);
                    throw new TransfluxValidationException(owner == null
                        ? "Transition '" + td.getId() + "' attaches trigger '" + ref
                            + "', which is not registered on this state machine"
                        : "Transition '" + td.getId() + "' attaches trigger '" + ref
                            + "', which is declared inline on transition '" + owner
                            + "'; register it under its own id to attach it elsewhere");
                }

                checkTriggerContext(registered, td);

                // Whatever its kind, one trigger is not allowed to sit on two transitions leaving
                // one state. A manual trigger could not choose between them, having only the
                // current state to go on; an event's filter and a data trigger's gate are the same
                // object at both attachments, so they cannot either, and the one thing that could
                // still tell them apart - a firing context one transition accepts and the other
                // refuses - is deliberately not made to carry that weight. Two *different* triggers
                // competing is the first-match rule and stays legal, context eligibility included.
                String clash = attachedBySource
                    .computeIfAbsent(ref, k -> new HashMap<>())
                    .putIfAbsent(td.getSourceStateId(), td.getId());
                if (clash != null) {
                    throw new TransfluxValidationException(
                        capitalize(registered.defLabel()) + " is attached to transitions '" + clash
                            + "' and '" + td.getId() + "', which both leave state '"
                            + td.getSourceStateId() + "'; dispatch could not choose between them");
                }
            }
        }
    }

    /**
     * Checks a registered trigger's declared context against a transition attaching it, by the rule
     * a by-id component reference follows: a registration typed to {@code Object} attaches
     * anywhere, and any other must accept what the transition carries.
     *
     * @param registered the trigger registration
     * @param td the transition attaching it
     */
    private void checkTriggerContext(TriggerDefImpl<T, ?, ?> registered, TransitionDefImpl<T, ?> td) {
        Class<?> declared = registered.getContextType();
        Class<?> transitionContext = td.getContextType() == null ? Object.class : td.getContextType();
        if (declared == Object.class || declared.isAssignableFrom(transitionContext)) {
            return;
        }

        throw new TransfluxValidationException(
            "Context type mismatch: transition '" + td.getId() + "' (context "
                + transitionContext.getName() + ") attaches " + registered.defLabel()
                + " declared for context " + declared.getName()
                + "; a trigger runs against the context of the transition it fires");
    }

    /**
     * Validates every by-id condition reference a transition carries — its own pre- and
     * post-conditions plus those of the triggers attached to it — against the context the
     * referencing site runs under.
     *
     * @param td the transition to walk
     *
     * @throws TransfluxValidationException if a referenced condition is not registered, or
     *         declares an incompatible context type
     */
    private void checkConditionRefs(TransitionDefImpl<T, ?> td) {
        Class<?> context = td.getContextType() != null ? td.getContextType() : Object.class;
        String label = "transition '" + td.getId() + "'";

        checkConditionRefs(td.getPreConditionDescriptors(), context, label, "pre-condition");
        checkConditionRefs(td.getPostConditionDescriptors(), context, label, "post-condition");

        for (ManualTriggerDefImpl<T, ?> mt : td.getManualTriggers()) {
            checkConditionRefs(mt.getPreConditionDescriptors(), context,
                label + " > manual trigger '" + mt.getId() + "'", "pre-condition");
        }
        for (DataTriggerDefImpl<T, ?> dt : td.getDataTriggers()) {
            checkConditionRef(dt.getGateDescriptor(), context,
                label + " > data trigger '" + dt.getId() + "'", "gate condition");
        }
    }

    /**
     * Validates the by-id condition references a registered trigger carries, against the context
     * the registration itself declared.
     * <p>
     * A registration has no transition of its own, and the one it attaches to is checked
     * separately: what has to hold here is that the trigger's own gate can run against the context
     * the trigger says it runs against.
     *
     * @throws TransfluxValidationException if a referenced condition is not registered, or
     *         declares an incompatible context type
     */
    private void checkRegisteredTriggerConditionRefs() {
        for (TriggerDefImpl<T, ?, ?> registered : triggerRegistrations.values()) {
            Class<?> context = registered.getContextType();
            String label = registered.defLabel();
            if (registered instanceof ManualTriggerDefImpl<?, ?> manual) {
                checkConditionRefs(manual.getPreConditionDescriptors(), context, label,
                                   "pre-condition");
            } else if (registered instanceof DataTriggerDefImpl<?, ?> data) {
                checkConditionRef(data.getGateDescriptor(), context, label, "gate condition");
            }
        }
    }

    private void checkConditionRefs(List<ConditionDescriptor> descriptors, Class<?> scopeContext,
                                    String scopeLabel, String kind) {
        for (ConditionDescriptor descriptor : descriptors) {
            checkConditionRef(descriptor, scopeContext, scopeLabel, kind);
        }
    }

    /**
     * Rejects a reference to no registered condition, and one to a registered condition whose
     * declared context type cannot accept the referencing site's context. Only the reference form is
     * checkable — the inline forms are typed against the referencing def's own context by the
     * compiler, and expressions are dynamic. Conditions registered through the untyped overloads
     * carry no declared type, so their context is not checked.
     * <p>
     * A choice's branch calls this too, which is not merely for symmetry: a choice may
     * declare a context of its own, so a branch's gate can sit on the far side of a boundary its
     * own choice crossed, and a condition takes no mapper to get back.
     */
    void checkConditionRef(ConditionDescriptor descriptor, Class<?> scopeContext,
                           String scopeLabel, String kind) {
        if (!(descriptor instanceof ConditionDescriptor.Reference ref)) {
            return;
        }
        if (!conditionRegistrations.containsKey(ref.id())) {
            throw new TransfluxValidationException(
                scopeLabel + " references " + kind + " '" + ref.id() + "', which is not a registered condition");
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
     * A container declared in place and a choice are nodes in their own right, not merely
     * members of one: each is registered under its id in the enclosing scope, so a sibling - or
     * one of its own descendants - can name it, and a self-reference resolves and then recurses
     * without bound at execution. Rooting only at state-machine level left that edge invisible.
     * <p>
     * <b>Only an id that resolves becomes a node.</b> A transition's body is registered in no
     * registry, so nothing can name it and it can never lie on a cycle; entering it as a node
     * would let the transition's id answer for edges that are not any action's, which both rejects
     * sound definitions and hides real cycles. Everything the body declares <em>is</em> registered,
     * in the body's own scope, so a container or choice declared straight on a transition is
     * a node like any other and a cycle closed through one is found.
     * <p>
     * Registered containers go in first, and the rest through {@code putIfAbsent}: this pass runs
     * before ids are claimed, so a nested id colliding with a state-machine-level one is still
     * possible here, and a real container must not be shadowed by it and reported as a cycle
     * before the collision itself is reported.
     * <p>
     * The edge lists stay over-approximate in the way this detector already is: it does not reason
     * about which branch of a choice is selectable, just as it does not reason about whether
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
                "Action cycle detected: "
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

    /**
     * Refuses a definition that never named its entity class.
     * <p>
     * The entity type is what a built state machine is identified by when its definition is later
     * replaced, so a definition that declares none cannot produce a handle.
     *
     * @throws TransfluxValidationException if no entity type was declared
     */
    private void requireEntityType() {
        if (entityType == null) {
            throw new TransfluxValidationException(
                "No entity type declared: start from Transflux.defineStateMachine(EntityClass.class),"
                    + " or call forEntityType(EntityClass.class) before build()");
        }
    }

    /** @return the bound entity class */
    public Class<T> getEntityType() {
        return entityType;
    }

    /** @return the state machine id, or {@code null} if unset */
    public String getId() {
        return id;
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
    public StateResolver<? super T> getStateResolver() {
        return stateResolver;
    }

    /** @return the state applier, or {@code null} if unset */
    public StateApplier<? super T> getStateApplier() {
        return stateApplier;
    }

    Map<String, StateDefImpl<T>> getStates() {
        return states;
    }

    Map<String, TransitionDefImpl<T, ?>> getTransitionsById() {
        return transitionsById;
    }

    private record ActionRegistration<T>(Action<? super T, ?> instance, StepDefImpl<T, ?> def) {

        static <T> ActionRegistration<T> ofInstance(Action<? super T, ?> instance) {
            return new ActionRegistration<>(instance, null);
        }

        static <T> ActionRegistration<T> ofDef(StepDefImpl<T, ?> def) {
            return new ActionRegistration<>(null, def);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        BoundAction<T, ?> toBoundAction(String id) {
            if (def != null) {
                return def.buildBoundAction();
            }
            return BoundAction.of(id, (Action) instance, ActionKind.STEP);
        }
    }

    /**
     * One id's entry in the per-build table: what declared it, and as which kind, so a clash can
     * name both sides.
     *
     * @param payload the declared instance, def or expression; compared by identity, or by equality
     *        for an expression
     * @param kind the declaring kind, capitalised as it leads a message: {@code Step},
     *        {@code Operation}, {@code Choice}, {@code Condition} or {@code Mapper}
     */
    record CanonicalClaim(Object payload, String kind) {
    }

    private record ConditionRegistration<T>(Condition<? super T, ?> instance, BiPredicate<? super T, ?> predicate,
                                            String expression) {

        static <T> ConditionRegistration<T> ofInstance(Condition<? super T, ?> instance) {
                return new ConditionRegistration<>(instance, null, null);
            }

            static <T> ConditionRegistration<T> ofPredicate(BiPredicate<? super T, ?> predicate) {
                return new ConditionRegistration<>(null, predicate, null);
            }

            static <T> ConditionRegistration<T> ofExpression(String expression) {
                return new ConditionRegistration<>(null, null, expression);
            }

            @SuppressWarnings({"unchecked", "rawtypes"})
            BoundCondition<T, ?> toBoundCondition(String id) {
                if (instance != null) {
                    return BoundCondition.of(id, (Condition) instance);
                }
                if (predicate != null) {
                    BiPredicate<? super T, Object> p = (BiPredicate<? super T, Object>) predicate;
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
