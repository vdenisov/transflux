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

import org.transflux.core.*
import org.transflux.core.state.*
import org.transflux.core.transition.*
import org.transflux.core.operation.*
import org.transflux.core.condition.*
import org.transflux.core.exception.*

import org.transflux.core.impl.*

import org.slf4j.Logger
import org.transflux.core.exception.TransfluxValidationException
import spock.lang.Specification
import spock.lang.Unroll

import static org.transflux.core.impl.ValidationUtils.requireNotBlank
import static org.transflux.core.impl.ValidationUtils.requireNotNull
import static org.transflux.core.impl.ValidationUtils.warnIfSet

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
