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

class StateImplSpec extends Specification {

    def 'constructor should create DefaultState with valid StateDef'() {
        given:
        def smd = Transflux.stateMachineFor(Object) as StateMachineDefImpl
        def stateDef = new StateDefImpl<Object>(smd, 'state1')
            .withName('State Name')
            .withDescription('State Description')

        when:
        def state = new StateImpl(stateDef)

        then:
        state.id == 'state1'
        state.name == 'State Name'
        state.description == 'State Description'
    }

    def 'constructor should validate null StateDef'() {
        when:
        new StateImpl(null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'State definition cannot be null'
    }

    @Unroll
    def 'getter #getter should return #expected'() {
        given:
        def smd = Transflux.stateMachineFor(Object) as StateMachineDefImpl
        def stateDef = new StateDefImpl<Object>(smd, id)
        if (name != null) {
            stateDef = stateDef.withName(name)
        }
        if (description != null) {
            stateDef = stateDef.withDescription(description)
        }
        def state = new StateImpl(stateDef)

        when:
        def result = state."$getter"()

        then:
        result == expected

        where:
        getter           | id              | name              | description              | expected
        'getId'          | 'test-state-id' | null              | null                     | 'test-state-id'
        'getName'        | 'state1'        | 'Test State Name' | null                     | 'Test State Name'
        'getName'        | 'state1'        | null              | null                     | null
        'getDescription' | 'state1'        | null              | 'Test State Description' | 'Test State Description'
        'getDescription' | 'state1'        | null              | null                     | null
    }

    def 'toString should include all fields'() {
        given:
        def smd = Transflux.stateMachineFor(Object) as StateMachineDefImpl
        def stateDef = new StateDefImpl<Object>(smd, 'state1')
            .withName('Test Name')
            .withDescription('Test Description')
        def state = new StateImpl(stateDef)

        when:
        def result = state.toString()

        then:
        result == "StateImpl{id='state1', name='Test Name', description='Test Description'}"
    }

    def 'equals should return true for states with same ID'() {
        given:
        def smd = Transflux.stateMachineFor(Object) as StateMachineDefImpl
        def stateDef1 = new StateDefImpl<Object>(smd, 'same-id').withName('Name1')
        def stateDef2 = new StateDefImpl<Object>(smd, 'same-id').withName('Name2')
        def state1 = new StateImpl(stateDef1)
        def state2 = new StateImpl(stateDef2)

        when:
        def result = state1.equals(state2)

        then:
        result == true
    }

    def 'equals should return false for states with different IDs'() {
        given:
        def smd = Transflux.stateMachineFor(Object) as StateMachineDefImpl
        def stateDef1 = new StateDefImpl<Object>(smd, 'id1')
        def stateDef2 = new StateDefImpl<Object>(smd, 'id2')
        def state1 = new StateImpl(stateDef1)
        def state2 = new StateImpl(stateDef2)

        when:
        def result = state1.equals(state2)

        then:
        result == false
    }

    @Unroll
    def 'equals should return false for #description'() {
        given:
        def smd = Transflux.stateMachineFor(Object) as StateMachineDefImpl
        def stateDef = new StateDefImpl<Object>(smd, 'state1')
        def state = new StateImpl(stateDef)

        when:
        def result = state.equals(otherObject)

        then:
        result == false

        where:
        description             | otherObject
        'non-StateImpl objects' | 'not-a-state'
        'null'                  | null
    }

    def 'hashCode should return same value for states with same ID'() {
        given:
        def smd = Transflux.stateMachineFor(Object) as StateMachineDefImpl
        def stateDef1 = new StateDefImpl<Object>(smd, 'same-id').withName('Name1')
        def stateDef2 = new StateDefImpl<Object>(smd, 'same-id').withName('Name2')
        def state1 = new StateImpl(stateDef1)
        def state2 = new StateImpl(stateDef2)

        when:
        def hashCode1 = state1.hashCode()
        def hashCode2 = state2.hashCode()

        then:
        hashCode1 == hashCode2
    }

    def 'hashCode should return different values for states with different IDs'() {
        given:
        def smd = Transflux.stateMachineFor(Object) as StateMachineDefImpl
        def stateDef1 = new StateDefImpl<Object>(smd, 'id1')
        def stateDef2 = new StateDefImpl<Object>(smd, 'id2')
        def state1 = new StateImpl(stateDef1)
        def state2 = new StateImpl(stateDef2)

        when:
        def hashCode1 = state1.hashCode()
        def hashCode2 = state2.hashCode()

        then:
        hashCode1 != hashCode2
    }

}