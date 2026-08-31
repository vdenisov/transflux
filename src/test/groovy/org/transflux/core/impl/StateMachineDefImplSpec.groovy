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
import org.transflux.core.condition.Condition
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.action.ContextMapper
import org.transflux.core.action.Action
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.ExecutingTransition
import org.transflux.core.transition.Transition
import spock.lang.Specification
import spock.lang.Unroll

import java.util.function.Consumer
import java.util.function.Predicate

import static org.transflux.core.TestStateEnum.ACTIVE
import static org.transflux.core.TestStateEnum.EXPIRED
import static org.transflux.core.TestStateEnum.TRIAL

class StateMachineDefImplSpec extends Specification {

    def "getTransition by id should return correct transition definition"() {
        given:
        def smd = Transflux.defineStateMachine()
        smd.state(TRIAL.id, { s -> s.transitionsTo(ACTIVE.id, "trial-to-active", {}) })
        smd.state(ACTIVE.id, {})

        expect:
        smd.getTransition("trial-to-active").with {
            id == "trial-to-active"
                && sourceStateId == TRIAL.id && targetStateId == ACTIVE.id
        }
    }

    def "getTransition by id should error when transition not found"() {
        given:
        def smd = Transflux.defineStateMachine()
        smd.state(TRIAL.id, { s -> s.transitionsTo(ACTIVE.id, "trial-to-active", {}) })
        smd.state(ACTIVE.id, {})

        when:
        smd.getTransition("NOPE")

        then:
        def e = thrown(TransfluxValidationException)
        e.message == "Transition 'NOPE' not found"
    }

    def "transition id must be unique"() {
        given:
        def smd = Transflux.defineStateMachine()
        smd.state(TRIAL.id, { s -> s.transitionsTo(ACTIVE.id, "DUP", {}) })

        when:
        smd.state(ACTIVE.id, { s -> s.transitionsTo(EXPIRED.id, "DUP", {}) })

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

    def "withStateApplier should reject null"() {
        given:
        def smd = Transflux.defineStateMachine()

        when:
        smd.withStateApplier(null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'State applier cannot be null'
    }

    def "withStateApplier should override previous state applier"() {
        given:
        def smd = Transflux.defineStateMachine() as StateMachineDefImpl
        def captured = []
        def a1 = { e, s -> captured << ('a1:' + s) } as StateApplier<Object>
        def a2 = { e, s -> captured << ('a2:' + s) } as StateApplier<Object>

        when:
        smd.withStateApplier(a1).withStateApplier(a2)
        smd.getStateApplier().applyState(new Object(), 'X')

        then:
        captured == ['a2:X']
    }

    def "state applier should propagate to built state machine"() {
        given:
        def smd = Transflux.defineStateMachine() as StateMachineDefImpl
        def applier = { e, s -> } as StateApplier<Object>
        smd.withStateApplier(applier).state('s', {})

        when:
        def machine = smd.build() as StateMachineImpl

        then:
        machine.getStateApplier().is(applier)
    }

    def "build should leave state applier null when not configured"() {
        given:
        def smd = Transflux.defineStateMachine() as StateMachineDefImpl
        smd.state('s', {})

        when:
        def machine = smd.build() as StateMachineImpl

        then:
        machine.getStateApplier() == null
    }

    def "state should reject duplicate state ID"() {
        given:
        def smd = Transflux.defineStateMachine()
        smd.state('S1', {})

        when:
        smd.state('S1', {})

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'State ID S1 already defined'
    }

    def "build should return StateMachine instance"() {
        given:
        def smd = Transflux.defineStateMachine()
        smd.state('S1', {})

        when:
        def machine = smd.build()

        then:
        machine != null
    }

    def "getters should return correct values"() {
        given:
        def smd = Transflux.defineStateMachine() as StateMachineDefImpl
        smd.withName('N').withDescription('D').withVersion('1')
        smd.state('S1', {})

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
        scenario               | sourceStateId | targetStateId | transitionId | expectedMessage
        'null source state ID' | null          | 'T'           | 'X'          | 'Source state ID cannot be null or blank'
        'null target state ID' | 'S'           | null          | 'X'          | 'Target state ID cannot be null or blank'
        'null transition ID'   | 'S'           | 'T'           | null         | 'Transition ID cannot be null or blank'
    }

    def "forEntityType should reject null"() {
        given:
        def smd = Transflux.defineStateMachine()

        when:
        smd.forEntityType(null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Entity type cannot be null'
    }

    def "step(id, Class, Consumer) registers an SM-level operation invokable by id, with metadata on the def"() {
        given:
        def ran = []
        def captured = null
        def smd = Transflux.<Object> defineStateMachine()
            .forEntityType(Object)
            .withStateResolver({ e -> TRIAL.id } as StateResolver<Object>)
        smd.step('op', Object, { d ->
            captured = d
            d.withName('N').withDescription('D').using({ e, c, t -> ran << 'op' } as Action)
        })
        smd.state(TRIAL.id, { s -> s.transitionsTo(ACTIVE.id, 't1', { t ->
            t.operation('wrap', { c -> c.run('op') })
        }) })
        smd.state(ACTIVE.id, {})
        def sm = smd.build()

        when:
        def result = sm.entity(new Object()).transitionTo(ACTIVE.id)

        then: 'the SM-level operation runs through the composite reference'
        result.success
        ran == ['op']

        and: 'metadata + context type live on the def'
        captured.getId() == 'op'
        captured.getName() == 'N'
        captured.getDescription() == 'D'
    }

    def "step(id, Class, Consumer) rejects post-configurer mutation of the captured def"() {
        given:
        def captured = null
        def smd = Transflux.<Object> defineStateMachine().forEntityType(Object)
        smd.step('op', Object, { d -> captured = d; d.using({ e, c, t -> } as Action) })

        when:
        captured.using({ e, c, t -> } as Action)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("step 'op'")
        e.message.contains('after its configurer has returned')
    }


    static class IdOverloadStep implements Action<Object, Object> {
        @Override
        void execute(Object e, Object c, ExecutingTransition<Object, Object> t) {}
    }

    static class IdOverloadCondition implements Condition<Object, Object> {
        @Override
        boolean test(Object e, Object c, Transition t) { true }
    }

    static class IdOverloadOperation implements Action<Object, Object> {
        @Override
        void execute(Object e, Object c, ExecutingTransition<Object, Object> t) {}
    }

    static class IdOverloadMapper implements ContextMapper<Object, Object> {
        @Override
        Object mapTo(Object p) { p }
    }
}
