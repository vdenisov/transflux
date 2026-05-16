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

package org.transflux.core

import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.state.StateResolver
import spock.lang.Specification

class FireBoundaryRuntimeCheckSpec extends Specification {

    static class Entity {
        String state

        Entity(String state) {
            this.state = state
        }
    }

    static class CtxA { }

    static class CtxB { }

    def 'firing a transition with the expected context type succeeds'() {
        given:
        def sm = baseDef('t', CtxA).build()

        when:
        def result = sm.entity(new Entity('s1')).transitionTo('s2', new CtxA())

        then:
        result.success
    }

    def 'firing a transition with the wrong context type is rejected with a clear message'() {
        given:
        def sm = baseDef('t', CtxA).build()

        when:
        sm.entity(new Entity('s1')).transitionTo('s2', new CtxB())

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('Context type mismatch')
        e.message.contains('CtxA')
        e.message.contains('CtxB')
    }

    def 'firing a Void-context transition with no context succeeds'() {
        given:
        def smd = baseDef('t', null)
        def sm = smd.build()

        when:
        def result = sm.entity(new Entity('s1')).transitionTo('s2', (Object) null)

        then:
        result.success
    }

    def 'firing a Void-context transition with a non-null context is rejected'() {
        given:
        def sm = baseDef('t', null).build()

        when:
        sm.entity(new Entity('s1')).transitionTo('s2', new CtxA())

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('Void')
    }

    def 'firing by transition id with the wrong context type is rejected'() {
        given:
        def sm = baseDef('t', CtxA).build()

        when:
        sm.entity(new Entity('s1')).transitionTo('s2', 't', new CtxB())

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('Context type mismatch')
    }

    private static StateMachineDefImpl<Entity, Object> baseDef(String transitionId, Class<?> ctx) {
        def smd = new StateMachineDefImpl<Entity, Object>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .state('s1').transitionsTo('s2', transitionId)
            .state('s2')
        if (ctx != null) {
            smd.getTransition(transitionId).usingContext(ctx)
        }
        return smd
    }
}
