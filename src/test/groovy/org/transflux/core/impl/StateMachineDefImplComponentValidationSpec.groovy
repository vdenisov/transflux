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

import org.transflux.core.action.ActionKind
import org.transflux.core.TestContext
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.action.OperationDef
import org.transflux.core.action.Action
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.Transition
import spock.lang.Specification

/**
 * Covers the reach of the end-of-build {@code Component.validate()} pass. No component rule
 * reachable through the DSL can currently fail, so each case plants a component whose id
 * disagrees with its payload into the registry under test and drives the pass directly.
 */
class StateMachineDefImplComponentValidationSpec extends Specification {

    static class Entity {
        String state

        Entity(String state) {
            this.state = state
        }
    }

    static class NoOpStep implements Action<Entity, TestContext> {
        @Override
        void execute(Entity entity, TestContext context, Transition<Entity, TestContext> transition) {
        }
    }

    def 'a clean definition passes component validation'() {
        given:
        def smd = defWithComposites()
        def sm = smd.build()

        when:
        smd.validateComponents(sm.componentRegistry)

        then:
        noExceptionThrown()
    }

    def 'validation reaches the root registry'() {
        given:
        def smd = defWithComposites()
        def sm = smd.build()
        plant(sm.componentRegistry)

        when:
        smd.validateComponents(sm.componentRegistry)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == "Component 'planted' wraps a bound action with id 'other'"
    }

    def "validation reaches a transition composite's inline scope"() {
        given:
        def smd = defWithComposites()
        def sm = smd.build()
        plant(smd.transitionsById['t'].actionDef.scopeRegistry)

        when:
        smd.validateComponents(sm.componentRegistry)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == "Component 'planted' wraps a bound action with id 'other'"
    }

    def "validation reaches an SM-level composite's inline scope"() {
        given:
        def smd = defWithComposites()
        def sm = smd.build()
        plant(smd.getSmCompositeOperation('sm-level').scopeRegistry)

        when:
        smd.validateComponents(sm.componentRegistry)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == "Component 'planted' wraps a bound action with id 'other'"
    }

    private static StateMachineDefImpl<Entity> defWithComposites() {
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .operation('sm-level', TestContext, { OperationDef<Entity, TestContext> c ->
                c.step('sm-level-inline', new NoOpStep())
            })
            .state('s1', { s ->
                s.transitionsTo('s2', 't', TestContext, { t ->
                    t.operation('outer', { OperationDef<Entity, TestContext> c ->
                        c.step('outer-inline', new NoOpStep())
                    })
                })
            })
            .state('s2', {})
        return smd
    }

    private static void plant(Registry<Entity> registry) {
        ((RegistryImpl<Entity>) registry)
            .register(new Component.Action<>('planted', TestContext,
                BoundAction.of('other', new NoOpStep(), ActionKind.STEP)))
    }
}
