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

import org.transflux.core.StateMachineDef
import org.transflux.core.Transflux
import org.transflux.core.exception.TransfluxValidationException
import spock.lang.Specification
import spock.lang.Unroll

class StateMachineDefImplTransitionSpec extends Specification {

    def 'transition records its id, source and target'() {
        given:
        def smd = Transflux.defineStateMachine(Entity) as StateMachineDefImpl

        when:
        smd.state('s1').state('s2').transition('t', 's1', 's2', {})

        then:
        smd.getTransition('t').with {
            id == 't' && sourceStateId == 's1' && targetStateId == 's2'
        }
    }

    def 'a transition may be declared before the states it connects'() {
        when:
        def sm = machine().transition('t', 's1', 's2', {}).state('s1').state('s2').build()

        then:
        sm.entity(new Entity('s1')).transitionTo('s2').success
    }

    def 'a duplicate transition id is rejected'() {
        given:
        def smd = machine().state('s1').state('s2').transition('t', 's1', 's2', {})

        when:
        smd.transition('t', 's2', 's1', {})

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Transition ID t already defined'
    }

    def 'the untyped form defaults the transition context to Object'() {
        given:
        def smd = machine().state('s1').state('s2').transition('t', 's1', 's2', {})

        expect:
        (smd as StateMachineDefImpl).getTransition('t').contextType == Object
    }

    def 'the typed form pre-binds the transition context'() {
        given:
        def smd = machine().state('s1').state('s2').transition('t', 's1', 's2', CtxA, {})

        expect:
        (smd as StateMachineDefImpl).getTransition('t').contextType == CtxA
    }

    def 'a pre-bound transition rejects a firing with the wrong context type'() {
        given:
        def sm = machine().state('s1').state('s2').transition('t', 's1', 's2', CtxA, {}).build()

        when:
        sm.entity(new Entity('s1')).transitionTo('s2', new CtxB())

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('CtxA')
        e.message.contains('CtxB')
    }

    def 'a pre-bound transition accepts a firing with the matching context type'() {
        given:
        def sm = machine().state('s1').state('s2').transition('t', 's1', 's2', CtxA, {}).build()

        expect:
        sm.entity(new Entity('s1')).transitionTo('s2', new CtxA()).success
    }

    @Unroll
    def 'a transition rejects #scenario'() {
        when:
        machine().transition(*arguments)

        then:
        thrown(TransfluxValidationException)

        where:
        scenario             || arguments
        'a blank id'         || [' ', 's1', 's2', {}]
        'a blank source'     || ['t', ' ', 's2', {}]
        'a blank target'     || ['t', 's1', ' ', {}]
        'a null context'     || ['t', 's1', 's2', null, {}]
        'a null configurer'  || ['t', 's1', 's2', CtxA, null]
    }

    @Unroll
    def 'the build rejects a transition whose #end state is not declared'() {
        given:
        def smd = machine().state('s1').transition('t', source, target, {})

        when:
        smd.build()

        then:
        def e = thrown(TransfluxValidationException)
        e.message == message

        where:
        end      | source | target || message
        'source' | 'nope' | 's1'   || "Transition 't' leaves state 'nope', which is not declared"
        'target' | 's1'   | 'nope' || "Transition 't' targets state 'nope', which is not declared"
    }

    def 'state(id) declares a state that configures nothing'() {
        given:
        def smd = machine().state('s1')

        expect:
        (smd as StateMachineDefImpl).getStates()['s1'].with { id == 's1' && name == null }
    }

    def 'state(id) rejects an id already declared'() {
        given:
        def smd = machine().state('s1')

        when:
        smd.state('s1')

        then:
        thrown(TransfluxValidationException)
    }

    private static StateMachineDef<Entity> machine() {
        return Transflux.defineStateMachine(Entity).withStateResolver({ Entity e -> e.state })
    }

    static class Entity {
        String state

        Entity(String state) { this.state = state }
    }

    static class CtxA { }

    static class CtxB { }
}
