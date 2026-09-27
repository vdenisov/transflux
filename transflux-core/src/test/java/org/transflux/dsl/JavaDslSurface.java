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

package org.transflux.dsl;

import org.slf4j.event.Level;
import org.transflux.core.ComponentFactory;
import org.transflux.core.StateMachine;
import org.transflux.core.StateMachineDef;
import org.transflux.core.Transflux;
import org.transflux.core.action.Action;
import org.transflux.core.action.ActionExecution;
import org.transflux.core.action.ActionListener;
import org.transflux.core.action.Compensation;
import org.transflux.core.action.ContextMapper;
import org.transflux.core.action.AsyncRejectionPolicy;
import org.transflux.core.action.ForkableContext;
import org.transflux.core.action.NoMatchBehavior;
import org.transflux.core.condition.Condition;
import org.transflux.core.exception.TransfluxConditionException;
import org.transflux.core.exception.TransfluxContextException;
import org.transflux.core.exception.TransfluxNoMatchException;
import org.transflux.core.logging.ExecutionLogging;
import org.transflux.core.state.StateApplier;
import org.transflux.core.state.StateChange;
import org.transflux.core.state.StateListener;
import org.transflux.core.state.StateResolver;
import org.transflux.core.transition.ExecutingTransition;
import org.transflux.core.transition.ProcessResult;
import org.transflux.core.transition.Transition;
import org.transflux.core.transition.TransitionExecution;
import org.transflux.core.transition.TransitionListener;
import org.transflux.core.transition.TransitionResult;
import org.transflux.core.trigger.Trigger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Every DSL call shape a host would write, written the way a host writes it: in Java, from outside
 * {@code org.transflux.core.impl}, with implicitly-typed lambdas.
 * <p>
 * This class exists to be <em>compiled</em>. The specs that drive it check that the shapes behave,
 * but the assertion that matters most is made by javac before any test runs: an overload family
 * that a lambda cannot pick between, a generic signature that will not infer from a call site, or
 * a type a host cannot reach fails the build here rather than in a host's IDE.
 *
 * <p>Groovy cannot make that assertion. It resolves overloads at runtime against the argument's
 * actual class and coerces closures to whatever functional interface the chosen method wants, so a
 * call that javac rejects as ambiguous dispatches happily from a spec. The DSL is Java's public
 * surface, so it needs a Java caller.
 *
 * <p>When adding an overload to the DSL - especially one whose parameter is a functional interface
 * - add its call shape here.
 */
public final class JavaDslSurface {

    private JavaDslSurface() {
        // fixture holder — no instances
    }

    /**
     * The entity every fixture below transitions. The trail is concurrent because forked members
     * write to it from the pool while the synchronous path is still writing from this thread.
     */
    public static final class Order implements Trackable {
        public String state = "s1";
        public final List<String> trail = new CopyOnWriteArrayList<>();

        public String label() {
            return "o-1";
        }

        @Override
        public List<String> trail() {
            return trail;
        }

        @Override
        public String currentState() {
            return state;
        }

        @Override
        public void currentState(String next) {
            state = next;
        }
    }

    private static final StateResolver<Trackable> TRACKED_RESOLVER = Trackable::currentState;
    private static final StateApplier<Trackable> TRACKED_APPLIER = Trackable::currentState;
    private static final TrackStep TRACK_STEP = new TrackStep();
    private static final IsTracked IS_TRACKED = new IsTracked();

    /**
     * A trait two unrelated entities share. A component written against it registers on a machine
     * for either, which is what the contravariant entity type buys.
     */
    public interface Trackable {
        List<String> trail();

        String currentState();

        void currentState(String next);
    }

    /** The second entity, so a component shared across entity types has two machines to sit on. */
    public static final class Shipment implements Trackable {
        private String state = "s1";
        private final List<String> trail = new CopyOnWriteArrayList<>();

        @Override
        public List<String> trail() {
            return trail;
        }

        @Override
        public String currentState() {
            return state;
        }

        @Override
        public void currentState(String next) {
            state = next;
        }
    }

    /** A supertype of the transition's context, so a pass-through declaration has room to widen. */
    public interface HasOrderId {
        String orderId();
    }

    /** A context that can copy itself, so the fork boundary has something to fork. */
    public static final class OrderCtx implements ForkableContext<OrderCtx>, HasOrderId {
        public String orderId = "o-1";
        public String customerId = "c-1";
        public String receipt;

        @Override
        public String orderId() {
            return orderId;
        }

        @Override
        public OrderCtx fork() {
            OrderCtx copy = new OrderCtx();
            copy.orderId = orderId;
            copy.customerId = customerId;
            return copy;
        }

        public NotifyCtx asNotify() {
            return new NotifyCtx(orderId);
        }
    }

    /** What a mapper produces at a boundary. */
    public static final class NotifyCtx {
        public final String orderId;
        public String receipt;

        public NotifyCtx(String orderId) {
            this.orderId = orderId;
        }
    }

    /** An action that ignores its context, so it can be declared against a widened one. */
    public static final class IgnoresContext implements Action<Order, HasOrderId> {
        @Override
        public void execute(Order order, HasOrderId ctx, ExecutingTransition<Order, HasOrderId> t) {
            order.trail.add("widened:" + ctx.orderId());
        }
    }

    /**
     * A mapper with a real {@code mapFrom}, so the write-back at a mapped boundary is observable.
     * <p>
     * It writes back only what the child produced. Every mapped member gets its own {@code mapFrom}
     * call, so an unconditional write would have each boundary clobber the one before it.
     */
    public static final class NotifyFromOrder implements ContextMapper<OrderCtx, NotifyCtx> {
        @Override
        public NotifyCtx mapTo(OrderCtx parent) {
            return new NotifyCtx(parent.orderId);
        }

        @Override
        public void mapFrom(OrderCtx parent, NotifyCtx child) {
            if (child.receipt != null) {
                parent.receipt = child.receipt;
            }
        }
    }

    public static final class RecordingAction implements Action<Order, OrderCtx> {
        @Override
        public void execute(Order order, OrderCtx ctx, ExecutingTransition<Order, OrderCtx> t) {
            order.trail.add("recording");
        }
    }

    public static final class NotifyAction implements Action<Order, NotifyCtx> {
        @Override
        public void execute(Order order, NotifyCtx ctx, ExecutingTransition<Order, NotifyCtx> t) {
            order.trail.add("notify:" + ctx.orderId);
        }
    }

    public static final class RollbackCompensation implements Compensation<Order, OrderCtx> {
        @Override
        public void compensate(Order order, OrderCtx ctx) {
            order.trail.add("-rolled-back");
        }
    }

    /**
     * Every mapper-bearing call shape on a container member, synchronous and forked.
     * <p>
     * The lambda forms are the ones that broke: {@code ContextMapper} and any other single-method
     * interface of the same shape are indistinguishable to javac at an implicitly-typed lambda, so
     * only one of them may live under a given name.
     *
     * @return the built state machine
     */
    public static StateMachine<Order> mapperCallSites() {
        return Transflux.defineStateMachine(Order.class)
            .withStateResolver(o -> o.state)
            .withStateApplier((o, s) -> o.state = s)
            .withAsyncPool(2, 8)
            .step("record", new RecordingAction())
            .step("notify", NotifyCtx.class, new NotifyAction())
            .mapper("notify-from-order", OrderCtx.class, NotifyCtx.class,
                    parent -> new NotifyCtx(parent.orderId))
            .state("s1")
            .transition("t", "s1", "s2", OrderCtx.class, t -> t
                .operation("op", c -> c
                    // pass-through: same context type on both sides
                    .run("record")
                    // mapped: every shape that can carry a mapper
                    .run("notify", "notify-from-order")
                    .run("notify", parent -> new NotifyCtx(parent.orderId))

                    .fork("record")
                    .fork("notify", "notify-from-order")
                    .fork("notify", parent -> new NotifyCtx(parent.orderId))))
            .state("s2")
            .build();
    }

