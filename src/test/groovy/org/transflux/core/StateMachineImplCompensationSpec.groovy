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

import org.transflux.core.operation.Compensation
import org.transflux.core.operation.CompositeOperationDef
import org.transflux.core.operation.Step
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.Transition
import spock.lang.Specification

import java.util.function.Predicate

class StateMachineImplCompensationSpec extends Specification {

    static class Entity {
        String state
        List<String> trail = []

        Entity(String state) {
            this.state = state
        }
    }

    /**
     * Step that appends {@code tag} to the entity's trail on execute, and registers a
     * compensation that appends {@code "-tag"} (so trail reflects execute order followed
     * by reverse compensation order).
     */
    static class TrailStep implements Step<Entity, TestContext> {
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

    /** Step that throws unconditionally from execute, with no compensation. */
    static class ThrowingStep implements Step<Entity, TestContext> {
        final String message

        ThrowingStep(String message) {
            this.message = message
        }

        @Override
        void execute(Entity entity, TestContext context, Transition<Entity, TestContext> transition) {
            throw new RuntimeException(message)
        }
    }

    /** Step with no compensation (default getCompensation returns null). */
    static class NoCompStep implements Step<Entity, TestContext> {
        final String tag

        NoCompStep(String tag) {
            this.tag = tag
        }

        @Override
        void execute(Entity entity, TestContext context, Transition<Entity, TestContext> transition) {
            entity.trail << tag
        }
    }

    /** Step whose compensation itself throws. */
    static class CompThrowsStep implements Step<Entity, TestContext> {
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

    /**
     * Step that records a partial side effect (appends {@code tag} to the trail) and then
     * throws from execute. Its compensation appends {@code "-tag"} — pushed before execute
     * runs, so the rollback fires even though execute never completed.
     */
    static class ThrowingWithCompStep implements Step<Entity, TestContext> {
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

    /**
     * Step that creates entries in a shared {@code createdIds} list one at a time, and throws
     * partway through. Its compensation drains whatever's in {@code createdIds} into
     * {@code deletedIds} — read at rollback time, so partial creations made before the throw
     * are visible and get cleaned up.
     */
    static class PartialCreateStep implements Step<Entity, TestContext> {
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

    /**
     * Step that dispatches dynamically into another registered step via
     * {@code transition.step('dynamic')}.
     */
    static class DynamicDispatchStep implements Step<Entity, TestContext> {
        @Override
        void execute(Entity entity, TestContext context, Transition<Entity, TestContext> transition) {
            transition.step('dynamic')
        }
    }

    def 'three-step composite, step 3 throws: compensations run in reverse and applier is skipped'() {
        given:
        def applied = []
        def smd = baseDef(applied)
        smd.getTransition('t').compositeOperation('op', { CompositeOperationDef<Entity, TestContext> c ->
            c.step('s1', new TrailStep('a'))
             .step('s2', new TrailStep('b'))
             .step('s3', new ThrowingStep('boom'))
        })
        def sm = smd.build()
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        !result.success
        result.error instanceof RuntimeException
        result.error.message == 'boom'
        // Failing step's id is NOT on executedStepIds (executed = completed).
        result.executedStepIds*.toString() == ['s1', 's2']
        // s3 (ThrowingStep) has no compensation, so only s1 and s2 are on the stack.
        // Compensations run in LIFO of pushes (reverse execution order).
        result.compensatedStepIds*.toString() == ['s2', 's1']
        entity.trail == ['a', 'b', '-b', '-a']
        applied.isEmpty()
    }

    def "throwing step's compensation also runs (captured before execute)"() {
        given:
        def applied = []
        def smd = baseDef(applied)
        smd.getTransition('t').compositeOperation('op', { CompositeOperationDef<Entity, TestContext> c ->
            c.step('s1', new TrailStep('a'))
             .step('s2', new ThrowingWithCompStep('b'))
        })
        def sm = smd.build()
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        !result.success
        result.error.message == 'execute-blew-up-b'
        // s2's execute threw partway through → its id is NOT on executedStepIds.
        result.executedStepIds*.toString() == ['s1']
        // But s2's compensation was pushed BEFORE its execute ran, so the rollback fires.
        result.compensatedStepIds*.toString() == ['s2', 's1']
        // Trail shows the partial side effect (b) followed by reverse-order rollback.
        entity.trail == ['a', 'b', '-b', '-a']
        applied.isEmpty()
    }

    def 'partial-failure cleanup: compensation reads state mutated by execute before the throw'() {
        given:
        def applied = []
        def createdIds = []
        def deletedIds = []
        def smd = baseDef(applied)
        smd.getTransition('t').compositeOperation('op', { CompositeOperationDef<Entity, TestContext> c ->
            c.step('create', new PartialCreateStep(10, 5, createdIds, deletedIds))
        })
        def sm = smd.build()
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        !result.success
        result.error.message == 'external-service-failed-at-5'
        // Step's execute threw before completing → not on executedStepIds.
        result.executedStepIds == []
        // Compensation was pushed before execute and runs against the partially-populated list.
        result.compensatedStepIds*.toString() == ['create']
        // Five entities were created before the throw; the compensation deletes all five.
        createdIds == ['entity-0', 'entity-1', 'entity-2', 'entity-3', 'entity-4']
        deletedIds == ['entity-0', 'entity-1', 'entity-2', 'entity-3', 'entity-4']
        applied.isEmpty()
    }

