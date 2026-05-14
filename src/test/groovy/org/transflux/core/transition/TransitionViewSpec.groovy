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

package org.transflux.core.transition

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
import org.transflux.core.operation.BoundOperation
import org.transflux.core.operation.BoundStep
import org.transflux.core.operation.CompositeOperationDef
import org.transflux.core.operation.CompositeOperationDefImpl
import org.transflux.core.operation.Operation
import org.transflux.core.operation.SimpleOperationDef
import org.transflux.core.operation.SimpleOperationDefImpl
import org.transflux.core.operation.Step

import spock.lang.Specification

import static org.transflux.core.TestStateEnum.*

class TransitionViewSpec extends Specification {

    static class TestEntity {
        String state
        List<String> trail = []
    }

    static class TaggingStep implements Step<TestEntity, TestContext> {
        final String tag

        TaggingStep(String tag) {
            this.tag = tag
        }

        @Override
        void execute(TestEntity entity, TestContext context, Transition<TestEntity, TestContext> transition) {
            entity.trail << tag
            if (context != null) {
                context.counter++
            }
        }
    }

    def "view.step(id) should run the bound step against the captured scope and record the id"() {
        given:
        def step = new TaggingStep('foo')
        def smd = Transflux.<TestEntity, TestContext> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
            .step('foo-id', step)
        smd.state(TRIAL).transitionsTo(ACTIVE, 't1')
        smd.state(ACTIVE)

        def sm = (StateMachineImpl) smd.build()
        def entity = new TestEntity(state: 'TRIAL')
        def ctx = new TestContext()
        def view = new TransitionView<TestEntity, TestContext>(sm, sm.transitions['t1'], entity, ctx)

        when:
        view.step('foo-id')

        then:
        entity.trail == ['foo']
        ctx.counter == 1
        view.executedStepIds == ['foo-id']
    }

    def "view.step(id) should throw for an unknown id"() {
        given:
        def smd = Transflux.<TestEntity, TestContext> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
        smd.state(TRIAL).transitionsTo(ACTIVE, 't1')
        smd.state(ACTIVE)

        def sm = (StateMachineImpl) smd.build()
        def view = new TransitionView<TestEntity, TestContext>(
            sm, sm.transitions['t1'], new TestEntity(state: 'TRIAL'), new TestContext())

        when:
        view.step('nope')

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("'nope'")
        e.message.contains('No step registered')
    }

    def "view.step(id) should reject null or blank id"() {
        given:
        def smd = Transflux.<TestEntity, TestContext> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
        smd.state(TRIAL).transitionsTo(ACTIVE, 't1')
        smd.state(ACTIVE)

        def sm = (StateMachineImpl) smd.build()
        def view = new TransitionView<TestEntity, TestContext>(
            sm, sm.transitions['t1'], new TestEntity(state: 'TRIAL'), new TestContext())

        when:
        view.step(id)

        then:
        thrown(TransfluxValidationException)

        where:
        id << [null, '', '  ']
    }

    def "TransitionImpl.step(id) on the static-topology object should throw"() {
        given:
        def smd = Transflux.<TestEntity, TestContext> defineStateMachine()
            .forEntityType(TestEntity)
        smd.state(TRIAL).transitionsTo(ACTIVE, 't1')
        smd.state(ACTIVE)

        def sm = (StateMachineImpl) smd.build()

        when:
        sm.getTransition('t1').step('foo')

        then:
        thrown(TransfluxValidationException)
    }
}
