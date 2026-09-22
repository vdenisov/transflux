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

import org.transflux.core.action.Action
import org.transflux.core.action.ContextMapper
import org.transflux.core.transition.ExecutingTransition
import spock.lang.Specification

import java.util.function.Predicate

import static org.transflux.yaml.TypeArguments.Expected.exactly
import static org.transflux.yaml.TypeArguments.Expected.superOf

class TypeArgumentsSpec extends Specification {

    def '#type.simpleName is #verdict'() {
        expect:
        TypeArguments.mismatch(type, Action, superOf(Order), exactly(Billing)) == problem

        where:
        type              | verdict                                 || problem
        DirectStep        | 'accepted'                              || null
        InheritedStep     | 'accepted through its superclass'       || null
        TraitStep         | 'accepted: its entity is a supertype'   || null
        RawGenericStep    | 'left to the runtime check'             || null
        SubtypeStep       | 'refused: its entity is a subtype'      || "class ${SubtypeStep.name} declares Action's T as ${SpecialOrder.name}, where this position needs ${Order.name} or a supertype of it"
        WrongContextStep  | 'refused: its context is another class' || "class ${WrongContextStep.name} declares Action's C as ${Shipping.name}, where this position needs ${Billing.name}"
        ObjectContextStep | 'refused: a context is invariant'       || "class ${ObjectContextStep.name} declares Action's C as java.lang.Object, where this position needs ${Billing.name}"
    }

    def 'a parameterized argument is checked by its raw class'() {
        expect:
        TypeArguments.mismatch(ParameterizedStep, Action, superOf(Order), exactly(List)) == null
        TypeArguments.mismatch(ParameterizedStep, Action, superOf(Order), exactly(Collection)) != null
    }

    def 'a parameter the position leaves open is not checked'() {
        expect:
        TypeArguments.mismatch(WrongContextStep, Action, superOf(Order), null) == null
    }

    def 'both parameters of a mapper are invariant'() {
        expect:
        TypeArguments.mismatch(BillingFromShipping, ContextMapper, exactly(Shipping), exactly(Billing)) == null
        TypeArguments.mismatch(BillingFromShipping, ContextMapper, exactly(Billing), exactly(Billing)) != null
    }

    def 'a one-parameter position is checked the same way'() {
        expect:
        TypeArguments.mismatch(OrderPredicate, Predicate, superOf(SpecialOrder)) == null
        TypeArguments.mismatch(OrderPredicate, Predicate, superOf(Trackable)) != null
    }

    def 'an expectation list of the wrong length is a programming error'() {
        when:
        TypeArguments.mismatch(DirectStep, Action, superOf(Order))

        then:
        thrown(IllegalArgumentException)
    }

    static interface Trackable {
    }

    static class Order implements Trackable {
    }

    static class SpecialOrder extends Order {
    }

    static class Billing {
    }

    static class Shipping {
    }

    static class DirectStep implements Action<Order, Billing> {
        void execute(Order entity, Billing context, ExecutingTransition<Order, Billing> transition) {
        }
    }

    static abstract class BaseStep<C> implements Action<Order, C> {
    }

    static class InheritedStep extends BaseStep<Billing> {
        void execute(Order entity, Billing context, ExecutingTransition<Order, Billing> transition) {
        }
    }

    static class TraitStep implements Action<Trackable, Billing> {
        void execute(Trackable entity, Billing context, ExecutingTransition<Trackable, Billing> transition) {
        }
    }

    static class RawGenericStep<T, C> implements Action<T, C> {
        void execute(T entity, C context, ExecutingTransition<T, C> transition) {
        }
    }

    static class ParameterizedStep implements Action<Order, List<String>> {
        void execute(Order entity, List<String> context, ExecutingTransition<Order, List<String>> transition) {
        }
    }

    static class SubtypeStep implements Action<SpecialOrder, Billing> {
        void execute(SpecialOrder entity, Billing context, ExecutingTransition<SpecialOrder, Billing> transition) {
        }
    }

    static class WrongContextStep implements Action<Order, Shipping> {
        void execute(Order entity, Shipping context, ExecutingTransition<Order, Shipping> transition) {
        }
    }

    static class ObjectContextStep implements Action<Order, Object> {
        void execute(Order entity, Object context, ExecutingTransition<Order, Object> transition) {
        }
    }

    static class BillingFromShipping implements ContextMapper<Shipping, Billing> {
        Billing mapTo(Shipping parent) {
            return new Billing()
        }
    }

    static class OrderPredicate implements Predicate<Order> {
        boolean test(Order order) {
            return true
        }
    }
}
