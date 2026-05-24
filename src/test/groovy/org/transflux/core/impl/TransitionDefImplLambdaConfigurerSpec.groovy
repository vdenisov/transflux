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
import org.transflux.core.operation.Operation
import org.transflux.core.transition.Transition
import org.transflux.core.transition.TransitionDef
import spock.lang.Specification
import spock.lang.Unroll

import java.util.function.Consumer

import static org.transflux.core.TestStateEnum.ACTIVE
import static org.transflux.core.TestStateEnum.TRIAL

class TransitionDefImplLambdaConfigurerSpec extends Specification {

    static class Ctx {}

    static class NoopOp implements Operation<Object, Object> {
        @Override void execute(Object entity, Object context, Transition<Object, Object> transition) {}
    }

    def 'untyped transitionsTo configurer defaults context to Object'() {
        given:
        def smd = Transflux.defineStateMachine() as StateMachineDefImpl

        when:
        smd.state(TRIAL, { s -> s.transitionsTo(ACTIVE, 't1', { t -> t.withName('plain') }) })

        then:
        def td = smd.getTransition('t1')
        td.contextType == Object
        td.name == 'plain'
    }

    def 'typed transitionsTo configurer carries the declared context type'() {
        given:
        def smd = Transflux.defineStateMachine() as StateMachineDefImpl

        when:
        smd.state(TRIAL, { s -> s.transitionsTo(ACTIVE, 't1', Ctx, { t -> }) })

        then:
        smd.getTransition('t1').contextType == Ctx
    }

    def 'usingContext inside the configurer re-types the transition'() {
        given:
        def smd = Transflux.defineStateMachine() as StateMachineDefImpl

        when:
        smd.state(TRIAL, { s -> s.transitionsTo(ACTIVE, 't1', { t -> t.usingContext(Ctx) }) })

        then:
        smd.getTransition('t1').contextType == Ctx
    }

    def 'configurer accepts every mutator family'() {
        given:
        def smd = Transflux.defineStateMachine() as StateMachineDefImpl

        when:
        smd.state(TRIAL, { s ->
            s.transitionsTo(ACTIVE, 't-simple', { t ->
                t.withName('n').withDescription('d')
                t.simpleOperation('op', new NoopOp())
                t.preCondition('pre', { e -> true })
                t.postCondition('post', { e -> true })
            })
            s.transitionsTo(ACTIVE, 't-composite', { t -> t.compositeOperation('co', { co -> }) })
        })

        then:
        def simple = smd.getTransition('t-simple')
        simple.name == 'n'
        simple.description == 'd'
        simple.operationDef != null
        simple.preConditionDescriptors.size() == 1
        simple.postConditionDescriptors.size() == 1
        smd.getTransition('t-composite').operationDef != null
    }

    @Unroll
    def 'captured TransitionDef rejects #operation after configurer returns'() {
        given:
        def smd = Transflux.defineStateMachine() as StateMachineDefImpl
        TransitionDef<Object, Object> captured = null
        smd.state(TRIAL, { s -> s.transitionsTo(ACTIVE, 't1', { t -> captured = t }) })

        when:
        action.call(captured)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("'${operation}'")
        e.message.contains("'t1'")

        where:
        operation            || action
        'withName'           || { TransitionDef t -> t.withName('x') }
        'withDescription'    || { TransitionDef t -> t.withDescription('x') }
        'usingContext'       || { TransitionDef t -> t.usingContext(Ctx) }
        'simpleOperation'    || { TransitionDef t -> t.simpleOperation('op', new NoopOp()) }
        'compositeOperation' || { TransitionDef t -> t.compositeOperation('co', { c -> }) }
        'preCondition'       || { TransitionDef t -> t.preCondition('p', { ent -> true }) }
        'postCondition'      || { TransitionDef t -> t.postCondition('p', { ent -> true }) }
    }

    def 'null configurer is rejected with a clear message'() {
        given:
        def smd = Transflux.defineStateMachine() as StateMachineDefImpl

        when:
        smd.state(TRIAL, { s ->
            s.transitionsTo(ACTIVE.id, 't1', (Consumer<TransitionDef<Object, Object>>) null)
        })

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Transition configurer cannot be null'
    }
}
