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

import org.transflux.core.Identifiable
import org.transflux.core.StateMachine
import org.transflux.core.StateMachineDef
import org.transflux.core.TestContext
import org.transflux.core.condition.Condition
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.operation.ContextMapper
import org.transflux.core.operation.Step
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.Transition
import spock.lang.Specification
import spock.lang.Unroll

import java.util.function.BiPredicate
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Predicate

class TopologyTransitionSpec extends Specification {

    def 'the topology accessors answer from the bound transition'() {
        given:
        def view = topologyView()

        expect:
        view.id == 't'
        view.sourceStateId == 's1'
        view.targetStateId == 's2'
    }

    @Unroll
    def 'dispatch via #description is rejected'() {
        given:
        def view = topologyView()

        when:
        call.call(view)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("Transition 't' is a read-only topology view")
        e.message.contains("'${description}'")
        e.message.contains('inside an active transition execution')

        where:
        description                          | call
        'step(String)'                       | { v -> v.step('s') }
        'step(String, String)'               | { v -> v.step('s', 'm') }
        'step(String, Function)'             | { v -> v.step('s', { c -> c } as Function) }
        'step(String, ContextMapper)'        | { v -> v.step('s', Mock(ContextMapper)) }
        'step(Identifiable)'                 | { v -> v.step(idOf('s')) }
        'step(Identifiable, Identifiable)'   | { v -> v.step(idOf('s'), idOf('m')) }
        'step(Identifiable, String)'         | { v -> v.step(idOf('s'), 'm') }
        'step(String, Identifiable)'         | { v -> v.step('s', idOf('m')) }
        'operation(String)'                  | { v -> v.operation('o') }
        'operation(String, String)'          | { v -> v.operation('o', 'm') }
        'operation(String, Function)'        | { v -> v.operation('o', { c -> c } as Function) }
        'operation(String, ContextMapper)'   | { v -> v.operation('o', Mock(ContextMapper)) }
        'operation(Identifiable)'            | { v -> v.operation(idOf('o')) }
        'operation(Identifiable, Identifiable)' | { v -> v.operation(idOf('o'), idOf('m')) }
        'operation(Identifiable, String)'    | { v -> v.operation(idOf('o'), 'm') }
        'operation(String, Identifiable)'    | { v -> v.operation('o', idOf('m')) }
    }

    def 'a null Identifiable is reported as an illegal dispatch, not an argument complaint'() {
        given:
        def view = topologyView()

        when:
        view.step((Identifiable) null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('read-only topology view')
    }

    def 'a data-trigger gate cannot run a step, and the step never takes effect'() {
        given:
        def stepRuns = []
        def entity = new Entity('s1')
        def sm = build({ d -> d
            .step('audit', { e, c, tr -> stepRuns << 'ran' } as Step)
            .state('s1', { st -> st.transitionsTo('s2', 't', { t -> t.addDataTrigger('gated', { dt -> dt
                .condition('probes', { e, c, tr -> tr.step('audit'); true } as Condition) }) }) })
            .state('s2', {}) })

        when:
        sm.entity(entity).processDataChange()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('read-only topology view')
        stepRuns.isEmpty()
        entity.state == 's1'
    }

    def 'a gate that only reads topology is allowed'() {
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

    private static Transition<Entity, TestContext> topologyView() {
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .state('s1', { st -> st.transitionsTo('s2', 't', TestContext, { t -> }) })
            .state('s2', {})
        def sm = (StateMachineImpl<Entity>) smd.build()
        return new TopologyTransition<Entity, TestContext>(sm.getTransition('t'))
    }

    private static Identifiable idOf(String value) {
        return { -> value } as Identifiable
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
