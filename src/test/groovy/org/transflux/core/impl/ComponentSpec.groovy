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

import org.transflux.core.action.ActionKind
import org.transflux.core.condition.Condition
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.action.Action
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
        'step'      | { id -> new Component.Action<>(id, Object, step(id)) }
        'operation' | { id -> new Component.Action<>(id, Object, operation(id)) }
        'condition' | { id -> new Component.Condition<>(id, Object, condition(id)) }
    }

    @Unroll
    def 'a #kind component wrapping a payload with a different id is rejected'() {
        when:
        component.call().validate()

        then:
        def e = thrown(TransfluxValidationException)
        e.message == "Component 'a' wraps a bound ${reported} with id 'b'"

        where:
        kind        | reported    || component
        'step'      | 'action'    || { -> new Component.Action<>('a', Object, step('b')) }
        'operation' | 'action'    || { -> new Component.Action<>('a', Object, operation('b')) }
        'condition' | 'condition' || { -> new Component.Condition<>('a', Object, condition('b')) }
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
        'action'    | { -> new Component.Action<>('a', Object, null) }
        'condition' | { -> new Component.Condition<>('a', Object, null) }
    }

    private static BoundAction<Object, Object> step(String id) {
        return BoundAction.of(id, { e, ctx, tr -> } as Action, ActionKind.STEP)
    }

    private static BoundAction<Object, Object> operation(String id) {
        return BoundAction.of(id, { e, ctx, tr -> } as Action, ActionKind.OPERATION)
    }

    private static BoundCondition<Object, Object> condition(String id) {
        return BoundCondition.of(id, { e, ctx, tr -> true } as Condition)
    }
}
