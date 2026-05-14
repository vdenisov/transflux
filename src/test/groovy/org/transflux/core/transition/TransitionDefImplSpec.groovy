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

package org.transflux.core.transition

import org.transflux.core.state.State
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateDef
import org.transflux.core.state.StateDefImpl
import org.transflux.core.state.StateImpl
import org.transflux.core.state.StateResolver

import org.transflux.core.Identifiable
import org.transflux.core.StateMachine
import org.transflux.core.StateMachineDef
import org.transflux.core.StateMachineDefImpl
import org.transflux.core.StateMachineImpl
import org.transflux.core.TestContext
import org.transflux.core.TestStateEnum
import org.transflux.core.Transflux
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.operation.BoundOperation
import org.transflux.core.operation.BoundStep
import org.transflux.core.operation.CompositeOperationDef
import org.transflux.core.operation.CompositeOperationDefImpl
import org.transflux.core.operation.Operation
import org.transflux.core.operation.SimpleOperationDef
import org.transflux.core.operation.SimpleOperationDefImpl
import org.transflux.core.operation.Step

import spock.lang.Specification
import spock.lang.Unroll

class TransitionDefImplSpec extends Specification {

    def 'constructor should create TransitionDef with valid parameters'() {
        when:
        def transitionDef = new TransitionDefImpl('t1', 'source', 'target')

        then:
        transitionDef.id == 't1'
        transitionDef.sourceStateId == 'source'
        transitionDef.targetStateId == 'target'
    }

    @Unroll
    def 'constructor should validate parameters: #scenario'() {
        when:
        new TransitionDefImpl(id, sourceStateId, targetStateId)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == expectedMessage

        where:
        scenario                | id   | sourceStateId | targetStateId | expectedMessage
        'null transition ID'    | null | 'source'      | 'target'      | 'Transition ID cannot be null or blank'
        'blank transition ID'   | '  ' | 'source'      | 'target'      | 'Transition ID cannot be null or blank'
        'empty transition ID'   | ''   | 'source'      | 'target'      | 'Transition ID cannot be null or blank'
        'null source state ID'  | 't1' | null          | 'target'      | 'Source state ID cannot be null or blank'
        'blank source state ID' | 't1' | '  '          | 'target'      | 'Source state ID cannot be null or blank'
        'empty source state ID' | 't1' | ''            | 'target'      | 'Source state ID cannot be null or blank'
        'null target state ID'  | 't1' | 'source'      | null          | 'Target state ID cannot be null or blank'
        'blank target state ID' | 't1' | 'source'      | '  '          | 'Target state ID cannot be null or blank'
        'empty target state ID' | 't1' | 'source'      | ''            | 'Target state ID cannot be null or blank'
    }

    @Unroll
    def 'getter #getter should return #expected'() {
        given:
        def transitionDef = new TransitionDefImpl(id, sourceStateId, targetStateId)

        when:
        def result = transitionDef."$getter"()

        then:
        result == expected

        where:
        getter             | id             | sourceStateId     | targetStateId     | expected
        'getId'            | 'transition-1' | 'source'          | 'target'          | 'transition-1'
        'getSourceStateId' | 't1'           | 'source-state-id' | 'target'          | 'source-state-id'
        'getTargetStateId' | 't1'           | 'source'          | 'target-state-id' | 'target-state-id'
    }

    static class FooStep implements Step<Object, Object> {
        @Override
        void execute(Object entity, Object context, Transition<Object, Object> transition) {
        }
    }

    static class FooOperation implements Operation<Object, Object> {
        @Override
        void execute(Object entity, Object context, Transition<Object, Object> transition) {
        }
    }

    def 'simpleOperation(id, Operation instance) should attach a simple operation def'() {
        given:
        def transitionDef = new TransitionDefImpl<Object, Object>('t1', 'source', 'target')

        when:
        def returned = transitionDef.simpleOperation('op1', new FooOperation())

        then:
        returned.is(transitionDef)
        transitionDef.operationDef instanceof SimpleOperationDefImpl
        transitionDef.operationDef.id == 'op1'
    }

