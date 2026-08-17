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

import org.transflux.core.TestContext
import org.transflux.core.Transflux
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.action.Action
import org.transflux.core.action.Compensation
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.ActionPath
import org.transflux.core.transition.ExecutingTransition
import spock.lang.Specification

import static org.transflux.core.TestStateEnum.ACTIVE
import static org.transflux.core.TestStateEnum.TRIAL

class ExecutingTransitionImplSpec extends Specification {

    static class TestEntity {
        String state
        List<String> trail = []
    }

    static class TaggingStep implements Action<TestEntity, TestContext> {
        final String tag

        TaggingStep(String tag) {
            this.tag = tag
        }

        @Override
        void execute(TestEntity entity, TestContext context, ExecutingTransition<TestEntity, TestContext> transition) {
            entity.trail << tag
            if (context != null) {
                context.counter++
            }
        }
    }

    def "view.run(id) should run the bound step against the captured scope and record the id"() {
        given:
        def step = new TaggingStep('foo')
        def smd = Transflux.<TestEntity> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
            .step('foo-id', step)
        smd.state(TRIAL, { s -> s.transitionsTo(ACTIVE, 't1', {}) })
        smd.state(ACTIVE, {})

        def sm = (StateMachineImpl) smd.build()
        def entity = new TestEntity(state: 'TRIAL')
        def ctx = new TestContext()
        def view = new ExecutingTransitionImpl<TestEntity, TestContext>(sm, sm.transitions['t1'], entity, ctx)

        when:
        view.run('foo-id')

        then:
        entity.trail == ['foo']
        ctx.counter == 1
        view.executedPath*.toString() == ['foo-id']
    }

    def "view.run(id) should throw for an unknown id"() {
        given:
        def smd = Transflux.<TestEntity> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
        smd.state(TRIAL, { s -> s.transitionsTo(ACTIVE, 't1', {}) })
        smd.state(ACTIVE, {})

        def sm = (StateMachineImpl) smd.build()
        def view = new ExecutingTransitionImpl<TestEntity, TestContext>(
            sm, sm.transitions['t1'], new TestEntity(state: 'TRIAL'), new TestContext())

        when:
        view.run('nope')

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("'nope'")
        e.message.contains('No action registered')
    }

    def "pushCompensation should record the context the compensated action ran against"() {
        given:
        def sm = (StateMachineImpl) viewHostStateMachine()
        def ctx = new TestContext('parent')
        def childCtx = new TestContext('child')
        def view = new ExecutingTransitionImpl<TestEntity, TestContext>(
            sm, sm.transitions['t1'], new TestEntity(state: 'TRIAL'), ctx)

        when:
        view.pushCompensation(ActionPath.of('s1'), { e, c -> } as Compensation, childCtx)

        then:
        def drained = view.drainCompensationsLifo()
        drained.size() == 1
        drained[0].context().is(childCtx)
        drained[0].path().toString() == 's1'
    }

    def "pushCompensation should ignore a null compensation"() {
        given:
        def sm = (StateMachineImpl) viewHostStateMachine()
        def view = new ExecutingTransitionImpl<TestEntity, TestContext>(
            sm, sm.transitions['t1'], new TestEntity(state: 'TRIAL'), new TestContext())

        when:
        view.pushCompensation(ActionPath.of('s1'), null, new TestContext())

        then:
        view.drainCompensationsLifo().isEmpty()
    }

    def "view.run(id) should reject null or blank id"() {
        given:
        def smd = Transflux.<TestEntity> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
        smd.state(TRIAL, { s -> s.transitionsTo(ACTIVE, 't1', {}) })
        smd.state(ACTIVE, {})

        def sm = (StateMachineImpl) smd.build()
        def view = new ExecutingTransitionImpl<TestEntity, TestContext>(
            sm, sm.transitions['t1'], new TestEntity(state: 'TRIAL'), new TestContext())

        when:
        view.run(id)

        then:
        thrown(TransfluxValidationException)

        where:
        id << [null, '', '  ']
    }

    private static viewHostStateMachine() {
        def smd = Transflux.<TestEntity> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
        smd.state(TRIAL, { s -> s.transitionsTo(ACTIVE, 't1', {}) })
        smd.state(ACTIVE, {})
        return smd.build()
    }
}
