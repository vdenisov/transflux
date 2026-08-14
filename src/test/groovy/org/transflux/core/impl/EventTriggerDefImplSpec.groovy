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
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.operation.Operation
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateResolver
import org.transflux.core.trigger.EventTrigger
import spock.lang.Specification
import spock.lang.Unroll

import java.util.function.BiPredicate
import java.util.function.Consumer
import java.util.function.Predicate

class EventTriggerDefImplSpec extends Specification {

    static class Entity {
        String state

        Entity(String state) {
            this.state = state
        }
    }

    static class RejectingFilter implements BiPredicate<Object, Entity> {
        @Override
        boolean test(Object eventData, Entity entity) {
            return eventData == 'reject'
        }
    }

    def 'a configured event trigger surfaces its id, metadata, event id and transition in the catalog'() {
        given:
        def sm = build({ d -> d.state('s1', { st ->
            st.transitionsTo('s2', 't', { t -> t.addEventTrigger('paid', { et -> et
                .onEvent('PAYMENT')
                .withName('Payment received')
                .withDescription('Fires on a confirmed payment') }) }) })
            .state('s2', {}) })

        when:
        def trigger = (EventTrigger) sm.getTrigger('paid')

        then:
        trigger.id == 'paid'
        trigger.name == 'Payment received'
        trigger.description == 'Fires on a confirmed payment'
        trigger.eventId == 'PAYMENT'
        trigger.transitionId == 't'
    }

    def 'the flat (id, eventId) form sets the event id'() {
        given:
        def sm = build({ d -> d.state('s1', { st ->
            st.transitionsTo('s2', 't', { t -> t.addEventTrigger('paid', 'PAYMENT') }) })
            .state('s2', {}) })

        expect:
        ((EventTrigger) sm.getTrigger('paid')).eventId == 'PAYMENT'
    }

    def 'the single-Identifiable form uses the event id as the trigger id'() {
        given:
        def sm = build({ d -> d.state('s1', { st ->
            st.transitionsTo('s2', 't', { t -> t.addEventTrigger({ -> 'PAYMENT' } as Identifiable) }) })
            .state('s2', {}) })

        when:
        def trigger = (EventTrigger) sm.getTrigger('PAYMENT')

        then:
        trigger.id == 'PAYMENT'
        trigger.eventId == 'PAYMENT'
    }

    def 'an event trigger declaring no event id is rejected at build'() {
        when:
        build({ d -> d.state('s1', { st ->
            st.transitionsTo('s2', 't', { t -> t.addEventTrigger('paid', { et -> et.withName('No event') }) }) })
            .state('s2', {}) })

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("'paid'")
        e.message.contains('no event id')
    }

    @Unroll
    def 'the last filter declared wins, overriding a previously declared #firstForm filter'() {
        given:
        def seen = []
        def sm = build({ d -> d.state('s1', { st ->
            st.transitionsTo('s2', 't', { t -> t
                .simpleOperation('op', { e, c, tr -> seen << 'fired' } as Operation)
                .addEventTrigger('paid', { et -> et.onEvent('PAYMENT') })
                .addEventTrigger('paid2', { et ->
                    et.onEvent('OTHER')
                    declareFirst.call(et)
                    et.filter({ payload -> payload == 'accept' } as Predicate) }) }) })
            .state('s2', {}) })

        when: 'the payload only the last-declared filter accepts is published'
        def accepted = sm.entity(new Entity('s1')).processEvent('OTHER', 'accept')

        then:
        accepted.fired()

        when: 'a payload only the overridden filter would have accepted is published'
        def rejected = sm.entity(new Entity('s1')).processEvent('OTHER', 'reject')

        then:
        !rejected.fired()

        where:
        firstForm     | declareFirst
        'BiPredicate' | { et -> et.filter({ payload, entity -> payload == 'reject' } as BiPredicate) }
        'Predicate'   | { et -> et.filter({ payload -> payload == 'reject' } as Predicate) }
        'class'       | { et -> et.filter(RejectingFilter) }
        'expression'  | { et -> et.filterExpression("#event == 'reject'") }
    }

    def 'a trigger declaring no filter fires on every published event of its id'() {
        given:
        def sm = build({ d -> d.state('s1', { st ->
            st.transitionsTo('s2', 't', { t -> t.addEventTrigger('paid', { et -> et.onEvent('PAYMENT') }) }) })
            .state('s2', {}) })

        expect:
        sm.entity(new Entity('s1')).processEvent('PAYMENT', 'anything').fired()
    }

    def 'mutating the event-trigger def after its configurer returns is rejected'() {
        given:
        EventTriggerDefImpl<Entity, TestContext> escaped = null

        when:
        build({ d -> d.state('s1', { st ->
            st.transitionsTo('s2', 't', TestContext, { t -> t.addEventTrigger('paid', { et ->
                escaped = (EventTriggerDefImpl<Entity, TestContext>) et
                et.onEvent('PAYMENT') }) }) })
            .state('s2', {}) })
        escaped.onEvent('LATE')

        then:
        thrown(TransfluxValidationException)
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
