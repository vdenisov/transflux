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

package org.transflux.yaml

import org.transflux.core.StateMachine
import org.transflux.core.StateMachineDef
import org.transflux.core.action.Action
import org.transflux.core.action.ActionExecution
import org.transflux.core.action.ActionListener
import org.transflux.core.action.Compensation
import org.transflux.core.action.ContextMapper
import org.transflux.core.condition.Condition
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateChange
import org.transflux.core.state.StateResolver
import org.transflux.core.state.StateListener
import org.transflux.core.transition.ExecutingTransition
import org.transflux.core.transition.Transition
import org.transflux.core.transition.TransitionDef
import org.transflux.core.transition.TransitionExecution
import org.transflux.core.transition.TransitionListener
import org.transflux.yaml.source.DefinitionResource
import org.transflux.yaml.source.DefinitionSource

import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.BiPredicate
import java.util.function.Consumer
import java.util.function.Predicate

/**
 * Loads component sections for the loader's specs, completes them into a two-state machine, and
 * holds the components their documents name. Components record what they did on {@link #TRAIL}.
 */
class LoaderFixtures {

    static final List<String> TRAIL = new CopyOnWriteArrayList<>()

    /**
     * @param sections the component sections, as YAML at the top level of a root document
     *
     * @return the definition a root document carrying them loads to
     */
    static StateMachineDef<Order> load(String sections) {
        return loadDocument("apiVersion: transflux/v1\nstateMachine:\n  entityType: ${Order.name}\n"
            + sections.stripIndent())
    }

    /**
     * @param document a whole root document
     *
     * @return the definition it loads to
     */
    static StateMachineDef<Order> loadDocument(String document) {
        String text = document.stripIndent()
        def source = { String id -> Optional.of(new DefinitionResource(id, new ByteArrayInputStream(text.bytes))) }
        return YamlDefinitionLoader.builder(source as DefinitionSource).build().load('root.yml', Order)
    }

    /**
     * Completes a loaded definition with states {@code a} and {@code b} and a transition {@code t}
     * between them.
     *
     * @param definition the loaded definition
     * @param transition configures the transition
     *
     * @return the built machine
     */
    static StateMachine<Order> machine(StateMachineDef<Order> definition, Consumer<TransitionDef> transition = { }) {
        return machine(definition, Object, transition)
    }

    /**
     * Completes a loaded definition as {@link #machine(StateMachineDef, Consumer)} does, with a
     * transition declaring a context.
     *
     * @param definition the loaded definition
     * @param context the transition's context type
     * @param transition configures the transition
     *
     * @return the built machine
     */
    static StateMachine<Order> machine(StateMachineDef<Order> definition, Class<?> context,
                                       Consumer<TransitionDef> transition) {
        return definition
            .withStateResolver { Order o -> o.state }
            .withStateApplier { Order o, String s -> o.state = s }
            .state('a')
            .transition('t', 'a', 'b', context, transition)
            .state('b')
            .build()
    }

    static class Order {
        String state = 'a'
        int priority
    }

    static class OrderResolver implements StateResolver<Order> {
        @Override
        String resolveState(Order entity) {
            return entity.state
        }
    }

    static class OrderApplier implements StateApplier<Order> {
        @Override
        void applyState(Order entity, String newStateId) {
            entity.state = newStateId
        }
    }

    static class StringResolver implements StateResolver<String> {
        @Override
        String resolveState(String entity) {
            return entity
        }
    }

    static class EnumOrder {
        Status status = Status.NEW
    }

    enum Status { NEW, CHARGED }

    static class Ctx {
        String note = 'from-parent'
        String chargeId
        Status status = Status.NEW
    }

    static class ChildCtx {
        String note
        String chargeId = 'ch-1'
    }

    static class RecordingStep implements Action<Order, Object> {
        @Override
        void execute(Order entity, Object context, ExecutingTransition<Order, Object> transition) {
            TRAIL << 'step'
        }
    }

    static class CtxStep implements Action<Order, Ctx> {
        @Override
        void execute(Order entity, Ctx context, ExecutingTransition<Order, Ctx> transition) {
            TRAIL << "ctx-step:${context.note}".toString()
        }
    }

    static class ChildStep implements Action<Order, ChildCtx> {
        @Override
        void execute(Order entity, ChildCtx context, ExecutingTransition<Order, ChildCtx> transition) {
            TRAIL << "child-step:${context.note}".toString()
        }
    }

    static class FailingStep implements Action<Object, Object> {
        @Override
        void execute(Object entity, Object context, ExecutingTransition<Object, Object> transition) {
            throw new IllegalStateException('boom')
        }
    }

