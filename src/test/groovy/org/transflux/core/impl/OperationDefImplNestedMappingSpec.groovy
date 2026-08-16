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
import org.transflux.core.action.OperationDef
import org.transflux.core.action.ContextMapper
import org.transflux.core.action.Action
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.Transition
import org.transflux.core.transition.TransitionDef
import spock.lang.Specification

import java.util.function.Consumer
import java.util.function.Function

class OperationDefImplNestedMappingSpec extends Specification {

    static class Entity {
        String state

        Entity(String state) {
            this.state = state
        }
    }

    static class ParentCtx {
        String subscriptionId
        String activationResult
    }

    static class ChildCtx {
        String subscriptionId
        String activationResult
    }

    /** Reads `subscriptionId` from the child context and writes `activationResult` back. */
    static class ChildOp implements Action<Entity, ChildCtx> {
        @Override
        void execute(Entity entity, ChildCtx context, Transition<Entity, ChildCtx> transition) {
            context.activationResult = 'activated-' + context.subscriptionId
        }
    }

    static class ParentChildMapper implements ContextMapper<ParentCtx, ChildCtx> {
        @Override
        ChildCtx mapTo(ParentCtx p) {
            def n = new ChildCtx()
            n.subscriptionId = p.subscriptionId
            return n
        }

        @Override
        void mapFrom(ParentCtx p, ChildCtx n) {
            p.activationResult = n.activationResult
        }
    }

    def 'registered ContextMapper instance bridges parent and child context via by-id mapper ref'() {
        given:
        def sm = build(
            { smd -> smd.step('charge', ChildCtx, new ChildOp())
                .mapper('parent-to-child', ParentCtx, ChildCtx, new ParentChildMapper()) },
            { t -> t.operation('outer', { OperationDef<Entity, ParentCtx> c ->
                c.run('charge', 'parent-to-child')
            }) })
        def entity = new Entity('s1')
        def ctx = new ParentCtx(subscriptionId: 'sub-42')

        when:
        def result = sm.entity(entity).transitionTo('s2', ctx)

        then:
        result.success
        ctx.activationResult == 'activated-sub-42'
    }

    def 'registered ContextMapper class bridges parent and child context via by-id mapper ref'() {
        given:
        def sm = build(
            { smd -> smd.step('charge', ChildCtx, new ChildOp())
                .mapper('parent-to-child', ParentCtx, ChildCtx, ParentChildMapper) },
            { t -> t.operation('outer', { OperationDef<Entity, ParentCtx> c ->
                c.run('charge', 'parent-to-child')
            }) })
        def entity = new Entity('s1')
        def ctx = new ParentCtx(subscriptionId: 'sub-99')

        when:
        def result = sm.entity(entity).transitionTo('s2', ctx)

        then:
        result.success
        ctx.activationResult == 'activated-sub-99'
    }

    def 'inline ContextMapper instance at the call site bridges parent and child context'() {
        given:
        def sm = build(
            { smd -> smd.step('charge', ChildCtx, new ChildOp()) },
            { t -> t.operation('outer', { OperationDef<Entity, ParentCtx> c ->
                c.run('charge', new ParentChildMapper())
            }) })
        def entity = new Entity('s1')
        def ctx = new ParentCtx(subscriptionId: 'sub-inline-mapper')

        when:
        def result = sm.entity(entity).transitionTo('s2', ctx)

        then:
        result.success
        ctx.activationResult == 'activated-sub-inline-mapper'
    }

    def 'inline read-only Function at the call site projects parent to child (mapFrom is no-op)'() {
        given:
        Function<ParentCtx, ChildCtx> mapTo = { ParentCtx p ->
            def n = new ChildCtx()
            n.subscriptionId = p.subscriptionId
            return n
        }
        def sm = build(
            { smd -> smd.step('charge', ChildCtx, new ChildOp()) },
            { t -> t.operation('outer', { OperationDef<Entity, ParentCtx> c ->
                c.run('charge', mapTo)
            }) })
        def entity = new Entity('s1')
        def ctx = new ParentCtx(subscriptionId: 'sub-no-back')

        when:
        def result = sm.entity(entity).transitionTo('s2', ctx)

        then:
        result.success
        ctx.activationResult == null
    }

    def 'a registered mapper supplied as a lambda gets the default no-op mapFrom'() {
        given:
        def sm = build(
            { smd -> smd.step('charge', ChildCtx, new ChildOp())
                .mapper('parent-to-child', ParentCtx, ChildCtx, { ParentCtx p ->
                    def n = new ChildCtx()
                    n.subscriptionId = p.subscriptionId
                    return n
                } as ContextMapper<ParentCtx, ChildCtx>) },
            { t -> t.operation('outer', { OperationDef<Entity, ParentCtx> c ->
                c.run('charge', 'parent-to-child')
            }) })
        def entity = new Entity('s1')
        def ctx = new ParentCtx(subscriptionId: 'sub-fn-reg')

        when:
        def result = sm.entity(entity).transitionTo('s2', ctx)

        then:
        result.success
        ctx.activationResult == null
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
