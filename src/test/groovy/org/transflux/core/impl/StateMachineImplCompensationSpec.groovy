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
import org.transflux.core.action.Compensation
import org.transflux.core.action.OperationDef
import org.transflux.core.action.ContextMapper
import org.transflux.core.action.Action
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.Transition
import org.transflux.core.transition.TransitionDef
import spock.lang.Specification

import java.util.function.Consumer
import java.util.function.Predicate

class StateMachineImplCompensationSpec extends Specification {

    static class Entity {
        String state
        List<String> trail = []

        Entity(String state) {
            this.state = state
        }
    }

    static class TrailStep implements Action<Entity, TestContext> {
        final String tag

        TrailStep(String tag) {
            this.tag = tag
        }

        @Override
        void execute(Entity entity, TestContext context, Transition<Entity, TestContext> transition) {
            entity.trail << tag
        }

        @Override
        Compensation<Entity, TestContext> getCompensation(Entity entity, TestContext context) {
            String capturedTag = tag
            return { Entity e, TestContext c -> e.trail << ("-" + capturedTag) } as Compensation<Entity, TestContext>
        }
    }

    static class ThrowingStep implements Action<Entity, TestContext> {
        final String message

        ThrowingStep(String message) {
            this.message = message
        }

        @Override
        void execute(Entity entity, TestContext context, Transition<Entity, TestContext> transition) {
            throw new RuntimeException(message)
        }
    }

    static class NoCompStep implements Action<Entity, TestContext> {
        final String tag

        NoCompStep(String tag) {
            this.tag = tag
        }

        @Override
        void execute(Entity entity, TestContext context, Transition<Entity, TestContext> transition) {
            entity.trail << tag
        }
    }

    static class CompThrowsStep implements Action<Entity, TestContext> {
        final String tag

        CompThrowsStep(String tag) {
            this.tag = tag
        }

        @Override
        void execute(Entity entity, TestContext context, Transition<Entity, TestContext> transition) {
            entity.trail << tag
        }

        @Override
        Compensation<Entity, TestContext> getCompensation(Entity entity, TestContext context) {
            return { Entity e, TestContext c -> throw new RuntimeException("comp-blew-up-" + tag) } as Compensation<Entity, TestContext>
        }
    }

    static class ThrowingWithCompStep implements Action<Entity, TestContext> {
        final String tag

        ThrowingWithCompStep(String tag) {
            this.tag = tag
        }

        @Override
        void execute(Entity entity, TestContext context, Transition<Entity, TestContext> transition) {
            entity.trail << tag
            throw new RuntimeException("execute-blew-up-" + tag)
        }

        @Override
        Compensation<Entity, TestContext> getCompensation(Entity entity, TestContext context) {
            String capturedTag = tag
            return { Entity e, TestContext c -> e.trail << ("-" + capturedTag) } as Compensation<Entity, TestContext>
        }
    }

    static class PartialCreateStep implements Action<Entity, TestContext> {
        final int totalTarget
        final int failAt
        final List<String> createdIds
        final List<String> deletedIds

        PartialCreateStep(int totalTarget, int failAt, List<String> createdIds, List<String> deletedIds) {
            this.totalTarget = totalTarget
            this.failAt = failAt
            this.createdIds = createdIds
            this.deletedIds = deletedIds
        }

        @Override
        void execute(Entity entity, TestContext context, Transition<Entity, TestContext> transition) {
            for (int i = 0; i < totalTarget; i++) {
                if (i == failAt) {
                    throw new RuntimeException("external-service-failed-at-${i}")
                }
                createdIds << "entity-${i}".toString()
            }
        }

        @Override
        Compensation<Entity, TestContext> getCompensation(Entity entity, TestContext context) {
            List<String> created = createdIds
            List<String> deleted = deletedIds
            return { Entity e, TestContext c -> deleted.addAll(created) } as Compensation<Entity, TestContext>
        }
    }

    static class ChildCtx {
        String tag
    }

    /** Fails after the boundary mapping, and reports the context its compensation was handed. */
    static class ChildCtxThrowingStep implements Action<Entity, ChildCtx> {
        @Override
        void execute(Entity entity, ChildCtx context, Transition<Entity, ChildCtx> transition) {
            throw new RuntimeException('child-blew-up')
        }

        @Override
        Compensation<Entity, ChildCtx> getCompensation(Entity entity, ChildCtx context) {
            String captured = context == null ? 'null-ctx' : context.tag
            // Untyped params: the rollback drain hands compensations the transition's context,
            // which is the parent type here.
            return { e, c -> e.trail << ('-child:' + captured) } as Compensation<Entity, ChildCtx>
        }
    }

