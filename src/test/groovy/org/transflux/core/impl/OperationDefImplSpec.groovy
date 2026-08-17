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
import org.transflux.core.action.OperationDef
import org.transflux.core.action.ContextMapper
import org.transflux.core.action.Action
import org.transflux.core.action.Compensation
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.ExecutingTransition
import org.transflux.core.transition.TransitionDef
import spock.lang.Specification
import spock.lang.Unroll

import java.util.function.Consumer

import static org.transflux.core.TestStateEnum.ACTIVE
import static org.transflux.core.TestStateEnum.TRIAL

class OperationDefImplSpec extends Specification {

    def "constructor should reject null/blank id"() {
        when:
        new OperationDefImpl<TestEntity, TestContext>(id)

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
        def composite = new OperationDefImpl<TestEntity, TestContext>('op1')
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
        def composite = new OperationDefImpl<TestEntity, TestContext>('op1').tap { beginConfigurer() }
            .step('a-id', new AppendStep('a'))
            .run('b-id')
            .step('c-id', AppendStep)

        expect:
        composite.actionRefs.size() == 3
        composite.actionRefs[0] instanceof ActionRef.InlineInstance
        composite.actionRefs[1] instanceof ActionRef.ById
        composite.actionRefs[2] instanceof ActionRef.InlineClass
    }

    def "name and description should be optional and round-trip with covariant return"() {
        given:
        def composite = new OperationDefImpl<TestEntity, TestContext>('op1')
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

        def composite = new OperationDefImpl<TestEntity, TestContext>('op1').tap { beginConfigurer() }
            .run('c-id').run('a-id').run('b-id')
        composite.scopeRegistry = new RegistryImpl<TestEntity>(((StateMachineImpl<TestEntity>) sm).componentRegistry)

        def entity = new TestEntity('TRIAL')
        def view = new ExecutingTransitionImpl<TestEntity, TestContext>(
            (StateMachineImpl<TestEntity>) sm,
            ((StateMachineImpl<TestEntity>) sm).transitions['t1'],
            entity,
            new TestContext()
        )

        when:
        def bound = composite.buildBound((StateMachineImpl<TestEntity>) sm)
        bound.action.execute(entity, view.context, view)

        then:
        entity.trail == ['c', 'a', 'b']
        view.executedPath*.toString() == ['c-id', 'a-id', 'b-id']
    }

