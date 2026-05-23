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
import org.transflux.core.operation.Operation
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.Transition
import org.transflux.core.transition.TransitionDef
import spock.lang.Specification

import java.util.function.Consumer
import java.util.function.Predicate

class StateMachineImplConditionEvaluationSpec extends Specification {

    static class Entity {
        String state
        int value

        Entity(String state, int value) {
            this.state = state
            this.value = value
        }
    }

    static class FlaggingOperation implements Operation<Entity, TestContext> {
        boolean executed = false

        @Override
        void execute(Entity entity, TestContext context, Transition<Entity, TestContext> transition) {
            executed = true
        }
    }

    static class RegistryProbeCondition implements Condition<Entity, TestContext> {
        @Override
        boolean test(Entity entity, TestContext context, Transition<Entity, TestContext> transition) {
            entity.value > 0
        }
    }

    private static StateMachine<Entity> build(FlaggingOperation operation,
                                              List<String> appliedStates,
                                              Consumer<TransitionDef<Entity, TestContext>> extraConfig) {
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .withStateApplier({ e, s -> appliedStates.add(s); e.state = s } as StateApplier<Entity>)
            .state('s1', { state -> state.transitionsTo('s2', 't', TestContext, { t ->
                t.simpleOperation('op', operation)
                extraConfig.accept(t)
            }) })
            .state('s2', {})
        return smd.build()
    }

    def 'successful pre-condition lets operation and applier run'() {
        given:
        def operation = new FlaggingOperation()
        def applied = []
        def sm = build(operation, applied, { t -> t.preCondition('pre-ok', { e -> true } as Predicate) })
        def entity = new Entity('s1', 5)

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        result.isSuccess()
        operation.executed
        applied == ['s2']
    }

    def 'failing pre-condition short-circuits operation and applier'() {
        given:
        def operation = new FlaggingOperation()
        def applied = []
        def sm = build(operation, applied, { t -> t.preCondition('pre-fails', { e -> false } as Predicate) })
        def entity = new Entity('s1', 5)

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        result.isFailure()
        !operation.executed
        applied.isEmpty()
        result.error.message.contains("'pre-fails'")
        result.error.message.contains("'t'")
    }

    def 'successful post-condition lets the full pipeline succeed'() {
        given:
        def operation = new FlaggingOperation()
        def applied = []
        def sm = build(operation, applied, { t -> t.postCondition('post-ok', { e -> true } as Predicate) })
        def entity = new Entity('s1', 1)

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        result.isSuccess()
        operation.executed
        applied == ['s2']
    }

    def 'failing post-condition runs the operation but skips the applier'() {
        given:
        def operation = new FlaggingOperation()
        def applied = []
        def sm = build(operation, applied, { t -> t.postCondition('post-fails', { e -> false } as Predicate) })
        def entity = new Entity('s1', 1)

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        result.isFailure()
        operation.executed
        applied.isEmpty()
        result.error.message.contains("'post-fails'")
    }

    def 'first failing pre-condition short-circuits subsequent pre-conditions'() {
        given:
        def operation = new FlaggingOperation()
        def applied = []
        def secondInvocations = 0
        Predicate<Entity> secondProbe = { Entity e ->
            secondInvocations++
            true
        } as Predicate
        def sm = build(operation, applied, { t -> t
            .preCondition('first', { e -> false } as Predicate)
            .preCondition('second', secondProbe) })
        def entity = new Entity('s1', 1)

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        result.isFailure()
        result.error.message.contains("'first'")
        secondInvocations == 0
        !operation.executed
    }

    def 'pre-conditions in all four descriptor forms compose against the registry'() {
        given:
        def operation = new FlaggingOperation()
        def applied = []
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .withStateApplier({ e, s -> applied.add(s); e.state = s } as StateApplier<Entity>)
            .condition('registered', { e -> e.value > 0 } as Predicate)
            .state('s1', { state -> state.transitionsTo('s2', 't', TestContext, { t ->
                t.simpleOperation('op', operation)
                    .preCondition('pred', { Entity e -> e.value > 0 } as Predicate)
                    .preCondition('cls', RegistryProbeCondition)
                    .preCondition('expr', 'value > 0')
                    .preCondition('registered')
            }) })
            .state('s2', {})
        def sm = smd.build()
        def entity = new Entity('s1', 5)

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        result.isSuccess()
        operation.executed
        applied == ['s2']
    }
}
