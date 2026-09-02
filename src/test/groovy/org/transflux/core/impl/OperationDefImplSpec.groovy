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
import java.util.function.Predicate

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
            .state(TRIAL.id, { s -> s.transitionsTo(ACTIVE.id, 't1', {}) })
            .state(ACTIVE.id, {})
            .build()
        def composite = new OperationDefImpl<TestEntity, TestContext>('op1')
        composite.scopeRegistry = new RegistryImpl<TestEntity>(
            ((StateMachineImpl<TestEntity>) sm).componentRegistry, composite.getId())

        when:
        composite.buildBound()

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
            .step('c-id', { it.using(new AppendStep('c')) } as Consumer)

        expect:
        composite.actionRefs.size() == 3
        composite.actionRefs[0] instanceof ActionRef.InlineInstance
        composite.actionRefs[1] instanceof ActionRef.ById
        composite.actionRefs[2] instanceof ActionRef.InlineDef
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
            .state(TRIAL.id, { s -> s.transitionsTo(ACTIVE.id, 't1', {}) })
            .state(ACTIVE.id, {})
            .build()

        def composite = new OperationDefImpl<TestEntity, TestContext>('op1').tap { beginConfigurer() }
            .run('c-id').run('a-id').run('b-id')
        composite.scopeRegistry = new RegistryImpl<TestEntity>(
            ((StateMachineImpl<TestEntity>) sm).componentRegistry, composite.getId())

        def entity = new TestEntity('TRIAL')
        def view = new ExecutingTransitionImpl<TestEntity, TestContext>(
            (StateMachineImpl<TestEntity>) sm,
            ((StateMachineImpl<TestEntity>) sm).transitions['t1'],
            entity,
            new TestContext()
        )

        when:
        def bound = composite.buildBound()
        composite.bindMembers((StateMachineImpl<TestEntity>) sm, "operation 'op1'")
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
            .state(TRIAL.id, { s -> s.transitionsTo(ACTIVE.id, 't1', {}) })
            .state(ACTIVE.id, {})
            .build()

        def composite = new OperationDefImpl<TestEntity, TestContext>('op1').tap { beginConfigurer() }
            .withCompensation(compensation)
            .run('a-id')
        composite.scopeRegistry = new RegistryImpl<TestEntity>(
            ((StateMachineImpl<TestEntity>) sm).componentRegistry, composite.getId())

        expect:
        composite.buildBound().compensationRouter()?.fallback().is(compensation)
    }

    def "build should reject reference to unknown step id"() {
        given:
        def sm = Transflux.<TestEntity> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
            .step('known', new FooStep())
            .state(TRIAL.id, { s -> s.transitionsTo(ACTIVE.id, 't1', {}) })
            .state(ACTIVE.id, {})
            .build()

        def composite = new OperationDefImpl<TestEntity, TestContext>('op1').tap { beginConfigurer() }
            .run('known').run('missing')
        composite.scopeRegistry = new RegistryImpl<TestEntity>(
            ((StateMachineImpl<TestEntity>) sm).componentRegistry, composite.getId())

        when:
        composite.buildBound()
        composite.bindMembers((StateMachineImpl<TestEntity>) sm, "operation 'op1'")

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('op1')
        e.message.contains("'missing'")
    }

    def "an SM-level container may reference one declared after it"() {
        given: 'members bind once every container is registered, not while each one builds'
        def smd = Transflux.<TestEntity> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
        smd.operation('first', TestContext, { OperationDef<TestEntity, TestContext> c -> c.run('second') })
        smd.operation('second', TestContext,
                      { OperationDef<TestEntity, TestContext> c -> c.step('inner', new AppendStep('from-second')) })
        smd.state(TRIAL.id, { s -> s.transitionsTo(ACTIVE.id, 't1', TestContext, { t -> t.run('first') }) })
        smd.state(ACTIVE.id, {})

        def sm = smd.build()
        def entity = new TestEntity('TRIAL')

        when:
        def result = sm.entity(entity).transitionTo(ACTIVE.id, new TestContext())

        then:
        result.success
        entity.trail == ['from-second']
        result.executedPath*.toString() == ['first', 'first/second', 'first/second/inner']
    }

    def "an inline container reaches the enclosing container's inline ids"() {
        given: 'resolution walks the nested scope first, then the chain that encloses it'
        def smd = Transflux.<TestEntity> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
        smd.state(TRIAL.id, { s -> s.transitionsTo(ACTIVE.id, 't1', TestContext, { t ->
            t.operation('outer', { OperationDef<TestEntity, TestContext> c ->
                c.step('outer-inline', new AppendStep('outer'))
                 .operation('inner', { OperationDef<TestEntity, TestContext> nested ->
                     nested.run('outer-inline')
                 })
            })
        }) })
        smd.state(ACTIVE.id, {})

        def sm = smd.build()
        def entity = new TestEntity('TRIAL')

        when:
        def result = sm.entity(entity).transitionTo(ACTIVE.id, new TestContext())

        then:
        result.success
        entity.trail == ['outer', 'outer']
        result.executedPath*.toString() == ['outer', 'outer/outer-inline', 'outer/inner',
                                            'outer/inner/outer-inline']
    }

    def "a sibling cannot reach an inline container's own ids"() {
        given: 'the nested scope is private to its subtree, exactly as a container scope is'
        def smd = Transflux.<TestEntity> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
        smd.state(TRIAL.id, { s -> s.transitionsTo(ACTIVE.id, 't1', TestContext, { t ->
            t.operation('outer', { OperationDef<TestEntity, TestContext> c ->
                c.operation('inner', { OperationDef<TestEntity, TestContext> nested ->
                     nested.step('buried', new AppendStep('buried'))
                 })
                 .run('buried')
            })
        }) })
        smd.state(ACTIVE.id, {})

        when:
        smd.build()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("unknown action id 'buried'")
        e.message.contains('inner')
    }

    def "a container declared inside a branch is reached by the scope walks too"() {
        given: 'nesting position must not decide whether a scope is seen by the build'
        def smd = Transflux.<TestEntity> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
        smd.state(TRIAL.id, { s -> s.transitionsTo(ACTIVE.id, 't1', TestContext, { t ->
            t.operation('outer', { OperationDef<TestEntity, TestContext> c ->
                c.conditional('route', { cs ->
                    cs.branch('only', { b ->
                        b.condition('always', { TestEntity e -> true } as Predicate)
                         .operation('nested', { OperationDef<TestEntity, TestContext> n ->
                             n.step('buried', new AppendStep('buried'))
                         })
                    })
                }).run('buried')
            })
        }) })
        smd.state(ACTIVE.id, {})

        when:
        smd.build()

        then: 'the hint names the branch-nested container, as it does a direct one'
        def e = thrown(TransfluxValidationException)
        e.message.contains("unknown action id 'buried'")
        e.message.contains("composite 'nested'")
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
