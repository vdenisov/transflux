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

package org.transflux.core

import org.transflux.core.condition.Condition
import org.transflux.core.condition.ConditionDescriptor
import org.transflux.core.exception.TransfluxException
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.operation.BoundOperation
import org.transflux.core.operation.BoundStep
import org.transflux.core.operation.CompositeOperationDef
import org.transflux.core.operation.CompositeOperationDefImpl
import org.transflux.core.operation.Operation
import org.transflux.core.operation.OperationDef
import org.transflux.core.operation.SimpleOperationDef
import org.transflux.core.operation.SimpleOperationDefImpl
import org.transflux.core.operation.Step
import org.transflux.core.state.State
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateDef
import org.transflux.core.state.StateDefImpl
import org.transflux.core.state.StateImpl
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.OperationlessTransitionDef
import org.transflux.core.transition.Transition
import org.transflux.core.transition.TransitionDef
import org.transflux.core.transition.TransitionDefImpl
import org.transflux.core.transition.TransitionImpl
import org.transflux.core.transition.TransitionResult
import org.transflux.core.transition.TransitionView


import spock.lang.Specification

import static org.transflux.core.ThrowingUtils.sneakyGet
import static org.transflux.core.ThrowingUtils.sneakyRun

class ThrowingUtilsSpec extends Specification {

    def 'sneakyGet should return the supplier value on success'() {
        expect:
        sneakyGet({ -> 'ok' }, 'irrelevant') == 'ok'
    }

    def 'sneakyGet should wrap a checked exception in TransfluxValidationException'() {
        when:
        sneakyGet({ -> throw new IOException('disk gone') }, 'Reading config')

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Reading config: disk gone'
        e.cause instanceof IOException
    }

    def 'sneakyGet should wrap a runtime exception in TransfluxValidationException'() {
        when:
        sneakyGet({ -> throw new IllegalStateException('nope') }, 'Doing the thing')

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Doing the thing: nope'
        e.cause instanceof IllegalStateException
    }

    def 'sneakyRun should propagate void result on success'() {
        given:
        def called = false

        when:
        sneakyRun({ -> called = true }, 'irrelevant')

        then:
        called
    }

    def 'sneakyRun should wrap a checked exception in TransfluxValidationException'() {
        when:
        sneakyRun({ -> throw new IOException('disk gone') }, 'Writing config')

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Writing config: disk gone'
        e.cause instanceof IOException
    }
}
