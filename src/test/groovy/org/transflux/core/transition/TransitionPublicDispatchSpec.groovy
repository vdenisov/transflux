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

import org.transflux.core.Identifiable
import org.transflux.core.StateMachine
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.impl.StateMachineDefImpl
import org.transflux.core.operation.ContextMapper
import org.transflux.core.operation.Operation
import org.transflux.core.operation.Step
import org.transflux.core.state.StateResolver
import spock.lang.Specification

import java.util.function.Consumer
import java.util.function.Function

/**
 * Exercises the {@link Transition} dispatch surface from inside an
 * {@link Operation#execute} body without casting to the framework-internal
 * {@code TransitionView}. Confirms that {@code requirements.md} §2.5.3's
 * "same five forms on step/operation" contract is reachable from user code.
 */
class TransitionPublicDispatchSpec extends Specification {

    private static Identifiable id(String value) {
        return { -> value } as Identifiable
    }

    static class Entity {
        String state
        List<String> trail = []

        Entity(String state) { this.state = state }
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

    /** Pass-through child op typed against the parent context. */
    static class ParentPassThroughOp implements Operation<Entity, ParentCtx> {
        @Override
        void execute(Entity entity, ParentCtx context, Transition<Entity, ParentCtx> transition) {
            entity.trail << ('pt:' + context.input)
        }
    }

    def 'transition.step(id, mapperId) resolves the registered mapper and runs the child step'() {
        given:
        def sm = build(
            { smd ->
                smd.step('child-step', ChildCtx, new ChildStep())
                smd.mapper('pn', ParentCtx, ChildCtx, new PNMapper())
            },
            { t ->
                t.simpleOperation('outer', { entity, ctx, transition ->
                    transition.step('child-step', 'pn')
                } as Operation<Entity, ParentCtx>)
            })
        def entity = new Entity('s1')
        def ctx = new ParentCtx(input: 'foo')

        when:
        def result = sm.entity(entity).transitionTo('s2', ctx)

        then:
        result.success
        ctx.output == 'step-saw-foo'
        entity.trail == ['step:foo']
    }

    def 'transition.step(id, Function) wraps the projection and runs the child step'() {
        given:
        Function<ParentCtx, ChildCtx> mapTo = { p ->
            def c = new ChildCtx()
            c.input = p.input
            return c
        }
        def sm = build(
            { smd -> smd.step('child-step', ChildCtx, new ChildStep()) },
            { t ->
                t.simpleOperation('outer', { entity, ctx, transition ->
                    transition.step('child-step', mapTo)
                } as Operation<Entity, ParentCtx>)
            })
        def entity = new Entity('s1')
        def ctx = new ParentCtx(input: 'bar')

        when:
        def result = sm.entity(entity).transitionTo('s2', ctx)

        then:
        result.success
        // mapFrom is a default no-op on Function-wrapped mappers — output stays untouched.
        ctx.output == null
        entity.trail == ['step:bar']
    }

    def 'transition.step(id, ContextMapper) runs the child step under the mapped child context'() {
        given:
        def sm = build(
            { smd -> smd.step('child-step', ChildCtx, new ChildStep()) },
            { t ->
                t.simpleOperation('outer', { entity, ctx, transition ->
                    transition.step('child-step', new PNMapper())
                } as Operation<Entity, ParentCtx>)
            })
        def entity = new Entity('s1')
        def ctx = new ParentCtx(input: 'baz')

        when:
        def result = sm.entity(entity).transitionTo('s2', ctx)

        then:
        result.success
        ctx.output == 'step-saw-baz'
        entity.trail == ['step:baz']
        result.executedStepIds == [StepPath.of('child-step')]
    }

    def 'transition.operation(id) runs the registered operation in pass-through mode'() {
        given:
        def sm = build(
            { smd -> smd.operation('passthrough-op', ParentCtx, new ParentPassThroughOp()) },
            { t ->
                t.simpleOperation('outer', { entity, ctx, transition ->
                    transition.operation('passthrough-op')
                } as Operation<Entity, ParentCtx>)
            })
        def entity = new Entity('s1')
        def ctx = new ParentCtx(input: 'pt')

        when:
        def result = sm.entity(entity).transitionTo('s2', ctx)

        then:
        result.success
        entity.trail == ['pt:pt']
    }

    def 'transition.operation(id, mapperId) routes the operation through the registered mapper'() {
        given:
        def sm = build(
            { smd ->
                smd.operation('child-op', ChildCtx, new ChildOperation())
                smd.mapper('pn', ParentCtx, ChildCtx, new PNMapper())
            },
            { t ->
                t.simpleOperation('outer', { entity, ctx, transition ->
                    transition.operation('child-op', 'pn')
                } as Operation<Entity, ParentCtx>)
            })
        def entity = new Entity('s1')
        def ctx = new ParentCtx(input: 'qux')

        when:
        def result = sm.entity(entity).transitionTo('s2', ctx)

        then:
        result.success
        ctx.output == 'op-saw-qux'
        entity.trail == ['op:qux']
    }

