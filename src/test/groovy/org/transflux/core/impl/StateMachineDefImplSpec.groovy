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

import org.transflux.core.Identifiable
import org.transflux.core.Transflux
import org.transflux.core.condition.Condition
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.operation.ContextMapper
import org.transflux.core.operation.Operation
import org.transflux.core.operation.Step
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.Transition
import spock.lang.Specification
import spock.lang.Unroll

import java.util.function.Function
import java.util.function.Predicate

import static org.transflux.core.TestStateEnum.ACTIVE
import static org.transflux.core.TestStateEnum.EXPIRED
import static org.transflux.core.TestStateEnum.TRIAL

class StateMachineDefImplSpec extends Specification {

    def "getTransition by id should return correct transition definition"() {
        given:
        def smd = Transflux.defineStateMachine()
        smd.state(TRIAL, { s -> s.transitionsTo(ACTIVE, "trial-to-active", {}) })
        smd.state(ACTIVE, {})

        expect:
        smd.getTransition("trial-to-active").with {
            id == "trial-to-active"
                && sourceStateId == TRIAL.id && targetStateId == ACTIVE.id
        }
    }

    def "getTransition by id should error when transition not found"() {
        given:
        def smd = Transflux.defineStateMachine()
        smd.state(TRIAL, { s -> s.transitionsTo(ACTIVE, "trial-to-active", {}) })
        smd.state(ACTIVE, {})

        when:
        smd.getTransition("NOPE")

        then:
        def e = thrown(TransfluxValidationException)
        e.message == "Transition 'NOPE' not found"
    }

