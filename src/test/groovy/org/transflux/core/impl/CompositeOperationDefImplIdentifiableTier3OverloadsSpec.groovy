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
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.operation.Operation
import org.transflux.core.operation.Step
import org.transflux.core.transition.Transition
import spock.lang.Specification
import spock.lang.Unroll

class CompositeOperationDefImplIdentifiableTier3OverloadsSpec extends Specification {

    private static Identifiable id(String value) {
        return { -> value } as Identifiable
    }

    static class TestStep implements Step<Object, Object> {
        @Override
        void execute(Object e, Object c, Transition<Object, Object> t) {}
    }

    static class TestOp implements Operation<Object, Object> {
        @Override
        void execute(Object e, Object c, Transition<Object, Object> t) {}
    }

    @Unroll
    def 'inline Identifiable overload: #variant'() {
        given:
        def composite = new CompositeOperationDefImpl<Object, Object>('outer')

        when:
        action.call(composite)

        then:
        composite.actionRefs.size() == 1

        where:
        variant                                  | action
        'step(Id, Step)'                         | { c -> c.step(id('s1'), new TestStep()) }
        'step(Id, Class)'                        | { c -> c.step(id('s2'), TestStep) }
        'operation(Id, Operation)'               | { c -> c.operation(id('o1'), new TestOp()) }
        'operation(Id, Class)'                   | { c -> c.operation(id('o2'), TestOp) }
        'conditional(Id, Consumer)'              | { c -> c.conditional(id('cond1'), { cs -> cs.branch('b', { b -> b.condition('any'); b.step('x') }) }) }
    }

    @Unroll
    def '#variant rejects null Identifiable'() {
        given:
        def composite = new CompositeOperationDefImpl<Object, Object>('outer')

        when:
        action.call(composite)

        then:
        thrown(TransfluxValidationException)

        where:
        variant                                  | action
        'step(null, Step)'                       | { c -> c.step((Identifiable) null, new TestStep()) }
        'step(null, Class)'                      | { c -> c.step((Identifiable) null, TestStep) }
        'operation(null, Operation)'             | { c -> c.operation((Identifiable) null, new TestOp()) }
        'operation(null, Class)'                 | { c -> c.operation((Identifiable) null, TestOp) }
        'conditional(null, Consumer)'            | { c -> c.conditional((Identifiable) null, { cs -> }) }
    }
}
