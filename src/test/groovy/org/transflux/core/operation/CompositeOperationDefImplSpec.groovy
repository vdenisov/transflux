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

package org.transflux.core.operation

import org.transflux.core.state.State
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateDef
import org.transflux.core.state.StateDefImpl
import org.transflux.core.state.StateImpl
import org.transflux.core.state.StateResolver

import org.transflux.core.Identifiable
import org.transflux.core.StateMachine
import org.transflux.core.StateMachineDef
import org.transflux.core.StateMachineDefImpl
import org.transflux.core.StateMachineImpl
import org.transflux.core.TestContext
import org.transflux.core.TestStateEnum
import org.transflux.core.Transflux
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.transition.Transition
import org.transflux.core.transition.TransitionDef
import org.transflux.core.transition.TransitionDefImpl
import org.transflux.core.transition.TransitionImpl
import org.transflux.core.transition.TransitionResult
import org.transflux.core.transition.TransitionView

import spock.lang.Specification

import static org.transflux.core.TestStateEnum.*

class CompositeOperationDefImplSpec extends Specification {

    static class TestEntity {
        String state
        List<String> trail = []

        TestEntity(String state) {
            this.state = state
        }
    }

    static class AppendStep implements Step<TestEntity, TestContext> {
        final String tag

        AppendStep(String tag) {
            this.tag = tag
        }

        @Override
        void execute(TestEntity entity, TestContext context, Transition<TestEntity, TestContext> transition) {
            entity.trail << tag
        }
    }

    static class FooStep implements Step<TestEntity, TestContext> {
        @Override
        void execute(TestEntity entity, TestContext context, Transition<TestEntity, TestContext> transition) {
            entity.trail << 'foo'
        }
    }

    static class CtorlessStep implements Step<TestEntity, TestContext> {
        CtorlessStep(String unused) {
        }

        @Override
        void execute(TestEntity entity, TestContext context, Transition<TestEntity, TestContext> transition) {
        }
    }

    def "constructor should reject null/blank id"() {
        when:
        new CompositeOperationDefImpl<TestEntity, TestContext>(id)

        then:
        thrown(TransfluxValidationException)

        where:
        id << [null, '', '  ']
    }

    def "build should reject composite with no members"() {
        given:
        def sm = Transflux.<TestEntity, TestContext> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
            .state(TRIAL).transitionsTo(ACTIVE, 't1')
            .state(ACTIVE)
            .build()
        def composite = new CompositeOperationDefImpl<TestEntity, TestContext>('op1')

        when:
        composite.build((StateMachineImpl<TestEntity, TestContext>) sm)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('op1')
        e.message.contains('no members')
    }

    def "step(...) overloads should be appendable in any combination and order"() {
        given:
        def composite = new CompositeOperationDefImpl<TestEntity, TestContext>('op1')
            .step('a-id', new AppendStep('a'))
            .step('b-id')
            .step('c-id', AppendStep)

        expect:
        composite.actionRefs.size() == 3
        composite.actionRefs[0] instanceof ActionRef.InlineInstance
        composite.actionRefs[1] instanceof ActionRef.ById
        composite.actionRefs[2] instanceof ActionRef.InlineClass
    }

    def "name and description should be optional and round-trip with covariant return"() {
        given:
        def composite = new CompositeOperationDefImpl<TestEntity, TestContext>('op1')
            .withName('My Op').withDescription('does stuff').step('s1', new FooStep())

        expect:
        composite.id == 'op1'
        composite.name == 'My Op'
        composite.description == 'does stuff'
    }

    def "build should iterate steps in declaration order"() {
        given:
        def sm = Transflux.<TestEntity, TestContext> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
            .step('a-id', new AppendStep('a'))
            .step('b-id', new AppendStep('b'))
            .step('c-id', new AppendStep('c'))
            .state(TRIAL).transitionsTo(ACTIVE, 't1')
            .state(ACTIVE)
            .build()

        def composite = new CompositeOperationDefImpl<TestEntity, TestContext>('op1')
            .step('c-id').step('a-id').step('b-id')

        def entity = new TestEntity('TRIAL')
        def view = new TransitionView<TestEntity, TestContext>(
            (StateMachineImpl<TestEntity, TestContext>) sm,
            ((StateMachineImpl<TestEntity, TestContext>) sm).transitions['t1'],
            entity,
            new TestContext()
        )

        when:
        def bound = composite.build((StateMachineImpl<TestEntity, TestContext>) sm)
        bound.operation.execute(entity, view.context, view)

        then:
        entity.trail == ['c', 'a', 'b']
        view.executedStepIds*.toString() == ['c-id', 'a-id', 'b-id']
    }

    def "build should reject reference to unknown step id"() {
        given:
        def sm = Transflux.<TestEntity, TestContext> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
            .step('known', new FooStep())
            .state(TRIAL).transitionsTo(ACTIVE, 't1')
            .state(ACTIVE)
            .build()

        def composite = new CompositeOperationDefImpl<TestEntity, TestContext>('op1')
            .step('known').step('missing')

        when:
        composite.build((StateMachineImpl<TestEntity, TestContext>) sm)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('op1')
        e.message.contains("'missing'")
    }

    def "composite using inline class form is reflectively instantiated through the SM registry"() {
        given:
        def smd = Transflux.<TestEntity, TestContext> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
        smd.state(TRIAL).transitionsTo(ACTIVE, 't1')
        smd.state(ACTIVE)
        smd.getTransition('t1')
            .compositeOperation('op1', { CompositeOperationDef<TestEntity, TestContext> c -> c.step('foo-id', FooStep) })

        def sm = (StateMachineImpl<TestEntity, TestContext>) smd.build()
        def entity = new TestEntity('TRIAL')
        def view = new TransitionView<TestEntity, TestContext>(sm, sm.transitions['t1'], entity, new TestContext())

        when:
        sm.transitions['t1'].boundOperation.operation.execute(entity, view.context, view)

        then:
        entity.trail == ['foo']
    }

    def "build should fail-fast when inline class has no no-arg constructor"() {
        given:
        def smd = Transflux.<TestEntity, TestContext> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
        smd.state(TRIAL).transitionsTo(ACTIVE, 't1')
        smd.state(ACTIVE)
        smd.getTransition('t1')
            .compositeOperation('op1', { CompositeOperationDef<TestEntity, TestContext> c -> c.step('bad-id', CtorlessStep) })

        when:
        smd.build()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('no accessible no-arg constructor')
        e.message.contains('CtorlessStep')
    }
}
