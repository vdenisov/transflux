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

package org.transflux.core.condition


import org.transflux.core.Identifiable
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.transition.Transition
import spock.lang.Specification
import spock.lang.Unroll

import java.util.function.Predicate

class ConditionDescriptorSpec extends Specification {

    def "ref(...) should produce a Reference descriptor carrying the supplied id"() {
        when:
        def d = ConditionDescriptor.ref('cond-a')

        then:
        d instanceof ConditionDescriptor.Reference
        d.id() == 'cond-a'
    }

    def "classBased(...) should produce a ClassBased descriptor carrying id and class"() {
        when:
        def d = ConditionDescriptor.classBased('cond-a', SampleCondition)

        then:
        d instanceof ConditionDescriptor.ClassBased
        d.id() == 'cond-a'
        (d as ConditionDescriptor.ClassBased).conditionClass() == SampleCondition
    }

    def "predicate(...) should produce a PredicateBased descriptor carrying id and predicate"() {
        given:
        Predicate<Object> p = { o -> true }

        when:
        def d = ConditionDescriptor.predicate('cond-a', p)

        then:
        d instanceof ConditionDescriptor.PredicateBased
        d.id() == 'cond-a'
        (d as ConditionDescriptor.PredicateBased).predicate().is(p)
    }

    def "expression(expr) should produce an ExpressionBased descriptor with null id"() {
        when:
        def d = ConditionDescriptor.expression('#entity.value > 0')

        then:
        d instanceof ConditionDescriptor.ExpressionBased
        d.id() == null
        (d as ConditionDescriptor.ExpressionBased).expression() == '#entity.value > 0'
    }

    def "expression(id, expr) should produce an ExpressionBased descriptor with explicit id"() {
        when:
        def d = ConditionDescriptor.expression('cond-a', '#entity.value > 0')

        then:
        d instanceof ConditionDescriptor.ExpressionBased
        d.id() == 'cond-a'
        (d as ConditionDescriptor.ExpressionBased).expression() == '#entity.value > 0'
    }

    @Unroll
    def "ref(String) should reject null or blank id (id='#id')"() {
        when:
        ConditionDescriptor.ref((String) id)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('cannot be null or blank')

        where:
        id << [null, '', '  ']
    }

    @Unroll
    def "classBased(...) should reject null or blank id (id='#id')"() {
        when:
        ConditionDescriptor.classBased(id, SampleCondition)

        then:
        thrown(TransfluxValidationException)

        where:
        id << [null, '', '  ']
    }

    def "classBased(...) should reject null class"() {
        when:
        ConditionDescriptor.classBased('cond-a', null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Condition class cannot be null'
    }

    @Unroll
    def "predicate(...) should reject null or blank id (id='#id')"() {
        when:
        ConditionDescriptor.predicate(id, { o -> true } as Predicate)

        then:
        thrown(TransfluxValidationException)

        where:
        id << [null, '', '  ']
    }

    def "predicate(...) should reject null predicate"() {
        when:
        ConditionDescriptor.predicate('cond-a', (Predicate) null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Predicate cannot be null'
    }

    @Unroll
    def "expression(expr) should reject null or blank expression (expr='#expr')"() {
        when:
        ConditionDescriptor.expression(expr)

        then:
        thrown(TransfluxValidationException)

        where:
        expr << [null, '', '  ']
    }

    @Unroll
    def "expression(id, expr) should reject null or blank id (id='#id')"() {
        when:
        ConditionDescriptor.expression(id, '#entity.value > 0')

        then:
        thrown(TransfluxValidationException)

        where:
        id << [null, '', '  ']
    }

    def 'ref(Identifiable) builds the same Reference descriptor as ref(String)'() {
        when:
        def fromIdentifiable = ConditionDescriptor.ref(id('cond-1'))
        def fromString = ConditionDescriptor.ref('cond-1')

        then:
        fromIdentifiable.id() == 'cond-1'
        fromIdentifiable.class == fromString.class
        fromIdentifiable == fromString
    }

    def 'ref(Identifiable) rejects null'() {
        when:
        ConditionDescriptor.ref((Identifiable) null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.toLowerCase().contains('identifiable')
    }

    private static Identifiable id(String value) {
        return { -> value } as Identifiable
    }

    static class SampleCondition implements Condition<Object, Object> {
        @Override
        boolean test(Object entity, Object context, Transition<Object, Object> transition) {
            true
        }
    }
}