    static class ChildCtxMapper implements ContextMapper<TestContext, ChildCtx> {
        @Override
        ChildCtx mapTo(TestContext parent) {
            return new ChildCtx(tag: parent.tag)
        }
    }

    static class DynamicDispatchStep implements Action<Entity, TestContext> {
        @Override
        void execute(Entity entity, TestContext context, Transition<Entity, TestContext> transition) {
            transition.run('dynamic')
        }
    }

    def 'three-step composite, step 3 throws: compensations run in reverse and applier is skipped'() {
        given:
        def applied = []
        def sm = build(applied, { t -> t.operation('op', { OperationDef<Entity, TestContext> c ->
            c.step('s1', new TrailStep('a'))
             .step('s2', new TrailStep('b'))
             .step('s3', new ThrowingStep('boom'))
        }) })
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        !result.success
        result.error instanceof RuntimeException
        result.error.message == 'boom'
        result.executedPath*.toString() == ['op', 'op/s1', 'op/s2', 'op/s3']
        result.compensatedPath*.toString() == ['op/s2', 'op/s1']
        entity.trail == ['a', 'b', '-b', '-a']
        applied.isEmpty()
    }

    def "throwing step's compensation also runs (captured before execute)"() {
        given:
        def applied = []
        def sm = build(applied, { t -> t.operation('op', { OperationDef<Entity, TestContext> c ->
            c.step('s1', new TrailStep('a'))
             .step('s2', new ThrowingWithCompStep('b'))
        }) })
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        !result.success
        result.error.message == 'execute-blew-up-b'
        result.executedPath*.toString() == ['op', 'op/s1', 'op/s2']
        result.compensatedPath*.toString() == ['op/s2', 'op/s1']
        entity.trail == ['a', 'b', '-b', '-a']
        applied.isEmpty()
    }

    def 'partial-failure cleanup: compensation reads state mutated by execute before the throw'() {
        given:
        def applied = []
        def createdIds = []
        def deletedIds = []
        def sm = build(applied, { t -> t.operation('op', { OperationDef<Entity, TestContext> c ->
            c.step('create', new PartialCreateStep(10, 5, createdIds, deletedIds))
        }) })
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        !result.success
        result.error.message == 'external-service-failed-at-5'
        result.executedPath*.toString() == ['op', 'op/create']
        result.compensatedPath*.toString() == ['op/create']
        createdIds == ['entity-0', 'entity-1', 'entity-2', 'entity-3', 'entity-4']
        deletedIds == ['entity-0', 'entity-1', 'entity-2', 'entity-3', 'entity-4']
        applied.isEmpty()
    }

    def 'step with null compensation registers nothing'() {
        given:
        def applied = []
        def sm = build(applied, { t -> t.operation('op', { OperationDef<Entity, TestContext> c ->
            c.step('s1', new TrailStep('a'))
             .step('s2', new NoCompStep('b'))
             .step('s3', new ThrowingStep('boom'))
        }) })
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        !result.success
        result.executedPath*.toString() == ['op', 'op/s1', 'op/s2', 'op/s3']
        result.compensatedPath*.toString() == ['op/s1']
        entity.trail == ['a', 'b', '-a']
        applied.isEmpty()
    }

    def 'compensation that itself throws is logged and skipped, remaining compensations still run'() {
        given:
        def applied = []
        def sm = build(applied, { t -> t.operation('op', { OperationDef<Entity, TestContext> c ->
            c.step('s1', new TrailStep('a'))
             .step('s2', new CompThrowsStep('b'))
             .step('s3', new ThrowingStep('boom'))
        }) })
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        !result.success
        result.executedPath*.toString() == ['op', 'op/s1', 'op/s2', 'op/s3']
        result.compensatedPath*.toString() == ['op/s2', 'op/s1']
        entity.trail == ['a', 'b', '-a']
        applied.isEmpty()
    }

    def 'pre-condition failure: no compensation runs, compensatedPath is empty'() {
        given:
        def applied = []
        def sm = build(applied, { t -> t
            .operation('op', { OperationDef<Entity, TestContext> c ->
                c.step('s1', new TrailStep('a'))
            })
            .preCondition('always-false', { Entity e -> false } as Predicate) })
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        !result.success
        result.executedPath.isEmpty()
        result.compensatedPath.isEmpty()
        entity.trail.isEmpty()
        applied.isEmpty()
    }

    def 'post-condition failure: step compensations stay on the stack and no compensation runs'() {
        given:
        def applied = []
        def sm = build(applied, { t -> t
            .operation('op', { OperationDef<Entity, TestContext> c ->
                c.step('s1', new TrailStep('a'))
                 .step('s2', new TrailStep('b'))
            })
            .postCondition('always-false', { Entity e -> false } as Predicate) })
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        !result.success
        result.executedPath*.toString() == ['op', 'op/s1', 'op/s2']
        result.compensatedPath.isEmpty()
        entity.trail == ['a', 'b']
        applied.isEmpty()
    }