    def "transition id must be unique"() {
        given:
        def smd = Transflux.defineStateMachine()
        smd.state(TRIAL, { s -> s.transitionsTo(ACTIVE, "DUP", {}) })

        when:
        smd.state(ACTIVE, { s -> s.transitionsTo(EXPIRED, "DUP", {}) })

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

    def "state with Identifiable should reject null"() {
        given:
        def smd = Transflux.defineStateMachine()

        when:
        smd.state((Identifiable) null, {})

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'State identifiable cannot be null'
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
        scenario                  | sourceStateId | targetStateId | transitionId | expectedMessage
        'null source state ID'    | null          | 'T'           | 'X'          | 'Source state ID cannot be null or blank'
        'null target state ID'    | 'S'           | null          | 'X'          | 'Target state ID cannot be null or blank'
        'null transition ID'      | 'S'           | 'T'           | null         | 'Transition ID cannot be null or blank'
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

    @Unroll
    def 'step Identifiable overload accepted: #variant'() {
        given:
        def smd = new StateMachineDefImpl<>()
        smd.forEntityType(Object)

        when:
        action.call(smd)

        then:
        notThrown(Exception)

        where:
        variant                                | action
        'step(Id, Step)'                       | { d -> d.step(identifiable('s1'), new IdOverloadStep()) }
        'step(Id, Class)'                      | { d -> d.step(identifiable('s2'), IdOverloadStep) }
        'step(Id, Class<C>, Step)'             | { d -> d.step(identifiable('s3'), Object, new IdOverloadStep()) }
        'step(Id, Class<C>, Class)'            | { d -> d.step(identifiable('s4'), Object, IdOverloadStep) }
    }

    @Unroll
    def 'condition Identifiable overload accepted: #variant'() {
        given:
        def smd = new StateMachineDefImpl<>()
        smd.forEntityType(Object)

        when:
        action.call(smd)

        then:
        notThrown(Exception)

        where:
        variant                                          | action
        'condition(Id, Condition)'                       | { d -> d.condition(identifiable('c1'), new IdOverloadCondition()) }
        'condition(Id, Class)'                           | { d -> d.condition(identifiable('c2'), IdOverloadCondition) }
        'condition(Id, Predicate)'                       | { d -> d.condition(identifiable('c3'), { e -> true } as Predicate) }
        'condition(Id, String spel)'                     | { d -> d.condition(identifiable('c4'), 'true') }
        'condition(Id, Class<C>, Condition)'             | { d -> d.condition(identifiable('c5'), Object, new IdOverloadCondition()) }
        'condition(Id, Class<C>, Class)'                 | { d -> d.condition(identifiable('c6'), Object, IdOverloadCondition) }
        'conditionPredicate(Id, Class<C>, Predicate)'    | { d -> d.conditionPredicate(identifiable('c7'), Object, { e -> true } as Predicate) }
        'conditionExpression(Id, Class<C>, String)'      | { d -> d.conditionExpression(identifiable('c8'), Object, 'true') }
    }

    @Unroll
    def 'operation/composite/mapper Identifiable overload accepted: #variant'() {
        given:
        def smd = new StateMachineDefImpl<>()
        smd.forEntityType(Object)

        when:
        action.call(smd)

        then:
        notThrown(Exception)

        where:
        variant                                          | action
        'compositeOperation(Id, Class<C>, Consumer)'     | { d -> d.compositeOperation(identifiable('co1'), Object, { c -> c.step('any') }) }
        'operation(Id, Class<C>, Operation)'             | { d -> d.operation(identifiable('o1'), Object, new IdOverloadOperation()) }
        'operation(Id, Class<C>, Class)'                 | { d -> d.operation(identifiable('o2'), Object, IdOverloadOperation) }
        'mapper(Id, parent, child, ContextMapper)'       | { d -> d.mapper(identifiable('m1'), Object, Object, new IdOverloadMapper()) }
        'mapper(Id, parent, child, Class)'               | { d -> d.mapper(identifiable('m2'), Object, Object, IdOverloadMapper) }
        'mapper(Id, parent, child, Function)'            | { d -> d.mapper(identifiable('m3'), Object, Object, { p -> p } as Function) }
    }

    @Unroll
    def 'Identifiable overload rejects null: #variant'() {
        given:
        def smd = new StateMachineDefImpl<>()
        smd.forEntityType(Object)

        when:
        action.call(smd)

        then:
        thrown(TransfluxValidationException)

        where:
        variant                                          | action
        'step(null, Step)'                               | { d -> d.step((Identifiable) null, new IdOverloadStep()) }
        'step(null, Class)'                              | { d -> d.step((Identifiable) null, IdOverloadStep) }
        'condition(null, Condition)'                     | { d -> d.condition((Identifiable) null, new IdOverloadCondition()) }
        'condition(null, Class)'                         | { d -> d.condition((Identifiable) null, IdOverloadCondition) }
        'condition(null, Predicate)'                     | { d -> d.condition((Identifiable) null, { e -> true } as Predicate) }
        'condition(null, String spel)'                   | { d -> d.condition((Identifiable) null, 'true') }
        'compositeOperation(null, Class, Consumer)'      | { d -> d.compositeOperation((Identifiable) null, Object, { c -> c.step('x') }) }
        'operation(null, Class, Operation)'              | { d -> d.operation((Identifiable) null, Object, new IdOverloadOperation()) }
        'mapper(null, parent, child, ContextMapper)'     | { d -> d.mapper((Identifiable) null, Object, Object, new IdOverloadMapper()) }
    }

    def "simpleOperation(id, Class, Consumer) registers an SM-level operation invokable by id, with metadata on the def"() {
        given:
        def ran = []
        def captured = null
        def smd = Transflux.<Object> defineStateMachine()
            .forEntityType(Object)
            .withStateResolver({ e -> TRIAL.id } as StateResolver<Object>)
        smd.simpleOperation('op', Object, { d ->
            captured = d
            d.withName('N').withDescription('D').using({ e, c, t -> ran << 'op' } as Operation)
        })
        smd.state(TRIAL, { s -> s.transitionsTo(ACTIVE, 't1', { t ->
            t.compositeOperation('wrap', { c -> c.operation('op') })
        }) })
        smd.state(ACTIVE, {})
        def sm = smd.build()

        when:
        def result = sm.entity(new Object()).transitionTo(ACTIVE)

        then: 'the SM-level operation runs through the composite reference'
        result.success
        ran == ['op']

        and: 'metadata + context type live on the def'
        captured.getId() == 'op'
        captured.getName() == 'N'
        captured.getDescription() == 'D'
    }

    def "simpleOperation(id, Class, Consumer) rejects post-configurer mutation of the captured def"() {
        given:
        def captured = null
        def smd = Transflux.<Object> defineStateMachine().forEntityType(Object)
        smd.simpleOperation('op', Object, { d -> captured = d; d.using({ e, c, t -> } as Operation) })

        when:
        captured.using({ e, c, t -> } as Operation)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("operation 'op'")
        e.message.contains('after its configurer has returned')
    }

    private static Identifiable identifiable(String value) {
        return { -> value } as Identifiable
    }

    static class IdOverloadStep implements Step<Object, Object> {
        @Override
        void execute(Object e, Object c, Transition<Object, Object> t) {}
    }

    static class IdOverloadCondition implements Condition<Object, Object> {
        @Override
        boolean test(Object e, Object c, Transition<Object, Object> t) { true }
    }

    static class IdOverloadOperation implements Operation<Object, Object> {
        @Override
        void execute(Object e, Object c, Transition<Object, Object> t) {}
    }

    static class IdOverloadMapper implements ContextMapper<Object, Object> {
        @Override
        Object mapTo(Object p) { p }
    }
}
