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

import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.operation.CompositeOperationDef
import org.transflux.core.operation.Step
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.Transition
import spock.lang.Specification

class StateMachineDefImplNestedCycleDetectionSpec extends Specification {

    static class Entity {
        String state

        Entity(String state) {
            this.state = state
        }
    }

    static class Ctx { }

    static class NoopStep implements Step<Entity, Ctx> {
        @Override
        void execute(Entity entity, Ctx context, Transition<Entity, Ctx> transition) { }
    }

    def 'composite referring to itself by id is rejected with a clear cycle message'() {
        given:
        def smd = baseDef()
        smd.forContext(Ctx, { ContextScope<Entity, Ctx> scope ->
            scope.compositeOperation('a', { CompositeOperationDef<Entity, Ctx> c ->
                c.step('placeholder', new NoopStep()).operation('a')   // self-reference
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
            scope.compositeOperation('a', { CompositeOperationDef<Entity, Ctx> c ->
                c.step('placeholder-a', new NoopStep()).operation('b')
            }).compositeOperation('b', { CompositeOperationDef<Entity, Ctx> c ->
                c.step('placeholder-b', new NoopStep()).operation('a')
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
            scope.compositeOperation('a', { CompositeOperationDef<Entity, Ctx> c ->
                c.step('pa', new NoopStep()).operation('b')
            }).compositeOperation('b', { CompositeOperationDef<Entity, Ctx> c ->
                c.step('pb', new NoopStep()).operation('c')
            }).compositeOperation('c', { CompositeOperationDef<Entity, Ctx> c ->
                c.step('pc', new NoopStep()).operation('a')
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
            scope.compositeOperation('b', { CompositeOperationDef<Entity, Ctx> c ->
                c.step('pb', new NoopStep())
            }).compositeOperation('a', { CompositeOperationDef<Entity, Ctx> c ->
                c.step('pa', new NoopStep()).operation('b')
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
