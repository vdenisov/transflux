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

import org.transflux.core.TestContext
import org.transflux.core.condition.Condition
import org.transflux.core.condition.ConditionDescriptor
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.transition.Transition
import spock.lang.Specification

import java.util.function.BiPredicate
import java.util.function.Predicate

class TransitionDefImplConditionsSpec extends Specification {

    static class Entity {
        int value
    }

    static class AlwaysTrueCondition implements Condition<Entity, TestContext> {
        @Override
        boolean test(Entity entity, TestContext context, Transition<Entity, TestContext> transition) {
            true
        }
    }

    def 'preCondition with single-arg id appends a Reference descriptor'() {
        given:
        def td = new TransitionDefImpl<Entity, TestContext>('t1', 's1', 's2')
        td.beginConfigurer()

        when:
        td.preCondition('global-id')

        then:
        td.preConditionDescriptors.size() == 1
        def descriptor = td.preConditionDescriptors[0]
        descriptor instanceof ConditionDescriptor.Reference
        descriptor.id() == 'global-id'
    }

    def 'preConditionExpression appends an ExpressionBased descriptor with auto-derived id'() {
        given:
        def td = new TransitionDefImpl<Entity, TestContext>('t1', 's1', 's2')
        td.beginConfigurer()

        when:
        td.preConditionExpression('entity.value > 0')

        then:
        td.preConditionDescriptors.size() == 1
        def descriptor = td.preConditionDescriptors[0]
        descriptor instanceof ConditionDescriptor.ExpressionBased
        descriptor.id() == null
        (descriptor as ConditionDescriptor.ExpressionBased).expression() == 'entity.value > 0'
    }

    def 'preCondition with id + Condition appends an InstanceBased descriptor'() {
        given:
        def td = new TransitionDefImpl<Entity, TestContext>('t1', 's1', 's2')
        td.beginConfigurer()
        Condition<Entity, TestContext> condition = { e, c, t -> true } as Condition

        when:
        td.preCondition('pre-instance', condition)

        then:
        td.preConditionDescriptors.size() == 1
        def descriptor = td.preConditionDescriptors[0]
        descriptor instanceof ConditionDescriptor.InstanceBased
        descriptor.id() == 'pre-instance'
        (descriptor as ConditionDescriptor.InstanceBased).condition().is(condition)
    }

    def 'preCondition with id + Class appends a ClassBased descriptor'() {
        given:
        def td = new TransitionDefImpl<Entity, TestContext>('t1', 's1', 's2')
        td.beginConfigurer()

        when:
        td.preCondition('pre-class', AlwaysTrueCondition)

        then:
        td.preConditionDescriptors.size() == 1
        def descriptor = td.preConditionDescriptors[0]
        descriptor instanceof ConditionDescriptor.ClassBased
        descriptor.id() == 'pre-class'
        (descriptor as ConditionDescriptor.ClassBased).conditionClass() == AlwaysTrueCondition
    }

    def 'preCondition with id + BiPredicate appends a PredicateBased descriptor'() {
        given:
        def td = new TransitionDefImpl<Entity, TestContext>('t1', 's1', 's2')
        td.beginConfigurer()
        BiPredicate<Entity, TestContext> predicate = { e, c -> true } as BiPredicate

        when:
        td.preCondition('pre-predicate', predicate)

        then:
        td.preConditionDescriptors.size() == 1
        def descriptor = td.preConditionDescriptors[0]
        descriptor instanceof ConditionDescriptor.PredicateBased
        descriptor.id() == 'pre-predicate'
        (descriptor as ConditionDescriptor.PredicateBased).predicate().is(predicate)
    }

    def 'preCondition with id + Predicate appends a PredicateBased descriptor that ignores the context'() {
        given:
        def td = new TransitionDefImpl<Entity, TestContext>('t1', 's1', 's2')
        td.beginConfigurer()
        def calls = []
        Predicate<Entity> predicate = { e -> calls << e; true } as Predicate

        when:
        td.preCondition('pre-predicate', predicate)

        then:
        td.preConditionDescriptors.size() == 1
        def descriptor = td.preConditionDescriptors[0]
        descriptor instanceof ConditionDescriptor.PredicateBased
        descriptor.id() == 'pre-predicate'

        when:
        def adapted = (descriptor as ConditionDescriptor.PredicateBased).predicate() as BiPredicate<Entity, TestContext>
        def entity = new Entity(value: 7)
        def result = adapted.test(entity, new TestContext())

        then:
        result
        calls == [entity]
    }

