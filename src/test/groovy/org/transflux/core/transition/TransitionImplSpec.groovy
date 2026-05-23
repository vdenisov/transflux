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

package org.transflux.core.transition

import org.transflux.core.impl.*

import org.transflux.core.impl.BoundCondition
import org.transflux.core.exception.TransfluxValidationException
import spock.lang.Specification
import spock.lang.Unroll

class TransitionImplSpec extends Specification {

    def 'constructor should create DefaultTransition with valid TransitionDef'() {
        given:
        def transitionDef = new TransitionDefImpl('t1', 'state1', 'state2')

        when:
        def transition = new TransitionImpl(transitionDef, null, [:] as Map<String, BoundCondition>)

        then:
        transition.id == 't1'
        transition.sourceStateId == 'state1'
        transition.targetStateId == 'state2'
    }

    def 'constructor should validate null TransitionDef'() {
        when:
        new TransitionImpl(null, null, [:] as Map<String, BoundCondition>)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Transition definition cannot be null'
    }

    @Unroll
    def 'getter #getter should return #expected'() {
        given:
        def transitionDef = new TransitionDefImpl(id, sourceId, targetId)
        def transition = new TransitionImpl(transitionDef, null, [:] as Map<String, BoundCondition>)

        when:
        def result = transition."$getter"()

        then:
        result == expected

        where:
        getter             | id              | sourceId       | targetId       | expected
        'getId'            | 'transition-id' | 'source'       | 'target'       | 'transition-id'
        'getSourceStateId' | 't1'            | 'source-state' | 'target'       | 'source-state'
        'getTargetStateId' | 't1'            | 'source'       | 'target-state' | 'target-state'
    }

    def 'equals should return true for transitions with same ID'() {
        given:
        def transitionDef1 = new TransitionDefImpl('same-id', 'source1', 'target1')
        def transitionDef2 = new TransitionDefImpl('same-id', 'source2', 'target2')
        def transition1 = new TransitionImpl(transitionDef1, null, [:] as Map<String, BoundCondition>)
        def transition2 = new TransitionImpl(transitionDef2, null, [:] as Map<String, BoundCondition>)

        when:
        def result = transition1.equals(transition2)

        then:
        result == true
    }

    def 'equals should return false for transitions with different IDs'() {
        given:
        def transitionDef1 = new TransitionDefImpl('id1', 'source', 'target')
        def transitionDef2 = new TransitionDefImpl('id2', 'source', 'target')
        def transition1 = new TransitionImpl(transitionDef1, null, [:] as Map<String, BoundCondition>)
        def transition2 = new TransitionImpl(transitionDef2, null, [:] as Map<String, BoundCondition>)

        when:
        def result = transition1.equals(transition2)

        then:
        result == false
    }

    @Unroll
    def 'equals should return false for #description'() {
        given:
        def transitionDef = new TransitionDefImpl('t1', 'source', 'target')
        def transition = new TransitionImpl(transitionDef, null, [:] as Map<String, BoundCondition>)

        when:
        //noinspection ChangeToOperator, GrEqualsBetweenInconvertibleTypes
        def result = transition.equals(otherObject)

        then:
        result == false

        where:
        description                  | otherObject
        'non-TransitionImpl objects' | 'not-a-transition'
        'null'                       | null
    }

    def 'hashCode should return same value for transitions with same ID'() {
        given:
        def transitionDef1 = new TransitionDefImpl('same-id', 'source1', 'target1')
        def transitionDef2 = new TransitionDefImpl('same-id', 'source2', 'target2')
        def transition1 = new TransitionImpl(transitionDef1, null, [:] as Map<String, BoundCondition>)
        def transition2 = new TransitionImpl(transitionDef2, null, [:] as Map<String, BoundCondition>)

        when:
        def hashCode1 = transition1.hashCode()
        def hashCode2 = transition2.hashCode()

        then:
        hashCode1 == hashCode2
    }

    def 'hashCode should return different values for transitions with different IDs'() {
        given:
        def transitionDef1 = new TransitionDefImpl('id1', 'source', 'target')
        def transitionDef2 = new TransitionDefImpl('id2', 'source', 'target')
        def transition1 = new TransitionImpl(transitionDef1, null, [:] as Map<String, BoundCondition>)
        def transition2 = new TransitionImpl(transitionDef2, null, [:] as Map<String, BoundCondition>)

        when:
        def hashCode1 = transition1.hashCode()
        def hashCode2 = transition2.hashCode()

        then:
        hashCode1 != hashCode2
    }
}