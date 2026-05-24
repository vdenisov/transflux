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
import org.transflux.core.operation.Compensation
import org.transflux.core.operation.CompositeOperationDef
import org.transflux.core.operation.Operation
import org.transflux.core.operation.Step
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.StepPath
import org.transflux.core.transition.Transition
import org.transflux.core.transition.TransitionDef
import spock.lang.Specification

import java.util.function.Consumer

class CompositeOperationDefImplNestedPassThroughSpec extends Specification {

    static class Entity {
        String state
        List<String> trail = []

        Entity(String state) {
            this.state = state
        }
    }

    static class TrailStep implements Step<Entity, TestContext> {
        final String tag

        TrailStep(String tag) {
            this.tag = tag
        }

        @Override
        void execute(Entity entity, TestContext context, Transition<Entity, TestContext> transition) {
            entity.trail << tag
        }
    }

    static class CompTrailStep implements Step<Entity, TestContext> {
        final String tag

        CompTrailStep(String tag) {
            this.tag = tag
        }

        @Override
        void execute(Entity entity, TestContext context, Transition<Entity, TestContext> transition) {
            entity.trail << tag
        }

        @Override
        Compensation<Entity, TestContext> getCompensation(Entity entity, TestContext context) {
            String captured = tag
            return { Entity e, TestContext c -> e.trail << ('-' + captured) } as Compensation<Entity, TestContext>
        }
    }

    /**
     * Inline-class nested operation that drives two child steps by id from inside its
     * {@code execute(...)} body. The child step ids resolve against the SM registry and run
     * inside the nested op's scope, so they emit qualified paths.
     */
    static class TwoStepInlineOp implements Operation<Entity, TestContext> {
        @Override
        void execute(Entity entity, TestContext context, Transition<Entity, TestContext> transition) {
            transition.step('inner-a')
            transition.step('inner-b')
        }
    }

    static class CompensatingNestedOp implements Operation<Entity, TestContext> {
        @Override
        void execute(Entity entity, TestContext context, Transition<Entity, TestContext> transition) {
            transition.step('comp-step')
            throw new RuntimeException('nested-boom')
        }
    }

    def 'inline-instance nested op runs and emits qualified step paths'() {
        given:
        def applied = []
        def sm = build(applied,
            { smd -> smd.step('inner-a', new TrailStep('a')).step('inner-b', new TrailStep('b')) },
            { t -> t.compositeOperation('outer', { CompositeOperationDef<Entity, TestContext> c ->
                c.step('top', new TrailStep('top'))
                c.operation('nested-op', new TwoStepInlineOp())
                c.step('after', new TrailStep('after'))
            }) })
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        result.success
        entity.trail == ['top', 'a', 'b', 'after']
        result.executedPath == [
            StepPath.of('outer'),
            StepPath.of('outer', 'top'),
            StepPath.of('outer', 'nested-op'),
            StepPath.of('outer', 'nested-op', 'inner-a'),
            StepPath.of('outer', 'nested-op', 'inner-b'),
            StepPath.of('outer', 'after')]
        applied == ['s2']
    }

    def 'inline-class nested op is reflectively instantiated and runs'() {
        given:
        def applied = []
        def sm = build(applied,
            { smd -> smd.step('inner-a', new TrailStep('a')).step('inner-b', new TrailStep('b')) },
            { t -> t.compositeOperation('outer', { CompositeOperationDef<Entity, TestContext> c ->
                c.operation('nested-op', TwoStepInlineOp)
            }) })
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        result.success
        result.executedPath == [
            StepPath.of('outer'),
            StepPath.of('outer', 'nested-op'),
            StepPath.of('outer', 'nested-op', 'inner-a'),
            StepPath.of('outer', 'nested-op', 'inner-b')]
    }

    def 'by-id nested-op ref resolves an inline-registered operation from another composite'() {
        // The same SM cannot have two composites on transitions today, so we register the
        // operation inline on the same composite and reference it by id from a second member.
        // This exercises the OperationById branch of the resolver while exercising the unified
        // id namespace.
        given:
        def applied = []
        def sm = build(applied,
            { smd -> smd.step('inner-a', new TrailStep('a')).step('inner-b', new TrailStep('b')) },
            { t -> t.compositeOperation('outer', { CompositeOperationDef<Entity, TestContext> c ->
                c.operation('shared', new TwoStepInlineOp())
                c.operation('shared')
            }) })
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        result.success
        result.executedPath == [
            StepPath.of('outer'),
            StepPath.of('outer', 'shared'),
            StepPath.of('outer', 'shared', 'inner-a'),
            StepPath.of('outer', 'shared', 'inner-b'),
            StepPath.of('outer', 'shared'),
            StepPath.of('outer', 'shared', 'inner-a'),
            StepPath.of('outer', 'shared', 'inner-b')]
    }

    def 'qualified paths apply to compensatedPath too'() {
        given:
        def applied = []
        def sm = build(applied,
            { smd -> smd.step('comp-step', new CompTrailStep('cs')) },
            { t -> t.compositeOperation('outer', { CompositeOperationDef<Entity, TestContext> c ->
                c.operation('nested-op', new CompensatingNestedOp())
            }) })
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        !result.success
        result.error.message == 'nested-boom'
        result.compensatedPath == [StepPath.of('outer', 'nested-op', 'comp-step')]
        // The applier never runs on failure.
        applied.isEmpty()
        // The compensation ran, leaving its trail entry.
        entity.trail.contains('-cs')
    }

    def 'composite with only operation members (no steps) builds and runs'() {
        given:
        def applied = []
        def sm = build(applied,
            { smd -> smd.step('inner-a', new TrailStep('a')).step('inner-b', new TrailStep('b')) },
            { t -> t.compositeOperation('outer', { CompositeOperationDef<Entity, TestContext> c ->
                c.operation('only-op', new TwoStepInlineOp())
            }) })
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        result.success
        result.executedPath == [
            StepPath.of('outer'),
            StepPath.of('outer', 'only-op'),
            StepPath.of('outer', 'only-op', 'inner-a'),
            StepPath.of('outer', 'only-op', 'inner-b')]
    }

    def 'by-id ref to an unknown operation id fails at SM build time'() {
        given:
        def applied = []

        when:
        build(applied, { smd -> }, { t ->
            t.compositeOperation('outer', { CompositeOperationDef<Entity, TestContext> c ->
                c.operation('ghost')
            })
        })

        then:
        def e = thrown(org.transflux.core.exception.TransfluxValidationException)
        e.message.contains('outer')
        e.message.contains("'ghost'")
    }

    private static StateMachine<Entity> build(List<String> applied,
                                              Consumer<StateMachineDefImpl<Entity>> smdRegistrations,
                                              Consumer<TransitionDef<Entity, TestContext>> transitionConfigurer) {
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .withStateApplier({ e, s -> applied.add(s); e.state = s } as StateApplier<Entity>)
        smdRegistrations.accept(smd)
        smd.state('s1', { state -> state.transitionsTo('s2', 't', TestContext, transitionConfigurer) })
            .state('s2', {})
        return smd.build()
    }
}
