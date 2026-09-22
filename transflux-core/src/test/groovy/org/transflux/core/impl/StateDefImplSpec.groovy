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

import org.transflux.core.Transflux
import org.transflux.core.exception.TransfluxValidationException
import spock.lang.Specification
import spock.lang.Unroll

class StateDefImplSpec extends Specification {

    @Unroll
    def 'constructor with String id should validate: #scenario'() {
        when:
        new StateDefImpl<Object>(smd, stateId)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == expectedMessage

        where:
        scenario               | smd                                                   | stateId | expectedMessage
        'null StateMachineDef' | null                                                  | 'X'     | 'State machine definition cannot be null'
        'blank state ID'       | Transflux.defineStateMachine() as StateMachineDefImpl | '  '    | 'State ID cannot be null or blank'
    }

    def 'withName should override previous name value'() {
        given:
        def smd = Transflux.defineStateMachine() as StateMachineDefImpl
        def s = new StateDefImpl<Object>(smd, 'S1')
        s.beginConfigurer()

        when:
        s.withName('name1').withName('name2')

        then:
        s.name == 'name2'
    }

    def 'withDescription should override previous description value'() {
        given:
        def smd = Transflux.defineStateMachine() as StateMachineDefImpl
        def s = new StateDefImpl<Object>(smd, 'S1')
        s.beginConfigurer()

        when:
        s.withDescription('desc1').withDescription('desc2')

        then:
        s.description == 'desc2'
    }

    def 'mutator should throw after configurer returns'() {
        given:
        def smd = Transflux.defineStateMachine() as StateMachineDefImpl
        def s = new StateDefImpl<Object>(smd, 'S')
        s.beginConfigurer()
        s.endConfigurer()

        when:
        s.withName('x')

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("'withName'")
        e.message.contains("'S'")
    }
}
