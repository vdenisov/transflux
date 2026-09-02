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

import org.transflux.core.StateMachine;
import org.transflux.core.Transflux;
import org.transflux.core.action.Action;
import org.transflux.core.action.Compensation;
import org.transflux.core.action.ContextMapper;
import org.transflux.core.action.ForkRejectionPolicy;
import org.transflux.core.action.ForkableContext;
import org.transflux.core.transition.ExecutingTransition;

import java.util.ArrayList;
import java.util.List;

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

    /** The entity every fixture below transitions. */
    public static final class Order {
        public String state = "s1";
        public final List<String> trail = new ArrayList<>();
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
        return Transflux.<Order>defineStateMachine()
            .forEntityType(Order.class)
            .withStateResolver(o -> o.state)
            .withStateApplier((o, s) -> o.state = s)
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
        return Transflux.<Order>defineStateMachine()
            .forEntityType(Order.class)
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
                                .step("branch-class", RecordingAction.class)
                                .step("branch-configured", step -> step
                                    .using(RecordingAction.class)
                                    .withName("In a branch"))
                                .fork("record")
                                .fork("notify", "notify-from-order")
                                .fork("notify", parent -> new NotifyCtx(parent.orderId))
                                .conditional("nested-in-branch", inner -> inner
                                    .branch("deep", ib -> ib
                                        .condition("deep-cond", (order, ctx) -> true)
                                        .run("record"))))
                            .defaultBranch(d -> d
                                .run("record")
                                .run("notify", "notify-from-order")
                                .run("notify", parent -> new NotifyCtx(parent.orderId))
                                .step("default-instance", (order, ctx, view) -> order.trail.add("default-inline"))
                                .step("default-class", RecordingAction.class)
                                .step("default-configured", step -> step
                                    .using(RecordingAction.class)
                                    .withName("In the default branch"))
                                .fork("record")
                                .conditional("nested-in-default", inner -> inner
                                    .branch("deep-default", ib -> ib
                                        .condition("deep-default-cond", (order, ctx) -> true)
                                        .run("record"))))))))
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
        return Transflux.<Order>defineStateMachine()
            .forEntityType(Order.class)
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
        return Transflux.<Order>defineStateMachine()
            .forEntityType(Order.class)
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
        return Transflux.<Order>defineStateMachine()
            .forEntityType(Order.class)
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
        return Transflux.<Order>defineStateMachine()
            .forEntityType(Order.class)
            .withStateResolver(o -> o.state)
            .withStateApplier((o, s) -> o.state = s)
            .state("s1", s -> s
                .transitionsTo("s2", "t", OrderCtx.class, t -> t
                    .operation("op", c -> c
                        // pass-through: the declared context widens, so nothing maps
                        .step("pt-instance", HasOrderId.class,
                              (order, ctx, view) -> order.trail.add("pt:" + ctx.orderId()))
                        .step("pt-class", HasOrderId.class, IgnoresContext.class)
                        .step("pt-configured", HasOrderId.class,
                              st -> st.using(IgnoresContext.class).withName("Widened"))
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
                        .step("mapped-class", NotifyCtx.class, new NotifyFromOrder(),
                              NotifyAction.class)
                        .step("mapped-configured", NotifyCtx.class, new NotifyFromOrder(),
                              st -> st.using(NotifyAction.class).withName("Mapped"))
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
                        .step("lambda-class", NotifyCtx.class,
                              parent -> new NotifyCtx(parent.orderId), NotifyAction.class)
                        .step("lambda-configured", NotifyCtx.class,
                              parent -> new NotifyCtx(parent.orderId),
                              st -> st.using(NotifyAction.class).withName("Lambda-mapped"))
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

        return Transflux.<Order>defineStateMachine()
            .forEntityType(Order.class)
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
        return Transflux.<Order>defineStateMachine()
            .forEntityType(Order.class)
            .withStateResolver(o -> o.state)
            .withStateApplier((o, s) -> o.state = s)
            .state("s1", s -> s
                .transitionsTo("s2", "t", OrderCtx.class, t -> t
                    .operation("op", c -> c
                        .step("inline-instance", (order, ctx, view) -> order.trail.add("inline"))
                        .step("inline-class", RecordingAction.class)
                        .step("inline-configured", step -> step
                            .using(RecordingAction.class)
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
                            .defaultBranch(d -> d.run("inline-class"))))))
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
        return Transflux.<Order>defineStateMachine()
            .forEntityType(Order.class)
            .withStateResolver(o -> o.state)
            .withStateApplier((o, s) -> o.state = s)
            .state("s1", s -> s
                .transitionsTo("s2", "t", OrderCtx.class, t -> t
                    .operation("op", c -> c
                        .withCompensation((order, ctx) -> order.trail.add("-container"))
                        .step("charge", step -> step
                            .using(RecordingAction.class)
                            .withCompensation(RollbackCompensation.class)
                            .forException(IllegalStateException.class)
                                .withCompensation((order, ctx) -> order.trail.add("-illegal"))
                            .forException(RuntimeException.class)
                                .matching(e -> e.getMessage() != null)
                                .withCompensation(RollbackCompensation.class)))))
            .state("s2", s -> { })
            .build();
    }

    /**
     * Executor configuration in both ownership modes, plus the rejection policy.
     *
     * @return the built state machine, which owns a pool and must be closed
     */
    public static StateMachine<Order> executorConfiguration() {
        return Transflux.<Order>defineStateMachine()
            .forEntityType(Order.class)
            .withStateResolver(o -> o.state)
            .withStateApplier((o, s) -> o.state = s)
            .withAsyncPool(2, 8, runnable -> {
                Thread thread = new Thread(runnable, "host-named-async");
                thread.setDaemon(true);
                return thread;
            })
            .withForkRejectionPolicy(ForkRejectionPolicy.FAIL)
            .step("record", new RecordingAction())
            .state("s1", s -> s
                .transitionsTo("s2", "t", OrderCtx.class, t -> t
                    .operation("op", c -> c.fork("record"))))
            .state("s2", s -> { })
            .build();
    }
}
