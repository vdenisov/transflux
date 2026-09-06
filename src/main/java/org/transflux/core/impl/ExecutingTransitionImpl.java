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

import org.transflux.core.exception.TransfluxValidationException;
import org.transflux.core.action.ActionPhase;
import org.transflux.core.action.Compensation;
import org.transflux.core.action.ContextMapper;
import org.transflux.core.action.ForkableContext;
import org.transflux.core.action.MapperDef;
import org.transflux.core.action.Action;
import org.transflux.core.transition.ActionPath;
import org.transflux.core.transition.ExecutingTransition;
import org.transflux.core.transition.Transition;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

import static org.transflux.core.Preconditions.requireNotBlank;
import static org.transflux.core.Preconditions.requireNotNull;

/**
 * The {@link ExecutingTransition} implementation, built fresh for each transition execution and
 * handed to the underlying {@link Action} as the {@code transition} parameter.
 * <p>
 * Topology accessors delegate to the {@link BoundTransition} record that carries the resolved
 * per-transition data; the dispatch methods run against the captured execution scope (entity,
 * context, step-id recorder, compensation stack) by resolving the id against the enclosing state
 * machine's registries. Observers get {@link TransitionImpl} instead, which is the reason this
 * type is reachable from an action's body and from nowhere else.
 *
 * <p>This is framework-internal runtime infrastructure intended only for use by Transflux's
 * own runtime; user code should not reference it directly.
 *
 * @param <T> the entity type the enclosing state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
class ExecutingTransitionImpl<T, C> implements ExecutingTransition<T, C> {
    private final StateMachineImpl<T> stateMachine;
    private final BoundTransition<T, C> boundTransition;

    private final T entity;
    private final C context;

    private final Deque<Object> contextOverrideStack = new ArrayDeque<>();

    private final Deque<Registry<T>> scopeStack = new ArrayDeque<>();

    private final List<ActionPath> executedPath = new ArrayList<>();

    private final Deque<BoundCompensation<T, C>> compensationStack = new ArrayDeque<>();

    private final Deque<String> operationStack = new ArrayDeque<>();

    private final Transition readOnly;

    ExecutingTransitionImpl(StateMachineImpl<T> stateMachine, BoundTransition<T, C> boundTransition,
                            T entity, C context) {
        this(stateMachine, boundTransition, entity, context, List.of(), List.of());
    }

    /**
     * Builds a view whose lexical position is inherited rather than empty - the constructor a
     * forked member's branch uses.
     * <p>
     * The two inherited stacks are <em>copied</em> at construction and never shared: the thread
     * that spawned the branch keeps pushing and popping its own as it walks on, and a branch
     * reading those would see its qualified paths and its id resolution change underneath it. The
     * three that are not inherited start empty, which is what makes the branch's rollback its own.
     *
     * @param nesting the enclosing action-nesting stack, outermost last, as
     *                {@link Deque#iterator()} yields it
     * @param scopes the enclosing lexical-scope stack, in the same order
     */
    ExecutingTransitionImpl(StateMachineImpl<T> stateMachine, BoundTransition<T, C> boundTransition,
                            T entity, C context, Collection<String> nesting,
                            Collection<Registry<T>> scopes) {
        requireNotNull(stateMachine, "State machine");
        requireNotNull(boundTransition, "Bound transition");

        this.stateMachine = stateMachine;
        this.boundTransition = boundTransition;
        this.entity = entity;
        this.context = context;
        this.readOnly = TransitionImpl.of(boundTransition);

        // addLast preserves head-to-tail order, so a copy of a push-built stack keeps its head.
        this.operationStack.addAll(nesting);
        this.scopeStack.addAll(scopes);
    }

    /**
     * Returns the same transition without the dispatch surface, for handing to code that observes
     * the execution rather than drives it — conditions and listeners.
     * <p>
     * This is a separate object rather than an upcast of {@code this}, and it has to be: an upcast
     * would let a listener widen its payload back to {@link ExecutingTransition} and dispatch after
     * all. Built once per execution.
     *
     * @return the read-only view; never {@code null}
     */
    Transition asReadOnly() {
        return readOnly;
    }

    @Override
    public String getId() {
        return boundTransition.id();
    }

    @Override
    public String getSourceStateId() {
        return boundTransition.sourceStateId();
    }

    @Override
    public String getTargetStateId() {
        return boundTransition.targetStateId();
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void run(String id) {
        runAction((BoundAction) resolveAction(id, true).bound(), null);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void run(String id, String mapperId) {
        requireNotBlank(mapperId, "Mapper reference ID");
        Component.Action<T, ?> callee = resolveAction(id, false);
        runAction((BoundAction) callee.bound(), resolveRegisteredMapper(mapperId),
                  callee.contextType());
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void run(String id, ContextMapper<C, ?> inlineMapper) {
        requireNotNull(inlineMapper, "Inline mapper instance");
        Component.Action<T, ?> callee = resolveAction(id, false);
        runAction((BoundAction) callee.bound(), (ContextMapper<Object, Object>) inlineMapper,
                  callee.contextType());
    }

    T getEntity() {
        return entity;
    }

    @SuppressWarnings("unchecked")
    C getContext() {
        return contextOverrideStack.isEmpty() ? context : (C) contextOverrideStack.peek();
    }

    /**
     * Records an already-qualified path on the executed path. {@link #runAction} qualifies once and
     * reuses the result for the listener payload, so the qualification happens at the call site
     * rather than here.
     *
     * @param path the qualified path to record
     */
    void recordExecutedPath(ActionPath path) {
        executedPath.add(path);
    }

    List<ActionPath> getExecutedPath() {
        return Collections.unmodifiableList(executedPath);
    }

    /**
     * Pushes the supplied nested-operation id onto this view's operation-nesting stack. While
     * the stack is non-empty {@link #qualifyActionPath(String)} qualifies an action's id as
     * {@code parent-op-id/.../child-step-id}. Each push must be paired with a matching
     * {@link #exitOperation()} call.
     *
     * @param operationId the nested-operation id to push; must be non-blank
     */
    void enterOperation(String operationId) {
        requireNotBlank(operationId, "Operation ID");
        operationStack.push(operationId);
    }

    /**
     * Pops the most recently pushed nested-operation id from this view's operation-nesting
     * stack.
     *
     * @throws TransfluxValidationException if the nesting stack is empty
     */
    void exitOperation() {
        if (operationStack.isEmpty()) {
            throw new TransfluxValidationException(
                "exitOperation() called with no matching enterOperation()");
        }
        operationStack.pop();
    }

    /**
     * Runs a bound action. This is the single execution path: every action reaches the runtime
     * through here, whether it was authored imperatively or declaratively, dispatched as a member
     * of a container, a branch or a transition's body, or referenced from inside another action's
     * body.
     * <p>
     * The order is fixed and uniform:
     * <ol>
     *   <li>capture the action's compensation and push it onto the rollback stack, against the same
     *       context {@code execute} will see;</li>
     *   <li>record the action's id on the executed path;</li>
     *   <li>push the id onto the operation-nesting stack;</li>
     *   <li>execute;</li>
     *   <li>pop the nesting stack.</li>
     * </ol>
     * Capturing and recording <em>before</em> the nesting push puts both at the action's own
     * qualified path rather than one level beneath it. Recording before {@code execute} means an
     * action that throws still appears on the executed path - it did run - which keeps the
     * executed and compensated paths consistent with each other. Pushing the nesting stack for
     * every action means anything it dispatches is qualified underneath it, so the reported tree
     * matches the tree that actually ran at every level.
     *
     * <p>What gets pushed is the action's whole compensation table rather than one callback, because
     * which rollback applies depends on a failure that has not happened yet - see
     * {@link BoundCompensationRouter}. Which entries the table carries is decided by
     * {@link #resolveRouter}.
     *
     * <p>With a mapper, {@code mapTo} produces the child context before the action starts and
     * {@link ContextMapper#mapFrom(Object, Object) mapFrom} folds child-side changes back into
     * the parent on successful return only.
     *
     * <p>Action listeners are notified from inside the nesting scope, so the payload's path is the
     * action's own. {@code mapFrom} runs only once this action's notifications are closed: a
     * failing {@code mapFrom} is the parent's failure, and the child did complete, so it must not
     * turn the child's completion into an error as well. A failure notifies this level and then
     * propagates, so every enclosing action reports the same throwable on its way out - the
     * exception is rethrown unchanged so the transition still reports what actually failed.
     *
     * @param bound the bound action to run; never {@code null}
     * @param mapper the mapper to apply at the boundary, or {@code null} for pass-through
     */
    void runAction(BoundAction<T, Object> bound, ContextMapper<Object, Object> mapper) {
        runAction(bound, mapper, null);
    }

    /**
     * Runs an action, checking that a supplied mapper produced a context the callee can accept.
     * <p>
     * Only a caller holding the callee's registration knows what that is, which is why the type
     * is a parameter rather than something {@link BoundAction} carries: a member of a sequence
     * reaches this method through a bound record alone and passes {@code null}, so the check is
     * confined to the imperative {@code run(id, mapper)} surface for now.
     *
     * @param bound the bound action to run; never {@code null}
     * @param mapper the mapper to apply at the boundary, or {@code null} for pass-through
     * @param calleeContext the context the callee was registered against, or {@code null} to skip
     *                      the check
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    void runAction(BoundAction<T, Object> bound, ContextMapper<Object, Object> mapper,
                   Class<?> calleeContext) {
        Object active = getContext();
        Object child = mapper == null ? null : mapper.mapTo(active);
        // Before anything is recorded or captured, so a rejection is attributed the way a throwing
        // mapTo already is: the child never started.
        if (mapper != null) {
            requireMappedContextAccepted(bound.id(), calleeContext, child);
        }
        Object effective = mapper == null ? active : child;

        ActionPath path = qualifyActionPath(bound.id());
        pushCompensation(path, (BoundCompensationRouter) resolveRouter(bound, effective),
                         (C) effective);
        recordExecutedPath(path);
        enterOperation(bound.id());
        try {
            // The mapping decision, not the mapped value: the child context is the host's and may
            // carry anything. Its type is what a reader needs to see the boundary was crossed.
            if (Loggers.EXECUTION_ACTION.isTraceEnabled()) {
                Loggers.EXECUTION_ACTION.trace("Action entered, path={}, context={}", path,
                                               mapper == null
                                                   ? "pass-through"
                                                   : "mapped:" + describeType(child));
            }
            stateMachine.notifyActionListeners(bound, ActionPhase.START, entity, effective, path,
                                               readOnly, null);
            if (mapper == null) {
                ((Action) bound.action()).execute(entity, active, this);
            } else {
                contextOverrideStack.push(child);
                try {
                    ((Action) bound.action()).execute(entity, child, this);
                } finally {
                    contextOverrideStack.pop();
                }
            }
            stateMachine.notifyActionListeners(bound, ActionPhase.COMPLETE, entity, effective, path,
                                               readOnly, null);
            Loggers.EXECUTION_ACTION.trace("Action completed, path={}", path);
        } catch (Exception e) {
            stateMachine.notifyActionListeners(bound, ActionPhase.ERROR, entity, effective, path,
                                               readOnly, e);
            if (Loggers.EXECUTION_ACTION.isTraceEnabled()) {
                Loggers.EXECUTION_ACTION.trace("Action failed, path={}, errorType={}", path,
                                               e.getClass().getName());
            }
            throw e;
        } finally {
            exitOperation();
        }
        if (mapper != null) {
            mapper.mapFrom(active, child);
        }
    }

    /**
     * Picks the compensation table to push for one invocation, which is the single place the
     * precedence between the authoring channels is decided. The rule is one chain, not a switch
     * between channels: the rollback is the first of a matching route, the declared fallback, and
     * the action's own {@link Action#getCompensation(Object, Object)} that answers for the failure.
     *
     * <p>So the hook is consulted whenever nothing else answers <em>every</em> failure. A def that
     * declared a fallback suppresses it, since that fallback always answers; a def that declared
     * only routes does not, because a route that misses has said nothing about this failure and
     * must not veto on its behalf. The hook has to run here rather than at the drain: it is
     * documented to see the same references {@code execute} will, which is only true before
     * {@code execute} runs, and that also keeps rollback registration immune to an action that
     * fails partway through.
     *
     * @param bound the action about to run
     * @param effective the context it will run against - what the dynamic hook is handed
     *
     * @return the table to push, or {@code null} when nothing rolls this action back
     */
    private BoundCompensationRouter<T, Object> resolveRouter(BoundAction<T, Object> bound,
                                                             Object effective) {
        BoundCompensationRouter<T, Object> declared = bound.compensationRouter();
        if (declared != null && declared.fallback() != null) {
            return declared;
        }

        Compensation<T, Object> dynamic = bound.action().getCompensation(entity, effective);
        if (declared == null) {
            return dynamic == null ? null : BoundCompensationRouter.always(dynamic);
        }

        return declared.withFallback(dynamic);
    }

    /**
     * Hands one forked member to the executor and returns without waiting for it.
     * <p>
     * Everything the branch needs is produced here, on this thread, before the submission: the
     * context it will run against, and a view of its own carrying copies of the two stacks that
     * describe where it sits. That ordering is what the memory model rests on - every write this
     * method performs happens-before the branch starts, and nothing is shared afterwards.
     *
     * <p>Producing the context can fail, and when it does the failure is this transition's: the
     * host's {@code mapTo} or {@code fork} threw at a position this transition was executing, and
     * no branch exists yet to attribute it to. A refused <em>submission</em> is a different thing
     * and answers to the state machine's fork-rejection policy instead.
     *
     * @param action the member to run on the branch
     * @param mapping the call site's context mapping
     */
    void submitBranch(BoundAction<T, Object> action, ResolvedContextMapping mapping) {
        Object active = getContext();
        Object branchContext = acquireBranchContext(active, mapping, action.id());

        ActionPath path = qualifyActionPath(action.id());
        ExecutingTransitionImpl<T, Object> branch = branchView(branchContext);

        stateMachine.submitBranch(new AsyncBranchTask<>(stateMachine, branch, action, path), path);

        // After the submit, not before: a refused branch reports itself, and a line claiming it was
        // submitted would then be followed by one saying it never started.
        if (Loggers.EXECUTION_ASYNC.isTraceEnabled()) {
            Loggers.EXECUTION_ASYNC.trace("Async branch submitted, path={}, context={}",
                                          path, describeAcquisition(active, branchContext, mapping));
        }
    }

    /**
     * Produces the context one branch runs against: the call site's mapper first, then the
     * context's own {@link ForkableContext#fork() fork}, then the enclosing reference.
     *
     * @param parent the context active at the fork site
     * @param mapping the call site's context mapping
     * @param actionId the forked member's id, for the failure message
     *
     * @return the branch's context
     *
     * @throws TransfluxValidationException if {@code fork()} returns {@code null}
     */
    private Object acquireBranchContext(Object parent, ResolvedContextMapping mapping,
                                        String actionId) {
        if (!mapping.isPassThrough()) {
            return mapping.mapper().mapTo(parent);
        }

        if (parent instanceof ForkableContext<?> forkable) {
            Object forked = forkable.fork();
            if (forked == null) {
                throw new TransfluxValidationException(
                    "ForkableContext returned null while forking action '" + actionId
                        + "' in transition '" + getId() + "'; fork() must produce a context");
            }
            return forked;
        }

        return parent;
    }

    /**
     * Builds the branch's own view, inheriting this one's lexical position by copy.
     *
     * @param branchContext the context the branch runs against
     *
     * @return the branch's view
     */
    @SuppressWarnings("unchecked")
    private ExecutingTransitionImpl<T, Object> branchView(Object branchContext) {
        return new ExecutingTransitionImpl<>(stateMachine,
                                             (BoundTransition<T, Object>) boundTransition,
                                             entity, branchContext,
                                             new ArrayList<>(operationStack),
                                             new ArrayList<>(scopeStack));
    }

    /**
     * Names how a branch got its context, for the submission trace - the decision, never the
     * value, since the context is the host's and may carry anything.
     *
     * @param parent the context active at the fork site
     * @param branchContext what the branch will run against
     * @param mapping the call site's context mapping
     *
     * @return {@code "mapped:<type>"}, {@code "forked"} or {@code "shared"}
     */
    private static String describeAcquisition(Object parent, Object branchContext,
                                              ResolvedContextMapping mapping) {
        if (!mapping.isPassThrough()) {
            return "mapped:" + describeType(branchContext);
        }
        return branchContext == parent ? "shared" : "forked";
    }

    /**
     * Resolves an id dispatched from inside an action body, and — for a pass-through dispatch —
     * refuses one that would hand the callee a context it was not written against.
     * <p>
     * This is the one dispatch surface the build cannot check: what a Java body chooses to run is
     * not visible in the definition. It was harmless while every action in a scope necessarily
     * shared one context; a declaration that names its own ends that, so a sibling body naming it
     * would otherwise reach a {@code ClassCastException} inside host code with nothing to say
     * about why. Refusing here fails the transition with the two types named instead.
     *
     * @param id the id to resolve
     * @param passThrough whether the callee will be handed this action's own context
     */
    private Component.Action<T, ?> resolveAction(String id, boolean passThrough) {
        requireNotBlank(id, "Action ID");

        Registry<T> scope = activeScope();
        Component<T> component = scope.resolve(id)
            .orElseThrow(() -> new TransfluxValidationException(
                "No action registered with id '" + id + "' in the active scope"));

        if (!(component instanceof Component.Action<T, ?> action)) {
            throw new TransfluxValidationException(
                "Id '" + id + "' is registered as a " + component.getClass().getSimpleName().toLowerCase()
                    + ", not an action");
        }

        if (passThrough) {
            requireContextAccepted(id, action.contextType());
        }

        // Which scope claimed the id, not merely that it resolved: the id an action dispatches may
        // be its container's own inline one or an SM-level one it inherits, and that is the
        // distinction a lexical-visibility surprise turns on. The active scope would answer the
        // wrong question - after flattening it is the scope the lookup started in, always the
        // container.
        if (Loggers.EXECUTION_ACTION.isTraceEnabled()) {
            Loggers.EXECUTION_ACTION.trace("Action id resolved, id={}, scope={}", id,
                                           scope.declaringScope(id).orElse(null));
        }
        return action;
    }

    /**
     * Refuses a pass-through dispatch whose callee was declared against a context this one cannot
     * supply. {@code Object} accepts anything and a {@code null} context tells us nothing, so both
     * pass.
     */
    private void requireContextAccepted(String id, Class<?> calleeContext) {
        Object active = getContext();
        if (calleeContext == null || calleeContext == Object.class || active == null
                || calleeContext.isInstance(active)) {
            return;
        }
        throw new TransfluxValidationException(
            "Context type mismatch: action '" + id + "' is declared for context "
                + calleeContext.getName() + " and cannot be run pass-through from a "
                + active.getClass().getName() + " context; supply a mapper at this call site");
    }

    /**
     * Refuses a mapped dispatch whose mapper produced something the callee cannot run against.
     * <p>
     * The mapper's own child type cannot be checked at build time - it is erased on an inline
     * lambda, and the call site is inside a Java body the definition cannot see - so this is the
     * first moment the two are comparable. Without it the mismatch surfaces as a bare
     * {@code ClassCastException} thrown from the callee's bridge method, naming neither the action
     * nor the boundary it crossed.
     * <p>
     * A {@code null} child is refused here, where the pass-through check lets a {@code null} caller
     * context through. The two nulls are not the same thing: there, the caller genuinely has no
     * context and the callee is the sort that does not need one; here, host code at this very call
     * site was asked to produce a context for a callee that declared it needs one, and returned
     * nothing. That is the same broken-host-code case {@code ForkableContext.fork()} already
     * refuses, and letting it past turns a nameable boundary failure into a
     * {@code NullPointerException} somewhere inside the callee.
     */
    private void requireMappedContextAccepted(String id, Class<?> calleeContext, Object child) {
        if (calleeContext == null || calleeContext == Object.class) {
            return;
        }
        if (child == null) {
            throw new TransfluxValidationException(
                "Context type mismatch: action '" + id + "' is declared for context "
                    + calleeContext.getName() + ", but the mapper supplied at this call site"
                    + " produced null; a component that declares a context cannot be run without"
                    + " one");
        }
        if (calleeContext.isInstance(child)) {
            return;
        }
        throw new TransfluxValidationException(
            "Context type mismatch: action '" + id + "' is declared for context "
                + calleeContext.getName() + ", but the mapper supplied at this call site produced a "
                + child.getClass().getName());
    }

    /**
     * Names a context by type for a trace line. {@code null} is a legitimate context - an
     * {@code Object.class} component dispatched from a {@code Void.class} caller receives one.
     */
    private static String describeType(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    /**
     * Pushes {@code scopeRegistry} as the active lexical scope for subsequent imperative
     * {@code run(...)} resolution. A declarative container pushes its own scope on entry to
     * {@code execute} and pops it on exit; an imperative action pushes none, since it owns no
     * scope of its own.
     *
     * @param scopeRegistry the container's scope registry; never {@code null}
     */
    void pushScope(Registry<T> scopeRegistry) {
        requireNotNull(scopeRegistry, "Scope registry");
        scopeStack.push(scopeRegistry);
    }

    /**
     * Pops the most recently pushed scope registry. Must be paired with a preceding
     * {@link #pushScope(Registry)} call.
     *
     * @throws TransfluxValidationException if the scope stack is empty
     */
    void popScope() {
        if (scopeStack.isEmpty()) {
            throw new TransfluxValidationException(
                "popScope() called with no matching pushScope()");
        }
        scopeStack.pop();
    }

    /**
     * Returns the registry that {@code run(...)} resolution should consult. When the scope
     * stack is empty - which a transition's body makes rare, since it pushes its own scope before
     * dispatching anything - this falls back to the state machine's root registry.
     *
     * @return the active scope; never {@code null}
     */
    Registry<T> activeScope() {
        return scopeStack.isEmpty() ? stateMachine.getComponentRegistry() : scopeStack.peek();
    }

    private ContextMapper<Object, Object> resolveRegisteredMapper(String mapperId) {
        StateMachineDefImpl<T> def = stateMachine.getDef();
        MapperDef<?, ?> mapperDef = def.getMapperDef(mapperId);
        if (mapperDef == null) {
            throw new TransfluxValidationException(
                "No mapper registered with id '" + mapperId + "'");
        }
        @SuppressWarnings("unchecked")
        MapperDefImpl<Object, Object> impl = (MapperDefImpl<Object, Object>) mapperDef;
        return impl.buildMapper();
    }

    private ActionPath qualifyActionPath(String localStepId) {
        requireNotBlank(localStepId, "Step ID");

        if (operationStack.isEmpty()) {
            return ActionPath.of(localStepId);
        }

        List<String> segments = new ArrayList<>(operationStack.size() + 1);
        Iterator<String> descending = operationStack.descendingIterator();
        while (descending.hasNext()) {
            segments.add(descending.next());
        }
        segments.add(localStepId);

        return new ActionPath(segments);
    }

    /**
     * Pushes an action's compensation table onto this view's LIFO rollback stack at the supplied
     * qualified path. A {@code null} router is a no-op; this lets callers forward the result of
     * resolving an action's compensation channels unconditionally without first checking it for
     * {@code null}.
     *
     * <p>The context is captured alongside the table and handed back at rollback time, so a
     * compensation registered behind a call-site mapper is compensated against the child context
     * its action ran on rather than the enclosing one.
     *
     * @param path the qualified path of the action the compensation rolls back; never {@code null}
     * @param router the action's compensation table; ignored when {@code null}
     * @param context the context the compensated action runs against; may be {@code null}
     */
    void pushCompensation(ActionPath path, BoundCompensationRouter<T, C> router, C context) {
        requireNotNull(path, "Action path");
        if (router == null) {
            return;
        }
        compensationStack.push(new BoundCompensation<>(path, router, context));
    }

    /**
     * Drains the rollback stack and returns its contents in pop order, i.e. reverse order of
     * registration (LIFO). The stack is empty when this method returns.
     *
     * @return an unmodifiable list of the popped compensations in LIFO order
     */
    List<BoundCompensation<T, C>> drainCompensationsLifo() {
        if (compensationStack.isEmpty()) {
            return Collections.emptyList();
        }

        List<BoundCompensation<T, C>> drained = new ArrayList<>(compensationStack.size());
        while (!compensationStack.isEmpty()) {
            drained.add(compensationStack.pop());
        }

        return Collections.unmodifiableList(drained);
    }
}