    def 'step with null compensation registers nothing'() {
        given:
        def applied = []
        def smd = baseDef(applied)
        smd.getTransition('t').compositeOperation('op', { CompositeOperationDef<Entity, TestContext> c ->
            c.step('s1', new TrailStep('a'))
             .step('s2', new NoCompStep('b'))
             .step('s3', new ThrowingStep('boom'))
        })
        def sm = smd.build()
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        !result.success
        result.executedStepIds*.toString() == ['s1', 's2']
        result.compensatedStepIds*.toString() == ['s1']
        entity.trail == ['a', 'b', '-a']
        applied.isEmpty()
    }

    def 'compensation that itself throws is logged and skipped, remaining compensations still run'() {
        given:
        def applied = []
        def smd = baseDef(applied)
        smd.getTransition('t').compositeOperation('op', { CompositeOperationDef<Entity, TestContext> c ->
            c.step('s1', new TrailStep('a'))
             .step('s2', new CompThrowsStep('b'))
             .step('s3', new ThrowingStep('boom'))
        })
        def sm = smd.build()
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        !result.success
        result.executedStepIds*.toString() == ['s1', 's2']
        // The throwing compensation's stepId is still recorded — the rollback was attempted.
        result.compensatedStepIds*.toString() == ['s2', 's1']
        // s2's compensation threw before mutating the trail; s1's compensation still ran.
        entity.trail == ['a', 'b', '-a']
        applied.isEmpty()
    }

    def 'pre-condition failure: no compensation runs, compensatedStepIds is empty'() {
        given:
        def applied = []
        def smd = baseDef(applied)
        smd.getTransition('t').compositeOperation('op', { CompositeOperationDef<Entity, TestContext> c ->
            c.step('s1', new TrailStep('a'))
        })
        smd.getTransition('t').preCondition('always-false', { Entity e -> false } as Predicate)
        def sm = smd.build()
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        !result.success
        result.executedStepIds.isEmpty()
        result.compensatedStepIds.isEmpty()
        entity.trail.isEmpty()
        applied.isEmpty()
    }

    def 'post-condition failure: step compensations stay on the stack and no compensation runs'() {
        given:
        def applied = []
        def smd = baseDef(applied)
        smd.getTransition('t').compositeOperation('op', { CompositeOperationDef<Entity, TestContext> c ->
            c.step('s1', new TrailStep('a'))
             .step('s2', new TrailStep('b'))
        })
        smd.getTransition('t').postCondition('always-false', { Entity e -> false } as Predicate)
        def sm = smd.build()
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        !result.success
        result.executedStepIds*.toString() == ['s1', 's2']
        result.compensatedStepIds.isEmpty()
        // No compensation entries appended — only the original execute calls.
        entity.trail == ['a', 'b']
        applied.isEmpty()
    }

    def 'transition.step("id") invocations also push compensation'() {
        given:
        def applied = []
        def smd = new StateMachineDefImpl<Entity, TestContext>()
        smd.forEntityType(Entity)
            .forContextType(TestContext)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .withStateApplier({ e, s -> applied.add(s); e.state = s } as StateApplier<Entity>)
            .step('dynamic', new TrailStep('dyn'))
            .state('s1').transitionsTo('s2', 't')
            .state('s2')
        smd.getTransition('t').compositeOperation('op', { CompositeOperationDef<Entity, TestContext> c ->
            c.step('s1', new TrailStep('a'))
             .step('s2', new DynamicDispatchStep())
             .step('s3', new ThrowingStep('boom'))
        })
        def sm = smd.build()
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        !result.success
        // s2 (DynamicDispatchStep) calls transition.step('dynamic'); that nested call pushes
        // 'dynamic' inside s2's own execute. DynamicDispatchStep has no compensation of its
        // own, and s3 (ThrowingStep) has none either, so the stack ends up bottom -> top:
        // [s1, dynamic].
        result.executedStepIds*.toString() == ['s1', 'dynamic', 's2']
        result.compensatedStepIds*.toString() == ['dynamic', 's1']
        entity.trail == ['a', 'dyn', '-dyn', '-a']
        applied.isEmpty()
    }

    private static StateMachineDefImpl<Entity, TestContext> baseDef(List<String> applied) {
        def smd = new StateMachineDefImpl<Entity, TestContext>()
        smd.forEntityType(Entity)
            .forContextType(TestContext)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .withStateApplier({ e, s -> applied.add(s); e.state = s } as StateApplier<Entity>)
            .state('s1').transitionsTo('s2', 't')
            .state('s2')
        return smd
    }
}
