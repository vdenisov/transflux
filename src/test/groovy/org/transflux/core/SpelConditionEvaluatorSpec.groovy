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

import spock.lang.Specification

class SpelConditionEvaluatorSpec extends Specification {

    static class Entity {
        int value
        String name
    }

    static class Ctx {
        boolean flag
    }

    def "should evaluate a boolean expression against the entity"() {
        given:
        def eval = new SpelConditionEvaluator()
        def entity = new Entity(value: 5)

        expect:
        eval.evaluate('value > 0', entity, null, null)
        !eval.evaluate('value < 0', entity, null, null)
    }

    def "should expose the context as #context"() {
        given:
        def eval = new SpelConditionEvaluator()
        def entity = new Entity(value: 5)
        def ctx = new Ctx(flag: true)

        expect:
        eval.evaluate('#context.flag', entity, ctx, null)
    }

    def "should expose the transition view as #transition"() {
        given:
        def eval = new SpelConditionEvaluator()
        def entity = new Entity(value: 5)
        Transition<Entity, Ctx> transition = Mock()
        transition.getTargetStateId() >> 'ACTIVE'

        expect:
        eval.evaluate("#transition.targetStateId == 'ACTIVE'", entity, null, transition)
    }

    def "should throw TransfluxValidationException on invalid expression syntax"() {
        given:
        def eval = new SpelConditionEvaluator()

        when:
        eval.evaluate('value >', new Entity(value: 1), null, null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.startsWith("Invalid SpEL expression 'value >'")
    }

    def "should throw TransfluxValidationException when evaluation fails"() {
        given:
        def eval = new SpelConditionEvaluator()

        when:
        eval.evaluate('nonExistentProperty > 0', new Entity(value: 1), null, null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.startsWith("Failed to evaluate SpEL expression")
    }

    def "should throw TransfluxValidationException when result is not boolean"() {
        given:
        def eval = new SpelConditionEvaluator()

        when:
        eval.evaluate("'hello'", new Entity(), null, null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('must evaluate to boolean')
        e.message.contains('java.lang.String')
    }

    def "should cache parsed expressions"() {
        given:
        def eval = new SpelConditionEvaluator()
        def entity = new Entity(value: 5)

        when:
        eval.evaluate('value > 0', entity, null, null)
        eval.evaluate('value > 0', entity, null, null)
        eval.evaluate('value > 1', entity, null, null)

        then:
        eval.cacheSize() == 2
    }

    def "shared() should return the same singleton instance"() {
        expect:
        SpelConditionEvaluator.shared().is(SpelConditionEvaluator.shared())
    }

    def "should reject a null/blank expression"() {
        given:
        def eval = new SpelConditionEvaluator()

        when:
        eval.evaluate(expr, new Entity(), null, null)

        then:
        thrown(TransfluxValidationException)

        where:
        expr << [null, '', '  ']
    }
}
