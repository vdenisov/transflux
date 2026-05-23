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
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.state.StateResolver
import spock.lang.Specification
import spock.lang.Unroll

import static org.transflux.core.TestStateEnum.ACTIVE
import static org.transflux.core.TestStateEnum.EXPIRED
import static org.transflux.core.TestStateEnum.TRIAL

class StateDefImplSpec extends Specification {

    def 'transitionsTo should create TransitionDef correctly using Identifiable'() {
        given:
        def smd = Transflux.defineStateMachine() as StateMachineDefImpl
        def s = new StateDefImpl<Object>(smd, ACTIVE)
        s.beginConfigurer()

        when:
        s.transitionsTo(EXPIRED, "active-to-expired", {})

        then:
        smd.getTransition("active-to-expired").with {
            id == "active-to-expired"
                && sourceStateId == ACTIVE.id
                && targetStateId == EXPIRED.id
        }
    }

    def 'transitionsTo should create TransitionDef correctly using string ID'() {
        given:
        def smd = Transflux.defineStateMachine() as StateMachineDefImpl
        def s = new StateDefImpl<Object>(smd, ACTIVE)
        s.beginConfigurer()

        when:
        s.transitionsTo("EXPIRED", "active-to-expired", {})

        then:
        smd.getTransition("active-to-expired").with {
            id == "active-to-expired"
                && sourceStateId == ACTIVE.id
                && targetStateId == EXPIRED.id
        }
    }

