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

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.transflux.core.Identifiable
import org.transflux.core.TestContext
import org.transflux.core.condition.Condition
import org.transflux.core.condition.ConditionDescriptor
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.transition.Transition
import spock.lang.Specification
import spock.lang.Unroll

import java.util.function.BiPredicate
import java.util.function.Predicate

class ConditionDescriptorSinkSpec extends Specification {

    private static final def LOG = LoggerFactory.getLogger(ConditionDescriptorSinkSpec)

    def 'a multi-descriptor sink appends in declaration order'() {
        given:
        def owner = activeOwner()
        def sink = new ConditionDescriptorSink<Entity, TestContext, String>(owner, 'self', 'preCondition')

        when:
        sink.ref('first')
        sink.ref('second')
        sink.expression('third', 'entity.value > 0')

        then:
        sink.descriptors()*.id() == ['first', 'second', 'third']
    }

    def 'a multi-descriptor sink exposes an unmodifiable view'() {
        given:
        def sink = new ConditionDescriptorSink<Entity, TestContext, String>(activeOwner(), 'self', 'preCondition')
        sink.ref('only')

        when:
        sink.descriptors().add(ConditionDescriptor.ref('sneaky'))

        then:
        thrown(UnsupportedOperationException)
    }

    def 'a single-descriptor sink keeps the last write'() {
        given:
        def sink = new ConditionDescriptorSink<Entity, TestContext, String>(activeOwner(), 'self', 'condition', LOG)

        when:
        sink.ref('first')
        sink.ref('second')

        then:
        sink.descriptor().id() == 'second'
        sink.descriptors().size() == 1
    }

    def 'the override warning names each descriptor by form and id, never by its contents'() {
        given:
        def log = Mock(Logger)
        def sink = new ConditionDescriptorSink<Entity, TestContext, String>(activeOwner(), 'self', 'condition', log)

        when: 'an expression carrying a literal is overridden'
        sink.expression('first', 'entity.ssn == "123-45-6789"')
        sink.ref('second')

        then: 'the descriptor toString - which would render the literal - never reaches the logger'
        1 * log.warn(_ as String, 'Condition on stub owner',
                     'ExpressionBased[id=first]', 'Reference[id=second]')
    }

    def 'a single-descriptor sink reports no descriptor before anything is declared'() {
        expect:
        new ConditionDescriptorSink<Entity, TestContext, String>(activeOwner(), 'self', 'condition', LOG)
            .descriptor() == null
    }

    def 'every overload returns the supplied self reference for chaining'() {
        given:
        def sink = new ConditionDescriptorSink<Entity, TestContext, String>(activeOwner(), 'self', 'preCondition')

        expect:
        sink.ref('a') === 'self'
        sink.instanceBased('b', new AlwaysTrue()) === 'self'
        sink.predicate('c', { e -> true } as Predicate) === 'self'
    }

    @Unroll
    def 'the #form form builds a #expectedType descriptor'() {
        given:
        def sink = new ConditionDescriptorSink<Entity, TestContext, String>(activeOwner(), 'self', 'preCondition')

        when:
        call.call(sink)

        then:
        expectedType.isInstance(sink.descriptors()[0])

        where:
        form            | call                                                                    || expectedType
        'reference'     | { s -> s.ref('registered') }                                            || ConditionDescriptor.Reference
        'instance'      | { s -> s.instanceBased('i', new AlwaysTrue()) }                         || ConditionDescriptor.InstanceBased
        'class'         | { s -> s.classBased('c', AlwaysTrue) }                                  || ConditionDescriptor.ClassBased
        'BiPredicate'   | { s -> s.predicate('bp', { e, c -> true } as BiPredicate) }             || ConditionDescriptor.PredicateBased
        'Predicate'     | { s -> s.predicate('p', { e -> true } as Predicate) }                   || ConditionDescriptor.PredicateBased
        'expression'    | { s -> s.expression('x', 'entity.value > 0') }                          || ConditionDescriptor.ExpressionBased
        'auto-id expr'  | { s -> s.expression('entity.value > 0') }                               || ConditionDescriptor.ExpressionBased
    }

