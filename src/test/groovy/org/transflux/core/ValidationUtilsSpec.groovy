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


import org.slf4j.Logger
import spock.lang.Specification
import spock.lang.Unroll

import static org.transflux.core.ValidationUtils.requireNotBlank
import static org.transflux.core.ValidationUtils.requireNotNull
import static org.transflux.core.ValidationUtils.warnIfSet

class ValidationUtilsSpec extends Specification {

    def 'requireNotNull should return the value when non-null'() {
        given:
        def value = 'hello'

        expect:
        requireNotNull(value, 'Field').is(value)
    }

    def 'requireNotNull should accept arbitrary non-null types'() {
        given:
        def value = new Object()

        expect:
        requireNotNull(value, 'Object').is(value)
    }

    def 'requireNotNull should throw with a fieldName-derived message when value is null'() {
        when:
        requireNotNull(null, 'Some field')

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Some field cannot be null'
    }

    @Unroll
    def 'requireNotBlank should accept non-blank value: #value'() {
        expect:
        requireNotBlank(value, 'Field') == value

        where:
        value << ['x', 'hello', '  hi  ', '0']
    }

    @Unroll
    def 'requireNotBlank should throw for null / blank value: #scenario'() {
        when:
        requireNotBlank(value, 'Some field')

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Some field cannot be null or blank'

        where:
        scenario | value
        'null'   | null
        'empty'  | ''
        'spaces' | '   '
        'tabs'   | '\t\t'
    }

    def 'warnIfSet should emit a warning when current value is non-null'() {
        given:
        def log = Mock(Logger)

        when:
        warnIfSet('old', 'new', 'Name', log)

        then:
        1 * log.warn(_ as String, 'Name', 'old', 'new')
    }

    def 'warnIfSet should be a no-op when current value is null'() {
        given:
        def log = Mock(Logger)

        when:
        warnIfSet(null, 'new', 'Name', log)

        then:
        0 * log.warn(_, _, _, _)
    }

    def 'warnIfSet should work with non-string types'() {
        given:
        def log = Mock(Logger)
        def current = new Object()
        def incoming = new Object()

        when:
        warnIfSet(current, incoming, 'Some object', log)

        then:
        1 * log.warn(_ as String, 'Some object', current, incoming)
    }
}
