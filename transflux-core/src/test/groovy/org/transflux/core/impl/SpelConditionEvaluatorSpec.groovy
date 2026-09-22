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


import org.transflux.core.StateMachineDef
import org.transflux.core.Transflux
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.transition.Transition
import spock.lang.Specification

class SpelConditionEvaluatorSpec extends Specification {

    static class Entity {
        int value
        String name
    }

    static class Ctx {
        boolean flag
    }

    static class Named {
        static boolean isNamed(Entity entity) {
            return entity.name != null
        }
    }

    def "should evaluate a boolean expression against the entity"() {
        given:
        def eval = new SpelConditionEvaluator()
        def entity = new Entity(value: 5)

        expect:
        eval.evaluate('value > 0', entity, null, null)
        !eval.evaluate('value < 0', entity, null, null)
    }

    def "should expose the entity as #entity beside the root it names"() {
        given: 'a static helper, because passing the root whole has no other spelling'
        def eval = new SpelConditionEvaluator()
        def entity = new Entity(value: 5, name: 'e-1')

        expect: 'a condition and an event filter both bind it'
        eval.evaluate("T(${Named.name}).isNamed(#entity)", entity, null, null)
        eval.evaluateEventFilter("T(${Named.name}).isNamed(#entity)", entity, null, null)

        and: 'it is the same object the root resolves against'
        eval.evaluate('#entity.value == value', entity, null, null)
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
        Transition transition = Mock()
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

        and: "the parser's own complaint is kept - it is the only thing that says where"
        e.message.contains(e.cause.message)
    }

    def "should refuse a malformed expression where #position declares it, not at its first evaluation"() {
        given:
        def smd = Transflux.defineStateMachine(Entity)

        when:
        declare(smd)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.startsWith("Invalid SpEL expression 'value >'")

        where:
        position                           | declare
        'a registration'                   | { StateMachineDef d -> d.condition('c', 'value >') }
        'a typed registration'             | { StateMachineDef d -> d.condition('c', Ctx, 'value >') }
        'a forContext registration'        | { StateMachineDef d -> d.forContext(Ctx) { it.condition('c', 'value >') } }
        'a pre-condition'                  | { StateMachineDef d -> transition(d) { it.preConditionExpression('value >') } }
        'a named post-condition'           | { StateMachineDef d -> transition(d) { it.postCondition('c', 'value >') } }
        'a branch'                         | { StateMachineDef d -> transition(d) { t -> t.choice('ch') { it.branch('b') { b -> b.conditionExpression('value >') } } } }
        'a manual trigger pre-condition'   | { StateMachineDef d -> d.manualTrigger('m') { it.preConditionExpression('value >') } }
        'a data trigger gate'              | { StateMachineDef d -> d.dataTrigger('g') { it.condition('c', 'value >') } }
        'an event filter'                  | { StateMachineDef d -> d.eventTrigger('e') { it.onEvent('E').filterExpression('value >') } }
    }

    def "should throw TransfluxValidationException when evaluation fails"() {
        given:
        def eval = new SpelConditionEvaluator()

        when:
        eval.evaluate('nonExistentProperty > 0', new Entity(value: 1), null, null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.startsWith("Failed to evaluate SpEL expression")

        and: "evaluation ran against the entity, so its message stays on the cause"
        e.message.endsWith(e.cause.class.name)
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

    private static void transition(StateMachineDef<Entity> smd, Closure configurer) {
        smd.state('a')
            .transition('t', 'a', 'b', configurer)
    }
}
