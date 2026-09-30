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

package org.transflux.yaml;

import org.transflux.core.StateMachineDef;
import org.transflux.core.Transflux;
import org.transflux.core.action.Action;
import org.transflux.core.action.ActionExecution;
import org.transflux.core.action.ActionListener;
import org.transflux.core.action.Compensation;
import org.transflux.core.action.ContextMapper;
import org.transflux.core.action.ForkableContext;
import org.transflux.core.state.StateApplier;
import org.transflux.core.state.StateChange;
import org.transflux.core.state.StateListener;
import org.transflux.core.state.StateResolver;
import org.transflux.core.transition.ExecutingTransition;
import org.transflux.core.transition.TransitionExecution;
import org.transflux.core.transition.TransitionListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/**
 * One state machine written through the Java DSL, which {@code corpus/valid/parity/parity-machine.transflux.yml}
 * declares again in YAML over the same component classes. Every component records what it saw, so
 * the two machines can be driven side by side and their traces compared.
 */
public final class ParityMachine {

    /** What ran on the thread driving the machine, in order. */
    public static final List<String> INLINE = Collections.synchronizedList(new ArrayList<>());

    /** What ran on a forked branch, in whatever order the pool ran it. */
    public static final List<String> FORKED = Collections.synchronizedList(new ArrayList<>());

    /** The thread driving the machine; anything recorded elsewhere ran on a branch. */
    public static volatile Thread driver;

    private ParityMachine() {
    }

    /**
     * @return the machine's definition, not yet built
     */
    public static StateMachineDef<Account> definition() {
        return Transflux.defineStateMachine(Account.class)
            .withId("accounts")
            .withName("Accounts")
            .withDescription("An account from draft to closed")
            .withVersion("1")
            .withStateResolver(new AccountResolver())
            .withStateApplier(new AccountApplier())
            .withAsyncPool(2, 16)

            .step("prepare", new Prepare())
            .step("charge", Billing.class, new Charge())
            .step("audit", new Audit())
            .step("explode", step -> step
                .using(new Explode())
                .onError("explode-error", new ExplodeError()))
            .condition("priority-high", new PriorityHigh())
            .mapper("billing-from-activation", Activation.class, Billing.class, new BillingFromActivation())
            .operation("notification-flow", Activation.class, flow -> flow
                .operation("render", render -> render
                    .step("render-body", new RenderBody()))
                .choice("tier", tier -> tier
                    .branch("premium", branch -> branch
                        .condition("priority-high")
                        .step("premium-notice", new PremiumNotice()))
                    .branch("standard", branch -> branch
                        .condition("standard-priority", "priority >= 3")
                        .step("standard-notice", new StandardNotice()))
                    .defaultBranch(fallback -> fallback
                        .step("basic-notice", new BasicNotice()))))

            .manualTrigger("manual-activate", Activation.class, trigger -> trigger
                .withName("Manual activation")
                .withDescription("An operator activates the account"))
            .stateListener("state-audit", new StateAudit())
            .transitionListener("transition-audit", new TransitionAudit())
            .onAnyStateExit("exit-audit", new ExitAudit())
            .onAnyActionStart("action-audit", new ActionAudit())
            .onAnyActionComplete("action-audit")
            .onAnyActionError("action-audit")

            .state("draft", state -> state.withName("Draft"))
            .state("active", state -> state
                .withName("Active")
                .withDescription("Billed and notified")
                .onEntry("state-audit"))
            .state("suspended")
            .state("closed", state -> state.onEntry("state-audit"))

            .transition("activate", "draft", "active", Activation.class, transition -> transition
                .withName("Activate")
                .addTrigger("manual-activate")
                .onStart("transition-audit")
                .onComplete("transition-audit")
                .run("prepare")
                .run("charge", "billing-from-activation")
                .run("notification-flow")
                .fork("audit"))
            .transition("suspend", "active", "suspended", transition -> transition
                .addEventTrigger("overdue", trigger -> trigger
                    .withName("Payment overdue")
                    .onEvent("OVERDUE")
                    .filterExpression("#event == 'LATE'")))
            .transition("reactivate", "suspended", "active", Activation.class, transition -> transition
                .addTrigger("manual-activate")
                .run("prepare"))
            .transition("close", "suspended", "closed", transition -> transition
                .addDataTrigger("closing", trigger -> trigger.conditionExpression("priority < 0")))
            .transition("terminate", "active", "closed", transition -> transition
                .onStart("transition-audit")
                .onError("transition-audit")
                .step("reserve", step -> step
                    .using(new Reserve())
                    .withCompensation(new Release())
                    .forException(IllegalStateException.class).matching(new BoomGuard())
                    .withCompensation(new RouteUndo())
                    .forException(IllegalArgumentException.class)
                    .withCompensation(new ArgumentUndo()))
                .run("explode"));
    }

    static void record(String entry) {
        (Thread.currentThread() == driver ? INLINE : FORKED).add(entry);
    }

    /** The entity: its state, a priority the conditions read, and the failure {@link Explode} raises. */
    public static final class Account {
        private String state = "draft";
        private int priority;
        private String failure;

        public Account() {
        }

        public Account(int priority, String failure) {
            this.priority = priority;
            this.failure = failure;
        }

        public String getState() {
            return state;
        }

        public void setState(String state) {
            this.state = state;
        }

        public int getPriority() {
            return priority;
        }

        public void setPriority(int priority) {
            this.priority = priority;
        }

        public String getFailure() {
            return failure;
        }
    }

