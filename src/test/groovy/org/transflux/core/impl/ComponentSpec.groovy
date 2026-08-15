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

import org.transflux.core.condition.Condition
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.action.Operation
import org.transflux.core.action.Step
import spock.lang.Specification
import spock.lang.Unroll

class ComponentSpec extends Specification {

    @Unroll
    def 'a #kind component whose id matches its payload validates'() {
        when:
        component.call('a').validate()

        then:
        noExceptionThrown()

        where:
        kind        | component
        'step'      | { id -> new Component.Step<>(id, Object, step(id)) }
        'operation' | { id -> new Component.Operation<>(id, Object, operation(id)) }
        'condition' | { id -> new Component.Condition<>(id, Object, condition(id)) }
    }

    @Unroll
    def 'a #kind component wrapping a payload with a different id is rejected'() {
        when:
        component.call().validate()

        then:
        def e = thrown(TransfluxValidationException)
        e.message == "Component 'a' wraps a bound ${kind} with id 'b'"

        where:
        kind        | component
        'step'      | { -> new Component.Step<>('a', Object, step('b')) }
        'operation' | { -> new Component.Operation<>('a', Object, operation('b')) }
        'condition' | { -> new Component.Condition<>('a', Object, condition('b')) }
    }

    @Unroll
    def 'a #kind component with no payload is rejected'() {
        when:
        component.call().validate()

        then:
        def e = thrown(TransfluxValidationException)
        e.message == "Component 'a' has no bound ${kind}"

        where:
        kind        | component
        'step'      | { -> new Component.Step<>('a', Object, null) }
        'operation' | { -> new Component.Operation<>('a', Object, null) }
        'condition' | { -> new Component.Condition<>('a', Object, null) }
    }

    private static BoundStep<Object, Object> step(String id) {
        return BoundStep.of(id, { e, ctx, tr -> } as Step)
    }

    private static BoundOperation<Object, Object> operation(String id) {
        return BoundOperation.of(id, { e, ctx, tr -> } as Operation)
    }

    private static BoundCondition<Object, Object> condition(String id) {
        return BoundCondition.of(id, { e, ctx, tr -> true } as Condition)
    }
}
