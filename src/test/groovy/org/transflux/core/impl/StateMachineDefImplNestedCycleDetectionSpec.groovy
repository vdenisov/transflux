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

import org.transflux.core.ContextScope
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.action.OperationDef
import org.transflux.core.action.Action
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.ExecutingTransition
import spock.lang.Specification

class StateMachineDefImplNestedCycleDetectionSpec extends Specification {

    static class Entity {
        String state

        Entity(String state) {
            this.state = state
        }
    }

    static class Ctx { }

    static class NoopStep implements Action<Entity, Ctx> {
        @Override
        void execute(Entity entity, Ctx context, ExecutingTransition<Entity, Ctx> transition) { }
    }

    def 'composite referring to itself by id is rejected with a clear cycle message'() {
        given:
        def smd = baseDef()
        smd.forContext(Ctx, { ContextScope<Entity, Ctx> scope ->
            scope.operation('a', { OperationDef<Entity, Ctx> c ->
                c.step('placeholder', new NoopStep()).run('a')   // self-reference
            })
        })

        when:
        smd.build()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('cycle')
        e.message.contains('a')
    }

    def 'two composites referring to each other (A -> B -> A) are rejected'() {
        given:
        def smd = baseDef()
        smd.forContext(Ctx, { ContextScope<Entity, Ctx> scope ->
            scope.operation('a', { OperationDef<Entity, Ctx> c ->
                c.step('placeholder-a', new NoopStep()).run('b')
            }).operation('b', { OperationDef<Entity, Ctx> c ->
                c.step('placeholder-b', new NoopStep()).run('a')
            })
        })

        when:
        smd.build()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('cycle')
    }

    def 'three composites forming A -> B -> C -> A are rejected'() {
        given:
        def smd = baseDef()
        smd.forContext(Ctx, { ContextScope<Entity, Ctx> scope ->
            scope.operation('a', { OperationDef<Entity, Ctx> c ->
                c.step('pa', new NoopStep()).run('b')
            }).operation('b', { OperationDef<Entity, Ctx> c ->
                c.step('pb', new NoopStep()).run('c')
            }).operation('c', { OperationDef<Entity, Ctx> c ->
                c.step('pc', new NoopStep()).run('a')
            })
        })

        when:
        smd.build()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('cycle')
    }

    def 'acyclic composite chain A -> B is accepted'() {
        given:
        def smd = baseDef()
        smd.forContext(Ctx, { ContextScope<Entity, Ctx> scope ->
            scope.operation('b', { OperationDef<Entity, Ctx> c ->
                c.step('pb', new NoopStep())
            }).operation('a', { OperationDef<Entity, Ctx> c ->
                c.step('pa', new NoopStep()).run('b')
            })
        })

        when:
        def sm = smd.build()

        then:
        sm != null
    }

    private static StateMachineDefImpl<Entity> baseDef() {
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .state('s1', { s -> s.transitionsTo('s2', 't', {}) })
            .state('s2', {})
        return smd
    }
}