    def 'preCondition with id + expression appends an ExpressionBased descriptor'() {
        given:
        def td = new TransitionDefImpl<Entity, TestContext>('t1', 's1', 's2')
        td.beginConfigurer()

        when:
        td.preCondition('pre-expr', 'entity.value > 0')

        then:
        td.preConditionDescriptors.size() == 1
        def descriptor = td.preConditionDescriptors[0]
        descriptor instanceof ConditionDescriptor.ExpressionBased
        descriptor.id() == 'pre-expr'
        (descriptor as ConditionDescriptor.ExpressionBased).expression() == 'entity.value > 0'
    }

    def 'postCondition with single-arg id appends a Reference descriptor'() {
        given:
        def td = new TransitionDefImpl<Entity, TestContext>('t1', 's1', 's2')
        td.beginConfigurer()

        when:
        td.postCondition('global-id')

        then:
        td.postConditionDescriptors.size() == 1
        def descriptor = td.postConditionDescriptors[0]
        descriptor instanceof ConditionDescriptor.Reference
        descriptor.id() == 'global-id'
    }

    def 'postConditionExpression appends an ExpressionBased descriptor with auto-derived id'() {
        given:
        def td = new TransitionDefImpl<Entity, TestContext>('t1', 's1', 's2')
        td.beginConfigurer()

        when:
        td.postConditionExpression('entity.value > 0')

        then:
        td.postConditionDescriptors.size() == 1
        def descriptor = td.postConditionDescriptors[0]
        descriptor instanceof ConditionDescriptor.ExpressionBased
        descriptor.id() == null
        (descriptor as ConditionDescriptor.ExpressionBased).expression() == 'entity.value > 0'
    }

    def 'postCondition with id + Condition appends an InstanceBased descriptor'() {
        given:
        def td = new TransitionDefImpl<Entity, TestContext>('t1', 's1', 's2')
        td.beginConfigurer()
        Condition<Entity, TestContext> condition = { e, c, t -> true } as Condition

        when:
        td.postCondition('post-instance', condition)

        then:
        td.postConditionDescriptors.size() == 1
        def descriptor = td.postConditionDescriptors[0]
        descriptor instanceof ConditionDescriptor.InstanceBased
        descriptor.id() == 'post-instance'
        (descriptor as ConditionDescriptor.InstanceBased).condition().is(condition)
    }

    def 'postCondition with id + Class appends a ClassBased descriptor'() {
        given:
        def td = new TransitionDefImpl<Entity, TestContext>('t1', 's1', 's2')
        td.beginConfigurer()

        when:
        td.postCondition('post-class', AlwaysTrueCondition)

        then:
        td.postConditionDescriptors.size() == 1
        def descriptor = td.postConditionDescriptors[0]
        descriptor instanceof ConditionDescriptor.ClassBased
        descriptor.id() == 'post-class'
        (descriptor as ConditionDescriptor.ClassBased).conditionClass() == AlwaysTrueCondition
    }

    def 'postCondition with id + BiPredicate appends a PredicateBased descriptor'() {
        given:
        def td = new TransitionDefImpl<Entity, TestContext>('t1', 's1', 's2')
        td.beginConfigurer()
        BiPredicate<Entity, TestContext> predicate = { e, c -> true } as BiPredicate

        when:
        td.postCondition('post-predicate', predicate)

        then:
        td.postConditionDescriptors.size() == 1
        def descriptor = td.postConditionDescriptors[0]
        descriptor instanceof ConditionDescriptor.PredicateBased
        descriptor.id() == 'post-predicate'
        (descriptor as ConditionDescriptor.PredicateBased).predicate().is(predicate)
    }

    def 'postCondition with id + Predicate appends a PredicateBased descriptor that ignores the context'() {
        given:
        def td = new TransitionDefImpl<Entity, TestContext>('t1', 's1', 's2')
        td.beginConfigurer()
        def calls = []
        Predicate<Entity> predicate = { e -> calls << e; true } as Predicate

        when:
        td.postCondition('post-predicate', predicate)

        then:
        td.postConditionDescriptors.size() == 1
        def descriptor = td.postConditionDescriptors[0]
        descriptor instanceof ConditionDescriptor.PredicateBased
        descriptor.id() == 'post-predicate'

        when:
        def adapted = (descriptor as ConditionDescriptor.PredicateBased).predicate() as BiPredicate<Entity, TestContext>
        def entity = new Entity(value: 11)
        def result = adapted.test(entity, new TestContext())

        then:
        result
        calls == [entity]
    }

