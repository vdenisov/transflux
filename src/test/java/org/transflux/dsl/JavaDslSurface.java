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

import org.transflux.core.Identifiable;
import org.transflux.core.StateMachine;
import org.transflux.core.Transflux;
import org.transflux.core.action.Action;
import org.transflux.core.action.Compensation;
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

    /** A context that can copy itself, so the fork boundary has something to fork. */
    public static final class OrderCtx implements ForkableContext<OrderCtx> {
        public String orderId = "o-1";
        public String customerId = "c-1";

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

        public NotifyCtx(String orderId) {
            this.orderId = orderId;
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

    /** Enum-as-id, the pattern the Identifiable overloads exist for. */
    public enum Ids implements Identifiable {
        RECORD("record"),
        NOTIFY("notify"),
        NOTIFY_FROM_ORDER("notify-from-order");

        private final String id;

        Ids(String id) {
            this.id = id;
        }

        @Override
        public String getId() {
            return id;
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
            .step(Ids.NOTIFY, NotifyCtx.class, new NotifyAction())
            .mapper(Ids.NOTIFY_FROM_ORDER, OrderCtx.class, NotifyCtx.class,
                    parent -> new NotifyCtx(parent.orderId))
            .state("s1", s -> s
                .transitionsTo("s2", "t", OrderCtx.class, t -> t
                    .operation("op", c -> c
                        // pass-through: same context type on both sides
                        .run("record")
                        .run(Ids.RECORD)
                        // mapped: every shape that can carry a mapper
                        .run("notify", "notify-from-order")
                        .run("notify", parent -> new NotifyCtx(parent.orderId))
                        .run(Ids.NOTIFY, Ids.NOTIFY_FROM_ORDER)
                        .run(Ids.NOTIFY, "notify-from-order")
                        .run("notify", Ids.NOTIFY_FROM_ORDER)

                        .fork("record")
                        .fork(Ids.RECORD)
                        .fork("notify", "notify-from-order")
                        .fork("notify", parent -> new NotifyCtx(parent.orderId))
                        .fork(Ids.NOTIFY, Ids.NOTIFY_FROM_ORDER)
                        .fork(Ids.NOTIFY, "notify-from-order")
                        .fork("notify", Ids.NOTIFY_FROM_ORDER))))
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
            .step(Ids.NOTIFY, NotifyCtx.class, new NotifyAction())
            .mapper(Ids.NOTIFY_FROM_ORDER, OrderCtx.class, NotifyCtx.class,
                    parent -> new NotifyCtx(parent.orderId))
            .state("s1", s -> s
                .transitionsTo("s2", "t", OrderCtx.class, t -> t
                    .operation("op", c -> c
                        .conditional("branching", cond -> cond
                            .branch("taken", b -> b
                                .condition("always", (order, ctx) -> true)
                                .run("record")
                                .run(Ids.RECORD)
                                .run("notify", "notify-from-order")
                                .run("notify", parent -> new NotifyCtx(parent.orderId))
                                .run(Ids.NOTIFY, Ids.NOTIFY_FROM_ORDER)
                                .run(Ids.NOTIFY, "notify-from-order")
                                .run("notify", Ids.NOTIFY_FROM_ORDER)
                                .step("branch-instance", (order, ctx, view) -> order.trail.add("branch-inline"))
                                .step("branch-class", RecordingAction.class)
                                .step("branch-configured", step -> step
                                    .using(RecordingAction.class)
                                    .withName("In a branch"))
                                .fork("record")
                                .fork(Ids.RECORD)
                                .fork("notify", "notify-from-order")
                                .fork("notify", parent -> new NotifyCtx(parent.orderId))
                                .fork(Ids.NOTIFY, Ids.NOTIFY_FROM_ORDER)
                                .fork(Ids.NOTIFY, "notify-from-order")
                                .fork("notify", Ids.NOTIFY_FROM_ORDER)
                                .conditional("nested-in-branch", inner -> inner
                                    .branch("deep", ib -> ib
                                        .condition("deep-cond", (order, ctx) -> true)
                                        .run("record"))))
                            .defaultBranch(d -> d
                                .run("record")
                                .run(Ids.RECORD)
                                .run("notify", "notify-from-order")
                                .run("notify", parent -> new NotifyCtx(parent.orderId))
                                .run(Ids.NOTIFY, Ids.NOTIFY_FROM_ORDER)
                                .run(Ids.NOTIFY, "notify-from-order")
                                .run("notify", Ids.NOTIFY_FROM_ORDER)
                                .step("default-instance", (order, ctx, view) -> order.trail.add("default-inline"))
                                .step("default-class", RecordingAction.class)
                                .step("default-configured", step -> step
                                    .using(RecordingAction.class)
                                    .withName("In the default branch"))
                                .fork("record")
                                .fork(Ids.NOTIFY, Ids.NOTIFY_FROM_ORDER)
                                .conditional("nested-in-default", inner -> inner
                                    .branch("deep-default", ib -> ib
                                        .condition("deep-default-cond", (order, ctx) -> true)
                                        .run("record"))))))))
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
            view.run(Ids.RECORD);
            view.run("notify", "notify-from-order");
            view.run("notify", parent -> new NotifyCtx(parent.orderId));
            view.run(Ids.NOTIFY, Ids.NOTIFY_FROM_ORDER);
            view.run(Ids.NOTIFY, "notify-from-order");
            view.run("notify", Ids.NOTIFY_FROM_ORDER);
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
