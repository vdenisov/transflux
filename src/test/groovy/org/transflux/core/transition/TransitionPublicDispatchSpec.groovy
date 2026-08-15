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
import org.transflux.core.action.ContextMapper
import org.transflux.core.action.Action
import org.transflux.core.state.StateResolver
import spock.lang.Specification

import java.util.function.Consumer
import java.util.function.Function

/**
 * Exercises the {@link Transition} dispatch surface from inside an
 * {@link Action#execute} body without casting to the framework-internal
 * {@code TransitionView}. Confirms that {@code requirements.md} §2.5.3's
 * "same five forms on step/operation" contract is reachable from user code.
 */
class TransitionPublicDispatchSpec extends Specification {

    def 'transition.run(id, mapperId) resolves the registered mapper and runs the child step'() {
        given:
        def sm = build(
            { smd ->
                smd.step('child-step', ChildCtx, new ChildStep())
                smd.mapper('pn', ParentCtx, ChildCtx, new PNMapper())
            },
            { t ->
                t.step('outer', { entity, ctx, transition ->
                    transition.run('child-step', 'pn')
                } as Action<Entity, ParentCtx>)
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

    def 'transition.run(id, Function) wraps the projection and runs the child step'() {
        given:
        Function<ParentCtx, ChildCtx> mapTo = { p ->
            def c = new ChildCtx()
            c.input = p.input
            return c
        }
        def sm = build(
            { smd -> smd.step('child-step', ChildCtx, new ChildStep()) },
            { t ->
                t.step('outer', { entity, ctx, transition ->
                    transition.run('child-step', mapTo)
                } as Action<Entity, ParentCtx>)
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

    def 'transition.run(id, ContextMapper) runs the child step under the mapped child context'() {
        given:
        def sm = build(
            { smd -> smd.step('child-step', ChildCtx, new ChildStep()) },
            { t ->
                t.step('outer', { entity, ctx, transition ->
                    transition.run('child-step', new PNMapper())
                } as Action<Entity, ParentCtx>)
            })
        def entity = new Entity('s1')
        def ctx = new ParentCtx(input: 'baz')

        when:
        def result = sm.entity(entity).transitionTo('s2', ctx)

        then:
        result.success
        ctx.output == 'step-saw-baz'
        entity.trail == ['step:baz']
        result.executedPath == [ActionPath.of('outer'), ActionPath.of('outer', 'child-step')]
    }

    def 'transition.run(id) runs the registered operation in pass-through mode'() {
        given:
        def sm = build(
            { smd -> smd.step('passthrough-op', ParentCtx, new ParentPassThroughOp()) },
            { t ->
                t.step('outer', { entity, ctx, transition ->
                    transition.run('passthrough-op')
                } as Action<Entity, ParentCtx>)
            })
        def entity = new Entity('s1')
        def ctx = new ParentCtx(input: 'pt')

        when:
        def result = sm.entity(entity).transitionTo('s2', ctx)

        then:
        result.success
        entity.trail == ['pt:pt']
    }

    def 'transition.run(id, mapperId) routes the operation through the registered mapper'() {
        given:
        def sm = build(
            { smd ->
                smd.step('child-op', ChildCtx, new ChildOperation())
                smd.mapper('pn', ParentCtx, ChildCtx, new PNMapper())
            },
            { t ->
                t.step('outer', { entity, ctx, transition ->
                    transition.run('child-op', 'pn')
                } as Action<Entity, ParentCtx>)
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

    def 'transition.run(id, Function) runs the operation under the projected child context'() {
        given:
        Function<ParentCtx, ChildCtx> mapTo = { p ->
            def c = new ChildCtx()
            c.input = p.input
            return c
        }
        def sm = build(
            { smd -> smd.step('child-op', ChildCtx, new ChildOperation()) },
            { t ->
                t.step('outer', { entity, ctx, transition ->
                    transition.run('child-op', mapTo)
                } as Action<Entity, ParentCtx>)
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

    def 'transition.run(id, ContextMapper) runs the operation under the mapped child context'() {
        given:
        def sm = build(
            { smd -> smd.step('child-op', ChildCtx, new ChildOperation()) },
            { t ->
                t.step('outer', { entity, ctx, transition ->
                    transition.run('child-op', new PNMapper())
                } as Action<Entity, ParentCtx>)
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

    def 'transition.run(Identifiable) dispatches the same as step(String)'() {
        given:
        def sm = build(
            { smd -> smd.step('my-step', ParentCtx, new Action<Entity, ParentCtx>() {
                @Override
                void execute(Entity entity, ParentCtx context, Transition<Entity, ParentCtx> transition) {
                    entity.trail << ('step:' + context.input)
                }
            }) },
            { t ->
                t.step('outer', { entity, ctx, transition ->
                    transition.run(id('my-step'))
                } as Action<Entity, ParentCtx>)
            })

        when:
        def result = sm.entity(new Entity('s1')).transitionTo('s2', new ParentCtx(input: 'foo'))

        then:
        result.success
        result.entity.trail == ['step:foo']
    }

    def 'transition.run(Identifiable) dispatches the same as operation(String)'() {
        given:
        def sm = build(
            { smd -> smd.step('my-op', ParentCtx, new Action<Entity, ParentCtx>() {
                @Override
                void execute(Entity entity, ParentCtx context, Transition<Entity, ParentCtx> transition) {
                    entity.trail << ('op:' + context.input)
                }
            }) },
            { t ->
                t.step('outer', { entity, ctx, transition ->
                    transition.run(id('my-op'))
                } as Action<Entity, ParentCtx>)
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
                t.step('outer', { entity, ctx, transition ->
                    action.call(transition)
                } as Action<Entity, ParentCtx>)
            })

        when:
        def result = sm.entity(new Entity('s1')).transitionTo('s2', new ParentCtx())

        then:
        !result.success
        result.error instanceof TransfluxValidationException

        where:
        action << [
            { Transition t -> t.run((Identifiable) null) },
            { Transition t -> t.run((Identifiable) null, id('m')) },
            { Transition t -> t.run((Identifiable) null, 'm') },
            { Transition t -> t.run('a', (Identifiable) null) },
        ]
    }

    def 'transition.operation rejects unknown id'() {
        given:
        def sm = build(
            { smd -> },
            { t ->
                t.step('outer', { entity, ctx, transition ->
                    transition.run('does-not-exist')
                } as Action<Entity, ParentCtx>)
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

    static class ChildStep implements Action<Entity, ChildCtx> {
        @Override
        void execute(Entity entity, ChildCtx context, Transition<Entity, ChildCtx> transition) {
            context.output = 'step-saw-' + context.input
            entity.trail << ('step:' + context.input)
        }
    }

    static class ChildOperation implements Action<Entity, ChildCtx> {
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
    static class ParentPassThroughOp implements Action<Entity, ParentCtx> {
        @Override
        void execute(Entity entity, ParentCtx context, Transition<Entity, ParentCtx> transition) {
            entity.trail << ('pt:' + context.input)
        }
    }
}