    def 'postCondition with id + expression appends an ExpressionBased descriptor'() {
        given:
        def td = new TransitionDefImpl<Entity, TestContext>('t1', 's1', 's2')
        td.beginConfigurer()

        when:
        td.postCondition('post-expr', 'entity.value > 0')

        then:
        td.postConditionDescriptors.size() == 1
        def descriptor = td.postConditionDescriptors[0]
        descriptor instanceof ConditionDescriptor.ExpressionBased
        descriptor.id() == 'post-expr'
        (descriptor as ConditionDescriptor.ExpressionBased).expression() == 'entity.value > 0'
    }

    def 'preCondition single-arg rejects blank registered id'() {
        given:
        def td = new TransitionDefImpl<Entity, TestContext>('t1', 's1', 's2')
        td.beginConfigurer()

        when:
        td.preCondition((String) id)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Registered condition ID cannot be null or blank'

        where:
        id   || _
        null || _
        ''   || _
        '   '|| _
    }

    def 'preConditionExpression rejects blank expression'() {
        given:
        def td = new TransitionDefImpl<Entity, TestContext>('t1', 's1', 's2')
        td.beginConfigurer()

        when:
        td.preConditionExpression(expr)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Expression cannot be null or blank'

        where:
        expr || _
        null || _
        ''   || _
        '  ' || _
    }

    def 'preCondition rejects blank id with Condition'() {
        given:
        def td = new TransitionDefImpl<Entity, TestContext>('t1', 's1', 's2')
        td.beginConfigurer()

        when:
        td.preCondition('  ', { e, c, t -> true } as Condition)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Condition ID cannot be null or blank'
    }

    def 'preCondition rejects null Condition'() {
        given:
        def td = new TransitionDefImpl<Entity, TestContext>('t1', 's1', 's2')
        td.beginConfigurer()

        when:
        td.preCondition('id', (Condition<Entity, TestContext>) null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Condition cannot be null'
    }

    def 'preCondition rejects null Class'() {
        given:
        def td = new TransitionDefImpl<Entity, TestContext>('t1', 's1', 's2')
        td.beginConfigurer()

        when:
        td.preCondition('id', (Class<? extends Condition<Entity, TestContext>>) null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Condition class cannot be null'
    }

    def 'preCondition rejects null Predicate'() {
        given:
        def td = new TransitionDefImpl<Entity, TestContext>('t1', 's1', 's2')
        td.beginConfigurer()

        when:
        td.preCondition('id', (Predicate<Entity>) null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Predicate cannot be null'
    }

    def 'preCondition rejects blank expression'() {
        given:
        def td = new TransitionDefImpl<Entity, TestContext>('t1', 's1', 's2')
        td.beginConfigurer()

        when:
        td.preCondition('id', '   ')

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Expression cannot be null or blank'
    }

    def 'postCondition single-arg rejects blank registered id'() {
        given:
        def td = new TransitionDefImpl<Entity, TestContext>('t1', 's1', 's2')
        td.beginConfigurer()

        when:
        td.postCondition((String) id)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Registered condition ID cannot be null or blank'

        where:
        id   || _
        null || _
        ''   || _
        '   '|| _
    }

    def 'postConditionExpression rejects blank expression'() {
        given:
        def td = new TransitionDefImpl<Entity, TestContext>('t1', 's1', 's2')
        td.beginConfigurer()

        when:
        td.postConditionExpression(expr)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Expression cannot be null or blank'

        where:
        expr || _
        null || _
        ''   || _
        '  ' || _
    }

    def 'postCondition rejects blank id with Condition'() {
        given:
        def td = new TransitionDefImpl<Entity, TestContext>('t1', 's1', 's2')
        td.beginConfigurer()

        when:
        td.postCondition('  ', { e, c, t -> true } as Condition)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Condition ID cannot be null or blank'
    }

    def 'multiple preConditions are stored in declaration order'() {
        given:
        def td = new TransitionDefImpl<Entity, TestContext>('t1', 's1', 's2')
        td.beginConfigurer()

        when:
        td.preCondition('first', { e -> true } as Predicate)
          .preCondition('second', { e -> true } as Predicate)
          .preCondition('third', { e -> true } as Predicate)

        then:
        td.preConditionDescriptors*.id() == ['first', 'second', 'third']
    }

    def 'multiple postConditions are stored in declaration order'() {
        given:
        def td = new TransitionDefImpl<Entity, TestContext>('t1', 's1', 's2')
        td.beginConfigurer()

        when:
        td.postCondition('first', { e -> true } as Predicate)
          .postCondition('second', { e -> true } as Predicate)
          .postCondition('third', { e -> true } as Predicate)

        then:
        td.postConditionDescriptors*.id() == ['first', 'second', 'third']
    }
}
