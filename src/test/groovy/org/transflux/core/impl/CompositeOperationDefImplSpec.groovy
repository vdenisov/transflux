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
import org.transflux.core.StateMachine
import org.transflux.core.TestContext
import org.transflux.core.Transflux
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.operation.CompositeOperationDef
import org.transflux.core.operation.ContextMapper
import org.transflux.core.operation.Operation
import org.transflux.core.operation.Step
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.Transition
import org.transflux.core.transition.TransitionDef
import spock.lang.Specification
import spock.lang.Unroll

import java.util.function.Consumer

import static org.transflux.core.TestStateEnum.ACTIVE
import static org.transflux.core.TestStateEnum.TRIAL

class CompositeOperationDefImplSpec extends Specification {

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
        def sm = Transflux.<TestEntity> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
            .state(TRIAL, { s -> s.transitionsTo(ACTIVE, 't1', {}) })
            .state(ACTIVE, {})
            .build()
        def composite = new CompositeOperationDefImpl<TestEntity, TestContext>('op1')
        composite.scopeRegistry = new RegistryImpl<TestEntity>(((StateMachineImpl<TestEntity>) sm).componentRegistry)

        when:
        composite.buildBound((StateMachineImpl<TestEntity>) sm)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('op1')
        e.message.contains('no members')
    }

    def "step(...) overloads should be appendable in any combination and order"() {
        given:
        def composite = new CompositeOperationDefImpl<TestEntity, TestContext>('op1').tap { beginConfigurer() }
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
        composite.beginConfigurer()
        composite.withName('My Op').withDescription('does stuff').step('s1', new FooStep())

        expect:
        composite.id == 'op1'
        composite.name == 'My Op'
        composite.description == 'does stuff'
    }

    def "build should iterate steps in declaration order"() {
        given:
        def sm = Transflux.<TestEntity> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
            .step('a-id', new AppendStep('a'))
            .step('b-id', new AppendStep('b'))
            .step('c-id', new AppendStep('c'))
            .state(TRIAL, { s -> s.transitionsTo(ACTIVE, 't1', {}) })
            .state(ACTIVE, {})
            .build()

        def composite = new CompositeOperationDefImpl<TestEntity, TestContext>('op1').tap { beginConfigurer() }
            .step('c-id').step('a-id').step('b-id')
        composite.scopeRegistry = new RegistryImpl<TestEntity>(((StateMachineImpl<TestEntity>) sm).componentRegistry)

        def entity = new TestEntity('TRIAL')
        def view = new TransitionView<TestEntity, TestContext>(
            (StateMachineImpl<TestEntity>) sm,
            ((StateMachineImpl<TestEntity>) sm).transitions['t1'],
            entity,
            new TestContext()
        )

        when:
        def bound = composite.buildBound((StateMachineImpl<TestEntity>) sm)
        bound.operation.execute(entity, view.context, view)

        then:
        entity.trail == ['c', 'a', 'b']
        view.executedPath*.toString() == ['c-id', 'a-id', 'b-id']
    }

    def "build should reject reference to unknown step id"() {
        given:
        def sm = Transflux.<TestEntity> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
            .step('known', new FooStep())
            .state(TRIAL, { s -> s.transitionsTo(ACTIVE, 't1', {}) })
            .state(ACTIVE, {})
            .build()

        def composite = new CompositeOperationDefImpl<TestEntity, TestContext>('op1').tap { beginConfigurer() }
            .step('known').step('missing')
        composite.scopeRegistry = new RegistryImpl<TestEntity>(((StateMachineImpl<TestEntity>) sm).componentRegistry)

        when:
        composite.buildBound((StateMachineImpl<TestEntity>) sm)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('op1')
        e.message.contains("'missing'")
    }

    def "composite using inline class form is reflectively instantiated through the SM registry"() {
        given:
        def smd = Transflux.<TestEntity> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
        smd.state(TRIAL, { s -> s.transitionsTo(ACTIVE, 't1', { t ->
            t.compositeOperation('op1', { CompositeOperationDef<TestEntity, TestContext> c -> c.step('foo-id', FooStep) })
        }) })
        smd.state(ACTIVE, {})

        def sm = (StateMachineImpl<TestEntity>) smd.build()
        def entity = new TestEntity('TRIAL')
        def view = new TransitionView<TestEntity, TestContext>(sm, sm.transitions['t1'], entity, new TestContext())

        when:
        sm.transitions['t1'].boundOperation.operation.execute(entity, view.context, view)

        then:
        entity.trail == ['foo']
    }

    def "build should fail-fast when inline class has no no-arg constructor"() {
        given:
        def smd = Transflux.<TestEntity> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
        smd.state(TRIAL, { s -> s.transitionsTo(ACTIVE, 't1', { t ->
            t.compositeOperation('op1', { CompositeOperationDef<TestEntity, TestContext> c -> c.step('bad-id', CtorlessStep) })
        }) })
        smd.state(ACTIVE, {})

        when:
        smd.build()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('no accessible no-arg constructor')
        e.message.contains('CtorlessStep')
    }

    @Unroll
    def 'tier-1 step #variant accepts Identifiable refs'() {
        given:
        def composite = new CompositeOperationDefImpl<Object, Object>('outer')
        composite.beginConfigurer()

        when:
        action.call(composite)

        then:
        composite.actionRefs.size() == 1

        where:
        variant                                       | action
        'step(Identifiable)'                          | { c -> c.step(identifiable('my-step')) }
        'step(Identifiable, Identifiable)'            | { c -> c.step(identifiable('my-step'), identifiable('my-mapper')) }
        'step(Identifiable, String mapperId)'         | { c -> c.step(identifiable('my-step'), 'my-mapper') }
        'step(String stepId, Identifiable mapper)'    | { c -> c.step('my-step', identifiable('my-mapper')) }
    }

    @Unroll
    def 'tier-1 operation #variant accepts Identifiable refs'() {
        given:
        def composite = new CompositeOperationDefImpl<Object, Object>('outer')
        composite.beginConfigurer()

        when:
        action.call(composite)

        then:
        composite.actionRefs.size() == 1

        where:
        variant                                           | action
        'operation(Identifiable)'                         | { c -> c.operation(identifiable('my-op')) }
        'operation(Identifiable, Identifiable)'           | { c -> c.operation(identifiable('my-op'), identifiable('my-mapper')) }
        'operation(Identifiable, String mapperId)'        | { c -> c.operation(identifiable('my-op'), 'my-mapper') }
        'operation(String opId, Identifiable mapper)'     | { c -> c.operation('my-op', identifiable('my-mapper')) }
    }

    def 'tier-1 Identifiable overloads accept any Identifiable (e.g. a held-onto *Def reference)'() {
        given:
        def composite = new CompositeOperationDefImpl<Object, Object>('outer')
        composite.beginConfigurer()
        def heldDef = new TransitionDefImpl<Object, Object>('held-id', 's1', 's2')

        when:
        composite.step(heldDef)

        then:
        composite.actionRefs.size() == 1
        composite.actionRefs[0].id() == 'held-id'
    }

    @Unroll
    def 'tier-1 #variant rejects null Identifiable arg'() {
        given:
        def composite = new CompositeOperationDefImpl<Object, Object>('outer')
        composite.beginConfigurer()

        when:
        action.call(composite)

        then:
        thrown(TransfluxValidationException)

        where:
        variant                                       | action
        'step(null)'                                  | { c -> c.step((Identifiable) null) }
        'step(null, identifiable)'                    | { c -> c.step((Identifiable) null, identifiable('m')) }
        'step(null, mapperId)'                        | { c -> c.step((Identifiable) null, 'm') }
        'step(identifiable, null)'                    | { c -> c.step(identifiable('s'), (Identifiable) null) }
        'step(stepId, null)'                          | { c -> c.step('s', (Identifiable) null) }
        'operation(null)'                             | { c -> c.operation((Identifiable) null) }
        'operation(null, identifiable)'               | { c -> c.operation((Identifiable) null, identifiable('m')) }
        'operation(null, mapperId)'                   | { c -> c.operation((Identifiable) null, 'm') }
        'operation(identifiable, null)'               | { c -> c.operation(identifiable('o'), (Identifiable) null) }
        'operation(opId, null)'                       | { c -> c.operation('o', (Identifiable) null) }
    }

    @Unroll
    def 'tier-3 inline Identifiable overload accepted: #variant'() {
        given:
        def composite = new CompositeOperationDefImpl<Object, Object>('outer')
        composite.beginConfigurer()

        when:
        action.call(composite)

        then:
        composite.actionRefs.size() == 1

        where:
        variant                                  | action
        'step(Id, Step)'                         | { c -> c.step(identifiable('s1'), new IdOverloadStep()) }
        'step(Id, Class)'                        | { c -> c.step(identifiable('s2'), IdOverloadStep) }
        'operation(Id, Operation)'               | { c -> c.operation(identifiable('o1'), new IdOverloadOp()) }
        'operation(Id, Class)'                   | { c -> c.operation(identifiable('o2'), IdOverloadOp) }
        'conditional(Id, Consumer)'              | { c -> c.conditional(identifiable('cond1'), { cs -> cs.branch('b', { b -> b.condition('any'); b.step('x') }) }) }
    }

    @Unroll
    def 'tier-3 #variant rejects null Identifiable'() {
        given:
        def composite = new CompositeOperationDefImpl<Object, Object>('outer')
        composite.beginConfigurer()

        when:
        action.call(composite)

        then:
        thrown(TransfluxValidationException)

        where:
        variant                                  | action
        'step(null, Step)'                       | { c -> c.step((Identifiable) null, new IdOverloadStep()) }
        'step(null, Class)'                      | { c -> c.step((Identifiable) null, IdOverloadStep) }
        'operation(null, Operation)'             | { c -> c.operation((Identifiable) null, new IdOverloadOp()) }
        'operation(null, Class)'                 | { c -> c.operation((Identifiable) null, IdOverloadOp) }
        'conditional(null, Consumer)'            | { c -> c.conditional((Identifiable) null, { cs -> }) }
    }

    def 'usingContext(SMContext) accepts when the supplied class matches the SM context type'() {
        given:
        def smd = new StateMachineDefImpl<CtxAssertEntity>()
        smd.forEntityType(CtxAssertEntity)
            .withStateResolver({ e -> e.state } as StateResolver<CtxAssertEntity>)
            .state('s1', { s -> s.transitionsTo('s2', 't', { t ->
                t.compositeOperation('outer', { CompositeOperationDef<CtxAssertEntity, CtxAssertCorrectCtx> c ->
                    c.usingContext(CtxAssertCorrectCtx).step('s1', new CtxAssertNoopStep())
                })
            }) })
            .state('s2', {})

        when:
        def sm = smd.build()

        then:
        sm != null
    }

    def 'usingContext is a no-op when the SM did not declare a context type'() {
        given:
        def smd = new StateMachineDefImpl<CtxAssertEntity>()
        smd.forEntityType(CtxAssertEntity)
            .withStateResolver({ e -> e.state } as StateResolver<CtxAssertEntity>)
            .state('s1', { s -> s.transitionsTo('s2', 't', { t ->
                t.compositeOperation('outer', { CompositeOperationDef<CtxAssertEntity, CtxAssertCorrectCtx> c ->
                    c.usingContext(CtxAssertCorrectCtx).step('s1', new CtxAssertNoopStep())
                })
            }) })
            .state('s2', {})

        when:
        def sm = smd.build()

        then:
        sm != null
    }

    def 'mapTo failure surfaces as parent member failure — nested op never starts'() {
        given:
        def sm = buildNestedFail(
            { smd -> smd.operation('nested', NestedFailChildCtx, new NestedFailChildOp())
                .mapper('failing-mapto', NestedFailParentCtx, NestedFailChildCtx, new FailingMapToMapper()) },
            { t -> t.compositeOperation('outer', { CompositeOperationDef<NestedFailEntity, NestedFailParentCtx> c ->
                c.operation('nested', 'failing-mapto')
            }) })
        def entity = new NestedFailEntity('s1')

        when:
        def result = sm.entity(entity).transitionTo('s2', new NestedFailParentCtx())

        then:
        !result.success
        result.error instanceof RuntimeException
        result.error.message == 'mapTo-boom'
        // mapTo failure surfaces as parent failure; the nested op never starts, so only the
        // outer composite's own entry is recorded.
        result.executedPath*.toString() == ['outer']
        entity.trail == []
    }

    def 'mapFrom failure surfaces as parent failure — child completed but writeback blew up'() {
        given:
        def sm = buildNestedFail(
            { smd -> smd.operation('nested', NestedFailChildCtx, new NestedFailChildOp())
                .mapper('failing-mapfrom', NestedFailParentCtx, NestedFailChildCtx, new FailingMapFromMapper()) },
            { t -> t.compositeOperation('outer', { CompositeOperationDef<NestedFailEntity, NestedFailParentCtx> c ->
                c.operation('nested', 'failing-mapfrom')
            }) })
        def entity = new NestedFailEntity('s1')

        when:
        def result = sm.entity(entity).transitionTo('s2', new NestedFailParentCtx())

        then:
        !result.success
        result.error instanceof RuntimeException
        result.error.message == 'mapFrom-boom'
        entity.trail == ['child-ran']
    }

    private static Identifiable identifiable(String value) {
        return { -> value } as Identifiable
    }

    private static StateMachine<NestedFailEntity> buildNestedFail(Consumer<StateMachineDefImpl<NestedFailEntity>> smdRegistrations,
                                                                  Consumer<TransitionDef<NestedFailEntity, NestedFailParentCtx>> transitionConfigurer) {
        def smd = new StateMachineDefImpl<NestedFailEntity>()
        smd.forEntityType(NestedFailEntity)
            .withStateResolver({ e -> e.state } as StateResolver<NestedFailEntity>)
        smdRegistrations.accept(smd)
        smd.state('s1', { s -> s.transitionsTo('s2', 't', NestedFailParentCtx, transitionConfigurer) })
            .state('s2', {})
        return smd.build()
    }

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
        @SuppressWarnings('unused')
        CtorlessStep(String unused) {
        }

        @Override
        void execute(TestEntity entity, TestContext context, Transition<TestEntity, TestContext> transition) {
        }
    }

    static class IdOverloadStep implements Step<Object, Object> {
        @Override
        void execute(Object e, Object c, Transition<Object, Object> t) {}
    }

    static class IdOverloadOp implements Operation<Object, Object> {
        @Override
        void execute(Object e, Object c, Transition<Object, Object> t) {}
    }

    static class CtxAssertEntity {
        String state

        CtxAssertEntity(String state) { this.state = state }
    }

    static class CtxAssertCorrectCtx { }

    static class CtxAssertNoopStep implements Step<CtxAssertEntity, CtxAssertCorrectCtx> {
        @Override
        void execute(CtxAssertEntity entity, CtxAssertCorrectCtx context, Transition<CtxAssertEntity, CtxAssertCorrectCtx> transition) { }
    }

    static class NestedFailEntity {
        String state
        List<String> trail = []

        NestedFailEntity(String state) { this.state = state }
    }

    static class NestedFailParentCtx { }

    static class NestedFailChildCtx { }

    static class NestedFailChildOp implements Operation<NestedFailEntity, NestedFailChildCtx> {
        @Override
        void execute(NestedFailEntity entity, NestedFailChildCtx context, Transition<NestedFailEntity, NestedFailChildCtx> transition) {
            entity.trail << 'child-ran'
        }
    }

    static class FailingMapToMapper implements ContextMapper<NestedFailParentCtx, NestedFailChildCtx> {
        @Override
        NestedFailChildCtx mapTo(NestedFailParentCtx p) {
            throw new RuntimeException('mapTo-boom')
        }
    }

    static class FailingMapFromMapper implements ContextMapper<NestedFailParentCtx, NestedFailChildCtx> {
        @Override
        NestedFailChildCtx mapTo(NestedFailParentCtx p) {
            return new NestedFailChildCtx()
        }

        @Override
        void mapFrom(NestedFailParentCtx p, NestedFailChildCtx n) {
            throw new RuntimeException('mapFrom-boom')
        }
    }
}
