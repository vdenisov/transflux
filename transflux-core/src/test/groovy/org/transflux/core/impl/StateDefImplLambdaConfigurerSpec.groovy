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
import spock.lang.Specification
import spock.lang.Unroll

import java.util.function.Consumer

import static org.transflux.core.TestStateEnum.TRIAL

class StateDefImplLambdaConfigurerSpec extends Specification {

    def 'StateDef captured outside the configurer is inert'() {
        given:
        def smd = Transflux.defineStateMachine() as StateMachineDefImpl
        StateDef<Object> captured = null
        smd.state(TRIAL.id, { s -> captured = s })

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
        smd.state(TRIAL.id, { s -> captured = s })

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
        'disableGlobalListener'     || { StateDef s -> s.disableGlobalListener('g') }
        'disableGlobalListeners'    || { StateDef s -> s.disableGlobalListeners('g', 'h') }
        'disableAllGlobalListeners' || { StateDef s -> s.disableAllGlobalListeners() }
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
