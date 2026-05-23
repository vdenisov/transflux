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

import org.transflux.core.*
import org.transflux.core.state.*
import org.transflux.core.transition.*
import org.transflux.core.operation.*
import org.transflux.core.condition.*
import org.transflux.core.exception.*

import org.transflux.core.impl.*

import org.transflux.core.StateMachine
import org.transflux.core.impl.StateMachineDefImpl
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.Transition
import org.transflux.core.transition.TransitionDef
import spock.lang.Specification

import java.util.function.Consumer

class CompositeOperationDefImplNestedMapperFailureSpec extends Specification {

    static class Entity {
        String state
        List<String> trail = []

        Entity(String state) {
            this.state = state
        }
    }

    static class ParentCtx { }

    static class ChildCtx { }

    static class ChildOp implements Operation<Entity, ChildCtx> {
        @Override
        void execute(Entity entity, ChildCtx context, Transition<Entity, ChildCtx> transition) {
            entity.trail << 'child-ran'
        }
    }

    static class FailingMapToMapper implements ContextMapper<ParentCtx, ChildCtx> {
        @Override
        ChildCtx mapTo(ParentCtx p) {
            throw new RuntimeException('mapTo-boom')
        }
    }

    static class FailingMapFromMapper implements ContextMapper<ParentCtx, ChildCtx> {
        @Override
        ChildCtx mapTo(ParentCtx p) {
            return new ChildCtx()
        }

        @Override
        void mapFrom(ParentCtx p, ChildCtx n) {
            throw new RuntimeException('mapFrom-boom')
        }
    }

    def 'mapTo failure surfaces as parent member failure — nested op never starts'() {
        given:
        def sm = build(
            { smd -> smd.operation('nested', ChildCtx, new ChildOp())
                .mapper('failing-mapto', ParentCtx, ChildCtx, new FailingMapToMapper()) },
            { t -> t.compositeOperation('outer', { CompositeOperationDef<Entity, ParentCtx> c ->
                c.operation('nested', 'failing-mapto')
            }) })
        def entity = new Entity('s1')

        when:
        def result = sm.entity(entity).transitionTo('s2', new ParentCtx())

        then:
        !result.success
        result.error instanceof RuntimeException
        result.error.message == 'mapTo-boom'
        result.executedStepIds.isEmpty()
        entity.trail == []
    }

    def 'mapFrom failure surfaces as parent failure — child completed but writeback blew up'() {
        given:
        def sm = build(
            { smd -> smd.operation('nested', ChildCtx, new ChildOp())
                .mapper('failing-mapfrom', ParentCtx, ChildCtx, new FailingMapFromMapper()) },
            { t -> t.compositeOperation('outer', { CompositeOperationDef<Entity, ParentCtx> c ->
                c.operation('nested', 'failing-mapfrom')
            }) })
        def entity = new Entity('s1')

        when:
        def result = sm.entity(entity).transitionTo('s2', new ParentCtx())

        then:
        !result.success
        result.error instanceof RuntimeException
        result.error.message == 'mapFrom-boom'
        entity.trail == ['child-ran']
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