    def 'simpleOperation(id, Operation class) should attach a simple operation def'() {
        given:
        def transitionDef = new TransitionDefImpl<Object, Object>('t1', 'source', 'target')

        when:
        def returned = transitionDef.simpleOperation('op1', FooOperation)

        then:
        returned.is(transitionDef)
        transitionDef.operationDef instanceof SimpleOperationDefImpl
        transitionDef.operationDef.id == 'op1'
    }

    def 'simpleOperation(id, Consumer) should attach a configured simple operation def'() {
        given:
        def transitionDef = new TransitionDefImpl<Object, Object>('t1', 'source', 'target')

        when:
        def returned = transitionDef.simpleOperation('op1', { SimpleOperationDef<Object, Object> op ->
            op.withName('Foo').withDescription('Foo desc').using(FooOperation)
        })

        then:
        returned.is(transitionDef)
        transitionDef.operationDef instanceof SimpleOperationDefImpl
        transitionDef.operationDef.id == 'op1'
        transitionDef.operationDef.name == 'Foo'
        transitionDef.operationDef.description == 'Foo desc'
    }

    def 'simpleOperation(id, Consumer) should reject null configurer'() {
        given:
        def transitionDef = new TransitionDefImpl<Object, Object>('t1', 'source', 'target')

        when:
        transitionDef.simpleOperation('op1', (java.util.function.Consumer<SimpleOperationDef<Object, Object>>) null)

        then:
        thrown(TransfluxValidationException)
    }

    def 'compositeOperation(id, Consumer) should attach a composite operation def'() {
        given:
        def transitionDef = new TransitionDefImpl<Object, Object>('t1', 'source', 'target')

        when:
        def returned = transitionDef.compositeOperation('op1', { CompositeOperationDef<Object, Object> c ->
            c.step('s1', new FooStep())
        })

        then:
        returned.is(transitionDef)
        transitionDef.operationDef instanceof CompositeOperationDefImpl
        transitionDef.operationDef.id == 'op1'
        ((CompositeOperationDefImpl<Object, Object>) transitionDef.operationDef).stepRefs.size() == 1
    }

    def 'compositeOperation(id, Consumer) should reject null configurer'() {
        given:
        def transitionDef = new TransitionDefImpl<Object, Object>('t1', 'source', 'target')

        when:
        transitionDef.compositeOperation('op1', (java.util.function.Consumer<CompositeOperationDef<Object, Object>>) null)

        then:
        thrown(TransfluxValidationException)
    }

    def 'step(id) sugar should build a single-step composite with a deterministic id'() {
        given:
        def transitionDef = new TransitionDefImpl<Object, Object>('t-x', 'source', 'target')

        when:
        transitionDef.step('foo')

        then:
        transitionDef.operationDef instanceof CompositeOperationDefImpl
        transitionDef.operationDef.id == 'transition-t-x-op'
        def composite = (CompositeOperationDefImpl<Object, Object>) transitionDef.operationDef
        // A by-id reference produces no inline instance or inline class registration.
        composite.inlineStepInstances.isEmpty()
        composite.inlineStepClasses.isEmpty()
    }

    def 'step(id) sugar should reject null or blank id'() {
        given:
        def transitionDef = new TransitionDefImpl<Object, Object>('t1', 'source', 'target')

        when:
        transitionDef.step(id)

        then:
        thrown(TransfluxValidationException)

        where:
        id << [null, '', '  ']
    }

    def 'toString should include all fields'() {
        given:
        def transitionDef = new TransitionDefImpl('t1', 'source', 'target')

        when:
        def result = transitionDef.toString()

        then:
        result == "TransitionDefImpl{id='t1', sourceStateId='source', targetStateId='target'}"
    }
}