    /** The context an activation runs against; a forked member gets a copy. */
    public static final class Activation implements ForkableContext<Activation> {
        public String note;
        public String chargeId;

        public Activation() {
        }

        public Activation(String note) {
            this.note = note;
        }

        @Override
        public Activation fork() {
            return new Activation(note);
        }
    }

    /** The context a charge runs against, mapped from an {@link Activation}. */
    public static final class Billing {
        public String note;
        public String chargeId;
    }

    public static final class AccountResolver implements StateResolver<Account> {
        @Override
        public String resolveState(Account entity) {
            return entity.getState();
        }
    }

    public static final class AccountApplier implements StateApplier<Account> {
        @Override
        public void applyState(Account entity, String newStateId) {
            entity.setState(newStateId);
        }
    }

    public static final class BillingFromActivation implements ContextMapper<Activation, Billing> {
        @Override
        public Billing mapTo(Activation parentContext) {
            Billing billing = new Billing();
            billing.note = parentContext.note;
            return billing;
        }

        @Override
        public void mapFrom(Activation parentContext, Billing nestedContext) {
            parentContext.chargeId = nestedContext.chargeId;
        }
    }

    public static final class PriorityHigh implements Predicate<Account> {
        @Override
        public boolean test(Account account) {
            return account.getPriority() >= 7;
        }
    }

    public static final class BoomGuard implements Predicate<IllegalStateException> {
        @Override
        public boolean test(IllegalStateException failure) {
            return "boom".equals(failure.getMessage());
        }
    }

    public static final class Prepare implements Action<Account, Object> {
        @Override
        public void execute(Account entity, Object context, ExecutingTransition<Account, Object> transition) {
            record("prepare");
        }
    }

    public static final class Charge implements Action<Account, Billing> {
        @Override
        public void execute(Account entity, Billing context, ExecutingTransition<Account, Billing> transition) {
            context.chargeId = "charge-for-" + context.note;
            record("charge:" + context.note);
        }
    }

    public static final class Audit implements Action<Account, Object> {
        @Override
        public void execute(Account entity, Object context, ExecutingTransition<Account, Object> transition) {
            record("audit:" + ((Activation) context).note);
        }
    }

    public static final class RenderBody implements Action<Account, Activation> {
        @Override
        public void execute(Account entity, Activation context, ExecutingTransition<Account, Activation> transition) {
            record("render:" + context.note);
        }
    }

    public static final class PremiumNotice implements Action<Account, Activation> {
        @Override
        public void execute(Account entity, Activation context, ExecutingTransition<Account, Activation> transition) {
            record("premium");
        }
    }

    public static final class StandardNotice implements Action<Account, Activation> {
        @Override
        public void execute(Account entity, Activation context, ExecutingTransition<Account, Activation> transition) {
            record("standard");
        }
    }

    public static final class BasicNotice implements Action<Account, Activation> {
        @Override
        public void execute(Account entity, Activation context, ExecutingTransition<Account, Activation> transition) {
            record("basic");
        }
    }

    public static final class Reserve implements Action<Account, Object> {
        @Override
        public void execute(Account entity, Object context, ExecutingTransition<Account, Object> transition) {
            record("reserve");
        }
    }

    /** Fails as the entity says: {@code arg} raises an argument error, anything else a state error. */
    public static final class Explode implements Action<Account, Object> {
        @Override
        public void execute(Account entity, Object context, ExecutingTransition<Account, Object> transition) {
            if ("arg".equals(entity.getFailure())) {
                throw new IllegalArgumentException(entity.getFailure());
            }
            throw new IllegalStateException(entity.getFailure());
        }
    }

    public static final class Release implements Compensation<Account, Object> {
        @Override
        public void compensate(Account entity, Object context) {
            record("release");
        }
    }

    public static final class RouteUndo implements Compensation<Account, Object> {
        @Override
        public void compensate(Account entity, Object context) {
            record("route-undo");
        }
    }

    public static final class ArgumentUndo implements Compensation<Account, Object> {
        @Override
        public void compensate(Account entity, Object context) {
            record("argument-undo");
        }
    }

    public static final class StateAudit implements StateListener<Account> {
        @Override
        public void onState(Account entity, Object context, StateChange change) {
            record("state:" + change.phase() + ":" + change.state().getId() + ":" + change.state().getName()
                + ":" + change.state().getDescription());
        }
    }

    public static final class ExitAudit implements StateListener<Account> {
        @Override
        public void onState(Account entity, Object context, StateChange change) {
            record("exit:" + change.state().getId() + ":" + change.state().getName() + ":"
                + change.transition().getId());
        }
    }

    public static final class TransitionAudit implements TransitionListener<Account, Object> {
        @Override
        public void onTransition(Account entity, Object context, TransitionExecution<Account> execution) {
            record("transition:" + execution.phase() + ":" + execution.transition().getId() + ":"
                + execution.transition().getName() + ":"
                + (execution.firedBy() == null ? null : execution.firedBy().getId()));
        }
    }

    public static final class ActionAudit implements ActionListener<Account, Object> {
        @Override
        public void onAction(Account entity, Object context, ActionExecution execution) {
            record("action:" + execution.phase() + ":" + execution.path() + ":" + execution.kind());
        }
    }

    public static final class ExplodeError implements ActionListener<Account, Object> {
        @Override
        public void onAction(Account entity, Object context, ActionExecution execution) {
            record("explode-error:" + execution.error().getClass().getSimpleName());
        }
    }
}
