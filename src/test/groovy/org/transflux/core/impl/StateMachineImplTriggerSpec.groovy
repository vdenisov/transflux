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
import org.transflux.core.TestContext
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.action.Action
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.ExecutingTransition
import org.transflux.core.transition.TransitionDef
import spock.lang.Specification

import java.util.function.Consumer
import java.util.function.Predicate

class StateMachineImplTriggerSpec extends Specification {

    static class Entity {
        String state

        Entity(String state) {
            this.state = state
        }
    }

    static class FlaggingOperation implements Action<Entity, TestContext> {
        boolean executed = false

        @Override
        void execute(Entity entity, TestContext context, ExecutingTransition<Entity, TestContext> transition) {
            executed = true
        }
    }

    def 'firing a manual trigger executes its transition'() {
        given:
        def op = new FlaggingOperation()
        def applied = []
        def sm = build(op, applied, { t -> t.addManualTrigger('go') })

        when:
        def result = sm.entity(new Entity('s1')).fire('go')

        then:
        result.success
        result.transitionId == 't'
        op.executed
        applied == ['s2']
    }

    def 'the transition pre-conditions run before the trigger pre-conditions'() {
        given:
        def op = new FlaggingOperation()
        def applied = []
        def order = []
        def sm = build(op, applied, { t -> t
            .preCondition('tx-pre', { e -> order.add('tx-pre'); true } as Predicate)
            .addManualTrigger('go', { mt -> mt
                .preCondition('trig-pre', { e -> order.add('trig-pre'); true } as Predicate) }) })

        when:
        def result = sm.entity(new Entity('s1')).fire('go')

        then:
        result.success
        order == ['tx-pre', 'trig-pre']
        op.executed
    }

    def 'a failing trigger pre-condition blocks the transition'() {
        given:
        def op = new FlaggingOperation()
        def applied = []
        def sm = build(op, applied, { t -> t.addManualTrigger('go', { mt -> mt
            .preCondition('trig-fail', { e -> false } as Predicate) }) })

        when:
        def result = sm.entity(new Entity('s1')).fire('go')

        then:
        result.isFailure()
        !op.executed
        applied.isEmpty()
        result.error.message.contains("'trig-fail'")
    }

    def 'a trigger pre-condition gates fire while transitionTo bypasses the trigger'() {
        given:
        def op = new FlaggingOperation()
        def applied = []
        def sm = build(op, applied, { t -> t.addManualTrigger('go', { mt -> mt
            .preCondition('trig-fail', { e -> false } as Predicate) }) })

        expect:
        !sm.entity(new Entity('s1')).fire('go').success
        sm.entity(new Entity('s1')).transitionTo('s2').success
    }

    def 'firing from the wrong source state is rejected'() {
        given:
        def op = new FlaggingOperation()
        def applied = []
        def sm = build(op, applied, { t -> t.addManualTrigger('go') })

        when:
        sm.entity(new Entity('s2')).fire('go')

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("'go'")
        e.message.contains("source state 's1'")
    }

    def 'firing with the wrong context type is rejected'() {
        given:
        def op = new FlaggingOperation()
        def applied = []
        def sm = build(op, applied, { t -> t.addManualTrigger('go') })

        when:
        sm.entity(new Entity('s1')).fire('go', 'not-a-context')

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('Context type mismatch')
    }

    def 'firing passes a matching context through'() {
        given:
        def op = new FlaggingOperation()
        def applied = []
        def sm = build(op, applied, { t -> t.addManualTrigger('go') })

        when:
        def result = sm.entity(new Entity('s1')).fire('go', new TestContext('ctx'))

        then:
        result.success
        op.executed
    }

    def 'a transition can carry multiple manual triggers'() {
        given:
        def op = new FlaggingOperation()
        def applied = []
        def sm = build(op, applied, { t -> t
            .addManualTrigger('open')
            .addManualTrigger('gated', { mt -> mt.preCondition('block', { e -> false } as Predicate) }) })

        expect:
        sm.getTriggers().size() == 2
        sm.entity(new Entity('s1')).fire('open').success
        !sm.entity(new Entity('s1')).fire('gated').success
    }

    def 'a triggerless transition is absent from the catalog but still fireable via transitionTo'() {
        given:
        def op = new FlaggingOperation()
        def applied = []
        def sm = build(op, applied, { t -> })

        expect:
        sm.getTriggers().isEmpty()
        sm.entity(new Entity('s1')).transitionTo('s2').success

        when:
        sm.getTrigger('go')

        then:
        thrown(TransfluxValidationException)
    }

    def 'the catalog enumerates triggers by id'() {
        given:
        def op = new FlaggingOperation()
        def applied = []
        def sm = build(op, applied, { t -> t.addManualTrigger('go', { mt -> mt.withName('Go') }) })
        Identifiable goId = { -> 'go' } as Identifiable

        expect:
        sm.getTriggers()*.id == ['go']
        sm.getTrigger('go').name == 'Go'
        sm.getTrigger(goId).name == 'Go'
    }

    def 'firing by Identifiable delegates to the id form'() {
        given:
        def op = new FlaggingOperation()
        def applied = []
        def sm = build(op, applied, { t -> t.addManualTrigger('go') })
        Identifiable goId = { -> 'go' } as Identifiable

        when:
        def result = sm.entity(new Entity('s1')).fire(goId)

        then:
        result.success
    }

    def 'null and blank arguments are rejected'() {
        given:
        def op = new FlaggingOperation()
        def applied = []
        def sm = build(op, applied, { t -> t.addManualTrigger('go') })
        def binding = sm.entity(new Entity('s1'))

        when:
        binding.fire((String) null)

        then:
        thrown(TransfluxValidationException)

        when:
        binding.fire((Identifiable) null)

        then:
        thrown(TransfluxValidationException)

        when:
        sm.getTrigger((Identifiable) null)

        then:
        thrown(TransfluxValidationException)
    }

    private static StateMachine<Entity> build(FlaggingOperation op,
                                              List<String> applied,
                                              Consumer<TransitionDef<Entity, TestContext>> cfg) {
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .withStateApplier({ e, s -> applied.add(s); e.state = s } as StateApplier<Entity>)
            .state('s1', { st -> st.transitionsTo('s2', 't', TestContext, { t ->
                t.step('op', op)
                cfg.accept(t)
            }) })
            .state('s2', {})
        return smd.build()
    }
}
