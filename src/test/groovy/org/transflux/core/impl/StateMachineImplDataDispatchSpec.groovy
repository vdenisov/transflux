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
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.operation.Operation
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.Transition
import spock.lang.Specification

import java.util.function.BiPredicate
import java.util.function.Consumer
import java.util.function.Predicate

class StateMachineImplDataDispatchSpec extends Specification {

    static class Entity {
        String state
        int priority

        Entity(String state, int priority = 0) {
            this.state = state
            this.priority = priority
        }
    }

    def 'processDataChange fires when the gate holds and skips when it does not'() {
        given:
        def sm = build({ d -> d.state('s1', { st ->
            st.transitionsTo('s2', 't', { t -> t.addDataTrigger('hot', { dt -> dt
                .condition('high', { e -> e.priority > 5 } as Predicate) }) }) })
            .state('s2', {}) })

        expect:
        !sm.entity(new Entity('s1', 3)).processDataChange().fired()

        when:
        def entity = new Entity('s1', 9)
        def result = sm.entity(entity).processDataChange()

        then:
        result.fired()
        result.firedTriggerId() == 'hot'
        result.result().get().success
        entity.state == 's2'
    }

    def 'the gate is evaluated against the entity and the supplied context'() {
        given:
        def sm = build({ d -> d.state('s1', { st ->
            st.transitionsTo('s2', 't', TestContext, { t -> t.addDataTrigger('hot', { dt -> dt
                .conditionExpression("priority > 5 && #context.tag == 'go'") }) }) })
            .state('s2', {}) })

        expect:
        sm.entity(new Entity('s1', 9)).processDataChange(new TestContext('go')).fired()
        !sm.entity(new Entity('s1', 9)).processDataChange(new TestContext('stop')).fired()
        !sm.entity(new Entity('s1', 1)).processDataChange(new TestContext('go')).fired()
    }

    def 'when two gates hold, the first declared fires'() {
        given:
        def sm = build({ d -> d.state('s1', { st -> st
            .transitionsTo('s2', 't1', { t -> t.addDataTrigger('first', { dt -> dt
                .condition('any', { e -> true } as Predicate) }) })
            .transitionsTo('s3', 't2', { t -> t.addDataTrigger('second', { dt -> dt
                .condition('alsoAny', { e -> true } as Predicate) }) }) })
            .state('s2', {})
            .state('s3', {}) })
        def entity = new Entity('s1')

        when:
        def result = sm.entity(entity).processDataChange()

        then:
        result.fired()
        result.firedTriggerId() == 'first'
        entity.state == 's2'
    }

    def 'the firing context is passed to the operation'() {
        given:
        def seen = []
        def sm = build({ d -> d.state('s1', { st ->
            st.transitionsTo('s2', 't', TestContext, { t -> t
                .simpleOperation('op', { e, c, tr -> seen.add(c) } as Operation)
                .addDataTrigger('hot', { dt -> dt.condition('any', { e -> true } as Predicate) }) }) })
            .state('s2', {}) })
        def ctx = new TestContext('ctx')

        when:
        sm.entity(new Entity('s1')).processDataChange(ctx)

        then:
        seen == [ctx]
    }

    def 'a data trigger does not fire until processDataChange is called'() {
        given:
        def fired = []
        def sm = build({ d -> d.state('s1', { st ->
            st.transitionsTo('s2', 't', { t -> t
                .simpleOperation('op', { e, c, tr -> fired.add(e) } as Operation)
                .addDataTrigger('hot', { dt -> dt.condition('any', { e -> true } as Predicate) }) }) })
            .state('s2', {}) })
        def entity = new Entity('s1')

        expect: 'building and holding the machine fires nothing on its own'
        fired.isEmpty()
        entity.state == 's1'
        sm.getTriggers().size() == 1

        when: 'the host explicitly requests re-evaluation'
        sm.entity(entity).processDataChange()

        then:
        fired == [entity]
        entity.state == 's2'
    }

    def 'only triggers leaving the current state are eligible'() {
        given:
        def sm = build({ d -> d
            .state('s1', { st -> st.transitionsTo('s2', 't1', { t -> t.addDataTrigger('from-s1', { dt -> dt
                .condition('any', { e -> true } as Predicate) }) }) })
            .state('s2', { st -> st.transitionsTo('s3', 't2', { t -> t.addDataTrigger('from-s2', { dt -> dt
                .condition('any2', { e -> true } as Predicate) }) }) })
            .state('s3', {}) })

        when: 'the entity is in s2, the s1 trigger is not eligible'
        def entity = new Entity('s2')
        def result = sm.entity(entity).processDataChange()

        then:
        result.fired()
        result.firedTriggerId() == 'from-s2'
        entity.state == 's3'
    }

    def 'a gate that throws is attributed to its trigger and keeps the original cause'() {
        given:
        def boom = new IllegalStateException('entity had no priority')
        def sm = build({ d -> d.state('s1', { st ->
            st.transitionsTo('s2', 't', { t -> t.addDataTrigger('hot', { dt -> dt
                .condition('explodes', { e -> throw boom } as Predicate) }) }) })
            .state('s2', {}) })

        when:
        sm.entity(new Entity('s1')).processDataChange()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("Data trigger 'hot' gate condition failed")
        e.message.contains('entity had no priority')
        e.cause === boom
    }

    def 'a typed gate never sees a context its transition would reject'() {
        given:
        def gateCalls = []
        def sm = build({ d -> d.state('s1', { st ->
            st.transitionsTo('s2', 't', TestContext, { t -> t.addDataTrigger('gated', { dt -> dt
                .condition('tagged', { e, TestContext c -> gateCalls << c; true } as BiPredicate) }) }) })
            .state('s2', {}) })
        def entity = new Entity('s1')

        when: 'a context of the wrong type is supplied'
        def result = sm.entity(entity).processDataChange('not-a-context')

        then: 'the gate is never invoked, so no ClassCastException escapes'
        noExceptionThrown()
        !result.fired()
        gateCalls.isEmpty()
        entity.state == 's1'
    }

    def 'a context-incompatible data trigger is skipped and a later compatible one still fires'() {
        given:
        def entity = new Entity('s1')
        def sm = build({ d -> d.state('s1', { st -> st
            .transitionsTo('s2', 'typed', TestContext, { t -> t.addDataTrigger('first', { dt -> dt
                .condition('always', { e -> true } as Predicate) }) })
            .transitionsTo('s3', 'untyped', { t -> t.addDataTrigger('second', { dt -> dt
                .condition('always2', { e -> true } as Predicate) }) }) })
            .state('s2', {})
            .state('s3', {}) })

        when:
        def result = sm.entity(entity).processDataChange('not-a-context')

        then:
        result.fired()
        result.firedTriggerId() == 'second'
        entity.state == 's3'
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
