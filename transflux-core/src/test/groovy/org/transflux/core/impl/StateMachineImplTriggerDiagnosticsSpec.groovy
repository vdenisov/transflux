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

import ch.qos.logback.classic.Level
import org.transflux.core.StateMachine
import org.transflux.core.StateMachineDef
import org.transflux.core.TestContext
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateResolver
import spock.lang.Specification

import java.util.function.BiPredicate
import java.util.function.Consumer
import java.util.function.Predicate

/**
 * The contract behind the trigger-scan logging: {@code processEvent} / {@code processDataChange} can
 * return {@code fired() == false} for four different reasons, and a host reading the log must be
 * able to tell which one applied without guessing.
 */
class StateMachineImplTriggerDiagnosticsSpec extends Specification {

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

    LogCapture capture

    def setup() {
        capture = LogCapture.start('org.transflux.trigger')
    }

    def cleanup() {
        capture.stop()
    }

    // --- processEvent ---

    def 'the scan line reports zero candidates when no event trigger leaves the current state'() {
        given: 'the only event trigger leaves s2, while the entity sits in s1'
        def sm = build({ d -> d
            .state('s1', { st -> st.transitionsTo('s2', 't1', {}) })
            .state('s2', { st -> st.transitionsTo('s3', 't2', { t -> t.addEventTrigger('paid', 'PAYMENT') }) })
            .state('s3', {}) })

        when:
        def result = sm.entity(new Entity('s1')).processEvent('PAYMENT', null)

        then: 'wrong-source-state has no per-candidate line to carry it - the count is the explanation'
        !result.fired()
        capture.messages()
            .contains('Event dispatch scan, eventId=PAYMENT, currentState=s1, candidates=0, leaving=0')
        capture.messages().contains('No trigger fired, eventId=PAYMENT, currentState=s1')
        capture.messages().every { !it.startsWith('Trigger skipped') }
    }

    def 'a trigger listening for another event is not a candidate, and the two counts say which'() {
        given:
        def sm = build({ d -> d
            .state('s1', { st -> st.transitionsTo('s2', 't', { t -> t.addEventTrigger('paid', 'PAYMENT') }) })
            .state('s2', {}) })

        when:
        def result = sm.entity(new Entity('s1')).processEvent('SHIPPED', null)

        then: 'leaving=1 with candidates=0 says a trigger is here but not for this event id'
        !result.fired()
        capture.messages()
            .contains('Event dispatch scan, eventId=SHIPPED, currentState=s1, candidates=0, leaving=1')

        and: 'and it draws no skip line, so the reasons that do explain a miss are not buried'
        capture.messages().every { !it.startsWith('Trigger skipped') }
    }

    def 'a trigger whose transition refuses the context is skipped, naming both types'() {
        given:
        def sm = build({ d -> d
            .state('s1', { st -> st.transitionsTo('s2', 't', TestContext,
                { t -> t.addEventTrigger('paid', 'PAYMENT') }) })
            .state('s2', {}) })

        when: 'the firing context - not the payload - is of the wrong type'
        def result = sm.entity(new Entity('s1')).processEvent('PAYMENT', null, 'not a TestContext')

        then:
        !result.fired()
        capture.messages().contains('Trigger skipped, triggerId=paid, reason=context-incompatible('
            + "expects=${TestContext.name};got=java.lang.String)".toString())

        and: 'the reason value carries neither the field separator nor whitespace'
        def reason = capture.messages().find { it.startsWith('Trigger skipped') }.split('reason=')[1]
        !reason.contains(', ')
        !reason.contains(' ')
    }

    def 'a Void-context transition renders its expected type without a space'() {
        given: 'the reason token would otherwise read "expects=Void (no context)"'
        def sm = build({ d -> d
            .state('s1', { st -> st.transitionsTo('s2', 't', Void,
                { t -> t.addEventTrigger('paid', 'PAYMENT') }) })
            .state('s2', {}) })

        when:
        def result = sm.entity(new Entity('s1')).processEvent('PAYMENT', null, 'unwanted')

        then:
        !result.fired()
        capture.messages().contains('Trigger skipped, triggerId=paid, '
            + 'reason=context-incompatible(expects=Void;got=java.lang.String)')
    }

    def 'a filter that rejects the payload is skipped as filter-rejected'() {
        given:
        def sm = build({ d -> d
            .state('s1', { st -> st.transitionsTo('s2', 't', { t -> t.addEventTrigger('paid', { et -> et
                .onEvent('PAYMENT')
                .filter({ ev, e -> ((Payload) ev).kind == 'paid' } as BiPredicate) }) }) })
            .state('s2', {}) })

        when:
        def result = sm.entity(new Entity('s1')).processEvent('PAYMENT', new Payload('pending'))

        then:
        !result.fired()
        capture.messages().contains('Trigger skipped, triggerId=paid, reason=filter-rejected')
    }

    def 'a fired event trigger reports the trigger and the transition it drives'() {
        given:
        def sm = build({ d -> d
            .state('s1', { st -> st.transitionsTo('s2', 't', { t -> t.addEventTrigger('paid', 'PAYMENT') }) })
            .state('s2', {}) })

        when:
        def result = sm.entity(new Entity('s1')).processEvent('PAYMENT', null)

        then:
        result.fired()
        capture.messages().contains('Trigger fired, triggerId=paid, transitionId=t')
        capture.messages().every { !it.startsWith('No trigger fired') }
    }