    @Unroll
    def 'the Identifiable sibling of the #form form delegates through getId'() {
        given:
        def sink = new ConditionDescriptorSink<Entity, TestContext, String>(activeOwner(), 'self', 'preCondition')

        when:
        call.call(sink)

        then:
        sink.descriptors()[0].id() == 'from-identifiable'

        where:
        form          | call
        'reference'   | { s -> s.ref(idOf('from-identifiable')) }
        'instance'    | { s -> s.instanceBased(idOf('from-identifiable'), new AlwaysTrue()) }
        'class'       | { s -> s.classBased(idOf('from-identifiable'), AlwaysTrue) }
        'BiPredicate' | { s -> s.predicate(idOf('from-identifiable'), { e, c -> true } as BiPredicate) }
        'Predicate'   | { s -> s.predicate(idOf('from-identifiable'), { e -> true } as Predicate) }
        'expression'  | { s -> s.expression(idOf('from-identifiable'), 'entity.value > 0') }
    }

    def 'the id-less expression form guards under the derived Expression method name'() {
        given:
        def owner = inertOwner()
        def sink = new ConditionDescriptorSink<Entity, TestContext, String>(owner, 'self', 'preCondition')

        when:
        sink.expression('entity.value > 0')

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("'preConditionExpression'")
    }

    def 'the id-bearing forms guard under the plain method name'() {
        given:
        def sink = new ConditionDescriptorSink<Entity, TestContext, String>(inertOwner(), 'self', 'preCondition')

        when:
        sink.ref('registered')

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("'preCondition'")
        !e.message.contains('preConditionExpression')
    }

    @Unroll
    def 'the Identifiable sibling of the #form form rejects null before consulting the guard'() {
        given:
        def sink = new ConditionDescriptorSink<Entity, TestContext, String>(inertOwner(), 'self', 'preCondition')

        when:
        call.call(sink)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('Condition identifiable')

        where:
        form          | call
        'reference'   | { s -> s.ref((Identifiable) null) }
        'instance'    | { s -> s.instanceBased((Identifiable) null, new AlwaysTrue()) }
        'class'       | { s -> s.classBased((Identifiable) null, AlwaysTrue) }
        'BiPredicate' | { s -> s.predicate((Identifiable) null, { en, cx -> true } as BiPredicate) }
        'Predicate'   | { s -> s.predicate((Identifiable) null, { en -> true } as Predicate) }
        'expression'  | { s -> s.expression((Identifiable) null, 'entity.value > 0') }
    }

    @Unroll
    def 'the #form form rejects a blank or null argument'() {
        given:
        def sink = new ConditionDescriptorSink<Entity, TestContext, String>(activeOwner(), 'self', 'preCondition')

        when:
        call.call(sink)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains(expectedLabel)

        where:
        form                 | call                                                    || expectedLabel
        'blank reference id' | { s -> s.ref('  ') }                                    || 'Registered condition ID'
        'blank condition id' | { s -> s.instanceBased('  ', new AlwaysTrue()) }        || 'Condition ID'
        'null condition'     | { s -> s.instanceBased('i', (Condition) null) }         || 'Condition'
        'null class'         | { s -> s.classBased('c', (Class) null) }                || 'Condition class'
        'null BiPredicate'   | { s -> s.predicate('bp', (BiPredicate) null) }          || 'Predicate'
        'null Predicate'     | { s -> s.predicate('p', (Predicate) null) }             || 'Predicate'
        'blank expression'   | { s -> s.expression('x', '  ') }                        || 'Expression'
        'blank auto-id expr' | { s -> s.expression('  ') }                             || 'Expression'
    }

    private static ConfigurableDefImpl activeOwner() {
        return new StubOwner().tap { beginConfigurer() }
    }

    private static ConfigurableDefImpl inertOwner() {
        return new StubOwner()
    }

    private static Identifiable idOf(String value) {
        return { -> value } as Identifiable
    }

    static class StubOwner extends ConfigurableDefImpl {
        @Override
        protected String defLabel() {
            return "stub owner"
        }
    }

    static class Entity {
        String state
        int value
    }

    static class AlwaysTrue implements Condition<Entity, TestContext> {
        @Override
        boolean test(Entity entity, TestContext context, Transition transition) {
            return true
        }
    }
}
