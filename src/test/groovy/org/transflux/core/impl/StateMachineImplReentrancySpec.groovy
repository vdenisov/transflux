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
import org.transflux.core.TestContext
import org.transflux.core.condition.Condition
import org.transflux.core.exception.TransfluxReentrancyException
import org.transflux.core.operation.CompositeOperationDef
import org.transflux.core.operation.Operation
import org.transflux.core.operation.Step
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.Transition
import org.transflux.core.transition.TransitionDef
import spock.lang.Specification

import java.util.function.Consumer

class StateMachineImplReentrancySpec extends Specification {

    static class Entity {
        final String tag
        String state

        Entity(String tag, String state) {
            this.tag = tag
            this.state = state
        }

        @Override
        String toString() {
            return "Entity(${tag})"
        }
    }

    /** Step that calls back into the SM with a chosen entity, at most once. */
    static class ReentrantStep implements Step<Entity, TestContext> {
        StateMachineImpl<Entity> targetSm
        Entity targetEntity
        String targetState
        boolean fired = false

        @Override
        void execute(Entity entity, TestContext context, Transition<Entity, TestContext> transition) {
            if (fired) {
                return
            }
            fired = true
            targetSm.executeTransition(targetEntity, targetState)
        }
    }

    def 'step that calls back into the same SM with the same entity raises TransfluxReentrancyException'() {
        given:
        def step = new ReentrantStep()
        def sm = build([], { t -> t.compositeOperation('op', { CompositeOperationDef<Entity, TestContext> c ->
            c.step('reentrant', step)
        }) })
        def entity = new Entity('e1', 's1')
        step.targetSm = sm
        step.targetEntity = entity
        step.targetState = 's2'

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        !result.success
        result.error instanceof TransfluxReentrancyException
        result.error.message.contains("'t'")
        result.error.message.contains('Entity(e1)')
    }

    def 'operation that calls back into the same SM with the same entity raises TransfluxReentrancyException'() {
        given:
        StateMachineImpl<Entity> sm = null
        Entity entity = null
        def op = { Entity e, TestContext c, Transition<Entity, TestContext> t ->
            sm.executeTransition(entity, 's2')
        } as Operation<Entity, TestContext>

        sm = build([], { t -> t.simpleOperation('op', op) }) as StateMachineImpl<Entity>
        entity = new Entity('e1', 's1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        !result.success
        result.error instanceof TransfluxReentrancyException
    }

    def 'pre-condition that calls back into the same SM with the same entity raises TransfluxReentrancyException'() {
        given:
        StateMachineImpl<Entity> sm = null
        Entity entity = null
        def cond = { Entity e, TestContext c, Transition<Entity, TestContext> t ->
            sm.executeTransition(entity, 's2')
            return true
        } as Condition<Entity, TestContext>

        sm = build([], { t -> t.preCondition('reentrant-cond', cond) }) as StateMachineImpl<Entity>
        entity = new Entity('e1', 's1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        !result.success
        result.error instanceof TransfluxReentrancyException
    }

    def 'same SM, different entity reentrant call succeeds'() {
        given:
        def applied = []
        def step = new ReentrantStep()
        def sm = build(applied, { t -> t.compositeOperation('op', { CompositeOperationDef<Entity, TestContext> c ->
            c.step('reentrant', step)
        }) })
        def outer = new Entity('outer', 's1')
        def inner = new Entity('inner', 's1')
        step.targetSm = sm
        step.targetEntity = inner
        step.targetState = 's2'

        when:
        def result = sm.executeTransition(outer, 's2')

        then:
        result.success
        outer.state == 's2'
        inner.state == 's2'
        applied == ['s2', 's2']
    }

    def 'different SM instances, same entity, reentrant call succeeds'() {
        given:
        def outerApplied = []
        def innerApplied = []
        def step = new ReentrantStep()

        def outerSm = build(outerApplied, { t -> t.compositeOperation('op', { CompositeOperationDef<Entity, TestContext> c ->
            c.step('reentrant', step)
        }) })

        def innerSm = build(innerApplied, { t -> })

        def entity = new Entity('shared', 's1')
        step.targetSm = innerSm as StateMachineImpl<Entity>
        step.targetEntity = entity
        step.targetState = 's2'

        when:
        def result = outerSm.executeTransition(entity, 's2')

        then:
        result.success
        outerApplied == ['s2']
        innerApplied == ['s2']
        entity.state == 's2'
    }

    def 'guard is cleaned up after a successful transition: subsequent top-level call succeeds'() {
        given:
        def sm = build([], { t -> })
        def entity = new Entity('e1', 's1')

        when:
        def first = sm.executeTransition(entity, 's2')

        then:
        first.success

        when:
        entity.state = 's1'
        def second = sm.executeTransition(entity, 's2')

        then:
        second.success
    }

    def 'guard is cleaned up after a failed transition: subsequent top-level call succeeds'() {
        given:
        def sm = build([], { t -> t.compositeOperation('op', { CompositeOperationDef<Entity, TestContext> c ->
            c.step('boom', { Entity e, TestContext ctx, Transition<Entity, TestContext> tr ->
                throw new RuntimeException('boom')
            } as Step<Entity, TestContext>)
        }) })
        def entity = new Entity('e1', 's1')

        when:
        def first = sm.executeTransition(entity, 's2')

        then:
        !first.success
        first.error.message == 'boom'

        when: 'a second top-level transition runs after the failure'
        def second = sm.executeTransition(entity, 's2')

        then: 'the guard was cleared in the finally block, so the call is not seen as reentrant'
        !second.success
        second.error.message == 'boom'
    }

    private static StateMachine<Entity> build(List<String> applied,
                                              Consumer<TransitionDef<Entity, TestContext>> transitionConfigurer) {
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .withStateApplier({ e, s -> applied.add(s); e.state = s } as StateApplier<Entity>)
            .state('s1', { state -> state.transitionsTo('s2', 't', TestContext, transitionConfigurer) })
            .state('s2', {})
        return smd.build()
    }
}