    /**
     * The whole member grammar at a branch and at a default-branch position - the same family
     * {@link #mapperCallSites()} exercises on a container, proving the three positions really do
     * take the same call shapes and not merely the same method names.
     *
     * @return the built state machine
     */
    public static StateMachine<Order> branchMemberShapes() {
        return Transflux.defineStateMachine(Order.class)
            .withStateResolver(o -> o.state)
            .withStateApplier((o, s) -> o.state = s)
            .step("record", new RecordingAction())
            .step("notify", NotifyCtx.class, new NotifyAction())
            .mapper("notify-from-order", OrderCtx.class, NotifyCtx.class,
                    parent -> new NotifyCtx(parent.orderId))
            .state("s1")
            .transition("t", "s1", "s2", OrderCtx.class, t -> t
                .operation("op", c -> c
                    .choice("branching", choice -> choice
                        .branch("taken", b -> b
                            .condition("always", (order, ctx) -> true)
                            .run("record")
                            .run("notify", "notify-from-order")
                            .run("notify", parent -> new NotifyCtx(parent.orderId))
                            .step("branch-instance", (order, ctx, view) -> order.trail.add("branch-inline"))
                            .step("branch-configured", step -> step
                                .using(new RecordingAction())
                                .withName("In a branch"))
                            .fork("record")
                            .fork("notify", "notify-from-order")
                            .fork("notify", parent -> new NotifyCtx(parent.orderId))
                            .forkStep("branch-forked",
                                      (order, ctx, view) -> order.trail.add("branch-forked"))
                            .forkOperation("branch-forked-group", g -> g
                                .step("branch-forked-member", new RecordingAction()))
                            .forkChoice("branch-forked-route", forked -> forked
                                .branch("branch-forked-taken", branch -> branch
                                    .condition("branch-forked-always", (order, ctx) -> true)
                                    .run("record")))
                            .choice("nested-in-branch", inner -> inner
                                .branch("deep", branch -> branch
                                    .condition("deep-condition", (order, ctx) -> true)
                                    .run("record"))))
                        .defaultBranch(d -> d
                            .run("record")
                            .run("notify", "notify-from-order")
                            .run("notify", parent -> new NotifyCtx(parent.orderId))
                            .step("default-instance", (order, ctx, view) -> order.trail.add("default-inline"))
                            .step("default-configured", step -> step
                                .using(new RecordingAction())
                                .withName("In the default branch"))
                            .fork("record")
                            .forkStep("default-forked",
                                      (order, ctx, view) -> order.trail.add("default-forked"))
                            .forkOperation("default-forked-group", g -> g
                                .step("default-forked-member", new RecordingAction()))
                            .forkChoice("default-forked-route", forked -> forked
                                .branch("default-forked-taken", branch -> branch
                                    .condition("default-forked-always", (order, ctx) -> true)
                                    .run("record")))
                            .choice("nested-in-default", inner -> inner
                                .branch("deep-default", branch -> branch
                                    .condition("deep-default-condition", (order, ctx) -> true)
                                    .run("record")))))))
            .state("s2")
            .build();
    }

    /**
     * Every condition registration form under one {@code condition} name, at a context-typed
     * registration and inside a {@code forContext} scope. The four bodies are separated by the
     * arity of what they take - three parameters for a {@link org.transflux.core.condition.Condition},
     * two for a {@code BiPredicate}, one for a {@code Predicate}, and none at all for an expression
     * string - which is what lets an implicitly-typed lambda pick one of them.
     *
     * @return the built state machine
     */
    public static StateMachine<Order> typedConditions() {
        return Transflux.defineStateMachine(Order.class)
            .withStateResolver(o -> o.state)
            .withStateApplier((o, s) -> o.state = s)
            .condition("as-condition", OrderCtx.class, (order, ctx, view) -> true)
            .condition("as-bipredicate", OrderCtx.class, (order, ctx) -> true)
            .condition("as-predicate", OrderCtx.class, order -> true)
            .condition("as-expression", OrderCtx.class, "true")
            .forContext(OrderCtx.class, scope -> scope
                .condition("scoped-condition", (order, ctx, view) -> true)
                .condition("scoped-bipredicate", (order, ctx) -> true)
                .condition("scoped-predicate", order -> true)
                .condition("scoped-expression", "true"))
            .state("s1")
            .transition("t", "s1", "s2", OrderCtx.class, t -> t
                .preCondition("as-condition")
                .preCondition("as-bipredicate")
                .preCondition("as-predicate")
                .preCondition("as-expression")
                .preCondition("scoped-condition")
                .preCondition("scoped-bipredicate")
                .preCondition("scoped-predicate")
                .preCondition("scoped-expression")
                .step("record", new RecordingAction()))
            .state("s2")
            .build();
    }

    /**
     * A choice registered at state-machine level, in both registration forms, and reached by
     * id - which says nothing about the form it was authored in.
     *
     * @return the built state machine
     */
    public static StateMachine<Order> registeredChoice() {
        return Transflux.defineStateMachine(Order.class)
            .withStateResolver(o -> o.state)
            .withStateApplier((o, s) -> o.state = s)
            .step("record", new RecordingAction())
            .choice("flat", OrderCtx.class, choice -> choice
                .branch("always", b -> b
                    .condition("yes", (order, ctx) -> true)
                    .run("record")))
            .forContext(OrderCtx.class, scope -> scope
                .choice("scoped", choice -> choice
                    .branch("never", b -> b
                        .condition("no", (order, ctx) -> false)
                        .step("unreached", (order, ctx, view) -> order.trail.add("unreached")))
                    .defaultBranch(d -> d
                        .step("fallback", (order, ctx, view) -> order.trail.add("fallback")))))
            .state("s1")
            .transition("t", "s1", "s2", OrderCtx.class, t -> t
                .operation("op", c -> c
                    .run("flat")
                    .run("scoped")))
            .state("s2")
            .build();
    }

    /**
     * A choice attached straight to a transition's action slot - the slot holds one action,
     * and a choice is one, so it needs no wrapping operation.
     *
     * @return the built state machine
     */
    public static StateMachine<Order> transitionChoice() {
        return Transflux.defineStateMachine(Order.class)
            .withStateResolver(o -> o.state)
            .withStateApplier((o, s) -> o.state = s)
            .step("record", new RecordingAction())
            .state("s1")
            .transition("t", "s1", "s2", OrderCtx.class, t -> t
                .choice("route", choice -> choice
                    .branch("premium", b -> b
                        .condition("is-premium", (order, ctx) -> "o-1".equals(ctx.orderId))
                        .step("shared", (order, ctx, view) -> order.trail.add("shared"))
                        .run("record"))
                    .branch("standard", b -> b
                        .condition("is-standard", (order, ctx) -> false)
                        .run("shared"))
                    .defaultBranch(d -> d.run("record"))))
            .state("s2")
            .build();
    }

    /**
     * A sequence declared in place, at each position that holds one: inside a container, inside a
     * branch and a default branch, and nested two deep. The nesting is what proves the form is
     * recursive rather than a single extra level.
     *
     * @return the built state machine
     */
    public static StateMachine<Order> inlineSequenceShapes() {
        return Transflux.defineStateMachine(Order.class)
            .withStateResolver(o -> o.state)
            .withStateApplier((o, s) -> o.state = s)
            .step("record", new RecordingAction())
            .state("s1")
            .transition("t", "s1", "s2", OrderCtx.class, t -> t
                .operation("op", c -> c
                    .operation("in-container", inner -> inner
                        .step("in-container-step", (order, ctx, view) -> order.trail.add("in-container"))
                        .run("record"))
                    .operation("nested", inner -> inner
                        .step("nested-step", (order, ctx, view) -> order.trail.add("nested"))
                        .operation("two-deep", deepest -> deepest
                            .step("two-deep-step", (order, ctx, view) -> order.trail.add("two-deep"))))
                    .choice("branching", choice -> choice
                        .branch("taken", b -> b
                            .condition("always", (order, ctx) -> true)
                            .operation("in-branch", inner -> inner
                                .step("in-branch-step", (order, ctx, view) -> order.trail.add("in-branch"))
                                .choice("deep-choice", deep -> deep
                                    .branch("deep-taken", branch -> branch
                                        .condition("deep-always", (order, ctx) -> true)
                                        .run("record")))))
                        .defaultBranch(d -> d
                            .operation("in-default", inner -> inner
                                .step("in-default-step", (order, ctx, view) -> order.trail.add("in-default")))))))
            .state("s2")
            .build();
    }


