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
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.ExecutingTransition
import spock.lang.Specification
import spock.lang.Unroll

import java.util.function.Consumer

import static org.transflux.core.TestStateEnum.ACTIVE
import static org.transflux.core.TestStateEnum.TRIAL

class StateMachineDefImplStepRegistrationSpec extends Specification {

    static class TestEntity {
        String state
    }

    static class StepA implements Action<TestEntity, TestContext> {
        @Override
        void execute(TestEntity entity, TestContext context, ExecutingTransition<TestEntity, TestContext> transition) {
        }
    }

    static class StepB implements Action<TestEntity, TestContext> {
        @Override
        void execute(TestEntity entity, TestContext context, ExecutingTransition<TestEntity, TestContext> transition) {
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
        smd.step('a', (Action<TestEntity, TestContext>) null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Step cannot be null'
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
        def map = ((StateMachineDefImpl) smd).buildBoundActions()

        then:
        map.keySet() == ['shared'] as Set
        map['shared'].action.is(instance)
    }

    def "inline instance reference inside a composite is lexically scoped to that composite and not visible at SM root"() {
        given:
        def stepInstance = new StepA()
        def smd = Transflux.<TestEntity> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
        smd.state(TRIAL.id, { s -> s.transitionsTo(ACTIVE.id, 't1', { t ->
            t.operation('op1', { c -> c.step('inline-a', stepInstance) })
        }) })
        smd.state(ACTIVE.id, {})

        when:
        def sm = (StateMachineImpl) smd.build()

        then:
        // Inline composite members live in the composite's scope, not the SM root.
        sm.getBoundAction('inline-a') == null
    }

    def "explicit registration and matching inline instance under same id should coexist (idempotent)"() {
        given:
        def stepInstance = new StepA()
        def smd = Transflux.<TestEntity> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
            .step('shared', stepInstance)
        smd.state(TRIAL.id, { s -> s.transitionsTo(ACTIVE.id, 't1', { t ->
            t.operation('op1', { c -> c.step('shared', stepInstance) })
        }) })
        smd.state(ACTIVE.id, {})

        when:
        def sm = (StateMachineImpl) smd.build()

        then:
        // SM-level registration survives; inline registration of the same instance is treated
        // as an idempotent no-op and does not collide.
        sm.getBoundAction('shared').action.is(stepInstance)
    }

    def "two composites inlining different instances under the same id should fail"() {
        given:
        def smd = Transflux.<TestEntity> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
        smd.state(TRIAL.id, { s -> s
            .transitionsTo(ACTIVE.id, 't1', { t ->
                t.operation('op1', { c -> c.step('clash', new StepA()) })
            })
            .transitionsTo(ACTIVE.id, 't2', { t ->
                t.operation('op2', { c -> c.step('clash', new StepA()) })
            }) })
        smd.state(ACTIVE.id, {})

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

    def "two composites inlining the same instance under the same id are idempotent (each composite has its own scope entry)"() {
        given:
        def shared = new StepA()
        def smd = Transflux.<TestEntity> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
        smd.state(TRIAL.id, { s -> s
            .transitionsTo(ACTIVE.id, 't1', { t ->
                t.operation('op1', { c -> c.step('shared-class', shared) })
            })
            .transitionsTo(ACTIVE.id, 't2', { t ->
                t.operation('op2', { c -> c.step('shared-class', shared) })
            }) })
        smd.state(ACTIVE.id, {})

        when:
        def sm = (StateMachineImpl) smd.build()

        then:
        // The same instance across two composites is idempotent; the build succeeds.
        // The id is composite-local — it lives in each composite's scope, not the SM root.
        sm != null
        sm.getBoundAction('shared-class') == null
    }

    def "by-id reference cannot resolve an inline registration declared in a sibling composite"() {
        given:
        def smd = Transflux.<TestEntity> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
        smd.state(TRIAL.id, { s -> s
            .transitionsTo(ACTIVE.id, 't-consumer', { t ->
                // Consumer composite references an id only declared in another composite below.
                t.operation('op-consumer', { c -> c.run('via-inline') })
            })
            .transitionsTo(ACTIVE.id, 't-provider', { t ->
                t.operation('op-provider', { c -> c.step('via-inline', new StepA()) })
            }) })
        smd.state(ACTIVE.id, {})

        when:
        smd.build()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("'via-inline'")
        e.message.contains('unknown action id')
        // The enrichment names the composite that does hold the id inline.
        e.message.contains("composite 'op-provider'")
        e.message.contains('inline registrations are only visible inside')
        e.message.contains('Declare it in a scope that encloses both positions')
    }

    def "the holder named for a nested container is not called a sibling"() {
        given:
        def smd = Transflux.<TestEntity> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
        smd.state(TRIAL.id, { s -> s
            .transitionsTo(ACTIVE.id, 't', { t ->
                // 'buried' is declared one level *below* the position that references it, so the
                // remedy is hoisting into 'outer' - never "move to SM root".
                t.operation('outer', { c -> c
                    .operation('inner', { i -> i.step('buried', new StepA()) })
                    .run('buried') })
            }) })
        smd.state(ACTIVE.id, {})

        when:
        smd.build()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("unknown action id 'buried'")
        e.message.contains("composite 'inner'")
        !e.message.contains('sibling')
        !e.message.contains('SM root')
    }

    def "unknown action id error stays simple when nothing holds the id inline"() {
        given:
        def smd = Transflux.<TestEntity> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
        smd.state(TRIAL.id, { s -> s
            .transitionsTo(ACTIVE.id, 't-consumer', { t ->
                t.operation('op-consumer', { c -> c.run('truly-missing') })
            }) })
        smd.state(ACTIVE.id, {})

        when:
        smd.build()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("'truly-missing'")
        e.message.contains('unknown action id')
        e.message.contains("'op-consumer'")
        !e.message.contains('is registered in composite')
    }

    def "getBoundAction should return null for unknown id"() {
        given:
        def smd = Transflux.<TestEntity> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
        smd.state(TRIAL.id, {})

        when:
        def sm = (StateMachineImpl) smd.build()

        then:
        sm.getBoundAction('nothing') == null
    }

    def "step(id, Class, Consumer) captures metadata on the def and resolves to a bound step (instance form)"() {
        given:
        def step = new StepA()
        def captured = null
        def smd = Transflux.<TestEntity> defineStateMachine().forEntityType(TestEntity)
        smd.step('a', TestContext, { d -> captured = d; d.withName('N').withDescription('D').using(step) })

        when:
        def map = ((StateMachineDefImpl) smd).buildBoundActions()

        then: 'the bound step resolves to the supplied instance'
        map['a'].action.is(step)

        and: 'metadata + context type live on the def'
        captured.getId() == 'a'
        captured.getName() == 'N'
        captured.getDescription() == 'D'
        captured.contextType() == TestContext
    }

    def "step(id, Class, Consumer) and the flat step(id, Class, Step) register identically"() {
        given:
        def instance = new StepA()
        def viaLambda = Transflux.<TestEntity> defineStateMachine().forEntityType(TestEntity)
        viaLambda.step('a', TestContext, { d -> d.using(instance) })
        def viaFlat = Transflux.<TestEntity> defineStateMachine().forEntityType(TestEntity)
        viaFlat.step('a', TestContext, instance)

        expect:
        ((StateMachineDefImpl) viaLambda).buildBoundActions()['a'].action.is(instance)
        ((StateMachineDefImpl) viaFlat).buildBoundActions()['a'].action.is(instance)
    }

    def "step(id, Class, Consumer) rejects post-configurer mutation of the captured def"() {
        given:
        def captured = null
        def smd = Transflux.<TestEntity> defineStateMachine().forEntityType(TestEntity)
        smd.step('a', TestContext, { d -> captured = d; d.using(new StepA()) })

        when:
        captured.using(new StepB())

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("step 'a'")
        e.message.contains('after its configurer has returned')
    }

    def "step(id, Class, Consumer) reusing an existing step id is rejected"() {
        given:
        def smd = Transflux.<TestEntity> defineStateMachine().forEntityType(TestEntity)
            .step('a', new StepA())

        when:
        smd.step('a', TestContext, { d -> d.using(new StepB()) })

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("'a'")
    }

    def "step(id, Consumer) registers a def typed against Object and leaves the context tag unset"() {
        given:
        def step = new StepA()
        def captured = null
        def smd = Transflux.<TestEntity> defineStateMachine().forEntityType(TestEntity)
        smd.step('a', { d -> captured = d; d.withName('N').using(step) } as Consumer)

        when:
        def map = ((StateMachineDefImpl) smd).buildBoundActions()

        then: 'the bound step resolves to the supplied instance'
        map['a'].action.is(step)

        and: 'metadata lives on the def, whose context type is the permissive default'
        captured.getName() == 'N'
        captured.contextType() == Object

        and: 'the untyped form tags no context, exactly like step(id, Action)'
        ((StateMachineDefImpl) smd).getComponentContextType('a') == null
    }

    @Unroll
    def "step(id, Consumer) rejects null or blank id (id='#id')"() {
        given:
        def smd = Transflux.<TestEntity> defineStateMachine().forEntityType(TestEntity)

        when:
        smd.step((String) id, { d -> d.using(new StepA()) } as Consumer)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Step ID cannot be null or blank'

        where:
        id << [null, '', '  ']
    }

    def "step(id, Consumer) rejects a null configurer"() {
        given:
        def smd = Transflux.<TestEntity> defineStateMachine().forEntityType(TestEntity)

        when:
        smd.step('a', (Consumer) null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Step configurer cannot be null'
    }

    def "step(id, Consumer) rejects post-configurer mutation of the captured def"() {
        given:
        def captured = null
        def smd = Transflux.<TestEntity> defineStateMachine().forEntityType(TestEntity)
        smd.step('a', { d -> captured = d; d.using(new StepA()) } as Consumer)

        when:
        captured.using(new StepB())

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("step 'a'")
        e.message.contains('after its configurer has returned')
    }
}
