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
import org.transflux.core.state.StateDef
import org.transflux.core.transition.TransitionDef
import spock.lang.Specification
import spock.lang.Unroll

import java.util.function.Consumer

import static org.transflux.core.TestStateEnum.ACTIVE
import static org.transflux.core.TestStateEnum.EXPIRED
import static org.transflux.core.TestStateEnum.TRIAL

class StateDefImplLambdaConfigurerSpec extends Specification {

    def 'configurer wires transitions via String target overload'() {
        given:
        def smd = Transflux.defineStateMachine() as StateMachineDefImpl

        when:
        smd.state(TRIAL.id, { s -> s.transitionsTo(ACTIVE.id, 'trial-to-active', {}) })

        then:
        smd.getTransition('trial-to-active').with {
            it.id == 'trial-to-active' && it.sourceStateId == TRIAL.id && it.targetStateId == ACTIVE.id
        }
    }

    def 'configurer wires transitions via Identifiable target overload'() {
        given:
        def smd = Transflux.defineStateMachine() as StateMachineDefImpl

        when:
        smd.state(TRIAL, { s -> s.transitionsTo(ACTIVE, 'trial-to-active', {}) })

        then:
        smd.getTransition('trial-to-active').sourceStateId == TRIAL.id
    }

    def 'configurer wires a typed-context transition'() {
        given:
        def smd = Transflux.defineStateMachine() as StateMachineDefImpl

        when:
        smd.state(TRIAL, { s -> s.transitionsTo(ACTIVE, 'trial-to-active', String, { t -> }) })

        then:
        smd.getTransition('trial-to-active').contextType == String
    }

    def 'StateDef captured outside the configurer is inert'() {
        given:
        def smd = Transflux.defineStateMachine() as StateMachineDefImpl
        StateDef<Object> captured = null
        smd.state(TRIAL, { s -> captured = s })

        when:
        captured.withName('late')

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("'withName'")
        e.message.contains("'${TRIAL.id}'")
    }

    @Unroll
    def 'captured StateDef rejects #operation after configurer returns'() {
        given:
        def smd = Transflux.defineStateMachine() as StateMachineDefImpl
        StateDef<Object> captured = null
        smd.state(TRIAL, { s -> captured = s })

        when:
        action.call(captured)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("'${operation}'")
        e.message.contains("'${TRIAL.id}'")

        where:
        operation         || action
        'withName'        || { StateDef s -> s.withName('x') }
        'withDescription' || { StateDef s -> s.withDescription('x') }
        'transitionsTo'   || { StateDef s -> s.transitionsTo(ACTIVE.id, 't1', {}) }
    }

    def 'nested transitionsTo configurer succeeds while StateDef guard remains active'() {
        given:
        def smd = Transflux.defineStateMachine() as StateMachineDefImpl

        when:
        smd.state(TRIAL, { s ->
            s.transitionsTo(ACTIVE, 'trial-to-active', { t -> t.withName('inner') })
            s.transitionsTo(EXPIRED, 'trial-to-expired', {})
        })

        then:
        smd.getTransition('trial-to-active').name == 'inner'
        smd.getTransition('trial-to-expired') != null
    }

    def 'captured inner TransitionDef is inert after its configurer returns'() {
        given:
        def smd = Transflux.defineStateMachine() as StateMachineDefImpl
        TransitionDef<Object, Object> capturedT = null
        smd.state(TRIAL, { s ->
            s.transitionsTo(ACTIVE, 'trial-to-active', { t -> capturedT = t })
        })

        when:
        capturedT.withName('late')

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("'withName'")
        e.message.contains("'trial-to-active'")
    }

    def 'null configurer is rejected'() {
        given:
        def smd = Transflux.defineStateMachine()

        when:
        smd.state(TRIAL.id, (Consumer<StateDef<Object>>) null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'State configurer cannot be null'
    }
}
