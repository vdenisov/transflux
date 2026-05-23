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
import org.transflux.core.operation.Operation
import org.transflux.core.operation.Step
import org.transflux.core.transition.Transition
import spock.lang.Specification
import spock.lang.Unroll

import java.util.function.Predicate

class ContextScopeImplIdentifiableOverloadsSpec extends Specification {

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

    private StateMachineDefImpl<Object> smd

    void setup() {
        smd = new StateMachineDefImpl<>()
        smd.forEntityType(Object)
    }

    @Unroll
    def 'ContextScope Identifiable overload: #variant'() {
        when:
        smd.forContext(Object, { scope ->
            action.call(scope)
        })

        then:
        notThrown(Exception)

        where:
        variant                                          | action
        'step(Id, Step)'                                 | { s -> s.step(id('s1'), new TestStep()) }
        'step(Id, Class)'                                | { s -> s.step(id('s2'), TestStep) }
        'condition(Id, Condition)'                       | { s -> s.condition(id('c1'), new TestCondition()) }
        'condition(Id, Class)'                           | { s -> s.condition(id('c2'), TestCondition) }
        'condition(Id, Predicate)'                       | { s -> s.condition(id('c3'), { e -> true } as Predicate) }
        'condition(Id, String)'                          | { s -> s.condition(id('c4'), 'true') }
        'compositeOperation(Id, Consumer)'               | { s -> s.compositeOperation(id('co1'), { c -> c.step('any') }) }
        'operation(Id, Operation)'                       | { s -> s.operation(id('o1'), new TestOperation()) }
        'operation(Id, Class)'                           | { s -> s.operation(id('o2'), TestOperation) }
    }

    @Unroll
    def '#variant rejects null Identifiable'() {
        when:
        smd.forContext(Object, { scope ->
            action.call(scope)
        })

        then:
        thrown(TransfluxValidationException)

        where:
        variant                                          | action
        'step(null, Step)'                               | { s -> s.step((Identifiable) null, new TestStep()) }
        'step(null, Class)'                              | { s -> s.step((Identifiable) null, TestStep) }
        'condition(null, Condition)'                     | { s -> s.condition((Identifiable) null, new TestCondition()) }
        'condition(null, Class)'                         | { s -> s.condition((Identifiable) null, TestCondition) }
        'condition(null, Predicate)'                     | { s -> s.condition((Identifiable) null, { e -> true } as Predicate) }
        'condition(null, String)'                        | { s -> s.condition((Identifiable) null, 'true') }
        'compositeOperation(null, Consumer)'             | { s -> s.compositeOperation((Identifiable) null, { c -> }) }
        'operation(null, Operation)'                     | { s -> s.operation((Identifiable) null, new TestOperation()) }
        'operation(null, Class)'                         | { s -> s.operation((Identifiable) null, TestOperation) }
    }
}
