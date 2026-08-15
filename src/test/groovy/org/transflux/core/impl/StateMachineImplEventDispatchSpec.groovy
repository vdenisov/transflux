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
import org.transflux.core.action.Action
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateResolver
import spock.lang.Specification

import java.util.function.BiPredicate
import java.util.function.Consumer
import java.util.function.Predicate

class StateMachineImplEventDispatchSpec extends Specification {

    static class Entity {
        String state

        Entity(String state) {
            this.state = state
        }
    }

    static class Payload {
        String kind

        Payload(String kind) {
            this.kind = kind
        }
    }

    static class KindIsPaid implements BiPredicate<Object, Entity> {
        @Override
        boolean test(Object eventData, Entity entity) {
            return ((Payload) eventData).kind == 'paid'
        }
    }

    def 'processEvent fires the trigger whose event id matches'() {
        given:
        def sm = build({ d -> d.state('s1', { st ->
            st.transitionsTo('s2', 't', { t -> t.addEventTrigger('paid-trigger', 'PAYMENT') }) })
            .state('s2', {}) })
        def entity = new Entity('s1')

        when:
        def result = sm.entity(entity).processEvent('PAYMENT', null)

        then:
        result.fired()
        result.firedTriggerId() == 'paid-trigger'
        result.result().get().success
        entity.state == 's2'
    }

    def 'processEvent with no matching event id fires nothing'() {
        given:
        def sm = build({ d -> d.state('s1', { st ->
            st.transitionsTo('s2', 't', { t -> t.addEventTrigger('paid-trigger', 'PAYMENT') }) })
            .state('s2', {}) })
        def entity = new Entity('s1')

        when:
        def result = sm.entity(entity).processEvent('SHIPPED', null)

        then:
        !result.fired()
        result.result().isEmpty()
        result.firedTriggerId() == null
        entity.state == 's1'
    }

    def 'a filter that rejects the payload skips the trigger'() {
        given:
        def sm = build({ d -> d.state('s1', { st ->
            st.transitionsTo('s2', 't', { t -> t.addEventTrigger('paid', { et -> et
                .onEvent('PAYMENT')
                .filter({ ev, e -> ((Payload) ev).kind == 'paid' } as BiPredicate) }) }) })
            .state('s2', {}) })

        expect:
        !sm.entity(new Entity('s1')).processEvent('PAYMENT', new Payload('pending')).fired()
        sm.entity(new Entity('s1')).processEvent('PAYMENT', new Payload('paid')).fired()
    }

    def 'a predicate-class filter resolves and gates'() {
        given:
        def sm = build({ d -> d.state('s1', { st ->
            st.transitionsTo('s2', 't', { t -> t.addEventTrigger('paid', { et -> et
                .onEvent('PAYMENT')
                .filter(KindIsPaid) }) }) })
            .state('s2', {}) })

        expect:
        !sm.entity(new Entity('s1')).processEvent('PAYMENT', new Payload('pending')).fired()
        sm.entity(new Entity('s1')).processEvent('PAYMENT', new Payload('paid')).fired()
    }

    def 'an expression filter binds the entity as root, the payload as #event, and the context as #context'() {
        given:
        def sm = build({ d -> d.state('s1', { st ->
            st.transitionsTo('s2', 't', TestContext, { t -> t.addEventTrigger('paid', { et -> et
                .onEvent('PAYMENT')
                .filterExpression("state == 's1' && #event.kind == 'paid' && #context.tag == 'ok'") }) }) })
            .state('s2', {}) })

        expect:
        sm.entity(new Entity('s1')).processEvent('PAYMENT', new Payload('paid'), new TestContext('ok')).fired()
        !sm.entity(new Entity('s1')).processEvent('PAYMENT', new Payload('pending'), new TestContext('ok')).fired()
        !sm.entity(new Entity('s1')).processEvent('PAYMENT', new Payload('paid'), new TestContext('no')).fired()
    }

    def 'among triggers on different transitions, the first declared whose filter passes fires'() {
        given:
        def sm = build({ d -> d.state('s1', { st -> st
            .transitionsTo('s2', 't1', { t -> t.addEventTrigger('first', { et -> et
                .onEvent('GO').filter({ ev, e -> false } as BiPredicate) }) })
            .transitionsTo('s3', 't2', { t -> t.addEventTrigger('second', { et -> et
                .onEvent('GO') }) }) })
            .state('s2', {})
            .state('s3', {}) })
        def entity = new Entity('s1')

        when:
        def result = sm.entity(entity).processEvent('GO', null)

        then:
        result.fired()
        result.firedTriggerId() == 'second'
        entity.state == 's3'
    }