    def "declared compensation rides onto the bound container"() {
        given: 'a container has no Java body, so the def is its only channel for a compensation'
        def compensation = { e, c -> } as Compensation<TestEntity, TestContext>
        def sm = Transflux.<TestEntity> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
            .step('a-id', new AppendStep('a'))
            .state(TRIAL, { s -> s.transitionsTo(ACTIVE, 't1', {}) })
            .state(ACTIVE, {})
            .build()

        def composite = new OperationDefImpl<TestEntity, TestContext>('op1').tap { beginConfigurer() }
            .withCompensation(compensation)
            .run('a-id')
        composite.scopeRegistry = new RegistryImpl<TestEntity>(((StateMachineImpl<TestEntity>) sm).componentRegistry)

        expect:
        composite.buildBound((StateMachineImpl<TestEntity>) sm).compensation().is(compensation)
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

        def composite = new OperationDefImpl<TestEntity, TestContext>('op1').tap { beginConfigurer() }
            .run('known').run('missing')
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
            t.operation('op1', { OperationDef<TestEntity, TestContext> c -> c.step('foo-id', FooStep) })
        }) })
        smd.state(ACTIVE, {})

        def sm = (StateMachineImpl<TestEntity>) smd.build()
        def entity = new TestEntity('TRIAL')
        def view = new ExecutingTransitionImpl<TestEntity, TestContext>(sm, sm.transitions['t1'], entity, new TestContext())

        when:
        sm.transitions['t1'].boundAction.action.execute(entity, view.context, view)

        then:
        entity.trail == ['foo']
    }

    def "build should fail-fast when inline class has no no-arg constructor"() {
        given:
        def smd = Transflux.<TestEntity> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
        smd.state(TRIAL, { s -> s.transitionsTo(ACTIVE, 't1', { t ->
            t.operation('op1', { OperationDef<TestEntity, TestContext> c -> c.step('bad-id', CtorlessStep) })
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
    def 'by-id member #variant accepts Identifiable refs'() {
        given:
        def composite = new OperationDefImpl<Object, Object>('outer')
        composite.beginConfigurer()

        when:
        action.call(composite)

        then:
        composite.actionRefs.size() == 1

        where:
        variant                                   | action
        'run(Identifiable)'                       | { c -> c.run(identifiable('my-action')) }
        'run(Identifiable, Identifiable)'         | { c -> c.run(identifiable('my-action'), identifiable('my-mapper')) }
        'run(Identifiable, String mapperId)'      | { c -> c.run(identifiable('my-action'), 'my-mapper') }
        'run(String actionId, Identifiable)'      | { c -> c.run('my-action', identifiable('my-mapper')) }
    }

    def 'tier-1 Identifiable overloads accept any Identifiable (e.g. a held-onto *Def reference)'() {
        given:
        def composite = new OperationDefImpl<Object, Object>('outer')
        composite.beginConfigurer()
        def heldDef = new TransitionDefImpl<Object, Object>('held-id', 's1', 's2')

        when:
        composite.run(heldDef)

        then:
        composite.actionRefs.size() == 1
        composite.actionRefs[0].id() == 'held-id'
    }

    @Unroll
    def 'tier-1 #variant rejects null Identifiable arg'() {
        given:
        def composite = new OperationDefImpl<Object, Object>('outer')
        composite.beginConfigurer()

        when:
        action.call(composite)

        then:
        thrown(TransfluxValidationException)

        where:
        variant                          | action
        'run(null)'                      | { c -> c.run((Identifiable) null) }
        'run(null, identifiable)'        | { c -> c.run((Identifiable) null, identifiable('m')) }
        'run(null, mapperId)'            | { c -> c.run((Identifiable) null, 'm') }
        'run(identifiable, null)'        | { c -> c.run(identifiable('a'), (Identifiable) null) }
        'run(actionId, null)'            | { c -> c.run('a', (Identifiable) null) }
    }

    @Unroll
    def 'tier-3 inline Identifiable overload accepted: #variant'() {
        given:
        def composite = new OperationDefImpl<Object, Object>('outer')
        composite.beginConfigurer()

        when:
        action.call(composite)

        then:
        composite.actionRefs.size() == 1

        where:
        variant                                  | action
        'step(Id, Action)'                       | { c -> c.step(identifiable('s1'), new IdOverloadStep()) }
        'step(Id, Class)'                        | { c -> c.step(identifiable('s2'), IdOverloadStep) }
        'conditional(Id, Consumer)'              | { c -> c.conditional(identifiable('cond1'), { cs -> cs.branch('b', { b -> b.condition('any'); b.run('x') }) }) }
    }

    @Unroll
    def 'tier-3 #variant rejects null Identifiable'() {
        given:
        def composite = new OperationDefImpl<Object, Object>('outer')
        composite.beginConfigurer()

        when:
        action.call(composite)

        then:
        thrown(TransfluxValidationException)

        where:
        variant                                  | action
        'step(null, Step)'                       | { c -> c.step((Identifiable) null, new IdOverloadStep()) }
        'step(null, Class)'                      | { c -> c.step((Identifiable) null, IdOverloadStep) }
        'operation(null, Operation)'             | { c -> c.step((Identifiable) null, new IdOverloadOp()) }
        'operation(null, Class)'                 | { c -> c.step((Identifiable) null, IdOverloadOp) }
        'conditional(null, Consumer)'            | { c -> c.conditional((Identifiable) null, { cs -> }) }
    }

    def 'usingContext(SMContext) accepts when the supplied class matches the SM context type'() {
        given:
        def smd = new StateMachineDefImpl<CtxAssertEntity>()
        smd.forEntityType(CtxAssertEntity)
            .withStateResolver({ e -> e.state } as StateResolver<CtxAssertEntity>)
            .state('s1', { s -> s.transitionsTo('s2', 't', { t ->
                t.operation('outer', { OperationDef<CtxAssertEntity, CtxAssertCorrectCtx> c ->
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
                t.operation('outer', { OperationDef<CtxAssertEntity, CtxAssertCorrectCtx> c ->
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
            { smd -> smd.step('nested', NestedFailChildCtx, new NestedFailChildOp())
                .mapper('failing-mapto', NestedFailParentCtx, NestedFailChildCtx, new FailingMapToMapper()) },
            { t -> t.operation('outer', { OperationDef<NestedFailEntity, NestedFailParentCtx> c ->
                c.run('nested', 'failing-mapto')
            }) })
        def entity = new NestedFailEntity('s1')

        when:
        def result = sm.entity(entity).transitionTo('s2', new NestedFailParentCtx())

        then:
        !result.success
        result.error instanceof RuntimeException
        result.error.message == 'mapTo-boom'
        // mapTo failure surfaces as parent failure; the nested op never starts, so only the
        // outer composite's own entry is recorded. mapTo throws before the child's compensation
        // is captured, so there is nothing to unwind either.
        result.executedPath*.toString() == ['outer']
        result.compensatedPath*.toString() == []
        entity.trail == []
    }

    def 'mapFrom failure surfaces as parent failure — child completed but writeback blew up'() {
        given:
        def sm = buildNestedFail(
            { smd -> smd.step('nested', NestedFailChildCtx, new NestedFailChildOp())
                .mapper('failing-mapfrom', NestedFailParentCtx, NestedFailChildCtx, new FailingMapFromMapper()) },
            { t -> t.operation('outer', { OperationDef<NestedFailEntity, NestedFailParentCtx> c ->
                c.run('nested', 'failing-mapfrom')
            }) })
        def entity = new NestedFailEntity('s1')

        when:
        def result = sm.entity(entity).transitionTo('s2', new NestedFailParentCtx())

        then:
        !result.success
        result.error instanceof RuntimeException
        result.error.message == 'mapFrom-boom'
        result.executedPath*.toString() == ['outer', 'outer/nested']
        // The child completed, but the failure is still the transition's: compensation is captured
        // before the child executes and the transition drains the whole stack, so the completed
        // child is compensated too.
        result.compensatedPath*.toString() == ['outer/nested']
        entity.trail == ['child-ran', 'child-compensated']
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

    static class AppendStep implements Action<TestEntity, TestContext> {
        final String tag

        AppendStep(String tag) {
            this.tag = tag
        }

        @Override
        void execute(TestEntity entity, TestContext context, ExecutingTransition<TestEntity, TestContext> transition) {
            entity.trail << tag
        }
    }

    static class FooStep implements Action<TestEntity, TestContext> {
        @Override
        void execute(TestEntity entity, TestContext context, ExecutingTransition<TestEntity, TestContext> transition) {
            entity.trail << 'foo'
        }
    }

    static class CtorlessStep implements Action<TestEntity, TestContext> {
        @SuppressWarnings('unused')
        CtorlessStep(String unused) {
        }

        @Override
        void execute(TestEntity entity, TestContext context, ExecutingTransition<TestEntity, TestContext> transition) {
        }
    }

    static class IdOverloadStep implements Action<Object, Object> {
        @Override
        void execute(Object e, Object c, ExecutingTransition<Object, Object> t) {}
    }

    static class IdOverloadOp implements Action<Object, Object> {
        @Override
        void execute(Object e, Object c, ExecutingTransition<Object, Object> t) {}
    }

    static class CtxAssertEntity {
        String state

        CtxAssertEntity(String state) { this.state = state }
    }

    static class CtxAssertCorrectCtx { }

    static class CtxAssertNoopStep implements Action<CtxAssertEntity, CtxAssertCorrectCtx> {
        @Override
        void execute(CtxAssertEntity entity, CtxAssertCorrectCtx context, ExecutingTransition<CtxAssertEntity, CtxAssertCorrectCtx> transition) { }
    }

    static class NestedFailEntity {
        String state
        List<String> trail = []

        NestedFailEntity(String state) { this.state = state }
    }

    static class NestedFailParentCtx { }

    static class NestedFailChildCtx { }

    static class NestedFailChildOp implements Action<NestedFailEntity, NestedFailChildCtx> {
        @Override
        void execute(NestedFailEntity entity, NestedFailChildCtx context, ExecutingTransition<NestedFailEntity, NestedFailChildCtx> transition) {
            entity.trail << 'child-ran'
        }

        @Override
        Compensation<NestedFailEntity, NestedFailChildCtx> getCompensation(NestedFailEntity entity, NestedFailChildCtx context) {
            return { e, c -> e.trail << 'child-compensated' } as Compensation
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
