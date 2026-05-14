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

package org.transflux.core


import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateResolver
import spock.lang.Specification

import static org.transflux.core.TestStateEnum.ACTIVE
import static org.transflux.core.TestStateEnum.TRIAL

class StateMachineImplEntityBindingSpec extends Specification {

    static class TestEntity {
        String id
        String state

        TestEntity(String id, String state) {
            this.id = id
            this.state = state
        }
    }

    def "entity(e).transitionTo(target) should behave equivalently to executeTransition(e, target)"() {
        given:
        def applied = []
        def applier = { TestEntity e, String s -> applied << [e.id, s] } as StateApplier<TestEntity>

        StateMachine<TestEntity, TestContext> sm = Transflux.<TestEntity, TestContext> defineStateMachine()
            .forEntityType(TestEntity)
            .forContextType(TestContext)
            .withStateResolver({ TestEntity e -> e.state } as StateResolver<TestEntity>)
            .withStateApplier(applier)
            .state(TRIAL).transitionsTo(ACTIVE, "t1")
            .state(ACTIVE)
            .build()

        def entity = new TestEntity("e1", "TRIAL")

        when:
        def result = sm.entity(entity).transitionTo("ACTIVE")

        then:
        result.success
        result.targetStateId == "ACTIVE"
        applied == [["e1", "ACTIVE"]]
    }

    def "entity(e).withContext(c).transitionTo(target) should succeed and not lose the context"() {
        given:
        StateMachine<TestEntity, TestContext> sm = Transflux.<TestEntity, TestContext> defineStateMachine()
            .forEntityType(TestEntity)
            .forContextType(TestContext)
            .withStateResolver({ TestEntity e -> e.state } as StateResolver<TestEntity>)
            .state(TRIAL).transitionsTo(ACTIVE, "t1")
            .state(ACTIVE)
            .build()

        def entity = new TestEntity("e1", "TRIAL")
        def context = new TestContext("hello")

        when:
        def result = sm.entity(entity).withContext(context).transitionTo("ACTIVE")

        then:
        result.success
        result.sourceStateId == "TRIAL"
        result.targetStateId == "ACTIVE"
    }

    def "entity(null) should throw"() {
        given:
        def sm = Transflux.defineStateMachine()
            .forEntityType(Object)
            .state("a")
            .build()

        when:
        sm.entity(null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == "Entity cannot be null"
    }

    def "entity(e).transitionTo with explicit transitionId should validate the transition matches"() {
        given:
        StateMachine<TestEntity, TestContext> sm = Transflux.<TestEntity, TestContext> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ TestEntity e -> e.state } as StateResolver<TestEntity>)
            .state(TRIAL).transitionsTo(ACTIVE, "t1")
            .state(ACTIVE)
            .build()

        def entity = new TestEntity("e1", "TRIAL")

        when:
        def result = sm.entity(entity).transitionTo("ACTIVE", "t1")

        then:
        result.success
    }
}
