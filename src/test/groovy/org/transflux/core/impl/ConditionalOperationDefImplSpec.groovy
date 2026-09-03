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
import org.transflux.core.action.ActionKind
import org.transflux.core.action.BranchDef
import org.transflux.core.action.Compensation
import org.transflux.core.action.DefaultBranchDef
import org.transflux.core.action.NoMatchBehavior
import org.transflux.core.action.Action
import org.transflux.core.transition.ExecutingTransition
import org.transflux.core.transition.Transition
import spock.lang.Specification
import spock.lang.Unroll

import java.util.function.BiPredicate
import java.util.function.Predicate

class ConditionalOperationDefImplSpec extends Specification {

    static class Entity {
        String state
        int value
    }


    static class NoopStep implements Action<Entity, TestContext> {
        @Override
        void execute(Entity entity, TestContext context, ExecutingTransition<Entity, TestContext> transition) {
        }
    }

    def 'constructor rejects null/blank id'() {
        when:
        new ConditionalOperationDefImpl<Entity, TestContext>(id)

        then:
        thrown(TransfluxValidationException)

        where:
        id   || _
        null || _
        ''   || _
        '  ' || _
    }

    def 'onNoMatch WARN is the default'() {
        given:
        def cond = new ConditionalOperationDefImpl<Entity, TestContext>('c1').tap { beginConfigurer() }

        expect:
        cond.noMatchBehavior == NoMatchBehavior.WARN
    }

    @Unroll
    def 'onNoMatch sets the behavior to #behavior'() {
        given:
        def cond = new ConditionalOperationDefImpl<Entity, TestContext>('c1').tap { beginConfigurer() }

        when:
        cond.onNoMatch(behavior)

        then:
        cond.noMatchBehavior == behavior

        where:
        behavior << [NoMatchBehavior.WARN, NoMatchBehavior.SILENT, NoMatchBehavior.ERROR]
    }

    def 'onNoMatch rejects null behavior'() {
        given:
        def cond = new ConditionalOperationDefImpl<Entity, TestContext>('c1').tap { beginConfigurer() }

        when:
        cond.onNoMatch(null)

        then:
        thrown(TransfluxValidationException)
    }

    def 'branch with no condition fails at build time'() {
        given:
        def cond = new ConditionalOperationDefImpl<Entity, TestContext>('c1').tap { beginConfigurer() }
            .branch('b1', { BranchDef<Entity, TestContext> b -> b.step('s1', new NoopStep()) })

        when:
        cond.buildBoundAction([:])

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("Branch 'b1'")
        e.message.contains('must declare a condition')
    }

    def 'branch with no steps fails at build time'() {
        given:
        def cond = new ConditionalOperationDefImpl<Entity, TestContext>('c1').tap { beginConfigurer() }
            .branch('b1', { BranchDef<Entity, TestContext> b -> b.condition('b1-cond', { e -> true } as Predicate) })

        when:
        cond.buildBoundAction([:])

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("Branch 'b1'")
        e.message.contains('at least one action')
    }

    def 'default branch with no steps fails at build time'() {
        given:
        def cond = new ConditionalOperationDefImpl<Entity, TestContext>('c1').tap { beginConfigurer() }
            .branch('b1', { BranchDef<Entity, TestContext> b ->
                b.condition('b1-cond', { e -> true } as Predicate).step('s1', new NoopStep())
            })
            .defaultBranch({ DefaultBranchDef<Entity, TestContext> d -> })

        when:
        cond.buildBoundAction([:])

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('Default branch')
        e.message.contains('at least one action')
    }

    def 'conditional with no branches fails at build time even when default is declared'() {
        given:
        def cond = new ConditionalOperationDefImpl<Entity, TestContext>('c1').tap { beginConfigurer() }
            .defaultBranch({ DefaultBranchDef<Entity, TestContext> d -> d.step('s1', new NoopStep()) })

        when:
        cond.buildBoundAction([:])

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("Conditional operation 'c1'")
        e.message.contains('at least one branch')
    }

