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
//file:noinspection GroovyPointlessBoolean

package org.transflux.core.impl

import org.transflux.core.exception.TransfluxValidationException
import spock.lang.Specification
import spock.lang.Unroll

class BoundTransitionSpec extends Specification {

    private static final SpelConditionEvaluator EVALUATOR = new SpelConditionEvaluator(BoundTransitionSpec.classLoader)

    def 'from() builds a record populated from the def'() {
        given:
        def transitionDef = new TransitionDefImpl(new StateMachineDefImpl(), 't1', 'state1', 'state2', Object)

        when:
        def transition = BoundTransition.from(transitionDef, [:] as Map<String, BoundCondition>, BoundTransitionListeners.none(), EVALUATOR)

        then:
        transition.id() == 't1'
        transition.sourceStateId() == 'state1'
        transition.targetStateId() == 'state2'
        transition.contextType() == Object
        transition.boundAction() == null
        transition.boundPreConditions().isEmpty()
        transition.boundPostConditions().isEmpty()
        transition.boundListeners().onStart().isEmpty()
        transition.boundListeners().onComplete().isEmpty()
        transition.boundListeners().onError().isEmpty()
    }

    def 'from() rejects null listeners'() {
        given:
        def transitionDef = new TransitionDefImpl(new StateMachineDefImpl(), 't1', 'state1', 'state2', Object)

        when:
        BoundTransition.from(transitionDef, [:] as Map<String, BoundCondition>, null, EVALUATOR)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Bound transition listeners cannot be null'
    }

    def 'from() rejects a null def'() {
        when:
        BoundTransition.from(null, [:] as Map<String, BoundCondition>, BoundTransitionListeners.none(), EVALUATOR)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Transition definition cannot be null'
    }

    def 'from() rejects a null condition registry'() {
        given:
        def transitionDef = new TransitionDefImpl(new StateMachineDefImpl(), 't1', 'state1', 'state2', Object)

        when:
        BoundTransition.from(transitionDef, null, BoundTransitionListeners.none(), EVALUATOR)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Condition registry cannot be null'
    }

    @Unroll
    def 'accessor #accessor returns #expected'() {
        given:
        def transitionDef = new TransitionDefImpl(new StateMachineDefImpl(), id, sourceId, targetId, Object)
        def transition = BoundTransition.from(transitionDef, [:] as Map<String, BoundCondition>, BoundTransitionListeners.none(), EVALUATOR)

        expect:
        transition."$accessor"() == expected

        where:
        accessor        | id              | sourceId       | targetId       | expected
        'id'            | 'transition-id' | 'source'       | 'target'       | 'transition-id'
        'sourceStateId' | 't1'            | 'source-state' | 'target'       | 'source-state'
        'targetStateId' | 't1'            | 'source'       | 'target-state' | 'target-state'
    }

    def 'records built from equivalent defs are equal'() {
        given:
        def defA = new TransitionDefImpl(new StateMachineDefImpl(), 't1', 'source', 'target', Object)
        def defB = new TransitionDefImpl(new StateMachineDefImpl(), 't1', 'source', 'target', Object)
        def a = BoundTransition.from(defA, [:] as Map<String, BoundCondition>, BoundTransitionListeners.none(), EVALUATOR)
        def b = BoundTransition.from(defB, [:] as Map<String, BoundCondition>, BoundTransitionListeners.none(), EVALUATOR)

        expect:
        a == b
        a.hashCode() == b.hashCode()
    }

    def 'records with different source/target are not equal even when ids match'() {
        given:
        def defA = new TransitionDefImpl(new StateMachineDefImpl(), 'same-id', 'source1', 'target1', Object)
        def defB = new TransitionDefImpl(new StateMachineDefImpl(), 'same-id', 'source2', 'target2', Object)
        def a = BoundTransition.from(defA, [:] as Map<String, BoundCondition>, BoundTransitionListeners.none(), EVALUATOR)
        def b = BoundTransition.from(defB, [:] as Map<String, BoundCondition>, BoundTransitionListeners.none(), EVALUATOR)

        expect:
        a != b
    }
}
