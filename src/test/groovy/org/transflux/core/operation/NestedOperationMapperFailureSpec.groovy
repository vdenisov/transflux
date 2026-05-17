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

package org.transflux.core.operation

import org.transflux.core.StateMachineDefImpl
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.Transition
import spock.lang.Specification

class NestedOperationMapperFailureSpec extends Specification {

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

    def 'mapTo failure surfaces as parent member failure — nested op never starts'() {
        given:
        def smd = baseDef()
        smd.getTransition('t').compositeOperation('outer', { CompositeOperationDef<Entity, ParentCtx> c ->
            c.operation('nested', ChildOp, { NestedOperationDef<Entity, ParentCtx, ParentCtx> op ->
                op.usingContext(ChildCtx)
                    .mapTo({ ParentCtx p -> throw new RuntimeException('mapTo-boom') })
            })
        })
        def sm = smd.build()
        def entity = new Entity('s1')

        when:
        def result = sm.entity(entity).transitionTo('s2', new ParentCtx())

        then:
        !result.success
        result.error instanceof RuntimeException
        result.error.message == 'mapTo-boom'
        result.executedStepIds.isEmpty()    // child never ran, no inner steps recorded
        entity.trail == []                   // child execute() never reached
    }

    def 'mapFrom failure surfaces as child failure — child execution recorded, mapFrom blows up'() {
        given:
        def smd = baseDef()
        smd.getTransition('t').compositeOperation('outer', { CompositeOperationDef<Entity, ParentCtx> c ->
            c.operation('nested', ChildOp, { NestedOperationDef<Entity, ParentCtx, ParentCtx> op ->
                op.usingContext(ChildCtx)
                    .mapTo({ ParentCtx p -> new ChildCtx() })
                    .mapFrom({ ParentCtx p, ChildCtx n -> throw new RuntimeException('mapFrom-boom') })
            })
        })
        def sm = smd.build()
        def entity = new Entity('s1')

        when:
        def result = sm.entity(entity).transitionTo('s2', new ParentCtx())

        then:
        !result.success
        result.error instanceof RuntimeException
        result.error.message == 'mapFrom-boom'
        // Child's execute() did reach completion before mapFrom blew up; its side-effect is observable.
        entity.trail == ['child-ran']
    }

    private static StateMachineDefImpl<Entity> baseDef() {
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .state('s1').transitionsTo('s2', 't')
            .state('s2')
        return smd
    }
}
