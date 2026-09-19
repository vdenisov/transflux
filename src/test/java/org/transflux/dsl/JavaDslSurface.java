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
import org.transflux.core.StateMachine;
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
import org.transflux.core.state.StateChange;
import org.transflux.core.state.StateListener;
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
    public static final class Order {
        public String state = "s1";
        public final List<String> trail = new CopyOnWriteArrayList<>();

        public String label() {
            return "o-1";
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
            .state("s1", s -> s
                .transitionsTo("s2", "t", OrderCtx.class, t -> t
                    .operation("op", c -> c
                        // pass-through: same context type on both sides
                        .run("record")
                        // mapped: every shape that can carry a mapper
                        .run("notify", "notify-from-order")
                        .run("notify", parent -> new NotifyCtx(parent.orderId))

                        .fork("record")
                        .fork("notify", "notify-from-order")
                        .fork("notify", parent -> new NotifyCtx(parent.orderId)))))
            .state("s2", s -> { })
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
            .state("s1", s -> s
                .transitionsTo("s2", "t", OrderCtx.class, t -> t
                    .operation("op", c -> c
                        .conditional("branching", cond -> cond
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
                                .forkConditional("branch-forked-route", fc -> fc
                                    .branch("branch-forked-taken", fb -> fb
                                        .condition("branch-forked-always", (order, ctx) -> true)
                                        .run("record")))
                                .conditional("nested-in-branch", inner -> inner
                                    .branch("deep", ib -> ib
                                        .condition("deep-cond", (order, ctx) -> true)
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
                                .forkConditional("default-forked-route", dfc -> dfc
                                    .branch("default-forked-taken", fb -> fb
                                        .condition("default-forked-always", (order, ctx) -> true)
                                        .run("record")))
                                .conditional("nested-in-default", inner -> inner
                                    .branch("deep-default", ib -> ib
                                        .condition("deep-default-cond", (order, ctx) -> true)
                                        .run("record"))))))))
            .state("s2", s -> { })
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
            .state("s1", s -> s
                .transitionsTo("s2", "t", OrderCtx.class, t -> t
                    .preCondition("as-condition")
                    .preCondition("as-bipredicate")
                    .preCondition("as-predicate")
                    .preCondition("as-expression")
                    .preCondition("scoped-condition")
                    .preCondition("scoped-bipredicate")
                    .preCondition("scoped-predicate")
                    .preCondition("scoped-expression")
                    .step("record", new RecordingAction())))
            .state("s2", s -> { })
            .build();
    }

    /**
     * A conditional registered at state-machine level, in both registration forms, and reached by
     * id - which says nothing about the form it was authored in.
     *
     * @return the built state machine
     */
    public static StateMachine<Order> registeredConditional() {
        return Transflux.defineStateMachine(Order.class)
            .withStateResolver(o -> o.state)
            .withStateApplier((o, s) -> o.state = s)
            .step("record", new RecordingAction())
            .conditional("flat", OrderCtx.class, cond -> cond
                .branch("always", b -> b
                    .condition("yes", (order, ctx) -> true)
                    .run("record")))
            .forContext(OrderCtx.class, scope -> scope
                .conditional("scoped", cond -> cond
                    .branch("never", b -> b
                        .condition("no", (order, ctx) -> false)
                        .step("unreached", (order, ctx, view) -> order.trail.add("unreached")))
                    .defaultBranch(d -> d
                        .step("fallback", (order, ctx, view) -> order.trail.add("fallback")))))
            .state("s1", s -> s
                .transitionsTo("s2", "t", OrderCtx.class, t -> t
                    .operation("op", c -> c
                        .run("flat")
                        .run("scoped"))))
            .state("s2", s -> { })
            .build();
    }

    /**
     * A conditional attached straight to a transition's action slot - the slot holds one action,
     * and a conditional is one, so it needs no wrapping operation.
     *
     * @return the built state machine
     */
    public static StateMachine<Order> transitionConditional() {
        return Transflux.defineStateMachine(Order.class)
            .withStateResolver(o -> o.state)
            .withStateApplier((o, s) -> o.state = s)
            .step("record", new RecordingAction())
            .state("s1", s -> s
                .transitionsTo("s2", "t", OrderCtx.class, t -> t
                    .conditional("route", cond -> cond
                        .branch("premium", b -> b
                            .condition("is-premium", (order, ctx) -> "o-1".equals(ctx.orderId))
                            .step("shared", (order, ctx, view) -> order.trail.add("shared"))
                            .run("record"))
                        .branch("standard", b -> b
                            .condition("is-standard", (order, ctx) -> false)
                            .run("shared"))
                        .defaultBranch(d -> d.run("record")))))
            .state("s2", s -> { })
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
            .state("s1", s -> s
                .transitionsTo("s2", "t", OrderCtx.class, t -> t
                    .operation("op", c -> c
                        .operation("in-container", inner -> inner
                            .step("in-container-step", (order, ctx, view) -> order.trail.add("in-container"))
                            .run("record"))
                        .operation("nested", inner -> inner
                            .step("nested-step", (order, ctx, view) -> order.trail.add("nested"))
                            .operation("two-deep", deepest -> deepest
                                .step("two-deep-step", (order, ctx, view) -> order.trail.add("two-deep"))))
                        .conditional("branching", cond -> cond
                            .branch("taken", b -> b
                                .condition("always", (order, ctx) -> true)
                                .operation("in-branch", inner -> inner
                                    .step("in-branch-step", (order, ctx, view) -> order.trail.add("in-branch"))
                                    .conditional("deep-conditional", deep -> deep
                                        .branch("deep-taken", db -> db
                                            .condition("deep-always", (order, ctx) -> true)
                                            .run("record")))))
                            .defaultBranch(d -> d
                                .operation("in-default", inner -> inner
                                    .step("in-default-step", (order, ctx, view) -> order.trail.add("in-default"))))))))
            .state("s2", s -> { })
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
            .state("s1", s -> s
                .transitionsTo("s2", "t", OrderCtx.class, t -> t
                    .operation("op", c -> c
                        // pass-through: the declared context widens, so nothing maps
                        .step("pt-instance", HasOrderId.class,
                              (order, ctx, view) -> order.trail.add("pt:" + ctx.orderId()))
                        .step("pt-configured", HasOrderId.class,
                              st -> st.using(new IgnoresContext()).withName("Widened"))
                        .operation("pt-op", HasOrderId.class, inner -> inner
                            .step("pt-op-step",
                                  (order, ctx, view) -> order.trail.add("pt-op:" + ctx.orderId())))
                        .conditional("pt-cond", HasOrderId.class, cond -> cond
                            .branch("pt-taken", b -> b
                                .condition("pt-always", (order, ctx) -> true)
                                .step("pt-cond-step",
                                      (order, ctx, view) -> order.trail.add("pt-cond:" + ctx.orderId()))))

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
                        .conditional("mapped-cond", NotifyCtx.class, new NotifyFromOrder(), cond -> cond
                            .branch("mapped-taken", b -> b
                                .condition("mapped-always", (order, ctx) -> true)
                                .step("mapped-cond-step",
                                      (order, ctx, view) -> order.trail.add("mapped-cond:" + ctx.orderId))))

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
                        .conditional("lambda-cond", NotifyCtx.class,
                                     parent -> new NotifyCtx(parent.orderId), cond -> cond
                            .branch("lambda-taken", b -> b
                                .condition("lambda-always", (order, ctx) -> true)
                                .step("lambda-cond-step",
                                      (order, ctx, view) -> order.trail.add("lambda-cond:" + ctx.orderId)))))))
            .state("s2", s -> { })
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
            .state("s1", s -> s
                .transitionsTo("s2", "t", OrderCtx.class, t -> t
                    .step("dispatch", dispatcher)))
            .state("s2", s -> { })
            .build();
    }

    /**
     * The declaration shapes: inline steps in each form, a conditional with branches, and the
     * configurer forms that carry metadata, listeners and compensation.
     *
     * @return the built state machine
     */
    public static StateMachine<Order> declarationShapes() {
        return Transflux.defineStateMachine(Order.class)
            .withStateResolver(o -> o.state)
            .withStateApplier((o, s) -> o.state = s)
            .state("s1", s -> s
                .transitionsTo("s2", "t", OrderCtx.class, t -> t
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
                        .conditional("branching", cond -> cond
                            .branch("premium", b -> b
                                .condition("is-premium", (order, ctx) -> "o-1".equals(ctx.orderId))
                                .run("inline-instance"))
                            .defaultBranch(d -> d.run("inline-configured"))))))
            .state("s2", s -> { })
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
            .state("s1", s -> s
                .transitionsTo("s2", "t", OrderCtx.class, t -> t
                    .operation("op", c -> c
                        .withCompensation((order, ctx) -> order.trail.add("-container"))
                        .step("charge", step -> step
                            .using(new RecordingAction())
                            .withCompensation(new RollbackCompensation())
                            .forException(IllegalStateException.class)
                                .withCompensation((order, ctx) -> order.trail.add("-illegal"))
                            .forException(RuntimeException.class)
                                .matching(e -> e.getMessage() != null)
                                .withCompensation(new RollbackCompensation())))))
            .state("s2", s -> { })
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
                .onExit("log-exit", ExecutionLogging.defaults().stateListener())
                .transitionsTo("s2", "t", OrderCtx.class, t -> t
                    .onComplete("log-payment", ExecutionLogging.atLevel(Level.DEBUG)
                        .withEntityLabel((Order o) -> o.label())
                        .withContext()
                        .transitionListener())
                    .run("record")))
            .state("s2", s -> { })
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
                .disableGlobalListeners("any-exit", "any-entry")
                .transitionsTo("s2", "t", OrderCtx.class, t -> t
                    .disableGlobalListener("any-start")
                    .disableGlobalListeners("any-start")
                    .run("wrap")))
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
            .state("s1", s -> s
                .transitionsTo("s2", "t", OrderCtx.class, t -> t
                    .run("record")))
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
            .state("s1", s -> s
                .transitionsTo("s2", "t", OrderCtx.class, t -> t
                    .step("dispatch", dispatcher)))
            .state("s2", s -> { })
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
            .state("s1", s -> s
                .transitionsTo("s2", "t", OrderCtx.class, t -> t
                    .operation("op", c -> c
                        .fork("record")
                        .fork("notify-async")
                        // the policy declared where the work is forked, in each by-id shape
                        .fork("record", AsyncRejectionPolicy.CALLER_RUNS)
                        .fork("notify", "notify-from-order", AsyncRejectionPolicy.DROP)
                        .fork("notify", parent -> new NotifyCtx(parent.orderId),
                              AsyncRejectionPolicy.DROP))))
            .state("s2", s -> { })
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
            .state("s1", s -> s
                .transitionsTo("s2", "t", OrderCtx.class, t -> t
                    // several members in a row, which the single-action slot could not hold
                    .run("record")
                    .run("notify", "notify-from-order")
                    .run("notify", parent -> new NotifyCtx(parent.orderId))

                    // declarations, inheriting the transition's context
                    .step("inline", (order, ctx, view) -> order.trail.add("inline:" + ctx.orderId))
                    .step("configured", st -> st.using(new RecordingAction()).withName("Configured"))
                    .operation("group", c -> c.step("grouped", new RecordingAction()))
                    .conditional("route", cond -> cond
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
                    .forkConditional("t-forked-route", cond -> cond
                        .branch("t-forked-taken", b -> b
                            .condition("t-forked-always", (order, ctx) -> true)
                            .run("record")))))
            .state("s2", s -> { })
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
            .state("s1", s -> s
                .transitionsTo("s2", "t", OrderCtx.class, t -> t
                    .operation("op", c -> c
                        // inheriting the enclosing context
                        .forkStep("f-instance", (order, ctx, view) -> order.trail.add("f-instance"))
                        .forkStep("f-configured", st -> st
                            .using(new RecordingAction())
                            .withName("Forked"))
                        .forkOperation("f-group", g -> g.run("record"))
                        .forkConditional("f-route", cond -> cond
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
                        .forkConditional("f-pt-route", HasOrderId.class, cond -> cond
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
                        .forkConditional("f-mapped-route", NotifyCtx.class,
                                         parent -> new NotifyCtx(parent.orderId), cond -> cond
                            .branch("f-mapped-taken", b -> b
                                .condition("f-mapped-always", (order, ctx) -> true)
                                .step("f-mapped-branch-member", new NotifyAction()))))))
            .state("s2", s -> { })
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
        public void onState(Order order, Object ctx, StateChange<Order> change) {
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

    /**
     * Every condition attachment form on a transition and every trigger declaration form, on the
     * pass-through {@code transitionsTo} that declares no context. The condition family is the
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
                .withDescription("An order nobody has paid for")
                .transitionsTo("s2", "t", t -> t
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

                    .step("pay", (order, ctx, view) -> order.trail.add("pay"))))
            .state("s2", s -> { })
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
                .onExit("s-exit", (order, ctx, change) -> order.trail.add("s-exit"))
                .transitionsTo("s2", "t", OrderCtx.class, t -> t
                    .onStart("t-start", new TransitionAudit())
                    .onStart("t-start-cfg", l -> l.using(new TransitionAudit()))
                    .onComplete("t-complete", (order, ctx, execution) -> order.trail.add("t-complete"))
                    .onComplete("t-complete-cfg", l -> l.using(new TransitionAudit()))
                    .onError("t-error", new TransitionAudit())
                    .onError("t-error-cfg", l -> l
                        .withDescription("Audits a failed transition")
                        .using(new TransitionAudit()))
                    .disableAllGlobalListeners()
                    .run("record")))
            .state("s2", s -> { })
            .build();
    }

    /**
     * Registration shapes the other fixtures leave out: a mapper from an instance, from a method
     * reference and through {@code mapperDef}; an action that supplies its own compensation; a route
     * guard written as a method reference, on a container whose chain carries on afterwards; a
     * conditional's no-match behaviour; and a branch condition in its one-argument and class forms.
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
            .state("s1", s -> s
                .transitionsTo("s2", "t", OrderCtx.class, t -> t
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
                    .conditional("route", cond -> cond
                        .onNoMatch(NoMatchBehavior.SILENT)
                        .branch("labelled", b -> b
                            .condition("is-labelled", new OrderHasLabel())
                            .step("labelled-member", (order, ctx, view) -> order.trail.add("labelled")))
                        .branch("open", b -> b
                            .condition("is-open", order -> "s1".equals(order.state))
                            .step("open-member", (order, ctx, view) -> order.trail.add("open"))))))
            .state("s2", s -> { })
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
            .state("s1", s -> s
                .transitionsTo("s2", "t", t -> t.preCondition("never", order -> false)))
            .state("s2", s -> { })
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
            return noMatch.getConditionalId();
        }
        if (error instanceof TransfluxContextException context) {
            return context.getSubjectId();
        }
        return error.getClass().getName();
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
