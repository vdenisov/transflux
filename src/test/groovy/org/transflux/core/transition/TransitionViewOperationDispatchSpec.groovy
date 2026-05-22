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

package org.transflux.core.transition

import org.transflux.core.StateMachine
import org.transflux.core.StateMachineDefImpl
import org.transflux.core.operation.ContextMapper
import org.transflux.core.operation.Operation
import org.transflux.core.operation.Step
import org.transflux.core.operation.StepPath
import org.transflux.core.state.StateResolver
import spock.lang.Specification

import java.util.function.Consumer

class TransitionViewOperationDispatchSpec extends Specification {

    static class Entity {
        String state
        List<String> trail = []

        Entity(String state) {
            this.state = state
        }
    }

    static class ParentCtx {
        String input
        String output
    }

    static class ChildCtx {
        String input
        String output
    }

    static class ChildStep implements Step<Entity, ChildCtx> {
        @Override
        void execute(Entity entity, ChildCtx context, Transition<Entity, ChildCtx> transition) {
            context.output = 'step-saw-' + context.input
            entity.trail << ('step:' + context.input)
        }
    }

    static class ChildOperation implements Operation<Entity, ChildCtx> {
        @Override
        void execute(Entity entity, ChildCtx context, Transition<Entity, ChildCtx> transition) {
            context.output = 'op-saw-' + context.input
            entity.trail << ('op:' + context.input)
        }
    }

    static class PNMapper implements ContextMapper<ParentCtx, ChildCtx> {
        @Override
        ChildCtx mapTo(ParentCtx p) {
            def c = new ChildCtx()
            c.input = p.input
            return c
        }

        @Override
        void mapFrom(ParentCtx p, ChildCtx c) {
            p.output = c.output
        }
    }

    /** Simple operation that dispatches a child step via view.step(...). */
    static class DispatchingViaStep implements Operation<Entity, ParentCtx> {
        @Override
        void execute(Entity entity, ParentCtx context, Transition<Entity, ParentCtx> transition) {
            ((TransitionView) transition).step('child-step', new PNMapper())
        }
    }

    /** Simple operation that dispatches a child operation via view.operation(...). */
    static class DispatchingViaOperation implements Operation<Entity, ParentCtx> {
        @Override
        void execute(Entity entity, ParentCtx context, Transition<Entity, ParentCtx> transition) {
            ((TransitionView) transition).operation('child-op', new PNMapper())
        }
    }

    /** Pass-through dispatch via view.operation(id). */
    static class PassThroughOpDispatcher implements Operation<Entity, ParentCtx> {
        @Override
        void execute(Entity entity, ParentCtx context, Transition<Entity, ParentCtx> transition) {
            ((TransitionView) transition).operation('passthrough-op')
        }
    }

    /** Child op that runs with parent context unchanged. */
    static class PassThroughChildOp implements Operation<Entity, ParentCtx> {
        @Override
        void execute(Entity entity, ParentCtx context, Transition<Entity, ParentCtx> transition) {
            entity.trail << ('passthrough:' + context.input)
        }
    }

    def 'view.step with inline ContextMapper dispatches child step under mapped child context'() {
        given:
        def sm = build(
            { smd -> smd.step('child-step', ChildCtx, new ChildStep()) },
            { t -> t.simpleOperation('outer', new DispatchingViaStep()) })
        def entity = new Entity('s1')
        def ctx = new ParentCtx(input: 'foo')

        when:
        def result = sm.entity(entity).transitionTo('s2', ctx)

        then:
        result.success
        ctx.output == 'step-saw-foo'
        entity.trail == ['step:foo']
        result.executedStepIds == [StepPath.of('child-step')]
    }

    def 'view.operation with inline ContextMapper dispatches child operation under mapped child context'() {
        given:
        def sm = build(
            { smd -> smd.operation('child-op', ChildCtx, new ChildOperation()) },
            { t -> t.simpleOperation('outer', new DispatchingViaOperation()) })
        def entity = new Entity('s1')
        def ctx = new ParentCtx(input: 'bar')

        when:
        def result = sm.entity(entity).transitionTo('s2', ctx)

        then:
        result.success
        ctx.output == 'op-saw-bar'
        entity.trail == ['op:bar']
    }

    def 'view.operation pass-through invokes the operation with parent context unchanged'() {
        given:
        def sm = build(
            { smd -> smd.operation('passthrough-op', ParentCtx, new PassThroughChildOp()) },
            { t -> t.simpleOperation('outer', new PassThroughOpDispatcher()) })
        def entity = new Entity('s1')
        def ctx = new ParentCtx(input: 'baz')

        when:
        def result = sm.entity(entity).transitionTo('s2', ctx)

        then:
        result.success
        entity.trail == ['passthrough:baz']
    }

    def 'view.operation rejects unknown id'() {
        given:
        def dispatcher = new Operation<Entity, ParentCtx>() {
            @Override
            void execute(Entity entity, ParentCtx context, Transition<Entity, ParentCtx> transition) {
                ((TransitionView) transition).operation('does-not-exist')
            }
        }
        def sm = build({ smd -> }, { t -> t.simpleOperation('outer', dispatcher) })
        def entity = new Entity('s1')

        when:
        def result = sm.entity(entity).transitionTo('s2', new ParentCtx())

        then:
        !result.success
        result.error.message.contains("'does-not-exist'")
    }

    private static StateMachine<Entity> build(Consumer<StateMachineDefImpl<Entity>> smdRegistrations,
                                              Consumer<TransitionDef<Entity, ParentCtx>> transitionConfigurer) {
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
        smdRegistrations.accept(smd)
        smd.state('s1', { s -> s.transitionsTo('s2', 't', ParentCtx, transitionConfigurer) })
            .state('s2', {})
        return smd.build()
    }
}