    /**
     * Every shape for declaring a context where the action is declared: the pass-through form that
     * widens, and the mapped form that reaches a context the enclosing one cannot widen to. Each of
     * the three declaration verbs, in each of its authoring forms.
     *
     * @return the built state machine
     */
    public static StateMachine<Order> declaredContextShapes() {
        return Transflux.defineStateMachine(Order.class)
            .withStateResolver(o -> o.state)
            .withStateApplier((o, s) -> o.state = s)
            .state("s1")
            .transition("t", "s1", "s2", OrderCtx.class, t -> t
                .operation("op", c -> c
                    // pass-through: the declared context widens, so nothing maps
                    .step("pt-instance", HasOrderId.class,
                          (order, ctx, view) -> order.trail.add("pt:" + ctx.orderId()))
                    .step("pt-configured", HasOrderId.class,
                          st -> st.using(new IgnoresContext()).withName("Widened"))
                    .operation("pt-op", HasOrderId.class, inner -> inner
                        .step("pt-op-step",
                              (order, ctx, view) -> order.trail.add("pt-op:" + ctx.orderId())))
                    .choice("pt-choice", HasOrderId.class, choice -> choice
                        .branch("pt-taken", b -> b
                            .condition("pt-always", (order, ctx) -> true)
                            .step("pt-choice-step",
                                  (order, ctx, view) -> order.trail.add("pt-choice:" + ctx.orderId()))))

                    // mapped: the declared context is produced from the enclosing one
                    .step("mapped-instance", NotifyCtx.class, new NotifyFromOrder(),
                          new NotifyAction())
                    .step("mapped-configured", NotifyCtx.class, new NotifyFromOrder(),
                          st -> st.using(new NotifyAction()).withName("Mapped"))
                    .operation("mapped-op", NotifyCtx.class, new NotifyFromOrder(), inner -> inner
                        .step("mapped-op-step", (order, ctx, view) -> {
                            order.trail.add("mapped-op:" + ctx.orderId);
                            ctx.receipt = "r-1";
                        }))
                    .choice("mapped-choice", NotifyCtx.class, new NotifyFromOrder(), choice -> choice
                        .branch("mapped-taken", b -> b
                            .condition("mapped-always", (order, ctx) -> true)
                            .step("mapped-choice-step",
                                  (order, ctx, view) -> order.trail.add("mapped-choice:" + ctx.orderId))))

                    // the same five, with an implicitly-typed lambda in the mapper slot: the
                    // shape a concrete mapper instance cannot prove resolves
                    .step("lambda-instance", NotifyCtx.class,
                          parent -> new NotifyCtx(parent.orderId), new NotifyAction())
                    .step("lambda-configured", NotifyCtx.class,
                          parent -> new NotifyCtx(parent.orderId),
                          st -> st.using(new NotifyAction()).withName("Lambda-mapped"))
                    .operation("lambda-op", NotifyCtx.class,
                               parent -> new NotifyCtx(parent.orderId), inner -> inner
                        .step("lambda-op-step",
                              (order, ctx, view) -> order.trail.add("lambda-op:" + ctx.orderId)))
                    .choice("lambda-choice", NotifyCtx.class,
                                 parent -> new NotifyCtx(parent.orderId), choice -> choice
                        .branch("lambda-taken", b -> b
                            .condition("lambda-always", (order, ctx) -> true)
                            .step("lambda-choice-step",
                                  (order, ctx, view) -> order.trail.add("lambda-choice:" + ctx.orderId))))
                    .step("read-receipt", (order, ctx, view) -> order.trail.add("receipt:" + ctx.receipt))

                    // the same four, through a mapper registered on the state machine
                    .step("registered-instance", NotifyCtx.class, "notify-from-order", new NotifyAction())
                    .step("registered-configured", NotifyCtx.class, "notify-from-order",
                          st -> st.using(new NotifyAction()).withName("Registered-mapped"))
                    .operation("registered-op", NotifyCtx.class, "notify-from-order", inner -> inner
                        .step("registered-op-step", (order, ctx, view) -> {
                            order.trail.add("registered-op:" + ctx.orderId);
                            ctx.receipt = "r-2";
                        }))
                    .choice("registered-choice", NotifyCtx.class, "notify-from-order", choice -> choice
                        .branch("registered-taken", b -> b
                            .condition("registered-always", (order, ctx) -> true)
                            .step("registered-choice-step",
                                  (order, ctx, view) -> order.trail.add("registered-choice:" + ctx.orderId))))))
            .mapper("notify-from-order", OrderCtx.class, NotifyCtx.class, new NotifyFromOrder())
            .state("s2")
            .build();
    }

    /**
     * The same mapper grammar as dispatched from inside an action's body, which is the second
     * surface carrying it.
     *
     * @return the built state machine
     */
    public static StateMachine<Order> dispatchFromActionBody() {
        Action<Order, OrderCtx> dispatcher = (order, ctx, view) -> {
            view.run("record");
            view.run("notify", "notify-from-order");
            view.run("notify", parent -> new NotifyCtx(parent.orderId));
        };

        return Transflux.defineStateMachine(Order.class)
            .withStateResolver(o -> o.state)
            .withStateApplier((o, s) -> o.state = s)
            .step("record", new RecordingAction())
            .step("notify", NotifyCtx.class, new NotifyAction())
            .mapper("notify-from-order", OrderCtx.class, NotifyCtx.class,
                    parent -> new NotifyCtx(parent.orderId))
            .state("s1")
            .transition("t", "s1", "s2", OrderCtx.class, t -> t
                .step("dispatch", dispatcher))
            .state("s2")
            .build();
    }

    /**
     * The declaration shapes: inline steps in each form, a choice with branches, and the
     * configurer forms that carry metadata, listeners and compensation.
     *
     * @return the built state machine
     */
    public static StateMachine<Order> declarationShapes() {
        return Transflux.defineStateMachine(Order.class)
            .withStateResolver(o -> o.state)
            .withStateApplier((o, s) -> o.state = s)
            .state("s1")
            .transition("t", "s1", "s2", OrderCtx.class, t -> t
                .operation("op", c -> c
                    .step("inline-instance", (order, ctx, view) -> order.trail.add("inline"))
                    .step("inline-configured", step -> step
                        .using(new RecordingAction())
                        .withName("Configured")
                        .withDescription("Declared through a configurer")
                        .withCompensation(new RollbackCompensation())
                        .onStart("watch-start", (order, ctx, execution) -> { })
                        .onComplete("watch-done", (order, ctx, execution) -> { })
                        .onError("watch-fail", (order, ctx, execution) -> { }))
                    .choice("branching", choice -> choice
                        .branch("premium", b -> b
                            .condition("is-premium", (order, ctx) -> "o-1".equals(ctx.orderId))
                            .run("inline-instance"))
                        .defaultBranch(d -> d.run("inline-configured")))))
            .state("s2")
            .build();
    }

    /**
     * Compensation declaration in all three channels, including the {@code forException}
     * continuation, whose fluent chain has to hand the def back for the next call to compile.
     *
     * @return the built state machine
     */
    public static StateMachine<Order> compensationShapes() {
        return Transflux.defineStateMachine(Order.class)
            .withStateResolver(o -> o.state)
            .withStateApplier((o, s) -> o.state = s)
            .state("s1")
            .transition("t", "s1", "s2", OrderCtx.class, t -> t
                .operation("op", c -> c
                    .withCompensation((order, ctx) -> order.trail.add("-container"))
                    .step("charge", step -> step
                        .using(new RecordingAction())
                        .withCompensation(new RollbackCompensation())
                        .forException(IllegalStateException.class)
                            .withCompensation((order, ctx) -> order.trail.add("-illegal"))
                        .forException(RuntimeException.class)
                            .matching(e -> e.getMessage() != null)
                            .withCompensation(new RollbackCompensation()))))
            .state("s2")
            .build();
    }

