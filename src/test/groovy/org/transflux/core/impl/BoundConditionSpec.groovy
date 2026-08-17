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
import org.transflux.core.action.Action
import org.transflux.core.action.OperationDef
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.ExecutingTransition
import spock.lang.Specification
import spock.lang.Unroll

import java.util.function.Consumer
import java.util.function.Predicate

/**
 * The one seam every condition kind passes through. Each of the four roles is driven end-to-end
 * through a real state machine, so the spec also pins which role each call site reports.
 */
class BoundConditionSpec extends Specification {

    static class Entity {
        String state
        int value

        Entity(String state, int value) {
            this.state = state
            this.value = value
        }
    }

    static class NoopStep implements Action<Entity, TestContext> {
        @Override
        void execute(Entity entity, TestContext context, ExecutingTransition<Entity, TestContext> transition) {
        }
    }

    LogCapture capture

    def setup() {
        capture = LogCapture.start('org.transflux.execution.condition')
    }

    def cleanup() {
        capture.stop()
    }

    @Unroll
    def 'a #outcome pre-condition reports its id, role and result'() {
        given:
        def sm = build({ t -> t.preCondition('positive', { Entity e -> e.value > 0 } as Predicate) })

        when:
        def result = sm.entity(new Entity('s1', value)).transitionTo('s2', new TestContext())

        then:
        result.success == held
        capture.messages()
            .contains("Condition evaluated, conditionId=positive, role=PRE_CONDITION, held=${held}".toString())

        where:
        outcome    | value || held
        'holding'  | 1     || true
        'rejected' | -1    || false
    }

    def 'a post-condition reports under its own role, distinguishable from a pre-condition'() {
        given:
        def sm = build({ t -> t
            .preCondition('pre-gate', { Entity e -> true } as Predicate)
            .postCondition('post-gate', { Entity e -> false } as Predicate) })

        when:
        def result = sm.entity(new Entity('s1', 1)).transitionTo('s2', new TestContext())

        then:
        !result.success
        capture.messages().contains('Condition evaluated, conditionId=pre-gate, role=PRE_CONDITION, held=true')
        capture.messages().contains('Condition evaluated, conditionId=post-gate, role=POST_CONDITION, held=false')
    }

    def 'a data trigger gate reports under TRIGGER_GATE, on the trigger logger'() {
        given: 'the gate belongs to the dispatch scan it explains, not to condition evaluation'
        def triggerCapture = LogCapture.start('org.transflux.trigger')
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .withStateApplier({ e, s -> e.state = s } as StateApplier<Entity>)
            .state('s1', { st -> st.transitionsTo('s2', 't', { t -> t.addDataTrigger('ready', { dt ->
                dt.condition('gate', { Entity e -> e.value > 0 } as Predicate) }) }) })
            .state('s2', {})
        def sm = smd.build()

        when:
        def result = sm.entity(new Entity('s1', 0)).processDataChange()

        then: 'the gate value sits next to the gate-rejected line that summarises it'
        !result.fired()
        triggerCapture.messages()
            .contains('Condition evaluated, conditionId=gate, role=TRIGGER_GATE, held=false')
        triggerCapture.messages().contains('Trigger skipped, triggerId=ready, reason=gate-rejected')

        and: 'and a host silencing the condition tree per its documented scope keeps it'
        capture.messages().isEmpty()

        cleanup:
        triggerCapture.stop()
    }

    def 'branch selectors report at TRACE, while transition-level conditions report at DEBUG'() {
        given:
        def sm = build({ t -> t
            .preCondition('pre-gate', { Entity e -> true } as Predicate)
            .operation('route', { OperationDef<Entity, TestContext> op ->
                op.conditional('pick', { c -> c
                    .branch('low', { b -> b
                        .condition('is-low', { Entity e -> e.value < 0 } as Predicate)
                        .step('low-step', new NoopStep()) })
                    .branch('high', { b -> b
                        .condition('is-high', { Entity e -> e.value > 0 } as Predicate)
                        .step('high-step', new NoopStep()) }) }) }) })

        when:
        def result = sm.entity(new Entity('s1', 5)).transitionTo('s2', new TestContext())

        then:
        result.success

        and: 'both selectors evaluated, the first rejecting and the second matching'
        capture.messages().contains('Condition evaluated, conditionId=is-low, role=BRANCH, held=false')
        capture.messages().contains('Condition evaluated, conditionId=is-high, role=BRANCH, held=true')

        and: 'a branch selector runs once per branch per execution, so it stays below DEBUG'
        capture.messagesAtOrAbove(Level.DEBUG).every { !it.contains('role=BRANCH') }
        capture.messagesAtOrAbove(Level.DEBUG)
            .contains('Condition evaluated, conditionId=pre-gate, role=PRE_CONDITION, held=true')
    }

    def 'a condition that throws reports the failure, by class name only'() {
        given: 'the one outcome no listener sees - the start hook has not fired yet'
        def sm = build({ t -> t.preCondition('boom', { Entity e ->
            throw new IllegalStateException('entity ' + e.value + ' is unloved')
        } as Predicate) })

        when:
        def result = sm.entity(new Entity('s1', 7)).transitionTo('s2', new TestContext())

        then:
        !result.success
        capture.messages().contains(
            'Condition failed, conditionId=boom, role=PRE_CONDITION, errorType=java.lang.IllegalStateException')

        and: 'the message is not logged - it may carry entity contents'
        capture.messages().every { !it.contains('unloved') && !it.contains('7') }
    }

    def 'condition evaluation never reaches INFO'() {
        given:
        def sm = build({ t -> t.preCondition('gate', { Entity e -> true } as Predicate) })

        when:
        sm.entity(new Entity('s1', 1)).transitionTo('s2', new TestContext())

        then:
        !capture.events().isEmpty()
        capture.messagesAtOrAbove(Level.INFO).isEmpty()
    }

    def 'every condition line lands on the condition logger'() {
        given:
        def sm = build({ t -> t.preCondition('gate', { Entity e -> true } as Predicate) })

        when:
        sm.entity(new Entity('s1', 1)).transitionTo('s2', new TestContext())

        then:
        !capture.events().isEmpty()
        capture.events().every { it.loggerName == 'org.transflux.execution.condition' }
    }

    private static StateMachine<Entity> build(Consumer<Object> transitionConfig) {
        def smd = new StateMachineDefImpl<Entity>()
        StateMachineDef<Entity> builder = smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .withStateApplier({ e, s -> e.state = s } as StateApplier<Entity>)
        builder.state('s1', { st -> st.transitionsTo('s2', 't', TestContext, { t ->
            transitionConfig.accept(t)
        }) })
        builder.state('s2', {})
        return smd.build()
    }
}
