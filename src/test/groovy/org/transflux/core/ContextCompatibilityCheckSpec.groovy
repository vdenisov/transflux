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
import org.transflux.core.operation.CompositeOperationDef
import org.transflux.core.operation.Step
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.Transition
import spock.lang.Specification

class ContextCompatibilityCheckSpec extends Specification {

    static class Entity {
        String state

        Entity(String state) {
            this.state = state
        }
    }

    static class CtxA { }

    static class CtxB { }

    static class StepA implements Step<Entity, CtxA> {
        @Override
        void execute(Entity entity, CtxA context, Transition<Entity, CtxA> transition) { }
    }

    static class StepB implements Step<Entity, CtxB> {
        @Override
        void execute(Entity entity, CtxB context, Transition<Entity, CtxB> transition) { }
    }

    def 'SM-level composite referencing a step declared for the same context builds cleanly'() {
        given:
        def smd = baseDef()
        smd.useContext(CtxA, { ContextScope<Entity, CtxA> scope ->
            scope.step('s', new StepA())
                .compositeOperation('outer', { CompositeOperationDef<Entity, CtxA> c -> c.step('s') })
        })

        when:
        def sm = smd.build()

        then:
        sm != null
    }

    def 'SM-level composite referencing a step declared for a different context is rejected at build'() {
        given:
        def smd = baseDef()
        smd.useContext(CtxB, { ContextScope<Entity, CtxB> scope -> scope.step('s', new StepB()) })
        smd.useContext(CtxA, { ContextScope<Entity, CtxA> scope ->
            scope.compositeOperation('outer', { CompositeOperationDef<Entity, CtxA> c ->
                c.step('s')   // referencing CtxB-tagged step from CtxA-tagged composite
            })
        })

        when:
        smd.build()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('outer')
        e.message.contains('s')
    }

    def 'legacy (untagged) registrations skip the context-compatibility check'() {
        given:
        def smd = baseDef()
        smd.step('legacy-step', new StepA())   // no useContext block — no tag
        smd.getTransition('t').compositeOperation('legacy-composite',
            { CompositeOperationDef<Entity, Object> c -> c.step('legacy-step') })

        when:
        def sm = smd.build()

        then:
        sm != null
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
