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

import spock.lang.Specification
import spock.lang.Unroll

import static org.transflux.core.TestStateEnum.*

class StepRegistrationSpec extends Specification {

    static class TestEntity {
        String state
    }

    static class StepA implements Step<TestEntity, TestContext> {
        @Override
        void execute(TestEntity entity, TestContext context, Transition<TestEntity, TestContext> transition) {
        }
    }

    static class StepB implements Step<TestEntity, TestContext> {
        @Override
        void execute(TestEntity entity, TestContext context, Transition<TestEntity, TestContext> transition) {
        }
    }

    static class CtorlessStep implements Step<TestEntity, TestContext> {
        CtorlessStep(String unused) {
        }

        @Override
        void execute(TestEntity entity, TestContext context, Transition<TestEntity, TestContext> transition) {
        }
    }

    @Unroll
    def "step(...) should reject null or blank id (instance form, id='#id')"() {
        given:
        def smd = Transflux.<TestEntity, TestContext> defineStateMachine().forEntityType(TestEntity)

        when:
        smd.step(id, new StepA())

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Step ID cannot be null or blank'

        where:
        id << [null, '', '  ']
    }

    def "step(...) should reject null instance"() {
        given:
        def smd = Transflux.<TestEntity, TestContext> defineStateMachine().forEntityType(TestEntity)

        when:
        smd.step('a', (Step<TestEntity, TestContext>) null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Step cannot be null'
    }

    def "step(...) should reject null class"() {
        given:
        def smd = Transflux.<TestEntity, TestContext> defineStateMachine().forEntityType(TestEntity)

        when:
        smd.step('a', (Class<? extends Step<TestEntity, TestContext>>) null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Step class cannot be null'
    }

    def "registering two different instances under same id should fail"() {
        given:
        def smd = Transflux.<TestEntity, TestContext> defineStateMachine().forEntityType(TestEntity)
            .step('shared', new StepA())

        when:
        smd.step('shared', new StepA())

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("'shared'")
        e.message.contains('already registered')
    }

    def "registering same instance twice under same id should be a no-op"() {
        given:
        def instance = new StepA()
        def smd = Transflux.<TestEntity, TestContext> defineStateMachine().forEntityType(TestEntity)
            .step('shared', instance)
            .step('shared', instance)

        when:
        def map = ((StateMachineDefImpl) smd).buildBoundSteps()

        then:
        map.keySet() == ['shared'] as Set
        map['shared'].step.is(instance)
    }

    def "registering same class twice under same id should be a no-op"() {
        given:
        def smd = Transflux.<TestEntity, TestContext> defineStateMachine().forEntityType(TestEntity)
            .step('shared', StepA)
            .step('shared', StepA)

        when:
        def map = ((StateMachineDefImpl) smd).buildBoundSteps()

        then:
        map.keySet() == ['shared'] as Set
        map['shared'].step instanceof StepA
    }

    def "registering a different class under same id should fail"() {
        given:
        def smd = Transflux.<TestEntity, TestContext> defineStateMachine().forEntityType(TestEntity)
            .step('shared', StepA)

        when:
        smd.step('shared', StepB)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('already registered')
    }

    def "registering an instance after a class under same id should fail"() {
        given:
        def smd = Transflux.<TestEntity, TestContext> defineStateMachine().forEntityType(TestEntity)
            .step('shared', StepA)

        when:
        smd.step('shared', new StepA())

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('already registered')
    }

    def "class-form registration should be reflectively instantiated at SM build time"() {
        given:
        def smd = Transflux.<TestEntity, TestContext> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
            .step('a', StepA)
        smd.state(TRIAL).transitionsTo(ACTIVE, 't1')
        smd.state(ACTIVE)

        when:
        def sm = (StateMachineImpl) smd.build()

        then:
        sm.getBoundStep('a') != null
        sm.getBoundStep('a').step instanceof StepA
    }

    def "class-form registration with no no-arg constructor should fail at SM build time"() {
        given:
        def smd = Transflux.<TestEntity, TestContext> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
            .step('bad', CtorlessStep)
        smd.state(TRIAL)

        when:
        smd.build()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('no accessible no-arg constructor')
        e.message.contains('CtorlessStep')
    }

    def "inline instance reference inside a composite should auto-register on the SM"() {
        given:
        def stepInstance = new StepA()
        def smd = Transflux.<TestEntity, TestContext> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
        smd.state(TRIAL).transitionsTo(ACTIVE, 't1')
        smd.state(ACTIVE)
        smd.getTransition('t1')
            .compositeOperation('op1', { c -> c.step('inline-a', stepInstance) })

        when:
        def sm = (StateMachineImpl) smd.build()

        then:
        sm.getBoundStep('inline-a') != null
        sm.getBoundStep('inline-a').step.is(stepInstance)
    }

    def "explicit registration and matching inline instance under same id should coexist"() {
        given:
        def stepInstance = new StepA()
        def smd = Transflux.<TestEntity, TestContext> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
            .step('shared', stepInstance)
        smd.state(TRIAL).transitionsTo(ACTIVE, 't1')
        smd.state(ACTIVE)
        smd.getTransition('t1')
            .compositeOperation('op1', { c -> c.step('shared', stepInstance) })

        when:
        def sm = (StateMachineImpl) smd.build()

        then:
        sm.getBoundStep('shared').step.is(stepInstance)
    }

    def "two composites inlining different instances under the same id should fail"() {
        given:
        def smd = Transflux.<TestEntity, TestContext> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
        smd.state(TRIAL)
            .transitionsTo(ACTIVE, 't1')
            .transitionsTo(ACTIVE, 't2')
        smd.state(ACTIVE)
        smd.getTransition('t1')
            .compositeOperation('op1', { c -> c.step('clash', new StepA()) })
        smd.getTransition('t2')
            .compositeOperation('op2', { c -> c.step('clash', new StepA()) })

        when:
        smd.build()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("'clash'")
        e.message.contains('already registered')
    }

    def "two composites referencing the same inline class under the same id should be a no-op"() {
        given:
        def smd = Transflux.<TestEntity, TestContext> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
        smd.state(TRIAL)
            .transitionsTo(ACTIVE, 't1')
            .transitionsTo(ACTIVE, 't2')
        smd.state(ACTIVE)
        smd.getTransition('t1')
            .compositeOperation('op1', { c -> c.step('shared-class', StepA) })
        smd.getTransition('t2')
            .compositeOperation('op2', { c -> c.step('shared-class', StepA) })

        when:
        def sm = (StateMachineImpl) smd.build()

        then:
        sm.getBoundStep('shared-class') != null
    }

    def "byId reference should resolve to an inline registration declared in another composite, regardless of declaration order"() {
        given:
        def smd = Transflux.<TestEntity, TestContext> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
        smd.state(TRIAL)
            .transitionsTo(ACTIVE, 't-consumer')
            .transitionsTo(ACTIVE, 't-provider')
        smd.state(ACTIVE)
        // Consumer composite references the id BEFORE the provider declares the inline.
        smd.getTransition('t-consumer')
            .compositeOperation('op-consumer', { c -> c.step('via-inline') })
        smd.getTransition('t-provider')
            .compositeOperation('op-provider', { c -> c.step('via-inline', new StepA()) })

        when:
        def sm = (StateMachineImpl) smd.build()

        then:
        sm.getBoundStep('via-inline') != null
    }

    def "getBoundStep should return null for unknown id"() {
        given:
        def smd = Transflux.<TestEntity, TestContext> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
        smd.state(TRIAL)

        when:
        def sm = (StateMachineImpl) smd.build()

        then:
        sm.getBoundStep('nothing') == null
    }
}
