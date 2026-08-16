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

package org.transflux.core.action

import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.transition.ActionPath
import org.transflux.core.transition.Transition
import spock.lang.Specification
import spock.lang.Unroll

class ActionExecutionSpec extends Specification {

    static class Entity {
    }

    def 'the record carries the phase, the path, the kind, and the transition'() {
        given:
        Transition<Entity, Object> transition = Stub(Transition)
        def path = ActionPath.of('activate', 'charge')

        when:
        def execution = new ActionExecution<Entity>(
            ActionPhase.COMPLETE, path, ActionKind.STEP, transition, null)

        then:
        execution.phase() == ActionPhase.COMPLETE
        execution.path().is(path)
        execution.kind() == ActionKind.STEP
        execution.transition().is(transition)
        execution.error() == null
    }

    def 'actionId is the leaf of the path'() {
        expect:
        new ActionExecution<Entity>(ActionPhase.START, ActionPath.of('activate', 'charge'),
                                    ActionKind.STEP, Stub(Transition), null).actionId() == 'charge'
    }

    @Unroll
    def 'the constructor rejects a null #component'() {
        when:
        new ActionExecution<Entity>(phase, path, kind, transition, null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.toLowerCase().contains(component)

        where:
        component    | phase              | path                  | kind            | transition
        'phase'      | null               | ActionPath.of('a')    | ActionKind.STEP | Stub(Transition)
        'path'       | ActionPhase.START  | null                  | ActionKind.STEP | Stub(Transition)
        'kind'       | ActionPhase.START  | ActionPath.of('a')    | null            | Stub(Transition)
        'transition' | ActionPhase.START  | ActionPath.of('a')    | ActionKind.STEP | null
    }

    def 'ERROR requires an error'() {
        when:
        new ActionExecution<Entity>(ActionPhase.ERROR, ActionPath.of('a'), ActionKind.STEP,
                                    Stub(Transition), null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Action execution error cannot be null at phase ERROR'
    }

    @Unroll
    def '#phase rejects an error'() {
        when:
        new ActionExecution<Entity>(phase, ActionPath.of('a'), ActionKind.STEP, Stub(Transition),
                                    new IllegalStateException('boom'))

        then:
        def e = thrown(TransfluxValidationException)
        e.message == "Action execution error must be null at phase ${phase}"

        where:
        phase << [ActionPhase.START, ActionPhase.COMPLETE]
    }

    def 'ERROR carries the throwable'() {
        given:
        def boom = new IllegalStateException('boom')

        expect:
        new ActionExecution<Entity>(ActionPhase.ERROR, ActionPath.of('a'), ActionKind.OPERATION,
                                    Stub(Transition), boom).error().is(boom)
    }
}