    def 'transition.run("id") invocations also push compensation'() {
        given:
        def applied = []
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .withStateApplier({ e, s -> applied.add(s); e.state = s } as StateApplier<Entity>)
            .step('dynamic', new TrailStep('dyn'))
            .state('s1', { state -> state.transitionsTo('s2', 't', TestContext, { t ->
                t.operation('op', { OperationDef<Entity, TestContext> c ->
                    c.step('s1', new TrailStep('a'))
                     .step('s2', new DynamicDispatchStep())
                     .step('s3', new ThrowingStep('boom'))
                })
            }) })
            .state('s2', {})
        def sm = smd.build()
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        !result.success
        result.executedPath*.toString() == ['op', 'op/s1', 'op/s2', 'op/s2/dynamic', 'op/s3']
        result.compensatedPath*.toString() == ['op/s2/dynamic', 'op/s1']
        entity.trail == ['a', 'dyn', '-dyn', '-a']
        applied.isEmpty()
    }

    def 'action dispatched in operation position compensates: transition root by-id reference'() {
        given:
        def applied = []
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .withStateApplier({ e, s -> applied.add(s); e.state = s } as StateApplier<Entity>)
            .step('charge', new ThrowingWithCompStep('c'))
            .state('s1', { state -> state.transitionsTo('s2', 't', TestContext, { t -> t.run('charge') }) })
            .state('s2', {})
        def sm = smd.build()
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then: 'the callee is authored as a step; the call site names it with the operation verb'
        !result.success
        result.error.message == 'execute-blew-up-c'
        result.executedPath*.toString() == ['charge']
        result.compensatedPath*.toString() == ['charge']
        entity.trail == ['c', '-c']
        applied.isEmpty()
    }

    def 'action dispatched in operation position compensates: transition-root inline step'() {
        given:
        def applied = []
        def sm = build(applied, { t -> t.step('charge', new ThrowingWithCompStep('c')) })
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        !result.success
        result.error.message == 'execute-blew-up-c'
        result.compensatedPath*.toString() == ['charge']
        entity.trail == ['c', '-c']
        applied.isEmpty()
    }

    def 'action dispatched in operation position compensates: composite member, under the composite path'() {
        given:
        def applied = []
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .withStateApplier({ e, s -> applied.add(s); e.state = s } as StateApplier<Entity>)
            .step('charge', TestContext, new ThrowingWithCompStep('c'))
            .state('s1', { state -> state.transitionsTo('s2', 't', TestContext, { t ->
                t.operation('op', { OperationDef<Entity, TestContext> c ->
                    c.step('s1', new TrailStep('a'))
                     .run('charge')
                })
            }) })
            .state('s2', {})
        def sm = smd.build()
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        !result.success
        result.executedPath*.toString() == ['op', 'op/s1', 'op/charge']
        result.compensatedPath*.toString() == ['op/charge', 'op/s1']
        entity.trail == ['a', 'c', '-c', '-a']
        applied.isEmpty()
    }

    def 'mapped child operation captures its compensation against the child context'() {
        given:
        def applied = []
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .withStateApplier({ e, s -> applied.add(s); e.state = s } as StateApplier<Entity>)
            .step('charge', ChildCtx, new ChildCtxThrowingStep())
            .mapper('child-from-parent', TestContext, ChildCtx, new ChildCtxMapper())
            .state('s1', { state -> state.transitionsTo('s2', 't', TestContext, { t ->
                t.operation('op', { OperationDef<Entity, TestContext> c ->
                    c.run('charge', 'child-from-parent')
                })
            }) })
            .state('s2', {})
        def sm = smd.build()
        def entity = new Entity('s1')

        when:
        def result = sm.entity(entity).transitionTo('s2', new TestContext('parent-tag'))

        then: 'getCompensation sees the same context execute would have seen, not the parent one'
        !result.success
        result.error.message == 'child-blew-up'
        result.compensatedPath*.toString() == ['op/charge']
        entity.trail == ['-child:parent-tag']
        applied.isEmpty()
    }

    private static StateMachine<Entity> build(List<String> applied,
                                              Consumer<TransitionDef<Entity, TestContext>> transitionConfigurer) {
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .withStateApplier({ e, s -> applied.add(s); e.state = s } as StateApplier<Entity>)
            .state('s1', { state -> state.transitionsTo('s2', 't', TestContext, transitionConfigurer) })
            .state('s2', {})
        return smd.build()
    }
}
