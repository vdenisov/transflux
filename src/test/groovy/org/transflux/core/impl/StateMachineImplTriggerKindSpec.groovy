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
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateResolver
import org.transflux.core.trigger.DataTrigger
import org.transflux.core.trigger.EventTrigger
import org.transflux.core.trigger.ManualTrigger
import org.transflux.core.trigger.Trigger
import spock.lang.Specification
import spock.lang.Unroll

import java.util.function.Consumer
import java.util.function.Predicate

class StateMachineImplTriggerKindSpec extends Specification {

    static class Entity {
        String state

        Entity(String state) {
            this.state = state
        }
    }

    def 'getTriggers(Class) partitions the catalog by kind'() {
        given:
        def sm = mixedMachine()

        expect:
        sm.getTriggers().size() == 3
        sm.getTriggers(ManualTrigger)*.id == ['m']
        sm.getTriggers(EventTrigger)*.id == ['e']
        sm.getTriggers(DataTrigger)*.id == ['d']
        sm.getTriggers(Trigger).size() == 3
    }

    def 'getTriggers(Class) narrows the element type'() {
        given:
        def sm = mixedMachine()

        when:
        Collection<EventTrigger> events = sm.getTriggers(EventTrigger)

        then:
        events.size() == 1
        events.first().eventId == 'EVT'
    }

    def 'getTriggers(Class) returns an empty collection for a kind with no instances'() {
        given:
        def sm = build({ d -> d.state('s1', { st ->
            st.transitionsTo('s2', 't', { t -> t.addManualTrigger('m') }) })
            .state('s2', {}) })

        expect:
        sm.getTriggers(EventTrigger).isEmpty()
        sm.getTriggers(DataTrigger).isEmpty()
    }

    def 'getTriggers(null) is rejected'() {
        given:
        def sm = mixedMachine()

        when:
        sm.getTriggers((Class) null)

        then:
        thrown(TransfluxValidationException)
    }

    def 'a trigger id reused across two kinds is rejected at build'() {
        when:
        build({ d -> d.state('s1', { st -> st
            .transitionsTo('s2', 't1', { t -> t.addManualTrigger('dup') })
            .transitionsTo('s3', 't2', { t -> t.addEventTrigger('dup', 'EVT') }) })
            .state('s2', {})
            .state('s3', {}) })

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("'dup'")
        e.message.contains('already registered')
    }

    @Unroll
    def 'fire() rejects #kind trigger id "#triggerId", pointing at #entryPoint'() {
        given:
        def sm = mixedMachine()

        when:
        sm.entity(new Entity('s1')).fire(triggerId)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("'${triggerId}'")
        e.message.contains(kind)
        e.message.contains('cannot be fired directly')
        e.message.contains(entryPoint)

        where:
        triggerId | kind              || entryPoint
        'e'       | 'an event trigger' || 'processEvent'
        'd'       | 'a data trigger'   || 'processDataChange'
    }

    def 'fire() still accepts a manual trigger id'() {
        given:
        def sm = mixedMachine()
        def entity = new Entity('s1')

        when:
        def result = sm.entity(entity).fire('m')

        then:
        result.success
        entity.state == 's2'
    }

    def 'firing an event trigger id does not bypass its filter by executing the transition'() {
        given:
        def entity = new Entity('s1')
        def sm = build({ d -> d.state('s1', { st ->
            st.transitionsTo('s2', 't', { t -> t.addEventTrigger('gated', { et -> et
                .onEvent('EVT')
                .filter({ payload -> false } as Predicate) }) }) })
            .state('s2', {}) })

        when:
        sm.entity(entity).fire('gated')

        then:
        thrown(TransfluxValidationException)
        entity.state == 's1'
    }

    private static StateMachine<Entity> mixedMachine() {
        return build({ d -> d.state('s1', { st -> st
            .transitionsTo('s2', 't1', { t -> t.addManualTrigger('m') })
            .transitionsTo('s3', 't2', { t -> t.addEventTrigger('e', 'EVT') })
            .transitionsTo('s4', 't3', { t -> t.addDataTrigger('d', { dt -> dt.condition('any', { e -> true } as Predicate) }) }) })
            .state('s2', {})
            .state('s3', {})
            .state('s4', {}) })
    }

    private static StateMachine<Entity> build(Consumer<StateMachineDef<Entity>> cfg) {
        def smd = new StateMachineDefImpl<Entity>()
        StateMachineDef<Entity> builder = smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .withStateApplier({ e, s -> e.state = s } as StateApplier<Entity>)
        cfg.accept(builder)
        return smd.build()
    }
}
