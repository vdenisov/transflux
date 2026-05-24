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
import org.transflux.core.operation.Step
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.Transition
import spock.lang.Specification
import spock.lang.Unroll

import static org.transflux.core.TestStateEnum.ACTIVE
import static org.transflux.core.TestStateEnum.TRIAL

class StateMachineDefImplStepRegistrationSpec extends Specification {

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
        def smd = Transflux.<TestEntity> defineStateMachine().forEntityType(TestEntity)

        when:
        smd.step((String) id, new StepA())

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Step ID cannot be null or blank'

        where:
        id << [null, '', '  ']
    }

    def "step(...) should reject null instance"() {
        given:
        def smd = Transflux.<TestEntity> defineStateMachine().forEntityType(TestEntity)

        when:
        smd.step('a', (Step<TestEntity, TestContext>) null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Step cannot be null'
    }

    def "step(...) should reject null class"() {
        given:
        def smd = Transflux.<TestEntity> defineStateMachine().forEntityType(TestEntity)

        when:
        smd.step('a', (Class<? extends Step<TestEntity, TestContext>>) null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Step class cannot be null'
    }

    def "registering two different instances under same id should fail"() {
        given:
        def smd = Transflux.<TestEntity> defineStateMachine().forEntityType(TestEntity)
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
        def smd = Transflux.<TestEntity> defineStateMachine().forEntityType(TestEntity)
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
        def smd = Transflux.<TestEntity> defineStateMachine().forEntityType(TestEntity)
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
        def smd = Transflux.<TestEntity> defineStateMachine().forEntityType(TestEntity)
            .step('shared', StepA)

        when:
        smd.step('shared', StepB)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('already registered')
    }

    def "registering an instance after a class under same id should fail"() {
        given:
        def smd = Transflux.<TestEntity> defineStateMachine().forEntityType(TestEntity)
            .step('shared', StepA)

        when:
        smd.step('shared', new StepA())

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('already registered')
    }

    def "class-form registration should be reflectively instantiated at SM build time"() {
        given:
        def smd = Transflux.<TestEntity> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
            .step('a', StepA)
        smd.state(TRIAL, { s -> s.transitionsTo(ACTIVE, 't1', {}) })
        smd.state(ACTIVE, {})

        when:
        def sm = (StateMachineImpl) smd.build()

        then:
        sm.getBoundStep('a') != null
        sm.getBoundStep('a').step instanceof StepA
    }

    def "class-form registration with no no-arg constructor should fail at SM build time"() {
        given:
        def smd = Transflux.<TestEntity> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
            .step('bad', CtorlessStep)
        smd.state(TRIAL, {})

        when:
        smd.build()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('no accessible no-arg constructor')
        e.message.contains('CtorlessStep')
    }

    def "inline instance reference inside a composite is lexically scoped to that composite and not visible at SM root"() {
        given:
        def stepInstance = new StepA()
        def smd = Transflux.<TestEntity> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
        smd.state(TRIAL, { s -> s.transitionsTo(ACTIVE, 't1', { t ->
            t.compositeOperation('op1', { c -> c.step('inline-a', stepInstance) })
        }) })
        smd.state(ACTIVE, {})

        when:
        def sm = (StateMachineImpl) smd.build()

        then:
        // Inline composite members live in the composite's scope, not the SM root.
        sm.getBoundStep('inline-a') == null
    }

    def "explicit registration and matching inline instance under same id should coexist (idempotent)"() {
        given:
        def stepInstance = new StepA()
        def smd = Transflux.<TestEntity> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
            .step('shared', stepInstance)
        smd.state(TRIAL, { s -> s.transitionsTo(ACTIVE, 't1', { t ->
            t.compositeOperation('op1', { c -> c.step('shared', stepInstance) })
        }) })
        smd.state(ACTIVE, {})

        when:
        def sm = (StateMachineImpl) smd.build()

        then:
        // SM-level registration survives; inline registration of the same instance is treated
        // as an idempotent no-op and does not collide.
        sm.getBoundStep('shared').step.is(stepInstance)
    }

    def "two composites inlining different instances under the same id should fail"() {
        given:
        def smd = Transflux.<TestEntity> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
        smd.state(TRIAL, { s -> s
            .transitionsTo(ACTIVE, 't1', { t ->
                t.compositeOperation('op1', { c -> c.step('clash', new StepA()) })
            })
            .transitionsTo(ACTIVE, 't2', { t ->
                t.compositeOperation('op2', { c -> c.step('clash', new StepA()) })
            }) })
        smd.state(ACTIVE, {})

        when:
        smd.build()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("'clash'")
        e.message.contains('already registered')
        // Enriched message: names the kind plus both payload class names.
        e.message.startsWith('Step id')
        e.message.contains(StepA.class.name)
        e.message.contains('cannot re-register')
    }

    def "two composites referencing the same inline class under the same id are idempotent (each composite has its own scope entry)"() {
        given:
        def smd = Transflux.<TestEntity> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
        smd.state(TRIAL, { s -> s
            .transitionsTo(ACTIVE, 't1', { t ->
                t.compositeOperation('op1', { c -> c.step('shared-class', StepA) })
            })
            .transitionsTo(ACTIVE, 't2', { t ->
                t.compositeOperation('op2', { c -> c.step('shared-class', StepA) })
            }) })
        smd.state(ACTIVE, {})

        when:
        def sm = (StateMachineImpl) smd.build()

        then:
        // Same class across two composites is idempotent; the build succeeds.
        // The id is composite-local — it lives in each composite's scope, not the SM root.
        sm != null
        sm.getBoundStep('shared-class') == null
    }

    def "by-id reference cannot resolve an inline registration declared in a sibling composite"() {
        given:
        def smd = Transflux.<TestEntity> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
        smd.state(TRIAL, { s -> s
            .transitionsTo(ACTIVE, 't-consumer', { t ->
                // Consumer composite references an id only declared in another composite below.
                t.compositeOperation('op-consumer', { c -> c.step('via-inline') })
            })
            .transitionsTo(ACTIVE, 't-provider', { t ->
                t.compositeOperation('op-provider', { c -> c.step('via-inline', new StepA()) })
            }) })
        smd.state(ACTIVE, {})

        when:
        smd.build()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("'via-inline'")
        e.message.contains('unknown step id')
        // Sibling-scope diagnostic: message names the sibling composite hosting the inline registration.
        e.message.contains("sibling composite 'op-provider'")
        e.message.contains('inline registrations are only visible inside')
        e.message.contains('Move to SM root')
    }

    def "unknown step id error stays simple when no sibling composite hosts the id"() {
        given:
        def smd = Transflux.<TestEntity> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
        smd.state(TRIAL, { s -> s
            .transitionsTo(ACTIVE, 't-consumer', { t ->
                t.compositeOperation('op-consumer', { c -> c.step('truly-missing') })
            }) })
        smd.state(ACTIVE, {})

        when:
        smd.build()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("'truly-missing'")
        e.message.contains('unknown step id')
        e.message.contains("'op-consumer'")
        !e.message.contains('sibling composite')
    }

    def "getBoundStep should return null for unknown id"() {
        given:
        def smd = Transflux.<TestEntity> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
        smd.state(TRIAL, {})

        when:
        def sm = (StateMachineImpl) smd.build()

        then:
        sm.getBoundStep('nothing') == null
    }
}
