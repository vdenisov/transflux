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

import java.util.function.Predicate

class ConditionResolverSpec extends Specification {

    static class Entity {
        int value
    }

    static class TruthyCondition implements Condition<Entity, TestContext> {
        @Override
        boolean test(Entity entity, TestContext context, Transition<Entity, TestContext> transition) {
            true
        }
    }

    static class CtorlessCondition implements Condition<Entity, TestContext> {
        CtorlessCondition(String unused) {
        }

        @Override
        boolean test(Entity entity, TestContext context, Transition<Entity, TestContext> transition) {
            false
        }
    }

    def "should resolve a Reference descriptor against the registry"() {
        given:
        def bound = BoundCondition.of('a', { e, c, t -> true } as Condition)
        Map<String, BoundCondition<Entity, TestContext>> registry = ['a': bound]

        when:
        def resolved = ConditionResolver.resolve(ConditionDescriptor.ref('a'), registry, 'path')

        then:
        resolved.is(bound)
    }

    def "should throw when a Reference descriptor points at an unregistered id"() {
        when:
        ConditionResolver.resolve(ConditionDescriptor.ref('missing'), [:], 'path')

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("'missing'")
    }

    def "should reflectively instantiate a ClassBased descriptor"() {
        when:
        def resolved = ConditionResolver.resolve(
            ConditionDescriptor.classBased('a', TruthyCondition), [:], 'path')

        then:
        resolved.id == 'a'
        resolved.condition instanceof TruthyCondition
    }

    def "should throw when a ClassBased descriptor has no accessible no-arg constructor"() {
        when:
        ConditionResolver.resolve(
            ConditionDescriptor.classBased('a', CtorlessCondition), [:], 'path')

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('no accessible no-arg constructor')
    }

    def "should adapt a PredicateBased descriptor into an entity-only Condition"() {
        given:
        Predicate<Entity> p = { e -> e.value > 0 }

        when:
        def resolved = ConditionResolver.resolve(
            ConditionDescriptor.predicate('a', p), [:], 'path')

        then:
        resolved.id == 'a'
        resolved.condition.test(new Entity(value: 1), null, null)
        !resolved.condition.test(new Entity(value: 0), null, null)
    }

    def "should auto-derive id for an ExpressionBased descriptor with no explicit id"() {
        when:
        def resolved = ConditionResolver.resolve(
            ConditionDescriptor.expression('value > 0'), [:],
            'transition[t1]/preCondition[0]')

        then:
        resolved.id.startsWith('expr-')
        resolved.condition.test(new Entity(value: 1), null, null)
    }

    def "should use explicit id for an ExpressionBased descriptor"() {
        when:
        def resolved = ConditionResolver.resolve(
            ConditionDescriptor.expression('cond-a', 'value > 0'), [:], 'path')

        then:
        resolved.id == 'cond-a'
        resolved.condition.test(new Entity(value: 1), null, null)
    }

    def "should reject a null descriptor"() {
        when:
        ConditionResolver.resolve(null, [:], 'path')

        then:
        thrown(TransfluxValidationException)
    }
}