    /**
     * The execution logging registration, with the entity label written as a method reference, and
     * a per-owner listener with a typed-lambda label on a transition that declares its context.
     *
     * @return the built state machine
     */
    public static StateMachine<Order> executionLoggingShapes() {
        return Transflux.defineStateMachine(Order.class)
            .withStateResolver(o -> o.state)
            .withStateApplier((o, s) -> o.state = s)
            .withExecutionLogging(ExecutionLogging.atLevel(Level.INFO)
                                      .withEntityLabel(Order::label)
                                      .withTimings())
            .step("record", OrderCtx.class, new RecordingAction())
            .state("s1", s -> s
                .onExit("log-exit", ExecutionLogging.defaults().stateListener()))
            .transition("t", "s1", "s2", OrderCtx.class, t -> t
                .onComplete("log-payment", ExecutionLogging.atLevel(Level.DEBUG)
                    .withEntityLabel((Order o) -> o.label())
                    .withContext()
                    .transitionListener())
                .run("record"))
            .state("s2")
            .build();
    }

    /**
     * Both disable forms, on each of the three owners that carries them, against globals the same
     * definition registers.
     *
     * @return the built state machine
     */
    public static StateMachine<Order> globalListenerDisableShapes() {
        return Transflux.defineStateMachine(Order.class)
            .withStateResolver(o -> o.state)
            .withStateApplier((o, s) -> o.state = s)
            .onAnyStateEntry("any-entry", (order, ctx, change) -> order.trail.add("any-entry"))
            .onAnyStateExit("any-exit", (order, ctx, change) -> order.trail.add("any-exit"))
            .onAnyTransitionStart("any-start",
                                  (order, ctx, execution) -> order.trail.add("any-start"))
            .onAnyActionStart("any-action-start",
                              (order, ctx, execution) -> order.trail.add("any-action-start"))
            .step("record", OrderCtx.class, step -> step
                .using(new RecordingAction())
                .disableGlobalListener("any-action-start")
                .disableGlobalListeners("any-action-start", "any-action-start"))
            .operation("wrap", OrderCtx.class, op -> op
                .disableAllGlobalListeners()
                .run("record"))
            .state("s1", s -> s
                .disableGlobalListener("any-exit")
                .disableGlobalListeners("any-exit", "any-entry"))
            .transition("t", "s1", "s2", OrderCtx.class, t -> t
                .disableGlobalListener("any-start")
                .disableGlobalListeners("any-start")
                .run("wrap"))
            .state("s2", s -> s.disableAllGlobalListeners())
            .build();
    }

    /**
     * The async listener declaration in both shapes, on each of the three listener categories.
     *
     * @return the built state machine, which owns a pool and must be closed
     */
    public static StateMachine<Order> asyncListenerShapes() {
        return Transflux.defineStateMachine(Order.class)
            .withStateResolver(o -> o.state)
            .withStateApplier((o, s) -> o.state = s)
            .onAnyStateEntry("state-async", l -> l
                .using((order, ctx, change) -> order.trail.add("state-async"))
                .withAsync())
            .onAnyTransitionComplete("transition-async", l -> l
                .using((order, ctx, execution) -> order.trail.add("transition-async"))
                .withAsync(AsyncRejectionPolicy.CALLER_RUNS))
            .step("record", OrderCtx.class, step -> step
                .using(new RecordingAction())
                .onComplete("action-async", l -> l
                    .using((order, ctx, execution) -> order.trail.add("action-async"))
                    .withAsync(AsyncRejectionPolicy.BLOCK)))
            .state("s1")
            .transition("t", "s1", "s2", OrderCtx.class, t -> t
                .run("record"))
            .state("s2", s -> s
                .onExit("state-exit-async", l -> l
                    .using((order, ctx, change) -> order.trail.add("state-exit-async"))
                    .withAsync(AsyncRejectionPolicy.DROP)))
            .build();
    }

    /**
     * The forked half of the same grammar, dispatched from inside an action's body. Six shapes:
     * three call shapes, each with and without a policy of its own. The pool is asked for
     * explicitly because a fork written here is invisible to the build.
     *
     * @return the built state machine, which owns a pool and must be closed
     */
    public static StateMachine<Order> forkFromActionBody() {
        Action<Order, OrderCtx> dispatcher = (order, ctx, view) -> {
            view.fork("record");
            view.fork("record", AsyncRejectionPolicy.CALLER_RUNS);
            view.fork("notify", "notify-from-order");
            view.fork("notify", "notify-from-order", AsyncRejectionPolicy.DROP);
            view.fork("notify", parent -> new NotifyCtx(parent.orderId));
            view.fork("notify", parent -> new NotifyCtx(parent.orderId),
                      AsyncRejectionPolicy.DROP);
        };

        return Transflux.defineStateMachine(Order.class)
            .withStateResolver(o -> o.state)
            .withStateApplier((o, s) -> o.state = s)
            .withAsyncPool()
            .step("record", new RecordingAction())
            .step("notify", NotifyCtx.class, new NotifyAction())
            .mapper("notify-from-order", OrderCtx.class, NotifyCtx.class,
                    parent -> new NotifyCtx(parent.orderId))
            .state("s1")
            .transition("t", "s1", "s2", OrderCtx.class, t -> t
                .step("dispatch", dispatcher))
            .state("s2")
            .build();
    }

    /**
     * Executor configuration in both ownership modes, plus the rejection policy.
     *
     * @return the built state machine, which owns a pool and must be closed
     */
    public static StateMachine<Order> executorConfiguration() {
        return Transflux.defineStateMachine(Order.class)
            .withStateResolver(o -> o.state)
            .withStateApplier((o, s) -> o.state = s)
            .withAsyncPool(2, 8, runnable -> {
                Thread thread = new Thread(runnable, "host-named-async");
                thread.setDaemon(true);
                return thread;
            })
            .withAsyncRejectionPolicy(AsyncRejectionPolicy.FAIL)
            .step("record", new RecordingAction())
            // the policy declared where the work is, rather than where it is forked from
            .step("notify-async", OrderCtx.class, step -> step
                .using(new RecordingAction())
                .withAsyncRejectionPolicy(AsyncRejectionPolicy.DROP))
            .step("notify", NotifyCtx.class, new NotifyAction())
            .mapper("notify-from-order", OrderCtx.class, NotifyCtx.class,
                    parent -> new NotifyCtx(parent.orderId))
            .state("s1")
            .transition("t", "s1", "s2", OrderCtx.class, t -> t
                .operation("op", c -> c
                    .fork("record")
                    .fork("notify-async")
                    // the policy declared where the work is forked, in each by-id shape
                    .fork("record", AsyncRejectionPolicy.CALLER_RUNS)
                    .fork("notify", "notify-from-order", AsyncRejectionPolicy.DROP)
                    .fork("notify", parent -> new NotifyCtx(parent.orderId),
                          AsyncRejectionPolicy.DROP)))
            .state("s2")
            .build();
    }