    def 'the scan explains every candidate it passed over before firing'() {
        given: 'a non-candidate, two candidates skipped for different reasons, then one that fires'
        def sm = build({ d -> d
            .state('s1', { st -> st
                .transitionsTo('s2', 'wrong-event', { t -> t.addEventTrigger('other-event', 'SHIPPED') })
                .transitionsTo('s2', 'picky-ctx', TestContext, { t -> t.addEventTrigger('bad-ctx', 'PAYMENT') })
                .transitionsTo('s2', 'filtered', { t -> t.addEventTrigger('rejects', { et -> et
                    .onEvent('PAYMENT')
                    .filter({ ev, e -> false } as BiPredicate) }) })
                .transitionsTo('s2', 'accepts', { t -> t.addEventTrigger('winner', 'PAYMENT') }) })
            .state('s2', {}) })

        when:
        def result = sm.entity(new Entity('s1')).processEvent('PAYMENT', null, 'not a TestContext')

        then:
        result.firedTriggerId() == 'winner'

        and: 'one skip line per passed-over candidate, and nothing unexplained'
        def skips = capture.messages().findAll { it.startsWith('Trigger skipped') }
        skips.size() == 2
        skips[0].startsWith('Trigger skipped, triggerId=bad-ctx, reason=context-incompatible(')
        skips[1] == 'Trigger skipped, triggerId=rejects, reason=filter-rejected'
        capture.messages().contains('Trigger fired, triggerId=winner, transitionId=accepts')

        and: 'the trigger listening for SHIPPED shows up as the gap between the two counts'
        capture.messages()
            .contains('Event dispatch scan, eventId=PAYMENT, currentState=s1, candidates=3, leaving=4')
    }

    // --- processDataChange ---

    def 'the scan line reports zero candidates when no data trigger leaves the current state'() {
        given:
        def sm = build({ d -> d
            .state('s1', { st -> st.transitionsTo('s2', 't1', {}) })
            .state('s2', {}) })

        when:
        def result = sm.entity(new Entity('s1')).processDataChange()

        then:
        !result.fired()
        capture.messages().contains('Data change dispatch scan, currentState=s1, candidates=0')
        capture.messages().contains('No trigger fired, currentState=s1')
    }

    def 'a gate that does not hold is skipped as gate-rejected'() {
        given:
        def sm = build({ d -> d
            .state('s1', { st -> st.transitionsTo('s2', 't', { t -> t.addDataTrigger('ready', { dt ->
                dt.condition('never', { Entity e -> false } as Predicate) }) }) })
            .state('s2', {}) })

        when:
        def result = sm.entity(new Entity('s1')).processDataChange()

        then:
        !result.fired()
        capture.messages().contains('Data change dispatch scan, currentState=s1, candidates=1')
        capture.messages().contains('Trigger skipped, triggerId=ready, reason=gate-rejected')
        capture.messages().contains('No trigger fired, currentState=s1')
    }

    def 'a held gate reports the trigger and the transition it drives'() {
        given:
        def sm = build({ d -> d
            .state('s1', { st -> st.transitionsTo('s2', 't', { t -> t.addDataTrigger('ready', { dt ->
                dt.condition('always', { Entity e -> true } as Predicate) }) }) })
            .state('s2', {}) })

        when:
        def result = sm.entity(new Entity('s1')).processDataChange()

        then:
        result.fired()
        capture.messages().contains('Trigger fired, triggerId=ready, transitionId=t')
    }

    // --- fire ---

    def 'a manual fire reports the trigger and the transition it drives'() {
        given: 'the path that has no scan to explain it, and so is the easiest one to leave silent'
        def sm = build({ d -> d
            .state('s1', { st -> st.transitionsTo('s2', 't', { t -> t.addManualTrigger('go') }) })
            .state('s2', {}) })

        when:
        sm.entity(new Entity('s1')).fire('go')

        then: 'Trigger fired is a complete record of firings, not just of the dispatched ones'
        capture.messages().contains('Trigger fired, triggerId=go, transitionId=t')
    }

    // --- level discipline ---

    def 'the trigger tree stays below INFO across every dispatch path'() {
        given:
        def sm = build({ d -> d
            .state('s1', { st -> st
                .transitionsTo('s2', 'manual', { t -> t.addManualTrigger('go') })
                .transitionsTo('s2', 'evt', { t -> t.addEventTrigger('paid', 'PAYMENT') })
                .transitionsTo('s2', 'data', { t -> t.addDataTrigger('ready', { dt ->
                    dt.condition('never', { Entity e -> false } as Predicate) }) }) })
            .state('s2', {}) })

        when: 'a scan that fires nothing, a scan that fires, and a manual fire'
        sm.entity(new Entity('s1')).processDataChange()
        sm.entity(new Entity('s1')).processEvent('PAYMENT', null)
        sm.entity(new Entity('s1')).fire('go')

        then: 'the capture is at TRACE, so anything promoted to INFO would show up here'
        !capture.events().isEmpty()
        capture.messagesAtOrAbove(Level.INFO).isEmpty()
    }

    // --- logger placement ---

    def 'every scan line lands on the trigger logger'() {
        given:
        def sm = build({ d -> d
            .state('s1', { st -> st.transitionsTo('s2', 't', { t -> t.addEventTrigger('paid', 'PAYMENT') }) })
            .state('s2', {}) })

        when:
        sm.entity(new Entity('s1')).processEvent('SHIPPED', null)

        then:
        !capture.events().isEmpty()
        capture.events().every { it.loggerName == 'org.transflux.trigger' }
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