    def 'transition.operation(id, Function) runs the operation under the projected child context'() {
        given:
        Function<ParentCtx, ChildCtx> mapTo = { p ->
            def c = new ChildCtx()
            c.input = p.input
            return c
        }
        def sm = build(
            { smd -> smd.operation('child-op', ChildCtx, new ChildOperation()) },
            { t ->
                t.simpleOperation('outer', { entity, ctx, transition ->
                    transition.operation('child-op', mapTo)
                } as Operation<Entity, ParentCtx>)
            })
        def entity = new Entity('s1')
        def ctx = new ParentCtx(input: 'fn')

        when:
        def result = sm.entity(entity).transitionTo('s2', ctx)

        then:
        result.success
        entity.trail == ['op:fn']
        // Function-wrapped mapper has no-op mapFrom.
        ctx.output == null
    }

    def 'transition.operation(id, ContextMapper) runs the operation under the mapped child context'() {
        given:
        def sm = build(
            { smd -> smd.operation('child-op', ChildCtx, new ChildOperation()) },
            { t ->
                t.simpleOperation('outer', { entity, ctx, transition ->
                    transition.operation('child-op', new PNMapper())
                } as Operation<Entity, ParentCtx>)
            })
        def entity = new Entity('s1')
        def ctx = new ParentCtx(input: 'cm')

        when:
        def result = sm.entity(entity).transitionTo('s2', ctx)

        then:
        result.success
        ctx.output == 'op-saw-cm'
        entity.trail == ['op:cm']
    }

    def 'transition.step(Identifiable) dispatches the same as step(String)'() {
        given:
        def sm = build(
            { smd -> smd.step('my-step', ParentCtx, new Step<Entity, ParentCtx>() {
                @Override
                void execute(Entity entity, ParentCtx context, Transition<Entity, ParentCtx> transition) {
                    entity.trail << ('step:' + context.input)
                }
            }) },
            { t ->
                t.simpleOperation('outer', { entity, ctx, transition ->
                    transition.step(id('my-step'))
                } as Operation<Entity, ParentCtx>)
            })

        when:
        def result = sm.entity(new Entity('s1')).transitionTo('s2', new ParentCtx(input: 'foo'))

        then:
        result.success
        result.entity.trail == ['step:foo']
    }

    def 'transition.operation(Identifiable) dispatches the same as operation(String)'() {
        given:
        def sm = build(
            { smd -> smd.operation('my-op', ParentCtx, new Operation<Entity, ParentCtx>() {
                @Override
                void execute(Entity entity, ParentCtx context, Transition<Entity, ParentCtx> transition) {
                    entity.trail << ('op:' + context.input)
                }
            }) },
            { t ->
                t.simpleOperation('outer', { entity, ctx, transition ->
                    transition.operation(id('my-op'))
                } as Operation<Entity, ParentCtx>)
            })

        when:
        def result = sm.entity(new Entity('s1')).transitionTo('s2', new ParentCtx(input: 'bar'))

        then:
        result.success
        result.entity.trail == ['op:bar']
    }

    def 'all Transition Identifiable overloads fail the transition on null'() {
        given:
        def sm = build(
            { smd -> },
            { t ->
                t.simpleOperation('outer', { entity, ctx, transition ->
                    action.call(transition)
                } as Operation<Entity, ParentCtx>)
            })

        when:
        def result = sm.entity(new Entity('s1')).transitionTo('s2', new ParentCtx())

        then:
        !result.success
        result.error instanceof TransfluxValidationException

        where:
        action << [
            { Transition t -> t.step((Identifiable) null) },
            { Transition t -> t.step((Identifiable) null, id('m')) },
            { Transition t -> t.step((Identifiable) null, 'm') },
            { Transition t -> t.step('s', (Identifiable) null) },
            { Transition t -> t.operation((Identifiable) null) },
            { Transition t -> t.operation((Identifiable) null, id('m')) },
            { Transition t -> t.operation((Identifiable) null, 'm') },
            { Transition t -> t.operation('o', (Identifiable) null) },
        ]
    }

    def 'transition.operation rejects unknown id'() {
        given:
        def sm = build(
            { smd -> },
            { t ->
                t.simpleOperation('outer', { entity, ctx, transition ->
                    transition.operation('does-not-exist')
                } as Operation<Entity, ParentCtx>)
            })
        def entity = new Entity('s1')

        when:
        def result = sm.entity(entity).transitionTo('s2', new ParentCtx())

        then:
        !result.success
        result.error instanceof TransfluxValidationException
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
