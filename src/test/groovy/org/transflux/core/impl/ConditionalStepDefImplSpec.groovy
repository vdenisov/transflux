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
import org.transflux.core.operation.BranchDef
import org.transflux.core.operation.DefaultBranchDef
import org.transflux.core.operation.NoMatchBehavior
import org.transflux.core.operation.Step
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.Transition
import spock.lang.Specification
import spock.lang.Unroll

import java.util.function.Predicate

class ConditionalStepDefImplSpec extends Specification {

    static class Entity {
        String state
        int value
    }

    static class AlwaysTrueCondition implements Condition<Entity, TestContext> {
        @Override
        boolean test(Entity entity, TestContext context, Transition<Entity, TestContext> transition) {
            true
        }
    }

    static class NoopStep implements Step<Entity, TestContext> {
        @Override
        void execute(Entity entity, TestContext context, Transition<Entity, TestContext> transition) {
        }
    }

    def 'constructor rejects null/blank id'() {
        when:
        new ConditionalStepDefImpl<Entity, TestContext>(id)

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
        def cond = new ConditionalStepDefImpl<Entity, TestContext>('c1')

        expect:
        cond.noMatchBehavior == NoMatchBehavior.WARN
    }

    @Unroll
    def 'onNoMatch sets the behavior to #behavior'() {
        given:
        def cond = new ConditionalStepDefImpl<Entity, TestContext>('c1')

        when:
        cond.onNoMatch(behavior)

        then:
        cond.noMatchBehavior == behavior

        where:
        behavior << [NoMatchBehavior.WARN, NoMatchBehavior.SILENT, NoMatchBehavior.ERROR]
    }

    def 'onNoMatch rejects null behavior'() {
        given:
        def cond = new ConditionalStepDefImpl<Entity, TestContext>('c1')

        when:
        cond.onNoMatch(null)

        then:
        thrown(TransfluxValidationException)
    }

    def 'branch with no condition fails at build time'() {
        given:
        def cond = new ConditionalStepDefImpl<Entity, TestContext>('c1')
            .branch('b1', { BranchDef<Entity, TestContext> b -> b.step('s1', new NoopStep()) })

        when:
        cond.buildBoundStep(stateMachine(), [:])

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("Branch 'b1'")
        e.message.contains('must declare a condition')
    }

    def 'branch with no steps fails at build time'() {
        given:
        def cond = new ConditionalStepDefImpl<Entity, TestContext>('c1')
            .branch('b1', { BranchDef<Entity, TestContext> b -> b.condition('b1-cond', { e -> true } as Predicate) })

        when:
        cond.buildBoundStep(stateMachine(), [:])

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("Branch 'b1'")
        e.message.contains('at least one step')
    }

    def 'default branch with no steps fails at build time'() {
        given:
        def cond = new ConditionalStepDefImpl<Entity, TestContext>('c1')
            .branch('b1', { BranchDef<Entity, TestContext> b ->
                b.condition('b1-cond', { e -> true } as Predicate).step('s1', new NoopStep())
            })
            .defaultBranch({ DefaultBranchDef<Entity, TestContext> d -> })

        when:
        cond.buildBoundStep(stateMachine(), [:])

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('Default branch')
        e.message.contains('at least one step')
    }

    def 'conditional with no branches fails at build time even when default is declared'() {
        given:
        def cond = new ConditionalStepDefImpl<Entity, TestContext>('c1')
            .defaultBranch({ DefaultBranchDef<Entity, TestContext> d -> d.step('s1', new NoopStep()) })

        when:
        cond.buildBoundStep(stateMachine(), [:])

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("Conditional step 'c1'")
        e.message.contains('at least one branch')
    }

    def 'duplicate branch id is rejected at configurer time'() {
        given:
        def cond = new ConditionalStepDefImpl<Entity, TestContext>('c1')
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
        def cond = new ConditionalStepDefImpl<Entity, TestContext>('c1')
            .defaultBranch({ DefaultBranchDef<Entity, TestContext> d -> d.step('s1', new NoopStep()) })

        when:
        cond.defaultBranch({ DefaultBranchDef<Entity, TestContext> d -> d.step('s2', new NoopStep()) })

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('Default branch is already declared')
    }

    def 'multiple condition calls on the same branch: last-wins with warning'() {
        given:
        def cond = new ConditionalStepDefImpl<Entity, TestContext>('c1')

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
        def cond = new ConditionalStepDefImpl<Entity, TestContext>('c1')

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
        def cond = new ConditionalStepDefImpl<Entity, TestContext>('c1')

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

    def 'BranchDef.condition(id, Condition) builds an InstanceBased descriptor'() {
        given:
        def cond = new ConditionalStepDefImpl<Entity, TestContext>('c1')
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

    def 'BranchDef.condition(id, Class) builds a ClassBased descriptor'() {
        given:
        def cond = new ConditionalStepDefImpl<Entity, TestContext>('c1')

        when:
        cond.branch('b1', { BranchDef<Entity, TestContext> b ->
            b.condition('cls', AlwaysTrueCondition).step('s1', new NoopStep())
        })

        then:
        def descriptor = cond.branches[0].descriptor
        descriptor instanceof ConditionDescriptor.ClassBased
        descriptor.id() == 'cls'
        (descriptor as ConditionDescriptor.ClassBased).conditionClass() == AlwaysTrueCondition
    }

    def 'BranchDef.condition(id, Predicate) builds a PredicateBased descriptor'() {
        given:
        def cond = new ConditionalStepDefImpl<Entity, TestContext>('c1')
        Predicate<Entity> predicate = { e -> true } as Predicate

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

    def 'BranchDef.condition(id, expression) builds an ExpressionBased descriptor with explicit id'() {
        given:
        def cond = new ConditionalStepDefImpl<Entity, TestContext>('c1')

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

    private static StateMachineImpl<Entity> stateMachine() {
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .state('s1', { s -> s.transitionsTo('s2', 't', {}) })
            .state('s2', {})
        return (StateMachineImpl<Entity>) smd.build()
    }
}