    /**
     * The member grammar at a transition position. A transition's body is a sequence like any
     * other, so every form has to resolve here too - several members in a row, a call-site mapper,
     * {@code fork} beside a synchronous member, and the context-declaring shapes. None of this was
     * expressible while the transition held a single action.
     *
     * @return the built state machine, which owns a pool and must be closed
     */
    public static StateMachine<Order> transitionSequenceShapes() {
        return Transflux.defineStateMachine(Order.class)
            .withStateResolver(o -> o.state)
            .withStateApplier((o, s) -> o.state = s)
            .step("record", new RecordingAction())
            .step("notify", NotifyCtx.class, new NotifyAction())
            .mapper("notify-from-order", OrderCtx.class, NotifyCtx.class,
                    parent -> new NotifyCtx(parent.orderId))
            .state("s1")
            .transition("t", "s1", "s2", OrderCtx.class, t -> t
                // several members in a row, which the single-action slot could not hold
                .run("record")
                .run("notify", "notify-from-order")
                .run("notify", parent -> new NotifyCtx(parent.orderId))

                // declarations, inheriting the transition's context
                .step("inline", (order, ctx, view) -> order.trail.add("inline:" + ctx.orderId))
                .step("configured", st -> st.using(new RecordingAction()).withName("Configured"))
                .operation("group", c -> c.step("grouped", new RecordingAction()))
                .choice("route", choice -> choice
                    .branch("taken", b -> b
                        .condition("always", (order, ctx) -> true)
                        .step("branch-member", new RecordingAction())))

                // declarations naming a context of their own, pass-through and mapped
                .step("widened", HasOrderId.class,
                      (order, ctx, view) -> order.trail.add("widened:" + ctx.orderId()))
                .operation("mapped-group", NotifyCtx.class,
                           parent -> new NotifyCtx(parent.orderId), inner -> inner
                    .step("mapped-member", (order, ctx, view) ->
                        order.trail.add("mapped:" + ctx.orderId)))

                // and fork, beside a synchronous member rather than inside a wrapper
                .fork("record")
                .fork("notify", "notify-from-order")
                .fork("notify", parent -> new NotifyCtx(parent.orderId))
                .forkStep("t-forked", (order, ctx, view) -> order.trail.add("t-forked"))
                .forkOperation("t-forked-group", g -> g
                    .step("t-forked-member", new RecordingAction()))
                .forkChoice("t-forked-route", choice -> choice
                    .branch("t-forked-taken", b -> b
                        .condition("t-forked-always", (order, ctx) -> true)
                        .run("record"))))
            .state("s2")
            .build();
    }

    /**
     * Every inline forked declaration, in each of its three context shapes. They mirror
     * {@link #declaredContextShapes()} verb for verb; what they add is that a declaration and a
     * fork can be written as one thing, which needed a registered component and a reference before.
     * <p>
     * The lambda-mapper block is the load-bearing one again: {@code ContextMapper} and
     * {@code Consumer} are indistinguishable to javac at an implicitly-typed lambda, which is why
     * these are named verbs rather than {@code fork} overloads.
     *
     * @return the built state machine, which owns a pool and must be closed
     */
    public static StateMachine<Order> forkedDeclarationShapes() {
        return Transflux.defineStateMachine(Order.class)
            .withStateResolver(o -> o.state)
            .withStateApplier((o, s) -> o.state = s)
            .step("record", new RecordingAction())
            .state("s1")
            .transition("t", "s1", "s2", OrderCtx.class, t -> t
                .operation("op", c -> c
                    // inheriting the enclosing context
                    .forkStep("f-instance", (order, ctx, view) -> order.trail.add("f-instance"))
                    .forkStep("f-configured", st -> st
                        .using(new RecordingAction())
                        .withName("Forked"))
                    .forkOperation("f-group", g -> g.run("record"))
                    .forkChoice("f-route", choice -> choice
                        .branch("f-taken", b -> b
                            .condition("f-always", (order, ctx) -> true)
                            .run("record")))

                    // declaring a context of their own, pass-through
                    .forkStep("f-pt-instance", HasOrderId.class,
                              (order, ctx, view) -> order.trail.add("f-pt:" + ctx.orderId()))
                    .forkStep("f-pt-configured", HasOrderId.class,
                              st -> st.using(new IgnoresContext()).withName("Widened fork"))
                    .forkOperation("f-pt-group", HasOrderId.class, g -> g
                        .step("f-pt-member",
                              (order, ctx, view) -> order.trail.add("f-pt-member")))
                    .forkChoice("f-pt-route", HasOrderId.class, choice -> choice
                        .branch("f-pt-taken", b -> b
                            .condition("f-pt-always", (order, ctx) -> true)
                            .step("f-pt-branch-member", new IgnoresContext())))

                    // declaring a context of their own, produced by a mapper written inline
                    .forkStep("f-mapped-instance", NotifyCtx.class,
                              parent -> new NotifyCtx(parent.orderId), new NotifyAction())
                    .forkStep("f-mapped-configured", NotifyCtx.class, new NotifyFromOrder(),
                              st -> st.using(new NotifyAction()).withName("Mapped fork"))
                    .forkOperation("f-mapped-group", NotifyCtx.class,
                                   parent -> new NotifyCtx(parent.orderId), g -> g
                        .step("f-mapped-member",
                              (order, ctx, view) -> order.trail.add("f-mapped:" + ctx.orderId)))
                    .forkChoice("f-mapped-route", NotifyCtx.class,
                                parent -> new NotifyCtx(parent.orderId), choice -> choice
                        .branch("f-mapped-taken", b -> b
                            .condition("f-mapped-always", (order, ctx) -> true)
                            .step("f-mapped-branch-member", new NotifyAction())))

                    // declaring a context of their own, produced by a registered mapper
                    .forkStep("f-registered-instance", NotifyCtx.class, "notify-from-order",
                              new NotifyAction())
                    .forkStep("f-registered-configured", NotifyCtx.class, "notify-from-order",
                              st -> st.using(new NotifyAction()).withName("Registered fork"))
                    .forkOperation("f-registered-group", NotifyCtx.class, "notify-from-order", g -> g
                        .step("f-registered-member",
                              (order, ctx, view) -> order.trail.add("f-registered:" + ctx.orderId)))
                    .forkChoice("f-registered-route", NotifyCtx.class, "notify-from-order", choice -> choice
                        .branch("f-registered-taken", b -> b
                            .condition("f-registered-always", (order, ctx) -> true)
                            .step("f-registered-branch-member", new NotifyAction())))))
            .mapper("notify-from-order", OrderCtx.class, NotifyCtx.class, new NotifyFromOrder())
            .state("s2")
            .build();
    }

    /** An action that answers for its own rollback, the dynamic compensation channel. */
    public static final class SelfCompensatingAction implements Action<Order, OrderCtx> {
        @Override
        public void execute(Order order, OrderCtx ctx, ExecutingTransition<Order, OrderCtx> t) {
            order.trail.add("self-compensating");
        }

        @Override
        public Compensation<Order, OrderCtx> getCompensation(Order order, OrderCtx ctx) {
            return (o, c) -> o.trail.add("-self-compensated");
        }
    }

    /** A full condition, the three-argument contract a lambda of lower arity cannot be mistaken for. */
    public static final class OrderIsOpen implements Condition<Order, Object> {
        @Override
        public boolean test(Order order, Object ctx, Transition transition) {
            return "s1".equals(order.state) && "t".equals(transition.getId());
        }
    }

    /** A predicate class, the instance form of the one-argument lambda. */
    public static final class OrderHasLabel implements Predicate<Order> {
        @Override
        public boolean test(Order order) {
            return order.label() != null;
        }
    }

    /** A state listener written as a class, reading every component of its payload. */
    public static final class StateAudit implements StateListener<Order> {
        @Override
        public void onState(Order order, Object ctx, StateChange change) {
            order.trail.add(change.phase() + ":" + change.state().getId()
                                + ":" + change.transition().getId());
        }
    }

    /** A transition listener typed to the transition's context, reading the origin off the payload. */
    public static final class TransitionAudit implements TransitionListener<Order, OrderCtx> {
        @Override
        public void onTransition(Order order, OrderCtx ctx, TransitionExecution<Order> execution) {
            Trigger firedBy = execution.firedBy();
            order.trail.add(execution.phase() + ":" + execution.transition().getId()
                                + ":" + (firedBy == null ? "direct" : firedBy.getId())
                                + ":" + (execution.result() == null ? "-" : execution.result().isSuccess()));
        }
    }

