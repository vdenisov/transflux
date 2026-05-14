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

import org.transflux.core.condition.Condition
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.Transition

import spock.lang.Specification
import spock.lang.Unroll

import java.util.function.Predicate

class StateMachineDefImplConditionRegistrationSpec extends Specification {

    static class TestEntity {
        int value
    }

    static class CondA implements Condition<TestEntity, TestContext> {
        @Override
        boolean test(TestEntity entity, TestContext context, Transition<TestEntity, TestContext> transition) {
            entity.value > 0
        }
    }

    static class CondB implements Condition<TestEntity, TestContext> {
        @Override
        boolean test(TestEntity entity, TestContext context, Transition<TestEntity, TestContext> transition) {
            false
        }
    }

    static class CtorlessCondition implements Condition<TestEntity, TestContext> {
        CtorlessCondition(String unused) {
        }

        @Override
        boolean test(TestEntity entity, TestContext context, Transition<TestEntity, TestContext> transition) {
            false
        }
    }

    @Unroll
    def "condition(...) should reject null or blank id (instance form, id='#id')"() {
        given:
        def smd = Transflux.<TestEntity, TestContext> defineStateMachine().forEntityType(TestEntity)

        when:
        smd.condition(id, new CondA())

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Condition ID cannot be null or blank'

        where:
        id << [null, '', '  ']
    }

    def "condition(...) should reject null instance"() {
        given:
        def smd = Transflux.<TestEntity, TestContext> defineStateMachine().forEntityType(TestEntity)

        when:
        smd.condition('a', (Condition<TestEntity, TestContext>) null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Condition cannot be null'
    }

    def "condition(...) should reject null class"() {
        given:
        def smd = Transflux.<TestEntity, TestContext> defineStateMachine().forEntityType(TestEntity)

        when:
        smd.condition('a', (Class<? extends Condition<TestEntity, TestContext>>) null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Condition class cannot be null'
    }

    def "condition(...) should reject null predicate"() {
        given:
        def smd = Transflux.<TestEntity, TestContext> defineStateMachine().forEntityType(TestEntity)

        when:
        smd.condition('a', (Predicate<TestEntity>) null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Predicate cannot be null'
    }

    @Unroll
    def "condition(...) should reject null or blank SpEL expression (expr='#expr')"() {
        given:
        def smd = Transflux.<TestEntity, TestContext> defineStateMachine().forEntityType(TestEntity)

        when:
        smd.condition('a', (String) expr)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'SpEL expression cannot be null or blank'

        where:
        expr << [null, '', '  ']
    }

    def "registering two different instances under the same id should fail"() {
        given:
        def smd = Transflux.<TestEntity, TestContext> defineStateMachine().forEntityType(TestEntity)
            .condition('shared', new CondA())

        when:
        smd.condition('shared', new CondA())

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("'shared'")
        e.message.contains('already registered')
    }

    def "registering the same instance twice under the same id should be a no-op"() {
        given:
        def instance = new CondA()
        def smd = Transflux.<TestEntity, TestContext> defineStateMachine().forEntityType(TestEntity)
            .condition('shared', instance)
            .condition('shared', instance)

        when:
        def map = ((StateMachineDefImpl) smd).buildBoundConditions()

        then:
        map.keySet() == ['shared'] as Set
        map['shared'].condition.is(instance)
    }

    def "registering the same class twice under the same id should be a no-op"() {
        given:
        def smd = Transflux.<TestEntity, TestContext> defineStateMachine().forEntityType(TestEntity)
            .condition('shared', CondA)
            .condition('shared', CondA)

        when:
        def map = ((StateMachineDefImpl) smd).buildBoundConditions()

        then:
        map.keySet() == ['shared'] as Set
        map['shared'].condition instanceof CondA
    }

    def "registering a different class under the same id should fail"() {
        given:
        def smd = Transflux.<TestEntity, TestContext> defineStateMachine().forEntityType(TestEntity)
            .condition('shared', CondA)

        when:
        smd.condition('shared', CondB)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('already registered')
    }

    def "registering an instance after a class under the same id should fail"() {
        given:
        def smd = Transflux.<TestEntity, TestContext> defineStateMachine().forEntityType(TestEntity)
            .condition('shared', CondA)

        when:
        smd.condition('shared', new CondA())

        then:
        thrown(TransfluxValidationException)
    }

    def "class-form registration should be reflectively instantiated when buildBoundConditions runs"() {
        given:
        def smd = Transflux.<TestEntity, TestContext> defineStateMachine()
            .forEntityType(TestEntity)
            .condition('a', CondA)

        when:
        def map = ((StateMachineDefImpl) smd).buildBoundConditions()

        then:
        map['a'].condition instanceof CondA
    }

    def "class-form registration with no no-arg constructor should fail at buildBoundConditions"() {
        given:
        def smd = Transflux.<TestEntity, TestContext> defineStateMachine()
            .forEntityType(TestEntity)
            .condition('bad', CtorlessCondition)

        when:
        ((StateMachineDefImpl) smd).buildBoundConditions()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('no accessible no-arg constructor')
        e.message.contains('CtorlessCondition')
    }

    def "buildBoundConditions should expose all four registration forms"() {
        given:
        def instance = new CondA()
        Predicate<TestEntity> pred = { e -> e.value > 5 }
        def smd = Transflux.<TestEntity, TestContext> defineStateMachine()
            .forEntityType(TestEntity)
            .condition('inst', instance)
            .condition('cls', CondB)
            .condition('pred', pred)
            .condition('expr', 'value > 0')

        when:
        def map = ((StateMachineDefImpl) smd).buildBoundConditions()

        then:
        map.keySet() == ['inst', 'cls', 'pred', 'expr'] as Set
        map['inst'].condition.is(instance)
        map['cls'].condition instanceof CondB

        and: 'predicate adapter delegates to the predicate'
        def predBound = map['pred'].condition
        predBound.test(new TestEntity(value: 10), null, null)
        !predBound.test(new TestEntity(value: 1), null, null)

        and: 'expression adapter delegates to the shared evaluator'
        def exprBound = map['expr'].condition
        exprBound.test(new TestEntity(value: 1), null, null)
        !exprBound.test(new TestEntity(value: 0), null, null)
    }
}
