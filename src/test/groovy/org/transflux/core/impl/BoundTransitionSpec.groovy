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

    def 'from() builds a record populated from the def'() {
        given:
        def transitionDef = new TransitionDefImpl('t1', 'state1', 'state2')

        when:
        def transition = BoundTransition.from(transitionDef, null, [:] as Map<String, BoundCondition>)

        then:
        transition.id() == 't1'
        transition.sourceStateId() == 'state1'
        transition.targetStateId() == 'state2'
        transition.contextType() == Object
        transition.boundOperation() == null
        transition.boundPreConditions().isEmpty()
        transition.boundPostConditions().isEmpty()
    }

    def 'from() rejects a null def'() {
        when:
        BoundTransition.from(null, null, [:] as Map<String, BoundCondition>)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Transition definition cannot be null'
    }

    def 'from() rejects a null condition registry'() {
        given:
        def transitionDef = new TransitionDefImpl('t1', 'state1', 'state2')

        when:
        BoundTransition.from(transitionDef, null, null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Condition registry cannot be null'
    }

    @Unroll
    def 'accessor #accessor returns #expected'() {
        given:
        def transitionDef = new TransitionDefImpl(id, sourceId, targetId)
        def transition = BoundTransition.from(transitionDef, null, [:] as Map<String, BoundCondition>)

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
        def defA = new TransitionDefImpl('t1', 'source', 'target')
        def defB = new TransitionDefImpl('t1', 'source', 'target')
        def a = BoundTransition.from(defA, null, [:] as Map<String, BoundCondition>)
        def b = BoundTransition.from(defB, null, [:] as Map<String, BoundCondition>)

        expect:
        a == b
        a.hashCode() == b.hashCode()
    }

    def 'records with different source/target are not equal even when ids match'() {
        given:
        def defA = new TransitionDefImpl('same-id', 'source1', 'target1')
        def defB = new TransitionDefImpl('same-id', 'source2', 'target2')
        def a = BoundTransition.from(defA, null, [:] as Map<String, BoundCondition>)
        def b = BoundTransition.from(defB, null, [:] as Map<String, BoundCondition>)

        expect:
        a != b
    }
}