    def 'bound conditional carries the OPERATION kind'() {
        given: 'a conditional is a declarative action - only its ordering rule differs from a container'
        def cond = new ConditionalOperationDefImpl<Entity, TestContext>('c1').tap { beginConfigurer() }
            .branch('b1', { BranchDef<Entity, TestContext> b ->
                b.condition('cond1', { e -> true } as Predicate).step('s1', new NoopStep())
            })

        when:
        def bound = cond.buildBoundAction([:])

        then:
        bound.kind() == ActionKind.OPERATION
        bound.id() == 'c1'
    }

    def 'declared compensation rides onto the bound conditional'() {
        given: 'a conditional has no Java body, so the def is its only channel for a compensation'
        def compensation = { e, c -> } as Compensation<Entity, TestContext>
        def cond = new ConditionalOperationDefImpl<Entity, TestContext>('c1').tap { beginConfigurer() }
            .withCompensation(compensation)
            .branch('b1', { BranchDef<Entity, TestContext> b ->
                b.condition('cond1', { e -> true } as Predicate).step('s1', new NoopStep())
            })

        expect:
        cond.buildBoundAction([:]).compensationRouter()?.fallback().is(compensation)
    }

    def 'a conditional that declares no compensation binds none'() {
        given:
        def cond = new ConditionalOperationDefImpl<Entity, TestContext>('c1').tap { beginConfigurer() }
            .branch('b1', { BranchDef<Entity, TestContext> b ->
                b.condition('cond1', { e -> true } as Predicate).step('s1', new NoopStep())
            })

        expect:
        cond.buildBoundAction([:]).compensationRouter() == null
    }

    def 'duplicate branch id is rejected at configurer time'() {
        given:
        def cond = new ConditionalOperationDefImpl<Entity, TestContext>('c1').tap { beginConfigurer() }
            .branch('b1', { BranchDef<Entity, TestContext> b ->
                b.condition('cond1', { e -> true } as Predicate).step('s1', new NoopStep())
            })

        when:
        cond.branch('b1', { BranchDef<Entity, TestContext> b ->
            b.condition('cond2', { e -> true } as Predicate).step('s2', new NoopStep())
        })

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("Branch ID 'b1'")
    }

    def 'declaring default branch twice is rejected'() {
        given:
        def cond = new ConditionalOperationDefImpl<Entity, TestContext>('c1').tap { beginConfigurer() }
            .defaultBranch({ DefaultBranchDef<Entity, TestContext> d -> d.step('s1', new NoopStep()) })

        when:
        cond.defaultBranch({ DefaultBranchDef<Entity, TestContext> d -> d.step('s2', new NoopStep()) })

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('Default branch is already declared')
    }

    def 'multiple condition calls on the same branch: last-wins with warning'() {
        given:
        def cond = new ConditionalOperationDefImpl<Entity, TestContext>('c1').tap { beginConfigurer() }

        when:
        cond.branch('b1', { BranchDef<Entity, TestContext> b ->
            b.condition('first', { e -> true } as Predicate)
             .conditionExpression('entity.value > 0')
             .step('s1', new NoopStep())
        })

        then:
        def branch = cond.branches[0]
        def descriptor = branch.descriptor
        descriptor instanceof ConditionDescriptor.ExpressionBased
        (descriptor as ConditionDescriptor.ExpressionBased).expression() == 'entity.value > 0'
    }

    def 'BranchDef.condition(registeredId) builds a Reference descriptor'() {
        given:
        def cond = new ConditionalOperationDefImpl<Entity, TestContext>('c1').tap { beginConfigurer() }

        when:
        cond.branch('b1', { BranchDef<Entity, TestContext> b ->
            b.condition('global-id').step('s1', new NoopStep())
        })

        then:
        def descriptor = cond.branches[0].descriptor
        descriptor instanceof ConditionDescriptor.Reference
        descriptor.id() == 'global-id'
    }

