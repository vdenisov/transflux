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

package org.transflux.core.transition

import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.trigger.Trigger
import spock.lang.Specification

class TransitionExecutionSpec extends Specification {

    static class Entity {
    }

    def 'the record carries the phase, the transition, the trigger, and the result'() {
        given:
        Transition transition = Stub(Transition)
        Trigger trigger = Stub(Trigger)
        def result = success()

        when:
        def execution = new TransitionExecution<Entity>(TransitionPhase.COMPLETE, transition, trigger, result)

        then:
        execution.phase() == TransitionPhase.COMPLETE
        execution.transition().is(transition)
        execution.firedBy().is(trigger)
        execution.result().is(result)
    }

    def 'a direct invocation leaves firedBy null'() {
        expect:
        new TransitionExecution<Entity>(TransitionPhase.START, Stub(Transition), null, null)
            .firedBy() == null
    }

    def 'the constructor rejects a null phase'() {
        when:
        new TransitionExecution<Entity>(null, Stub(Transition), null, null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.toLowerCase().contains('phase')
    }

    def 'the constructor rejects a null transition'() {
        when:
        new TransitionExecution<Entity>(TransitionPhase.START, null, null, null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.toLowerCase().contains('transition')
    }

    def 'START requires a null result'() {
        when:
        new TransitionExecution<Entity>(TransitionPhase.START, Stub(Transition), null, success())

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Transition execution result must be null at phase START'
    }

    def 'COMPLETE requires a result'() {
        when:
        new TransitionExecution<Entity>(TransitionPhase.COMPLETE, Stub(Transition), null, null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Transition execution result cannot be null at phase COMPLETE'
    }

    def 'ERROR requires a result'() {
        when:
        new TransitionExecution<Entity>(TransitionPhase.ERROR, Stub(Transition), null, null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Transition execution result cannot be null at phase ERROR'
    }

    def 'COMPLETE rejects a failed result'() {
        when:
        new TransitionExecution<Entity>(TransitionPhase.COMPLETE, Stub(Transition), null, failure())

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Transition execution at phase COMPLETE requires a successful result'
    }

    def 'ERROR rejects a successful result'() {
        when:
        new TransitionExecution<Entity>(TransitionPhase.ERROR, Stub(Transition), null, success())

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Transition execution at phase ERROR requires a failed result'
    }

    private static TransitionResult<Entity> success() {
        return TransitionResult.success(new Entity(), 's1', 's2', 't')
    }

    private static TransitionResult<Entity> failure() {
        return TransitionResult.failure(new Entity(), 's1', 's2', 't', new IllegalStateException('boom'))
    }
}
