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
import org.transflux.core.transition.Transition
import spock.lang.Specification
import spock.lang.Unroll

import java.util.function.BiPredicate
import java.util.function.Predicate

class TransitionDefImplIdentifiableTier3OverloadsSpec extends Specification {

    private static Identifiable id(String value) {
        return { -> value } as Identifiable
    }

    static class TestOp implements Operation<Object, Object> {
        @Override
        void execute(Object e, Object c, Transition<Object, Object> t) {}
    }

    static class TestCond implements Condition<Object, Object> {
        @Override
        boolean test(Object e, Object c, Transition<Object, Object> t) { true }
    }

    private TransitionDefImpl<Object, Object> td

    void setup() {
        td = new TransitionDefImpl<>('t', 's1', 's2')
        td.beginConfigurer()
    }

    @Unroll
    def 'simpleOperation/compositeOperation Identifiable overload: #variant'() {
        when:
        action.call(td)

        then:
        notThrown(Exception)

        where:
        variant                                     | action
        'simpleOperation(Id, Operation)'            | { d -> d.simpleOperation(id('op1'), new TestOp()) }
        'simpleOperation(Id, Class)'                | { d -> d.simpleOperation(id('op2'), TestOp) }
        'simpleOperation(Id, Consumer)'             | { d -> d.simpleOperation(id('op3'), { o -> o.using(new TestOp()) }) }
        'compositeOperation(Id, Consumer)'          | { d -> d.compositeOperation(id('op4'), { c -> c.step('anything') }) }
    }

    @Unroll
    def 'preCondition Identifiable overload: #variant'() {
        when:
        action.call(td)

        then:
        td.preConditionDescriptors.size() == 1

        where:
        variant                                  | action
        'preCondition(Id, Condition)'            | { d -> d.preCondition(id('pc1'), new TestCond()) }
        'preCondition(Id, Class)'                | { d -> d.preCondition(id('pc2'), TestCond) }
        'preCondition(Id, Predicate)'            | { d -> d.preCondition(id('pc3'), { e -> true } as Predicate) }
        'preCondition(Id, String)'               | { d -> d.preCondition(id('pc4'), 'true') }
    }

    @Unroll
    def 'postCondition Identifiable overload: #variant'() {
        when:
        action.call(td)

        then:
        td.postConditionDescriptors.size() == 1

        where:
        variant                                  | action
        'postCondition(Id, Condition)'           | { d -> d.postCondition(id('pc1'), new TestCond()) }
        'postCondition(Id, Class)'               | { d -> d.postCondition(id('pc2'), TestCond) }
        'postCondition(Id, Predicate)'           | { d -> d.postCondition(id('pc3'), { e -> true } as Predicate) }
        'postCondition(Id, String)'              | { d -> d.postCondition(id('pc4'), 'true') }
    }

    @Unroll
    def '#variant rejects null Identifiable'() {
        when:
        action.call(td)

        then:
        thrown(TransfluxValidationException)

        where:
        variant                                  | action
        'simpleOperation(null, Operation)'       | { d -> d.simpleOperation((Identifiable) null, new TestOp()) }
        'simpleOperation(null, Class)'           | { d -> d.simpleOperation((Identifiable) null, TestOp) }
        'simpleOperation(null, Consumer)'        | { d -> d.simpleOperation((Identifiable) null, { o -> }) }
        'compositeOperation(null, Consumer)'     | { d -> d.compositeOperation((Identifiable) null, { c -> }) }
        'preCondition(null, Condition)'          | { d -> d.preCondition((Identifiable) null, new TestCond()) }
        'preCondition(null, Class)'              | { d -> d.preCondition((Identifiable) null, TestCond) }
        'preCondition(null, Predicate)'          | { d -> d.preCondition((Identifiable) null, { e -> true } as Predicate) }
        'preCondition(null, String)'             | { d -> d.preCondition((Identifiable) null, 'true') }
        'postCondition(null, Condition)'         | { d -> d.postCondition((Identifiable) null, new TestCond()) }
        'postCondition(null, Class)'             | { d -> d.postCondition((Identifiable) null, TestCond) }
        'postCondition(null, Predicate)'         | { d -> d.postCondition((Identifiable) null, { e -> true } as Predicate) }
        'postCondition(null, String)'            | { d -> d.postCondition((Identifiable) null, 'true') }
        'addManualTrigger(null)'                 | { d -> d.addManualTrigger((Identifiable) null) }
        'addEventTrigger(null, String)'          | { d -> d.addEventTrigger((Identifiable) null, 'evt') }
        'addEventTrigger(null, Identifiable)'    | { d -> d.addEventTrigger((Identifiable) null, id('evt')) }
        'addEventTrigger(null, BiPredicate)'     | { d -> d.addEventTrigger((Identifiable) null, { i, e -> true } as BiPredicate) }
        'addDataTrigger(null)'                   | { d -> d.addDataTrigger((Identifiable) null) }
        'addDataTrigger(null, Predicate)'        | { d -> d.addDataTrigger((Identifiable) null, { e -> true } as Predicate) }
    }
}
