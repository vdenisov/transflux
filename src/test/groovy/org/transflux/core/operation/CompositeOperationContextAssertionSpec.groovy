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
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.Transition
import spock.lang.Specification

class CompositeOperationContextAssertionSpec extends Specification {

    static class Entity {
        String state

        Entity(String state) {
            this.state = state
        }
    }

    static class CorrectCtx { }

    static class WrongCtx { }

    static class NoopStep implements Step<Entity, CorrectCtx> {
        @Override
        void execute(Entity entity, CorrectCtx context, Transition<Entity, CorrectCtx> transition) { }
    }

    def 'usingContext(SMContext) accepts when the supplied class matches the SM context type'() {
        given:
        def smd = baseDef()
        smd.getTransition('t').compositeOperation('outer', { CompositeOperationDef<Entity, CorrectCtx> c ->
            c.usingContext(CorrectCtx).step('s1', new NoopStep())
        })

        when:
        def sm = smd.build()

        then:
        sm != null
    }

    def 'usingContext is a no-op when the SM did not declare a context type'() {
        given:
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .state('s1').transitionsTo('s2', 't')
            .state('s2')
        // Note: SM no longer carries a context type — context is per-transition.
        smd.getTransition('t').compositeOperation('outer', { CompositeOperationDef<Entity, CorrectCtx> c ->
            c.usingContext(CorrectCtx).step('s1', new NoopStep())
        })

        when:
        def sm = smd.build()

        then:
        sm != null   // no validation triggered
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