    def 'BranchDef.conditionExpression builds an ExpressionBased descriptor with auto-derived id'() {
        given:
        def cond = new ConditionalOperationDefImpl<Entity, TestContext>('c1').tap { beginConfigurer() }

        when:
        cond.branch('b1', { BranchDef<Entity, TestContext> b ->
            b.conditionExpression('entity.value > 0').step('s1', new NoopStep())
        })

        then:
        def descriptor = cond.branches[0].descriptor
        descriptor instanceof ConditionDescriptor.ExpressionBased
        descriptor.id() == null
        (descriptor as ConditionDescriptor.ExpressionBased).expression() == 'entity.value > 0'
    }

    def 'BranchDef.conditionExpression names itself, not condition, when the configurer has returned'() {
        given:
        def cond = new ConditionalOperationDefImpl<Entity, TestContext>('c1').tap { beginConfigurer() }
        BranchDef<Entity, TestContext> escaped = null

        when:
        cond.branch('b1', { BranchDef<Entity, TestContext> b ->
            escaped = b
            b.conditionExpression('entity.value > 0').step('s1', new NoopStep())
        })
        escaped.conditionExpression('entity.value > 1')

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("'conditionExpression'")
        e.message.contains("branch 'b1'")
    }

    def 'BranchDef.condition(id, Condition) builds an InstanceBased descriptor'() {
        given:
        def cond = new ConditionalOperationDefImpl<Entity, TestContext>('c1').tap { beginConfigurer() }
        Condition<Entity, TestContext> condition = { e, c, t -> true } as Condition

        when:
        cond.branch('b1', { BranchDef<Entity, TestContext> b ->
            b.condition('inst', condition).step('s1', new NoopStep())
        })

        then:
        def descriptor = cond.branches[0].descriptor
        descriptor instanceof ConditionDescriptor.InstanceBased
        descriptor.id() == 'inst'
        (descriptor as ConditionDescriptor.InstanceBased).condition().is(condition)
    }

    def 'BranchDef.condition(id, BiPredicate) builds a PredicateBased descriptor'() {
        given:
        def cond = new ConditionalOperationDefImpl<Entity, TestContext>('c1').tap { beginConfigurer() }
        BiPredicate<Entity, TestContext> predicate = { e, c -> true } as BiPredicate

        when:
        cond.branch('b1', { BranchDef<Entity, TestContext> b ->
            b.condition('pred', predicate).step('s1', new NoopStep())
        })

        then:
        def descriptor = cond.branches[0].descriptor
        descriptor instanceof ConditionDescriptor.PredicateBased
        descriptor.id() == 'pred'
        (descriptor as ConditionDescriptor.PredicateBased).predicate().is(predicate)
    }

    def 'BranchDef.condition(id, Predicate) builds a PredicateBased descriptor that ignores the context'() {
        given:
        def cond = new ConditionalOperationDefImpl<Entity, TestContext>('c1').tap { beginConfigurer() }
        def calls = []
        Predicate<Entity> predicate = { e -> calls << e; true } as Predicate

        when:
        cond.branch('b1', { BranchDef<Entity, TestContext> b ->
            b.condition('pred', predicate).step('s1', new NoopStep())
        })

        then:
        def descriptor = cond.branches[0].descriptor
        descriptor instanceof ConditionDescriptor.PredicateBased
        descriptor.id() == 'pred'

        when:
        def adapted = (descriptor as ConditionDescriptor.PredicateBased).predicate() as BiPredicate<Entity, TestContext>
        def entity = new Entity(value: 13)
        def result = adapted.test(entity, new TestContext())

        then:
        result
        calls == [entity]
    }

    def 'BranchDef.condition(id, expression) builds an ExpressionBased descriptor with explicit id'() {
        given:
        def cond = new ConditionalOperationDefImpl<Entity, TestContext>('c1').tap { beginConfigurer() }

        when:
        cond.branch('b1', { BranchDef<Entity, TestContext> b ->
            b.condition('expr', 'entity.value > 0').step('s1', new NoopStep())
        })

        then:
        def descriptor = cond.branches[0].descriptor
        descriptor instanceof ConditionDescriptor.ExpressionBased
        descriptor.id() == 'expr'
        (descriptor as ConditionDescriptor.ExpressionBased).expression() == 'entity.value > 0'
    }
}
