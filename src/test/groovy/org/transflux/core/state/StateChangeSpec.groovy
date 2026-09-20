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

package org.transflux.core.state

import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.transition.Transition
import spock.lang.Specification

class StateChangeSpec extends Specification {

    static class Entity {
    }

    def 'the record carries the phase, the state, and the transition'() {
        given:
        State state = Stub(State)
        Transition transition = Stub(Transition)

        when:
        def change = new StateChange(StatePhase.ENTRY, state, transition)

        then:
        change.phase() == StatePhase.ENTRY
        change.state().is(state)
        change.transition().is(transition)
    }

    def 'the constructor rejects a null phase'() {
        when:
        new StateChange(null, Stub(State), Stub(Transition))

        then:
        def e = thrown(TransfluxValidationException)
        e.message.toLowerCase().contains('phase')
    }

    def 'the constructor rejects a null state'() {
        when:
        new StateChange(StatePhase.ENTRY, null, Stub(Transition))

        then:
        def e = thrown(TransfluxValidationException)
        e.message.toLowerCase().contains('state')
    }

    def 'the constructor rejects a null transition'() {
        when:
        new StateChange(StatePhase.EXIT, Stub(State), null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.toLowerCase().contains('transition')
    }

    def 'records with equal components are equal and share a hash code'() {
        given:
        State state = Stub(State)
        Transition transition = Stub(Transition)

        and:
        def a = new StateChange(StatePhase.EXIT, state, transition)
        def b = new StateChange(StatePhase.EXIT, state, transition)
        def c = new StateChange(StatePhase.ENTRY, state, transition)

        expect:
        a == b
        a.hashCode() == b.hashCode()
        a != c
    }
}
