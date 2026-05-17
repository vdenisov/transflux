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


import org.transflux.core.operation.CompositeOperationDef
import org.transflux.core.operation.Step
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.Transition
import spock.lang.Specification

class UseContextScopingSpec extends Specification {

    static class Entity {
        String state
        List<String> trail = []

        Entity(String state) {
            this.state = state
        }
    }

    static class CtxA { }

    static class CtxB { }

    static class StepA implements Step<Entity, CtxA> {
        @Override
        void execute(Entity entity, CtxA context, Transition<Entity, CtxA> transition) {
            entity.trail << 'step-a'
        }
    }

    static class StepB implements Step<Entity, CtxB> {
        @Override
        void execute(Entity entity, CtxB context, Transition<Entity, CtxB> transition) {
            entity.trail << 'step-b'
        }
    }

    def 'useContext block registers a step tagged with the scope context'() {
        given:
        def smd = baseDef()
        smd.useContext(CtxA, { ContextScope<Entity, CtxA> scope ->
            scope.step('s-a', new StepA())
        })

        when:
        def sm = smd.build()

        then:
        sm != null
        smd.getComponentContextType('s-a') == CtxA
    }

    def 'multiple useContext blocks with the same context class accumulate registrations'() {
        given:
        def smd = baseDef()
        smd.useContext(CtxA, { ContextScope<Entity, CtxA> scope -> scope.step('s-a', new StepA()) })
        smd.useContext(CtxA, { ContextScope<Entity, CtxA> scope -> scope.step('s-a2', new StepA()) })

        when:
        smd.build()

        then:
        smd.getComponentContextType('s-a') == CtxA
        smd.getComponentContextType('s-a2') == CtxA
    }

    def 'two useContext blocks with different context classes coexist'() {
        given:
        def smd = baseDef()
        smd.useContext(CtxA, { ContextScope<Entity, CtxA> scope -> scope.step('s-a', new StepA()) })
        smd.useContext(CtxB, { ContextScope<Entity, CtxB> scope -> scope.step('s-b', new StepB()) })

        when:
        smd.build()

        then:
        smd.getComponentContextType('s-a') == CtxA
        smd.getComponentContextType('s-b') == CtxB
    }

    def 'useContext registers an SM-level composite operation that can be referenced by id'() {
        given:
        def smd = baseDef()
        smd.useContext(CtxA, { ContextScope<Entity, CtxA> scope ->
            scope.step('inner-step', new StepA())
                .compositeOperation('outer', { CompositeOperationDef<Entity, CtxA> c ->
                    c.step('inner-step')
                })
        })

        when:
        smd.build()

        then:
        smd.getComponentContextType('outer') == CtxA
        smd.getSmCompositeOperation('outer') != null
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
