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


import org.transflux.core.exception.TransfluxValidationException
import spock.lang.Specification

class ExpressionIdDerivationSpec extends Specification {

    def "should produce the same id for the same expression and path"() {
        expect:
        ExpressionIdDerivation.deriveId('#entity.value > 0', 'transition[t1]/preCondition[0]') ==
            ExpressionIdDerivation.deriveId('#entity.value > 0', 'transition[t1]/preCondition[0]')
    }

    def "should produce different ids for the same expression at different paths"() {
        expect:
        ExpressionIdDerivation.deriveId('#entity.value > 0', 'transition[t1]/preCondition[0]') !=
            ExpressionIdDerivation.deriveId('#entity.value > 0', 'transition[t2]/preCondition[0]')
    }

    def "should produce different ids for different expressions at the same path"() {
        expect:
        ExpressionIdDerivation.deriveId('#entity.value > 0', 'p') !=
            ExpressionIdDerivation.deriveId('#entity.value > 1', 'p')
    }

    def "should prefix ids with 'expr-'"() {
        when:
        def id = ExpressionIdDerivation.deriveId('#entity.value > 0', 'transition[t1]/preCondition[0]')

        then:
        id.startsWith('expr-')
        id.length() == 'expr-'.length() + 12
    }

    def "should be stable across invocations within a JVM"() {
        given:
        def a = ExpressionIdDerivation.deriveId('expr', 'p')
        def b = ExpressionIdDerivation.deriveId('expr', 'p')

        expect:
        a == b
    }

    def "should reject null or blank expression"() {
        when:
        ExpressionIdDerivation.deriveId(expr, 'p')

        then:
        thrown(TransfluxValidationException)

        where:
        expr << [null, '', '  ']
    }

    def "should reject null path"() {
        when:
        ExpressionIdDerivation.deriveId('expr', null)

        then:
        thrown(TransfluxValidationException)
    }
}
