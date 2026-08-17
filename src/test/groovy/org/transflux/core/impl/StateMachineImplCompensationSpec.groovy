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
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.action.BranchDef
import org.transflux.core.action.Compensation
import org.transflux.core.action.OperationDef
import org.transflux.core.action.ContextMapper
import org.transflux.core.action.Action
import org.transflux.core.action.StepDef
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.ExecutingTransition
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
        void execute(Entity entity, TestContext context, ExecutingTransition<Entity, TestContext> transition) {
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
        void execute(Entity entity, TestContext context, ExecutingTransition<Entity, TestContext> transition) {
            throw new RuntimeException(message)
        }
    }

    static class NoCompStep implements Action<Entity, TestContext> {
        final String tag

        NoCompStep(String tag) {
            this.tag = tag
        }

        @Override
        void execute(Entity entity, TestContext context, ExecutingTransition<Entity, TestContext> transition) {
            entity.trail << tag
        }
    }

    static class CompThrowsStep implements Action<Entity, TestContext> {
        final String tag

        CompThrowsStep(String tag) {
            this.tag = tag
        }

        @Override
        void execute(Entity entity, TestContext context, ExecutingTransition<Entity, TestContext> transition) {
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
        void execute(Entity entity, TestContext context, ExecutingTransition<Entity, TestContext> transition) {
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
        void execute(Entity entity, TestContext context, ExecutingTransition<Entity, TestContext> transition) {
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

    /** Class-form declared compensation; writes through the entity, so it needs no shared state. */
    static class TrailCompensation implements Compensation<Entity, TestContext> {
        @Override
        void compensate(Entity entity, TestContext context) {
            entity.trail << '-declared'
        }
    }

    /** Reports the context it was handed, to pin which one the drain hands back. */
    static class ChildCtxCompensation implements Compensation<Entity, ChildCtx> {
        @Override
        void compensate(Entity entity, ChildCtx context) {
            entity.trail << ('-child:' + (context == null ? 'null-ctx' : context.tag))
        }
    }

    /** Throws with no compensation of its own, so a declared one is the only rollback. */
    static class PlainChildCtxThrowingStep implements Action<Entity, ChildCtx> {
        @Override
        void execute(Entity entity, ChildCtx context, ExecutingTransition<Entity, ChildCtx> transition) {
            throw new RuntimeException('child-blew-up')
        }
    }

    /** Marks the child context so an assertion can tell it from the parent one. */
    static class DerivingChildCtxMapper implements ContextMapper<TestContext, ChildCtx> {
        @Override
        ChildCtx mapTo(TestContext parent) {
            return new ChildCtx(tag: parent.tag + '-mapped')
        }
    }

    /** Fails after the boundary mapping, and reports the context its compensation was handed. */
    static class ChildCtxThrowingStep implements Action<Entity, ChildCtx> {
        @Override
        void execute(Entity entity, ChildCtx context, ExecutingTransition<Entity, ChildCtx> transition) {
            throw new RuntimeException('child-blew-up')
        }

        @Override
        Compensation<Entity, ChildCtx> getCompensation(Entity entity, ChildCtx context) {
            String captured = context == null ? 'null-ctx' : context.tag
            // Captured at push time rather than read off the parameter, so the assertion pins the
            // context this step actually ran against even if the drain were to hand back another.
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
        void execute(Entity entity, TestContext context, ExecutingTransition<Entity, TestContext> transition) {
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

    def 'post-condition failure: compensations unwind in LIFO order and no state is applied'() {
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
        result.error instanceof TransfluxValidationException
        result.error.message == "Post-condition 'always-false' failed for transition 't'"
        result.executedPath*.toString() == ['op', 'op/s1', 'op/s2']
        result.compensatedPath*.toString() == ['op/s2', 'op/s1']
        entity.trail == ['a', 'b', '-b', '-a']
        applied.isEmpty()
    }

    def 'post-condition that throws unwinds the same way'() {
        given:
        def applied = []
        def sm = build(applied, { t -> t
            .operation('op', { OperationDef<Entity, TestContext> c ->
                c.step('s1', new TrailStep('a'))
                 .step('s2', new TrailStep('b'))
            })
            .postCondition('blows-up', { Entity e -> throw new RuntimeException('post-blew-up') } as Predicate) })
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        !result.success
        result.error.message == 'post-blew-up'
        result.compensatedPath*.toString() == ['op/s2', 'op/s1']
        entity.trail == ['a', 'b', '-b', '-a']
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

    def 'declared compensation runs: instance form on a transition-root step'() {
        given:
        def applied = []
        def sm = build(applied, { t -> t.step('charge', { StepDef<Entity, TestContext> s -> s
            .using(new ThrowingStep('boom'))
            .withCompensation({ Entity e, TestContext c -> e.trail << '-declared' } as Compensation) }) })
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        !result.success
        result.compensatedPath*.toString() == ['charge']
        entity.trail == ['-declared']
        applied.isEmpty()
    }

    def 'declared compensation runs: class form, instantiated at build'() {
        given:
        def applied = []
        def sm = build(applied, { t -> t.step('charge', { StepDef<Entity, TestContext> s -> s
            .using(new ThrowingStep('boom'))
            .withCompensation(TrailCompensation) }) })
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        result.compensatedPath*.toString() == ['charge']
        entity.trail == ['-declared']
    }

    def "declared compensation wins over the action's own getCompensation"() {
        given: 'ThrowingWithCompStep would append -b; the declaration replaces it rather than adding to it'
        def applied = []
        def sm = build(applied, { t -> t.step('charge', { StepDef<Entity, TestContext> s -> s
            .using(new ThrowingWithCompStep('b'))
            .withCompensation(TrailCompensation) }) })
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        result.compensatedPath*.toString() == ['charge']
        entity.trail == ['b', '-declared']
    }

    def "container compensation is additive and unwinds after its members'"() {
        given:
        def applied = []
        def sm = build(applied, { t -> t.operation('op', { OperationDef<Entity, TestContext> c -> c
            .withCompensation({ Entity e, TestContext ctx -> e.trail << '-op' } as Compensation)
            .step('s1', new TrailStep('a'))
            .step('s2', new ThrowingStep('boom')) }) })
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then: 'the container is pushed on entry, so it drains last'
        !result.success
        result.compensatedPath*.toString() == ['op/s1', 'op']
        entity.trail == ['a', '-a', '-op']
        applied.isEmpty()
    }

    def 'container compensation runs even when its first member fails immediately'() {
        given:
        def applied = []
        def sm = build(applied, { t -> t.operation('op', { OperationDef<Entity, TestContext> c -> c
            .withCompensation({ Entity e, TestContext ctx -> e.trail << '-op' } as Compensation)
            .step('s1', new ThrowingStep('boom')) }) })
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then: 'the container had done nothing of its own yet, and still compensates'
        result.compensatedPath*.toString() == ['op']
        entity.trail == ['-op']
    }

    def 'conditional compensation runs when a branch member fails'() {
        given:
        def applied = []
        def sm = build(applied, { t -> t.operation('op', { OperationDef<Entity, TestContext> c ->
            c.conditional('route', { cs -> cs
                .withCompensation({ Entity e, TestContext ctx -> e.trail << '-cond' } as Compensation)
                .branch('always', { BranchDef<Entity, TestContext> b -> b
                    .condition('yes', { Entity e -> true } as Predicate)
                    .step('inner', new ThrowingStep('boom')) }) })
        }) })
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        !result.success
        result.executedPath*.toString() == ['op', 'op/route', 'op/route/inner']
        result.compensatedPath*.toString() == ['op/route']
        entity.trail == ['-cond']
    }

    def 'declared compensation on a container member and on a branch member'() {
        given:
        def applied = []
        def sm = build(applied, { t -> t.operation('op', { OperationDef<Entity, TestContext> c -> c
            .step('m1', { StepDef<Entity, TestContext> s -> s
                .using(new NoCompStep('m'))
                .withCompensation({ Entity e, TestContext ctx -> e.trail << '-m1' } as Compensation) })
            .conditional('route', { cs -> cs
                .branch('always', { BranchDef<Entity, TestContext> b -> b
                    .condition('yes', { Entity e -> true } as Predicate)
                    .step('m2', { StepDef<Entity, TestContext> s -> s
                        .using(new ThrowingStep('boom'))
                        .withCompensation({ Entity e, TestContext ctx -> e.trail << '-m2' } as Compensation) }) }) })
        }) })
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        !result.success
        result.compensatedPath*.toString() == ['op/route/m2', 'op/m1']
        entity.trail == ['m', '-m2', '-m1']
    }

    def 'declared compensation on a mapped child action receives the child context'() {
        given: 'the step is registered at SM level and reached by id through a mapper'
        def applied = []
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .withStateApplier({ e, s -> applied.add(s); e.state = s } as StateApplier<Entity>)
            .step('charge', ChildCtx, { StepDef<Entity, ChildCtx> s -> s
                .using(new PlainChildCtxThrowingStep())
                .withCompensation(ChildCtxCompensation) })
            .mapper('child-from-parent', TestContext, ChildCtx, new DerivingChildCtxMapper())
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

        then: 'the drain hands back the context the action ran against, not the enclosing one'
        !result.success
        result.error.message == 'child-blew-up'
        result.compensatedPath*.toString() == ['op/charge']
        entity.trail == ['-child:parent-tag-mapped']
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