    def 'transitionsTo should prevent duplicate transition id'() {
        given:
        def smd = Transflux.defineStateMachine() as StateMachineDefImpl
        def s = new StateDefImpl<>(smd, ACTIVE)
        s.beginConfigurer()
        s.transitionsTo(EXPIRED, "active-to-expired", {})

        when:
        s.transitionsTo(TRIAL, "active-to-expired", {})

        then:
        def e = thrown(TransfluxValidationException)
        e.message == "Transition ID active-to-expired already defined"
    }

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
        'blank state ID'       | Transflux.defineStateMachine() as StateMachineDefImpl | '  ' | 'State ID cannot be null or blank'
    }

    @Unroll
    def 'constructor with Identifiable should validate: #scenario'() {
        when:
        new StateDefImpl<Object>(smd, identifiable)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == expectedMessage

        where:
        scenario                     | smd                                                   | identifiable                                         | expectedMessage
        'null StateMachineDef'       | null                                                  | ACTIVE                                               | 'State machine definition cannot be null'
        'null identifiable'          | Transflux.defineStateMachine() as StateMachineDefImpl | null                                                 | 'Identifiable for state ID cannot be null'
        'identifiable with blank id' | Transflux.defineStateMachine() as StateMachineDefImpl | new Identifiable() { String getId() { return ' ' } } | 'State ID cannot be null or blank'
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

    def 'transitionsTo should reject null Identifiable target'() {
        given:
        def smd = Transflux.defineStateMachine() as StateMachineDefImpl
        def s = new StateDefImpl<Object>(smd, 'S')
        s.beginConfigurer()
        when:
        s.transitionsTo((Identifiable) null, 't', {})
        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Target state identifiable cannot be null'
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

    def 'transitionsTo(String target, Identifiable transition, Consumer) registers the transition under the identifiable id'() {
        given:
        def smd = new StateMachineDefImpl<>()
        smd.forEntityType(Object)
        smd.state('s2', {})

        when:
        smd.state('s1', { s ->
            s.transitionsTo('s2', identifiable('t1'), { t -> })
        })

        then:
        smd.getTransition('t1') != null
    }

    def 'transitionsTo(String target, Identifiable transition, Class<C>, Consumer) registers a typed transition'() {
        given:
        def smd = new StateMachineDefImpl<>()
        smd.forEntityType(Object)
        smd.state('s2', {})

        when:
        smd.state('s1', { s ->
            s.transitionsTo('s2', identifiable('t1'), String, { t -> })
        })

        then:
        smd.getTransition('t1') != null
        smd.getTransition('t1').contextType == String
    }

    def 'transitionsTo(Identifiable target, Identifiable transition, Consumer) registers the transition'() {
        given:
        def smd = new StateMachineDefImpl<>()
        smd.forEntityType(Object)
        smd.state('s2', {})

        when:
        smd.state('s1', { s ->
            s.transitionsTo(identifiable('s2'), identifiable('t1'), { t -> })
        })

        then:
        smd.getTransition('t1') != null
        smd.getTransition('t1').targetStateId == 's2'
    }

    def 'transitionsTo(Identifiable target, Identifiable transition, Class<C>, Consumer) registers a typed transition'() {
        given:
        def smd = new StateMachineDefImpl<>()
        smd.forEntityType(Object)
        smd.state('s2', {})

        when:
        smd.state('s1', { s ->
            s.transitionsTo(identifiable('s2'), identifiable('t1'), String, { t -> })
        })

        then:
        smd.getTransition('t1') != null
        smd.getTransition('t1').contextType == String
    }

    @Unroll
    def 'transitionsTo Identifiable overloads reject null'() {
        given:
        def smd = new StateMachineDefImpl<>()
        smd.forEntityType(Object)
        smd.state('s2', {})
        Throwable caught = null

        when:
        try {
            smd.state('s1', { s ->
                action.call(s)
            })
        } catch (Throwable t) {
            caught = t
        }

        then:
        caught instanceof TransfluxValidationException

        where:
        action << [
            { s -> s.transitionsTo('s2', (Identifiable) null, { t -> }) },
            { s -> s.transitionsTo('s2', (Identifiable) null, String, { t -> }) },
            { s -> s.transitionsTo((Identifiable) null, identifiable('t1'), { t -> }) },
            { s -> s.transitionsTo((Identifiable) null, identifiable('t1'), String, { t -> }) },
            { s -> s.transitionsTo(identifiable('s2'), (Identifiable) null, { t -> }) },
            { s -> s.transitionsTo(identifiable('s2'), (Identifiable) null, String, { t -> }) },
        ]
    }

    def 'transitionsTo(target, id, configurer) defaults the transition context to Object'() {
        given:
        def smd = new StateMachineDefImpl<CtxBoundEntity>()
        smd.forEntityType(CtxBoundEntity)
            .withStateResolver({ e -> e.state } as StateResolver<CtxBoundEntity>)
            .state('s1', { s -> s.transitionsTo('s2', 't', {}) })
            .state('s2', {})

        when:
        def td = smd.getTransition('t')

        then:
        td.getContextType() == Object
    }

    def 'transitionsTo(target, id, Class, configurer) pre-binds the transition context'() {
        given:
        def smd = new StateMachineDefImpl<CtxBoundEntity>()
        smd.forEntityType(CtxBoundEntity)
            .withStateResolver({ e -> e.state } as StateResolver<CtxBoundEntity>)
            .state('s1', { s -> s.transitionsTo('s2', 't', CtxBoundA, {}) })
            .state('s2', {})

        when:
        def td = smd.getTransition('t')

        then:
        td.getContextType() == CtxBoundA
    }

    def 'pre-bound transition rejects fire calls with a wrong context type'() {
        given:
        def smd = new StateMachineDefImpl<CtxBoundEntity>()
        smd.forEntityType(CtxBoundEntity)
            .withStateResolver({ e -> e.state } as StateResolver<CtxBoundEntity>)
            .state('s1', { s -> s.transitionsTo('s2', 't', CtxBoundA, {}) })
            .state('s2', {})
        def sm = smd.build()

        when:
        sm.entity(new CtxBoundEntity('s1')).transitionTo('s2', new CtxBoundB())

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('CtxBoundA')
        e.message.contains('CtxBoundB')
    }

    def 'pre-bound transition accepts fire calls with the matching context type'() {
        given:
        def smd = new StateMachineDefImpl<CtxBoundEntity>()
        smd.forEntityType(CtxBoundEntity)
            .withStateResolver({ e -> e.state } as StateResolver<CtxBoundEntity>)
            .state('s1', { s -> s.transitionsTo('s2', 't', CtxBoundA, {}) })
            .state('s2', {})
        def sm = smd.build()

        when:
        def result = sm.entity(new CtxBoundEntity('s1')).transitionTo('s2', new CtxBoundA())

        then:
        result.success
    }

    def 'transitionsTo with null contextType raises validation error'() {
        given:
        def smd = new StateMachineDefImpl<CtxBoundEntity>()
        smd.forEntityType(CtxBoundEntity)
            .withStateResolver({ e -> e.state } as StateResolver<CtxBoundEntity>)

        when:
        smd.state('s1', { s -> s.transitionsTo('s2', 't', (Class) null, {}) })

        then:
        thrown(TransfluxValidationException)
    }

    private static Identifiable identifiable(String value) {
        return { -> value } as Identifiable
    }

    static class CtxBoundEntity {
        String state

        CtxBoundEntity(String state) { this.state = state }
    }

    static class CtxBoundA { }

    static class CtxBoundB { }
}
