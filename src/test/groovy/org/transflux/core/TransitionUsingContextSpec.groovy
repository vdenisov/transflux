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

import org.transflux.core.state.StateResolver
import org.transflux.core.transition.TransitionDef
import org.transflux.core.transition.TransitionDefImpl
import spock.lang.Specification

class TransitionUsingContextSpec extends Specification {

    static class Entity {
        String state

        Entity(String state) {
            this.state = state
        }
    }

    static class TheCtx { }

    def 'TransitionDef defaults to Void context when usingContext is not called'() {
        given:
        def td = new TransitionDefImpl<Entity, Void>('t1', 's1', 's2')

        expect:
        td.getContextType() == Void
    }

    def 'usingContext narrows the transition\'s context type and re-types the builder'() {
        given:
        def td = new TransitionDefImpl<Entity, Void>('t1', 's1', 's2')

        when:
        TransitionDef<Entity, TheCtx> retyped = td.usingContext(TheCtx)

        then:
        retyped.getContextType() == TheCtx
        td.getContextType() == TheCtx   // same underlying impl
    }

    def 'transition\'s contextType is reachable from the runtime TransitionImpl'() {
        given:
        def smd = new StateMachineDefImpl<Entity, Object>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .state('s1').transitionsTo('s2', 't1')
            .state('s2')
        smd.getTransition('t1').usingContext(TheCtx)
        def sm = smd.build()

        when:
        def transition = sm.getTransition('t1')

        then:
        transition instanceof org.transflux.core.transition.TransitionImpl
        ((org.transflux.core.transition.TransitionImpl) transition).getContextType() == TheCtx
    }
}
