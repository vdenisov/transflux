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
import org.transflux.core.operation.Operation
import org.transflux.core.operation.Step
import org.transflux.core.state.StateResolver
import spock.lang.Specification

import java.util.function.Consumer

class TransitionIdentifiableOverloadsSpec extends Specification {

    private static Identifiable id(String value) {
        return { -> value } as Identifiable
    }

    static class Entity {
        String state
        List<String> trail = []

        Entity(String state) { this.state = state }
    }

    static class Ctx {
        String input
        String output
    }

    static class TrailStep implements Step<Entity, Ctx> {
        @Override
        void execute(Entity entity, Ctx ctx, Transition<Entity, Ctx> transition) {
            entity.trail << ('step:' + ctx.input)
        }
    }

    static class TrailOp implements Operation<Entity, Ctx> {
        @Override
        void execute(Entity entity, Ctx ctx, Transition<Entity, Ctx> transition) {
            entity.trail << ('op:' + ctx.input)
        }
    }

    def 'transition.step(Identifiable) dispatches the same as step(String)'() {
        given:
        def sm = build(
            { smd -> smd.step('my-step', Ctx, new TrailStep()) },
            { t ->
                t.simpleOperation('outer', { entity, ctx, transition ->
                    transition.step(id('my-step'))
                } as Operation<Entity, Ctx>)
            })

        when:
        def result = sm.entity(new Entity('s1')).transitionTo('s2', new Ctx(input: 'foo'))

        then:
        result.success
        result.entity.trail == ['step:foo']
    }

    def 'transition.operation(Identifiable) dispatches the same as operation(String)'() {
        given:
        def sm = build(
            { smd -> smd.operation('my-op', Ctx, new TrailOp()) },
            { t ->
                t.simpleOperation('outer', { entity, ctx, transition ->
                    transition.operation(id('my-op'))
                } as Operation<Entity, Ctx>)
            })

        when:
        def result = sm.entity(new Entity('s1')).transitionTo('s2', new Ctx(input: 'bar'))

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
                } as Operation<Entity, Ctx>)
            })

        when:
        def result = sm.entity(new Entity('s1')).transitionTo('s2', new Ctx())

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

    private static StateMachine<Entity> build(Consumer<StateMachineDefImpl<Entity>> smdRegistrations,
                                              Consumer<TransitionDef<Entity, Ctx>> transitionConfigurer) {
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
        smdRegistrations.accept(smd)
        smd.state('s1', { s -> s.transitionsTo('s2', 't', Ctx, transitionConfigurer) })
            .state('s2', {})
        return smd.build()
    }
}