    static class GenericStep<X> implements Action<X, Object> {
        @Override
        void execute(X entity, Object context, ExecutingTransition<X, Object> transition) {
            TRAIL << 'generic-step'
        }
    }

    static class Undo implements Compensation<Order, Object> {
        @Override
        void compensate(Order entity, Object context) {
            TRAIL << 'undo'
        }
    }

    static class RouteUndo implements Compensation<Order, Object> {
        @Override
        void compensate(Order entity, Object context) {
            TRAIL << 'route-undo'
        }
    }

    static class CtxUndo implements Compensation<Order, Ctx> {
        @Override
        void compensate(Order entity, Ctx context) {
            TRAIL << 'ctx-undo'
        }
    }

    static class BoomGuard implements Predicate<IllegalStateException> {
        @Override
        boolean test(IllegalStateException failure) {
            return failure.message == 'boom'
        }
    }

    static class PriorityCondition implements Condition<Order, Object> {
        @Override
        boolean test(Order entity, Object context, Transition transition) {
            return entity.priority > 5
        }
    }

    static class PriorityBi implements BiPredicate<Order, Object> {
        @Override
        boolean test(Order entity, Object context) {
            return entity.priority > 5
        }
    }

    static class PriorityPredicate implements Predicate<Order> {
        @Override
        boolean test(Order entity) {
            return entity.priority > 5
        }
    }

    static class BothPredicates implements BiPredicate<Order, Object>, Predicate<Order> {
        @Override
        boolean test(Order entity, Object context) {
            return true
        }

        @Override
        boolean test(Order entity) {
            return true
        }
    }

    static class CtxBi implements BiPredicate<Order, Ctx> {
        @Override
        boolean test(Order entity, Ctx context) {
            return true
        }
    }

    static class ConfirmedEvent implements BiPredicate<Object, Order> {
        @Override
        boolean test(Object event, Order entity) {
            return event == 'CONFIRMED'
        }
    }

    static class ConfirmedEventOnly implements Predicate<Object> {
        @Override
        boolean test(Object event) {
            return event == 'CONFIRMED'
        }
    }

    static class ChildMapper implements ContextMapper<Ctx, ChildCtx> {
        @Override
        ChildCtx mapTo(Ctx parent) {
            return new ChildCtx(note: "mapped-${parent.note}")
        }
    }

    static class StateAudit implements StateListener<Order> {
        @Override
        void onState(Order entity, Object context, StateChange change) {
            TRAIL << "state:${change.phase()}:${change.state().id}".toString()
        }
    }

    static class StateNameAudit implements StateListener<Order> {
        @Override
        void onState(Order entity, Object context, StateChange change) {
            TRAIL << "state-name:${change.state().name}".toString()
        }
    }

    static class TransitionNameAudit implements TransitionListener<Order, Object> {
        @Override
        void onTransition(Order entity, Object context, TransitionExecution<Order> execution) {
            TRAIL << "transition-name:${execution.transition().name}".toString()
        }
    }

    static class TransitionAudit implements TransitionListener<Order, Object> {
        @Override
        void onTransition(Order entity, Object context, TransitionExecution<Order> execution) {
            TRAIL << "transition:${execution.phase()}".toString()
        }
    }

    static class CtxTransitionAudit implements TransitionListener<Order, Ctx> {
        @Override
        void onTransition(Order entity, Ctx context, TransitionExecution<Order> execution) {
            TRAIL << "ctx-transition:${execution.phase()}".toString()
        }
    }

    static class ActionAudit implements ActionListener<Order, Object> {
        @Override
        void onAction(Order entity, Object context, ActionExecution execution) {
            TRAIL << "action:${execution.phase()}:${execution.actionId()}".toString()
        }
    }

    static class ThreadAudit implements ActionListener<Order, Object> {
        @Override
        void onAction(Order entity, Object context, ActionExecution execution) {
            TRAIL << "thread:${execution.phase()}:${Thread.currentThread().name}".toString()
        }
    }

    static class EveryAudit implements StateListener<Order>, TransitionListener<Order, Object>, ActionListener<Order, Object> {
        @Override
        void onState(Order entity, Object context, StateChange change) {
            TRAIL << "every-state:${change.phase()}".toString()
        }

        @Override
        void onTransition(Order entity, Object context, TransitionExecution<Order> execution) {
            TRAIL << "every-transition:${execution.phase()}".toString()
        }

        @Override
        void onAction(Order entity, Object context, ActionExecution execution) {
            TRAIL << "every-action:${execution.phase()}".toString()
        }
    }
}