    def 'a filter passing but a transition pre-condition failing reports a fired failure without trying the next trigger'() {
        given:
        def sm = build({ d -> d.state('s1', { st -> st
            .transitionsTo('s2', 't1', { t -> t
                .preCondition('blocked', { e -> false } as Predicate)
                .addEventTrigger('first', 'GO') })
            .transitionsTo('s3', 't2', { t -> t.addEventTrigger('second', 'GO') }) })
            .state('s2', {})
            .state('s3', {}) })
        def entity = new Entity('s1')

        when:
        def result = sm.entity(entity).processEvent('GO', null)

        then:
        result.fired()
        result.firedTriggerId() == 'first'
        result.result().get().isFailure()
        entity.state == 's1'
    }

    def 'the firing context is passed to the operation and is distinct from the event payload'() {
        given:
        def seen = []
        def sm = build({ d -> d.state('s1', { st ->
            st.transitionsTo('s2', 't', TestContext, { t -> t
                .step('op', { e, c, tr -> seen.add(c) } as Action)
                .addEventTrigger('go', 'GO') }) })
            .state('s2', {}) })
        def ctx = new TestContext('the-context')

        when:
        def result = sm.entity(new Entity('s1')).processEvent('GO', new Payload('paid'), ctx)

        then:
        result.fired()
        seen == [ctx]
    }

    def 'a trigger whose transition rejects the supplied context type is skipped, not fired'() {
        given:
        def entity = new Entity('s1')
        def sm = build({ d -> d.state('s1', { st ->
            st.transitionsTo('s2', 't', TestContext, { t -> t.addEventTrigger('go', 'GO') }) })
            .state('s2', {}) })

        when:
        def result = sm.entity(entity).processEvent('GO', null, 'not-a-context')

        then:
        !result.fired()
        entity.state == 's1'
    }

    def 'a context-incompatible trigger is skipped and a later compatible one still fires'() {
        given:
        def entity = new Entity('s1')
        def sm = build({ d -> d.state('s1', { st -> st
            .transitionsTo('s2', 'typed', TestContext, { t -> t.addEventTrigger('first', 'GO') })
            .transitionsTo('s3', 'untyped', { t -> t.addEventTrigger('second', 'GO') }) })
            .state('s2', {})
            .state('s3', {}) })

        when: 'a context the first trigger cannot accept is supplied'
        def result = sm.entity(entity).processEvent('GO', null, 'not-a-context')

        then: 'the first is passed over rather than aborting the scan'
        result.fired()
        result.firedTriggerId() == 'second'
        entity.state == 's3'
    }

    def 'a context-incompatible trigger never reaches its filter'() {
        given:
        def filterCalls = []
        def sm = build({ d -> d.state('s1', { st ->
            st.transitionsTo('s2', 't', TestContext, { t -> t.addEventTrigger('go', { et -> et
                .onEvent('GO')
                .filter({ payload, e -> filterCalls << payload; true } as BiPredicate) }) }) })
            .state('s2', {}) })

        when:
        def result = sm.entity(new Entity('s1')).processEvent('GO', 'payload', 'not-a-context')

        then:
        !result.fired()
        filterCalls.isEmpty()
    }

    def 'a targeted fire() still rejects the context the dispatch scan would skip'() {
        given:
        def sm = build({ d -> d.state('s1', { st ->
            st.transitionsTo('s2', 't', TestContext, { t -> t.addManualTrigger('go') }) })
            .state('s2', {}) })

        when:
        sm.entity(new Entity('s1')).fire('go', 'not-a-context')

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('Context type mismatch')
    }

    def 'a filter that throws is attributed to its trigger and keeps the original cause'() {
        given:
        def boom = new IllegalStateException('payload was not a PaymentEvent')
        def sm = build({ d -> d.state('s1', { st ->
            st.transitionsTo('s2', 't', { t -> t.addEventTrigger('go', { et -> et
                .onEvent('GO')
                .filter({ payload -> throw boom } as Predicate) }) }) })
            .state('s2', {}) })

        when:
        sm.entity(new Entity('s1')).processEvent('GO', 'payload')

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("Event trigger 'go' filter failed")
        e.message.contains('payload was not a PaymentEvent')
        e.cause === boom
    }

    def 'the Identifiable event overload delegates to the id form'() {
        given:
        def sm = build({ d -> d.state('s1', { st ->
            st.transitionsTo('s2', 't', { t -> t.addEventTrigger('go', 'GO') }) })
            .state('s2', {}) })
        Identifiable evt = { -> 'GO' } as Identifiable

        expect:
        sm.entity(new Entity('s1')).processEvent(evt, null).fired()
    }

    def 'a blank event id is rejected'() {
        given:
        def sm = build({ d -> d.state('s1', { st ->
            st.transitionsTo('s2', 't', { t -> t.addEventTrigger('go', 'GO') }) })
            .state('s2', {}) })

        when:
        sm.entity(new Entity('s1')).processEvent('  ', null)

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
