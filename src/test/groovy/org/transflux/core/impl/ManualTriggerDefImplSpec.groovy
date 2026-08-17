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
import org.transflux.core.condition.Condition
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.Transition
import org.transflux.core.transition.TransitionDef
import org.transflux.core.trigger.ManualTriggerDef
import spock.lang.Specification

import java.util.function.BiPredicate
import java.util.function.Consumer
import java.util.function.Predicate

class ManualTriggerDefImplSpec extends Specification {

    static class Entity {
        String state

        Entity(String state) {
            this.state = state
        }
    }

    static class AlwaysTrue implements Condition<Entity, TestContext> {
        @Override
        boolean test(Entity entity, TestContext context, Transition transition) {
            true
        }
    }

    def 'explicit id registers the trigger under that id with no metadata'() {
        when:
        def sm = buildSm({ t -> t.addManualTrigger('manual-cancel') })

        then:
        sm.getTriggers().size() == 1
        def trigger = sm.getTrigger('manual-cancel')
        trigger.id == 'manual-cancel'
        trigger.transitionId == 't'
        trigger.name == null
        trigger.description == null
    }

    def 'configurer captures name, description, and pre-conditions'() {
        when:
        def sm = buildSm({ t ->
            t.addManualTrigger('manual-cancel', { mt -> mt
                .withName('Cancel')
                .withDescription('User-initiated cancellation')
                .preCondition('authorized', { e -> true } as Predicate) })
        })

        then:
        def trigger = sm.getTrigger('manual-cancel')
        trigger.name == 'Cancel'
        trigger.description == 'User-initiated cancellation'
    }

    def 'Identifiable overloads delegate via getId'() {
        given:
        Identifiable simpleId = { -> 'simple' } as Identifiable
        Identifiable configuredId = { -> 'configured' } as Identifiable

        when:
        def sm = buildSm({ t ->
            t.addManualTrigger(simpleId)
            t.addManualTrigger(configuredId, { mt -> mt.withName('Configured') })
        })

        then:
        sm.getTrigger('simple').id == 'simple'
        sm.getTrigger('configured').name == 'Configured'
    }

    def 'captured ManualTriggerDef rejects mutation after the configurer returns'() {
        given:
        def smd = new StateMachineDefImpl<Entity>()
        ManualTriggerDef<Entity, TestContext> captured = null
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .state('s1', { st -> st.transitionsTo('s2', 't', TestContext, { t ->
                t.addManualTrigger('mt', { mt -> captured = mt })
            }) })
            .state('s2', {})

        when:
        captured.preCondition('p', { e -> true } as Predicate)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("'preCondition'")
        e.message.contains("manual trigger 'mt'")
    }

    def 'duplicate trigger ids are rejected at build time'() {
        when:
        buildSm({ t ->
            t.addManualTrigger('dup')
            t.addManualTrigger('dup')
        })

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("'dup'")
        e.message.contains('already registered')
    }

    def 'null configurer is rejected'() {
        when:
        buildSm({ t -> t.addManualTrigger('mt', (Consumer<ManualTriggerDef<Entity, TestContext>>) null) })

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('Manual trigger configurer')
    }

    def 'contextType reflects the enclosing transition context'() {
        given:
        Class captured = null

        when:
        buildSm({ t -> t.addManualTrigger('mt', { mt -> captured = mt.contextType() }) })

        then:
        captured == TestContext
    }

    def 'contextType follows a usingContext declared after the trigger'() {
        given:
        ManualTriggerDef<Entity, ?> captured = null
        def smd = new StateMachineDefImpl<Entity>()

        when: 'the trigger is declared before the transition re-types its context'
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .state('s1', { st -> st.transitionsTo('s2', 't', { t -> t
                .addManualTrigger('mt', { mt -> captured = mt })
                .usingContext(TestContext) }) })
            .state('s2', {})
        smd.build()

        then: 'the trigger reports the type the transition ended up with, not the one it started with'
        captured.contextType() == TestContext
    }

    def 'pre-conditions in every authoring form resolve and gate the trigger'() {
        given:
        Identifiable refId = { -> 'registered' } as Identifiable
        Identifiable instId = { -> 'inst-i' } as Identifiable
        Identifiable clsId = { -> 'cls-i' } as Identifiable
        Identifiable bipId = { -> 'bip-i' } as Identifiable
        Identifiable predId = { -> 'pred-i' } as Identifiable
        Identifiable exprId = { -> 'expr-i' } as Identifiable
        def alwaysTrue = new AlwaysTrue()
        BiPredicate<Entity, TestContext> biTrue = { e, c -> true } as BiPredicate
        Predicate<Entity> predTrue = { e -> true } as Predicate

        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .withStateApplier({ e, s -> e.state = s } as StateApplier<Entity>)
            .condition('registered', { e -> true } as Predicate)
            .state('s1', { st -> st.transitionsTo('s2', 't', TestContext, { t ->
                t.addManualTrigger('go', { mt -> mt
                    .preCondition('registered')
                    .preCondition(refId)
                    .preConditionExpression('true')
                    .preCondition('inst', alwaysTrue)
                    .preCondition(instId, alwaysTrue)
                    .preCondition('cls', AlwaysTrue)
                    .preCondition(clsId, AlwaysTrue)
                    .preCondition('bip', biTrue)
                    .preCondition(bipId, biTrue)
                    .preCondition('pred', predTrue)
                    .preCondition(predId, predTrue)
                    .preCondition('expr', 'true')
                    .preCondition(exprId, 'true') })
            }) })
            .state('s2', {})
        def sm = smd.build()

        when:
        def result = sm.entity(new Entity('s1')).fire('go')

        then:
        result.success
    }

    private static StateMachine<Entity> buildSm(Consumer<TransitionDef<Entity, TestContext>> cfg) {
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .withStateApplier({ e, s -> e.state = s } as StateApplier<Entity>)
            .state('s1', { st -> st.transitionsTo('s2', 't', TestContext, { t -> cfg.accept(t) }) })
            .state('s2', {})
        return smd.build()
    }
}
