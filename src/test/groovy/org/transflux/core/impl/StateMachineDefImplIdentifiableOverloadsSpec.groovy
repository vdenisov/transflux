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

package org.transflux.core.impl

import org.transflux.core.Identifiable
import org.transflux.core.condition.Condition
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.operation.ContextMapper
import org.transflux.core.operation.Operation
import org.transflux.core.operation.Step
import org.transflux.core.transition.Transition
import spock.lang.Specification
import spock.lang.Unroll

import java.util.function.Function
import java.util.function.Predicate

class StateMachineDefImplIdentifiableOverloadsSpec extends Specification {

    private static Identifiable id(String value) {
        return { -> value } as Identifiable
    }

    static class TestStep implements Step<Object, Object> {
        @Override
        void execute(Object e, Object c, Transition<Object, Object> t) {}
    }

    static class TestCondition implements Condition<Object, Object> {
        @Override
        boolean test(Object e, Object c, Transition<Object, Object> t) { true }
    }

    static class TestOperation implements Operation<Object, Object> {
        @Override
        void execute(Object e, Object c, Transition<Object, Object> t) {}
    }

    static class TestMapper implements ContextMapper<Object, Object> {
        @Override
        Object mapTo(Object p) { p }
    }

    private StateMachineDefImpl<Object> smd

    void setup() {
        smd = new StateMachineDefImpl<>()
        smd.forEntityType(Object)
    }

    @Unroll
    def 'step Identifiable overload: #variant'() {
        when:
        action.call(smd)

        then:
        notThrown(Exception)

        where:
        variant                                | action
        'step(Id, Step)'                       | { d -> d.step(id('s1'), new TestStep()) }
        'step(Id, Class)'                      | { d -> d.step(id('s2'), TestStep) }
        'step(Id, Class<C>, Step)'             | { d -> d.step(id('s3'), Object, new TestStep()) }
        'step(Id, Class<C>, Class)'            | { d -> d.step(id('s4'), Object, TestStep) }
    }

    @Unroll
    def 'condition Identifiable overload: #variant'() {
        when:
        action.call(smd)

        then:
        notThrown(Exception)

        where:
        variant                                          | action
        'condition(Id, Condition)'                       | { d -> d.condition(id('c1'), new TestCondition()) }
        'condition(Id, Class)'                           | { d -> d.condition(id('c2'), TestCondition) }
        'condition(Id, Predicate)'                       | { d -> d.condition(id('c3'), { e -> true } as Predicate) }
        'condition(Id, String spel)'                     | { d -> d.condition(id('c4'), 'true') }
        'condition(Id, Class<C>, Condition)'             | { d -> d.condition(id('c5'), Object, new TestCondition()) }
        'condition(Id, Class<C>, Class)'                 | { d -> d.condition(id('c6'), Object, TestCondition) }
        'conditionPredicate(Id, Class<C>, Predicate)'    | { d -> d.conditionPredicate(id('c7'), Object, { e -> true } as Predicate) }
        'conditionExpression(Id, Class<C>, String)'      | { d -> d.conditionExpression(id('c8'), Object, 'true') }
    }

    @Unroll
    def 'operation/composite/mapper Identifiable overload: #variant'() {
        when:
        action.call(smd)

        then:
        notThrown(Exception)

        where:
        variant                                          | action
        'compositeOperation(Id, Class<C>, Consumer)'     | { d -> d.compositeOperation(id('co1'), Object, { c -> c.step('any') }) }
        'operation(Id, Class<C>, Operation)'             | { d -> d.operation(id('o1'), Object, new TestOperation()) }
        'operation(Id, Class<C>, Class)'                 | { d -> d.operation(id('o2'), Object, TestOperation) }
        'mapper(Id, parent, child, ContextMapper)'       | { d -> d.mapper(id('m1'), Object, Object, new TestMapper()) }
        'mapper(Id, parent, child, Class)'               | { d -> d.mapper(id('m2'), Object, Object, TestMapper) }
        'mapper(Id, parent, child, Function)'            | { d -> d.mapper(id('m3'), Object, Object, { p -> p } as Function) }
    }

    @Unroll
    def '#variant rejects null Identifiable'() {
        when:
        action.call(smd)

        then:
        thrown(TransfluxValidationException)

        where:
        variant                                          | action
        'step(null, Step)'                               | { d -> d.step((Identifiable) null, new TestStep()) }
        'step(null, Class)'                              | { d -> d.step((Identifiable) null, TestStep) }
        'condition(null, Condition)'                     | { d -> d.condition((Identifiable) null, new TestCondition()) }
        'condition(null, Class)'                         | { d -> d.condition((Identifiable) null, TestCondition) }
        'condition(null, Predicate)'                     | { d -> d.condition((Identifiable) null, { e -> true } as Predicate) }
        'condition(null, String spel)'                   | { d -> d.condition((Identifiable) null, 'true') }
        'compositeOperation(null, Class, Consumer)'      | { d -> d.compositeOperation((Identifiable) null, Object, { c -> c.step('x') }) }
        'operation(null, Class, Operation)'              | { d -> d.operation((Identifiable) null, Object, new TestOperation()) }
        'mapper(null, parent, child, ContextMapper)'     | { d -> d.mapper((Identifiable) null, Object, Object, new TestMapper()) }
    }
}