    /** The global counterpart: a registration spanning transitions takes an {@code Object} context. */
    public static final class AnyTransitionAudit implements TransitionListener<Order, Object> {
        @Override
        public void onTransition(Order order, Object ctx, TransitionExecution<Order> execution) {
            order.trail.add("any:" + execution.phase());
        }
    }

    /** An action listener written as a class, reading every component of its payload. */
    public static final class ActionAudit implements ActionListener<Order, OrderCtx> {
        @Override
        public void onAction(Order order, OrderCtx ctx, ActionExecution execution) {
            order.trail.add(execution.phase() + ":" + execution.path() + ":" + execution.kind()
                                + ":" + execution.actionId() + ":" + execution.transition().getId()
                                + ":" + (execution.error() == null) + ":" + (execution.duration() == null));
        }
    }

    /** An action against the shared trait, so a machine for either entity can register it. */
    public static final class TrackStep implements Action<Trackable, Object> {
        @Override
        public void execute(Trackable entity, Object ctx, ExecutingTransition<Trackable, Object> view) {
            entity.trail().add("tracked");
        }
    }

    /** A compensation against the trait, attached to a step declared on either machine. */
    public static final class UntrackCompensation implements Compensation<Trackable, Object> {
        @Override
        public void compensate(Trackable entity, Object ctx) {
            entity.trail().add("untracked");
        }
    }

    /** A condition against the trait. */
    public static final class IsTracked implements Condition<Trackable, Object> {
        @Override
        public boolean test(Trackable entity, Object ctx, Transition transition) {
            return entity.currentState() != null;
        }
    }

    /** A state listener against the trait. */
    public static final class TrackedStateAudit implements StateListener<Trackable> {
        @Override
        public void onState(Trackable entity, Object ctx, StateChange change) {
            entity.trail().add("state:" + change.phase());
        }
    }

    /** A transition listener against the trait. */
    public static final class TrackedTransitionAudit implements TransitionListener<Trackable, Object> {
        @Override
        public void onTransition(Trackable entity, Object ctx, TransitionExecution<Trackable> execution) {
            entity.trail().add("transition:" + execution.phase());
        }
    }

    /** An action listener against the trait. */
    public static final class TrackedActionAudit implements ActionListener<Trackable, Object> {
        @Override
        public void onAction(Trackable entity, Object ctx, ActionExecution execution) {
            entity.trail().add("action:" + execution.phase());
        }
    }

    /**
     * Every condition attachment form on a transition and every trigger declaration form, on the
     * pass-through {@code transition} form that declares no context. The condition family is the
     * resolution-sensitive one: instance, two-argument lambda, one-argument lambda, method
     * reference and expression all live under one name.
     *
     * @return the built state machine
     */
    public static StateMachine<Order> conditionAndTriggerShapes() {
        return Transflux.defineStateMachine(Order.class)
            .withName("Orders")
            .withVersion("1.0.0")
            .withStateResolver(o -> o.state)
            .withStateApplier((o, s) -> o.state = s)
            .condition("registered-open", o -> "s1".equals(o.state))
            .state("s1", s -> s
                .withName("Open")
                .withDescription("An order nobody has paid for"))
            .transition("t", "s1", "s2", t -> t
                .withName("Pay")
                .withDescription("Takes payment")
                .preCondition("registered-open")
                .preCondition("pre-instance", new OrderIsOpen())
                .preCondition("pre-bi", (order, ctx) -> order.label() != null)
                .preCondition("pre-lambda", order -> order.label() != null)
                .preCondition("pre-class", new OrderHasLabel())
                .preCondition("pre-method-ref", JavaDslSurface::isOpen)
                .preCondition("pre-expression", "state == 's1'")
                .preConditionExpression("state == 's1'")
                .postCondition("registered-open")
                .postCondition("post-instance", new OrderIsOpen())
                .postCondition("post-bi", (order, ctx) -> true)
                .postCondition("post-lambda", order -> true)
                .postCondition("post-expression", "state == 's1'")
                .postConditionExpression("#transition.id == 't'")

                .addManualTrigger("pay-now")
                .addManualTrigger("pay-checked", mt -> mt
                    .withDescription("A manual trigger with pre-conditions of its own")
                    .preCondition("registered-open")
                    .preCondition("mt-lambda", order -> true)
                    .preConditionExpression("state == 's1'"))
                .addEventTrigger("on-paid", "PAID")
                .addEventTrigger("SETTLED")
                .addEventTrigger("on-refund-expr", et -> et
                    .onEvent("REFUND")
                    .filterExpression("#event == 'full'"))
                .addEventTrigger("on-refund-payload", et -> et
                    .onEvent("REFUND")
                    .filter(event -> "partial".equals(event)))
                .addEventTrigger("on-refund-entity", et -> et
                    .onEvent("REFUND")
                    .filter((event, order) -> order.label().equals(event)))
                .addDataTrigger("when-flagged", dt -> dt
                    .condition("flagged", order -> order.trail.contains("flag")))
                .addDataTrigger("when-registered", dt -> dt.condition("registered-open"))
                .addDataTrigger("when-expression", dt -> dt.conditionExpression("state == 's1'"))

                .step("pay", (order, ctx, view) -> order.trail.add("pay")))
            .state("s2")
            .build();
    }

    /**
     * Every listener hook, per owner and global, in both the instance and the configurer form. The
     * instance form takes a three-argument lambda and the configurer a one-argument one, which is
     * what keeps the two apart.
     *
     * @return the built state machine
     */
    public static StateMachine<Order> listenerHookShapes() {
        return Transflux.defineStateMachine(Order.class)
            .withStateResolver(o -> o.state)
            .withStateApplier((o, s) -> o.state = s)
            .onAnyStateEntry("g-entry", new StateAudit())
            .onAnyStateExit("g-exit", l -> l.using(new StateAudit()))
            .onAnyTransitionStart("g-start", l -> l.using(new AnyTransitionAudit()))
            .onAnyTransitionComplete("g-complete", new AnyTransitionAudit())
            .onAnyTransitionError("g-error", new AnyTransitionAudit())
            .onAnyTransitionError("g-error-cfg", l -> l
                .using((order, ctx, execution) -> order.trail.add("g-error-cfg")))
            .onAnyActionStart("g-action-start", l -> l
                .using((order, ctx, execution) -> order.trail.add("g-action-start")))
            .onAnyActionComplete("g-action-complete",
                                 (order, ctx, execution) -> order.trail.add("g-action-complete"))
            .onAnyActionComplete("g-action-complete-cfg", l -> l
                .using((order, ctx, execution) -> order.trail.add("g-action-complete-cfg")))
            .onAnyActionError("g-action-error",
                              (order, ctx, execution) -> order.trail.add("g-action-error"))
            .onAnyActionError("g-action-error-cfg", l -> l
                .using((order, ctx, execution) -> order.trail.add("g-action-error-cfg")))
            .step("record", OrderCtx.class, step -> step
                .using(new RecordingAction())
                .onStart("a-start", new ActionAudit())
                .onStart("a-start-cfg", l -> l.using(new ActionAudit()))
                .onComplete("a-complete", new ActionAudit())
                .onError("a-error", (order, ctx, execution) -> order.trail.add("a-error"))
                .onError("a-error-cfg", l -> l
                    .withDescription("Audits a failed charge")
                    .using(new ActionAudit())))
            .state("s1", s -> s
                .onEntry("s-entry", new StateAudit())
                .onEntry("s-entry-lambda", (order, ctx, change) -> order.trail.add("s-entry-lambda"))
                .onEntry("s-entry-cfg", l -> l.withName("Entry audit").using(new StateAudit()))
                .onExit("s-exit", (order, ctx, change) -> order.trail.add("s-exit")))
            .transition("t", "s1", "s2", OrderCtx.class, t -> t
                .onStart("t-start", new TransitionAudit())
                .onStart("t-start-cfg", l -> l.using(new TransitionAudit()))
                .onComplete("t-complete", (order, ctx, execution) -> order.trail.add("t-complete"))
                .onComplete("t-complete-cfg", l -> l.using(new TransitionAudit()))
                .onError("t-error", new TransitionAudit())
                .onError("t-error-cfg", l -> l
                    .withDescription("Audits a failed transition")
                    .using(new TransitionAudit()))
                .disableAllGlobalListeners()
                .run("record"))
            .state("s2")
            .build();
    }

