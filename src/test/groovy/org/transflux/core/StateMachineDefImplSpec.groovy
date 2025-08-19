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

package org.transflux.core

import spock.lang.Specification
import spock.lang.Unroll

import static org.transflux.core.TestStateEnum.ACTIVE
import static org.transflux.core.TestStateEnum.EXPIRED
import static org.transflux.core.TestStateEnum.TRIAL

class StateMachineDefImplSpec extends Specification {

    def "getTransition by source and target should return single transition"() {
        given:
        def smd = Transflux.defineStateMachine()
        smd.state(TRIAL).transitionsTo(ACTIVE, "trial-to-active")
        smd.state(ACTIVE)

        when:
        def td = smd.getTransition(TRIAL.id, ACTIVE.id)

        then:
        td.id == "trial-to-active"
        td.sourceStateId == TRIAL.id
        td.targetStateId == ACTIVE.id
    }

    def "getTransition by source and target should error when no transition exists"() {
        given:
        def smd = Transflux.defineStateMachine()
        smd.state(TRIAL).transitionsTo(ACTIVE, "trial-to-active")
        smd.state(ACTIVE)

        when:
        smd.getTransition(TRIAL.id, EXPIRED.id)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == "No transitions found for source state 'TRIAL' and target state 'EXPIRED'"
    }

    def "getTransition by source and target should error when multiple transitions exist"() {
        given:
        def smd = Transflux.defineStateMachine()
        smd.state(TRIAL).transitionsTo(ACTIVE, "trial-to-active").transitionsTo(ACTIVE, "trial-to-active-2")

        when:
        smd.getTransition(TRIAL.id, ACTIVE.id)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == "Multiple transitions found for source state 'TRIAL' and target state 'ACTIVE', use transition ID instead"
    }

    def "getTransition by id should return correct transition definition"() {
        given:
        def smd = Transflux.defineStateMachine()
        smd.state(TRIAL).transitionsTo(ACTIVE, "trial-to-active")
        smd.state(ACTIVE)

        expect:
        smd.getTransition("trial-to-active").with {
            id == "trial-to-active"
                && sourceStateId == TRIAL.id && targetStateId == ACTIVE.id
        }
    }

    def "getTransition by id should error when transition not found"() {
        given:
        def smd = Transflux.defineStateMachine()
        smd.state(TRIAL).transitionsTo(ACTIVE, "trial-to-active")
        smd.state(ACTIVE)

        when:
        smd.getTransition("NOPE")

        then:
        def e = thrown(TransfluxValidationException)
        e.message == "Transition 'NOPE' not found"
    }

    def "transition id must be unique"() {
        given:
        def smd = Transflux.defineStateMachine()
        smd.state(TRIAL).transitionsTo(ACTIVE, "DUP")

        when:
        smd.state(ACTIVE).transitionsTo(EXPIRED, "DUP")

        then:
        def e = thrown(TransfluxValidationException)
        e.message == "Transition ID DUP already defined"
    }

    @Unroll
    def "#method should override previous value"() {
        given:
        def smd = Transflux.defineStateMachine()
        
        when:
        smd."$method"(firstValue)."$method"(secondValue)
        
        then:
        smd."$getter"() == expectedValue

        where:
        method            | getter           | firstValue | secondValue | expectedValue
        'withName'        | 'getName'        | 'n1'       | 'n2'        | 'n2'
        'withDescription' | 'getDescription' | 'd1'       | 'd2'        | 'd2'
        'withVersion'     | 'getVersion'     | 'v1'       | 'v2'        | 'v2'
    }

    def "withStateResolver should reject null"() {
        given:
        def smd = Transflux.defineStateMachine()
        
        when:
        smd.withStateResolver(null)
        
        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'State resolver cannot be null'
    }

    def "withStateResolver should override previous state resolver"() {
        given:
        def smd = Transflux.defineStateMachine() as StateMachineDefImpl
        def r1 = { o -> 'A' } as StateResolver<Object>
        def r2 = { o -> 'B' } as StateResolver<Object>
        
        when:
        smd.withStateResolver(r1).withStateResolver(r2)
        
        then:
        smd.getStateResolver().resolveState(new Object()) == 'B'
    }

    def "state should reject duplicate state ID"() {
        given:
        def smd = Transflux.defineStateMachine()
        smd.state('S1')
        
        when:
        smd.state('S1')
        
        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'State ID S1 already defined'
    }

    def "state with Identifiable should reject null"() {
        given:
        def smd = Transflux.defineStateMachine()
        
        when:
        smd.state((Identifiable) null)
        
        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'State identifiable cannot be null'
    }

    def "build should return StateMachine instance"() {
        given:
        def smd = Transflux.defineStateMachine()
        smd.state('S1')
        
        when:
        def machine = smd.build()
        
        then:
        machine != null
    }

    def "getters should return correct values"() {
        given:
        def smd = Transflux.defineStateMachine() as StateMachineDefImpl
        smd.withName('N').withDescription('D').withVersion('1')
        smd.state('S1')
        
        expect:
        smd.getStates().keySet() == ['S1'] as Set
        smd.getTransitionsById().isEmpty()
        smd.getName() == 'N'
        smd.getDescription() == 'D'
        smd.getVersion() == '1'
    }

    @Unroll
    def "registerTransition should validate arguments: #scenario"() {
        given:
        def smd = Transflux.defineStateMachine() as StateMachineDefImpl
        
        when:
        smd.registerTransition(sourceStateId, targetStateId, transitionId)
        
        then:
        def e = thrown(TransfluxValidationException)
        e.message == expectedMessage

        where:
        scenario                  | sourceStateId | targetStateId | transitionId | expectedMessage
        'null source state ID'    | null          | 'T'           | 'X'          | 'Source state ID cannot be null or blank'
        'null target state ID'    | 'S'           | null          | 'X'          | 'Target state ID cannot be null or blank'
        'null transition ID'      | 'S'           | 'T'           | null         | 'Transition ID cannot be null or blank'
    }

    def "getTransition should error when source state not found"() {
        given:
        def smd = Transflux.defineStateMachine()
        smd.state(TRIAL).transitionsTo(ACTIVE, 't-1')
        
        when:
        smd.getTransition(EXPIRED.id, ACTIVE.id)
        
        then:
        def e = thrown(TransfluxValidationException)
        e.message == "Source state 'EXPIRED' not found"
    }
}
