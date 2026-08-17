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

import org.transflux.core.StateMachine
import org.transflux.core.StateMachineDef
import org.transflux.core.TestContext
import org.transflux.core.action.Action
import org.transflux.core.condition.Condition
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.ExecutingTransition
import org.transflux.core.transition.Transition
import spock.lang.Specification

import java.util.function.Consumer

class TransitionImplSpec extends Specification {

    def 'the topology accessors answer from the bound transition'() {
        given:
        def view = topologyView()

        expect:
        view.id == 't'
        view.sourceStateId == 's1'
        view.targetStateId == 's2'
    }

    def 'the view carries no dispatch surface at all'() {
        given:
        def view = topologyView()

        expect:
        !(view instanceof ExecutingTransition)
        TransitionImpl.methods.every { !it.name.startsWith('run') }
    }

    def 'the view copies the topology rather than holding the bound transition'() {
        expect: 'nothing on it can lead a reflective caller - SpEL included - back to the resolved graph'
        TransitionImpl.declaredFields
            .findAll { !it.synthetic }
            .every { it.type == String }
    }

    def 'a data-trigger gate reading topology is allowed'() {
        given:
        def seen = []
        def entity = new Entity('s1')
        def sm = build({ d -> d
            .state('s1', { st -> st.transitionsTo('s2', 't', { t -> t.addDataTrigger('gated', { dt -> dt
                .condition('reads', { e, c, tr ->
                    seen << "${tr.id}:${tr.sourceStateId}->${tr.targetStateId}".toString()
                    true
                } as Condition) }) }) })
            .state('s2', {}) })

        when:
        def result = sm.entity(entity).processDataChange()

        then:
        result.fired()
        seen == ['t:s1->s2']
        entity.state == 's2'
    }

    def 'an expression condition can read the transition it is evaluated against'() {
        given:
        def entity = new Entity('s1')
        def sm = build({ d -> d
            .state('s1', { st -> st.transitionsTo('s2', 't', { t -> t
                .preConditionExpression("#transition.id == 't' and #transition.sourceStateId == 's1'") }) })
            .state('s2', {}) })

        when:
        def result = sm.entity(entity).transitionTo('s2')

        then: 'the read-only view is reachable through SpEL, which resolves against the runtime object'
        result.success
        entity.state == 's2'
    }

    def 'an expression condition cannot dispatch through the transition it is handed'() {
        given:
        def stepRuns = []
        def entity = new Entity('s1')
        def sm = build({ d -> d
            .step('audit', { e, c, tr -> stepRuns << 'ran' } as Action)
            .state('s1', { st -> st.transitionsTo('s2', 't', { t -> t
                .preConditionExpression("#transition.run('audit') == null") }) })
            .state('s2', {}) })

        when:
        def result = sm.entity(entity).transitionTo('s2')

        then: 'there is no run method to find on the read-only view'
        !result.success
        result.error instanceof TransfluxValidationException
        result.error.message.startsWith('Failed to evaluate SpEL expression')
        stepRuns.isEmpty()
        entity.state == 's1'
    }

    def 'an expression condition cannot reach the resolved graph behind the view'() {
        given:
        def entity = new Entity('s1')
        def sm = build({ d -> d
            .state('s1', { st -> st.transitionsTo('s2', 't', { t -> t
                .preConditionExpression('#transition.boundAction == null') }) })
            .state('s2', {}) })

        when:
        def result = sm.entity(entity).transitionTo('s2')

        then: 'the view copies three strings, so there is no bound transition to walk into'
        !result.success
        result.error instanceof TransfluxValidationException
        result.error.message.startsWith('Failed to evaluate SpEL expression')
        entity.state == 's1'
    }

    private static Transition topologyView() {
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .state('s1', { st -> st.transitionsTo('s2', 't', TestContext, { t -> }) })
            .state('s2', {})
        def sm = (StateMachineImpl<Entity>) smd.build()
        return TransitionImpl.of(sm.getTransition('t'))
    }

    private static StateMachine<Entity> build(Consumer<StateMachineDef<Entity>> cfg) {
        def smd = new StateMachineDefImpl<Entity>()
        StateMachineDef<Entity> builder = smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .withStateApplier({ e, s -> e.state = s } as StateApplier<Entity>)
        cfg.accept(builder)
        return smd.build()
    }

    static class Entity {
        String state

        Entity(String state) {
            this.state = state
        }
    }
}