    /**
     * Registration shapes the other fixtures leave out: a mapper from an instance, from a method
     * reference and through {@code mapperDef}; an action that supplies its own compensation; a route
     * guard written as a method reference, on a container whose chain carries on afterwards; a
     * choice's no-match behaviour; and a branch condition in its one-argument and class forms.
     *
     * @return the built state machine
     */
    public static StateMachine<Order> registrationShapes() {
        // the no-argument entry point needs the witness: T is not inferable at the receiver
        return Transflux.<Order>defineStateMachine()
            .forEntityType(Order.class)
            .withStateResolver(o -> o.state)
            .withStateApplier((o, s) -> o.state = s)
            .step("notify", NotifyCtx.class, new NotifyAction())
            .mapper("notify-instance", OrderCtx.class, NotifyCtx.class, new NotifyFromOrder())
            .mapper("notify-method-ref", OrderCtx.class, NotifyCtx.class, OrderCtx::asNotify)
            .mapperDef("notify-def", OrderCtx.class, NotifyCtx.class, m -> m
                .withName("Notification from order")
                .using(new NotifyFromOrder()))
            .state("s1")
            .transition("t", "s1", "s2", OrderCtx.class, t -> t
                .step("self-compensating", new SelfCompensatingAction())
                .operation("guarded", op -> op
                    .withDescription("A container with routes and a fallback")
                    .forException(IllegalStateException.class)
                        .matching(JavaDslSurface::hasMessage)
                        .withCompensation((order, ctx) -> order.trail.add("-illegal"))
                    .withCompensation((order, ctx) -> order.trail.add("-guarded"))
                    .run("notify", "notify-instance")
                    .run("notify", "notify-method-ref")
                    .run("notify", "notify-def"))
                .choice("route", choice -> choice
                    .onNoMatch(NoMatchBehavior.SILENT)
                    .branch("labelled", b -> b
                        .condition("is-labelled", new OrderHasLabel())
                        .step("labelled-member", (order, ctx, view) -> order.trail.add("labelled")))
                    .branch("open", b -> b
                        .condition("is-open", order -> "s1".equals(order.state))
                        .step("open-member", (order, ctx, view) -> order.trail.add("open")))))
            .state("s2")
            .build();
    }

    /**
     * The host-side entry points and the accessors on what they return, against
     * {@link #conditionAndTriggerShapes()}. Every transition there leaves {@code s1}, so the entity
     * is put back between calls.
     *
     * @return one line per call, for the spec to assert on
     */
    public static List<String> hostEntryPoints() {
        List<String> outcomes = new ArrayList<>();
        try (StateMachine<Order> sm = conditionAndTriggerShapes()) {
            Order order = new Order();
            Object ctx = new OrderCtx();

            outcomes.add("transitionTo:" + reset(order, sm.entity(order).transitionTo("s2")));
            outcomes.add("transitionTo-id:" + reset(order, sm.entity(order).transitionTo("s2", "t")));
            outcomes.add("transitionTo-ctx:" + reset(order, sm.entity(order).transitionTo("s2", ctx)));
            outcomes.add("transitionTo-id-ctx:"
                             + reset(order, sm.entity(order).transitionTo("s2", "t", ctx)));
            outcomes.add("executeTransition:" + reset(order, sm.executeTransition(order, "s2")));
            outcomes.add("executeTransition-id:" + reset(order, sm.executeTransition(order, "s2", "t")));
            outcomes.add("fire:" + reset(order, sm.entity(order).fire("pay-now")));
            outcomes.add("fire-ctx:" + reset(order, sm.entity(order).fire("pay-checked", ctx)));

            ProcessResult<Order> paid = sm.entity(order).processEvent("PAID", "payload");
            outcomes.add("processEvent:" + paid.fired() + ":" + paid.firedTriggerId()
                             + ":" + reset(order, paid.result().orElseThrow()));
            ProcessResult<Order> unknown = sm.entity(order).processEvent("UNKNOWN", null, ctx);
            outcomes.add("processEvent-ctx:" + unknown.fired() + ":" + unknown.firedTriggerId()
                             + ":" + unknown.result().isPresent());
            ProcessResult<Order> changed = sm.entity(order).processDataChange();
            outcomes.add("processDataChange:" + changed.firedTriggerId()
                             + ":" + reset(order, changed.result().orElseThrow()));
            outcomes.add("processDataChange-ctx:"
                             + sm.entity(order).processDataChange(ctx).fired());
        }
        return outcomes;
    }

    /**
     * A transition a pre-condition rejects, described through {@link #describeRefusal(Throwable)}.
     *
     * @return the ids read off the reported error
     */
    public static String refusalIds() {
        try (StateMachine<Order> sm = Transflux.defineStateMachine(Order.class)
            .withStateResolver(o -> o.state)
            .state("s1")
            .transition("t", "s1", "s2", t -> t.preCondition("never", order -> false))
            .state("s2")
            .build()) {
            return describeRefusal(sm.entity(new Order()).transitionTo("s2").getError());
        }
    }

    /**
     * The ids a host reads off a framework refusal instead of parsing its message.
     *
     * @param error a transition's reported error
     *
     * @return the ids it carries, or its class name when it is not a framework refusal
     */
    public static String describeRefusal(Throwable error) {
        if (error instanceof TransfluxConditionException condition) {
            return condition.getConditionId() + ":" + condition.getRole() + ":" + condition.getTransitionId();
        }
        if (error instanceof TransfluxNoMatchException noMatch) {
            return noMatch.getChoiceId();
        }
        if (error instanceof TransfluxContextException context) {
            return context.getSubjectId();
        }
        return error.getClass().getName();
    }

    /**
     * One set of components, each written against {@link Trackable}, registered on a machine for
     * {@link Order} and on one for {@link Shipment} - the same instances both times. Before the
     * entity type accepted a supertype, sharing them meant writing every component as a generic
     * class.
     *
     * @return the two built machines, the one for orders first
     */
    public static List<StateMachine<? extends Trackable>> sharedAcrossEntityTypes() {
        return List.of(trackedMachine(Order.class), trackedMachine(Shipment.class));
    }

    private static <T extends Trackable> StateMachine<T> trackedMachine(Class<T> entityType) {
        return Transflux.defineStateMachine(entityType)
            .withStateResolver(TRACKED_RESOLVER)
            .withStateApplier(TRACKED_APPLIER)
            .step("track", TRACK_STEP)
            .condition("tracked", IS_TRACKED)
            // an implicitly-typed lambda still infers the entity type rather than the wildcard
            .condition("has-trail", entity -> entity.trail() != null)
            .onAnyStateEntry("any-state", new TrackedStateAudit())
            .onAnyTransitionStart("any-transition", new TrackedTransitionAudit())
            .onAnyActionStart("any-action", new TrackedActionAudit())
            .state("s1", s -> s
                .onExit("on-exit", new TrackedStateAudit()))
            .transition("t", "s1", "s2", t -> t
                .preCondition("tracked")
                .preCondition("has-trail")
                .onStart("on-start", new TrackedTransitionAudit())
                .run("track")
                .step("inline-track", TRACK_STEP)
                .step("compensated", step -> step
                    .using(TRACK_STEP)
                    .withCompensation(new UntrackCompensation())
                    .onStart("on-action", new TrackedActionAudit())))
            .state("s2")
            .build();
    }

    /**
     * The state machine's own metadata, declared on the definition and read back off the built
     * machine, alongside an expression that passes the entity whole through {@code #entity} - the
     * root has no other spelling a method call can use.
     *
     * @return the four accessors and the transition's outcome, joined
     */
    public static String stateMachineMetadata() {
        try (StateMachine<Order> sm = Transflux.defineStateMachine(Order.class)
            .withId("orders")
            .withName("Orders")
            .withDescription("What an order does")
            .withVersion("3")
            .withStateResolver(o -> o.state)
            .withStateApplier((o, next) -> o.state = next)
            .condition("labelled", "T(org.transflux.dsl.JavaDslSurface).hasLabel(#entity)")
            .state("s1")
            .transition("t", "s1", "s2", t -> t.preCondition("labelled"))
            .state("s2")
            .build()) {

            boolean fired = sm.entity(new Order()).transitionTo("s2").isSuccess();
            return sm.getId() + ":" + sm.getName() + ":" + sm.getDescription()
                + ":" + sm.getVersion() + ":" + fired;
        }
    }

    /**
     * Hot-swap: a state machine built from one definition, driven, handed a topologically different
     * one, and driven again. The entity type is the only thing the two have to agree on.
     *
     * @return the generation before and after the swap, and the trail both versions wrote, joined
     */
    public static String definitionReplacement() {
        try (StateMachine<Order> sm = tinyDefinition("first", "s2").build()) {
            Order order = new Order();
            sm.entity(order).transitionTo("s2");
            long before = sm.generation();

            // A different target state, a different step, a different version - same Order.class.
            long after = sm.replaceDefinition(tinyDefinition("second", "s3"));
            sm.entity(order).transitionTo("s1");
            sm.entity(order).transitionTo("s3");

            return before + ":" + after + ":" + String.join(",", order.trail);
        }
    }

    /**
     * A one-transition definition, left unbuilt so it can be handed to
     * {@link StateMachine#replaceDefinition(StateMachineDef)}.
     *
     * @param tag what the transition's step writes to the entity's trail
     * @param target the state the transition leads to
     *
     * @return the definition
     */
    private static StateMachineDef<Order> tinyDefinition(String tag, String target) {
        return Transflux.defineStateMachine(Order.class)
            .withVersion(tag)
            .withStateResolver(o -> o.state)
            .withStateApplier((o, next) -> o.state = next)
            .state("s1")
            .transition("t", "s1", target, t -> t
                .step("mark", (order, ctx, view) -> order.trail.add(tag)))
            .state("s2")
            .transition("back", "s2", "s1", t -> { })
            .state("s3");
    }

    /** Reachable from SpEL by name, so {@code #entity} has somewhere to be passed whole. */
    public static boolean hasLabel(Order order) {
        return order.label() != null;
    }

    /**
     * Every trigger registration form, and the by-id attachment that shares one across transitions.
     * One manual trigger sits on two transitions leaving different states, so {@code fire(id)}
     * picks between them by the entity's current state.
     *
     * @return the shared trigger's attachments and the two transitions it fired, joined
     */
    public static String sharedTriggerShapes() {
        try (StateMachine<Order> sm = Transflux.defineStateMachine(Order.class)
            .withStateResolver(o -> o.state)
            .withStateApplier((o, next) -> o.state = next)
            // untyped: registered against Object, so it attaches to any transition
            .manualTrigger("cancel", t -> t.withName("Cancel"))
            // typed: the build checks this context against every transition attaching it
            .eventTrigger("settled", OrderCtx.class, t -> t
                .onEvent("SETTLED")
                .filterExpression("#event == 'ok'"))
            .dataTrigger("swept", t -> t.conditionExpression("state == 's1'"))
            // and the same three under a forContext block
            .forContext(OrderCtx.class, scope -> scope
                .manualTrigger("scoped-manual", t -> t.withDescription("Scoped"))
                .eventTrigger("scoped-event", t -> t.onEvent("SCOPED"))
                .dataTrigger("scoped-data", t -> t.conditionExpression("state != null")))
            .state("s1")
            .transition("from-s1", "s1", "s2", OrderCtx.class, t -> t
                .addTrigger("cancel")
                .addTrigger("settled")
                .addTrigger("swept"))
            .state("s2")
            .transition("from-s2", "s2", "s3", t -> t.addTrigger("cancel"))
            .state("s3")
            .build()) {

            Trigger shared = sm.getTrigger("cancel");
            Order order = new Order();
            String first = sm.entity(order).fire("cancel", new OrderCtx()).getTransitionId();
            String second = sm.entity(order).fire("cancel").getTransitionId();

            return shared.getTransitionIds() + ":" + first + ":" + second;
        }
    }

    /**
     * Every listener registration form and every one-argument hook that attaches one by id. One
     * registration serves a transition hook, an action hook and a state-machine-wide hook at once.
     *
     * @return the ids the attached listeners recorded, joined
     */
    public static String listenerRegistrationShapes() {
        Order order = new Order();
        try (StateMachine<Order> sm = Transflux.defineStateMachine(Order.class)
            .withStateResolver(o -> o.state)
            .withStateApplier((o, next) -> o.state = next)
            .step("record", new RecordingAction())
            // instance and configurer forms, untyped and typed
            .stateListener("state-audit", new StateAudit())
            .stateListener("state-configured", l -> l.using(new StateAudit()).withName("Configured"))
            .transitionListener("any-transition", new AnyTransitionAudit())
            .transitionListener("typed-transition", OrderCtx.class, new TransitionAudit())
            .transitionListener("configured-transition", l -> l.using(new AnyTransitionAudit()))
            .actionListener("typed-action", OrderCtx.class, new ActionAudit())
            .actionListener("configured-action", l -> l.withAsync().using((o, ctx, x) -> { }))
            // and the same two categories under a forContext block
            .forContext(OrderCtx.class, scope -> scope
                .transitionListener("scoped-transition", l -> l.using(new TransitionAudit()))
                .actionListener("scoped-action", l -> l.using(new ActionAudit())))
            // attached state-machine-wide by id
            .onAnyTransitionStart("any-transition")
            .onAnyStateEntry("state-audit")
            .state("s1", s -> s
                .onExit("state-audit")
                .onEntry("state-configured"))
            .transition("t", "s1", "s2", OrderCtx.class, t -> t
                .onStart("typed-transition")
                .onComplete("scoped-transition")
                .onError("configured-transition")
                .step("tracked", step -> step
                    .using(new RecordingAction())
                    .onStart("typed-action")
                    .onComplete("scoped-action")
                    .onError("configured-action")))
            .state("s2")
            .build()) {

            sm.entity(order).transitionTo("s2", new OrderCtx());
            // The phases alone: enough to pin that every attachment fired, without restating the
            // payload fields the listener classes already assert elsewhere.
            return order.trail.stream()
                              .map(line -> line.split(":")[0])
                              .collect(Collectors.joining(","));
        }
    }

    /**
     * A component factory written as a lambda, beside the reflective default it would wrap.
     *
     * @return the class of what each factory created, joined
     */
    public static String componentFactoryShapes() {
        ComponentFactory reflective = ComponentFactory.reflective();
        ComponentFactory handOut = type -> type == TrackStep.class ? TRACK_STEP : reflective.create(type);
        return handOut.create(TrackStep.class).getClass().getSimpleName() + ":"
            + (handOut.create(TrackStep.class) == TRACK_STEP) + ":"
            + handOut.create(OrderIsOpen.class).getClass().getSimpleName();
    }

    private static boolean isOpen(Order order) {
        return "s1".equals(order.state);
    }

    private static boolean hasMessage(IllegalStateException e) {
        return e.getMessage() != null;
    }

    private static String reset(Order order, TransitionResult<Order> result) {
        String summary = result.isSuccess() + ":" + result.getSourceStateId()
            + ">" + result.getTargetStateId() + ":" + result.getTransitionId()
            + ":" + result.getExecutedPath().size() + ":" + result.getCompensatedPath().size()
            + ":" + (result.getError() == null) + ":" + (result.getEntity() == order)
            + ":" + (result.getDuration() != null);
        order.state = "s1";
        return summary;
    }
}
