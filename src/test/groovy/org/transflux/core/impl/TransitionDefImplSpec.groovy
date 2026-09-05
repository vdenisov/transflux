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
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.action.OperationDef
import org.transflux.core.action.Action
import org.transflux.core.action.StepDef
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.ExecutingTransition
import org.transflux.core.transition.Transition
import org.transflux.core.transition.TransitionDef
import spock.lang.Specification
import spock.lang.Unroll

import java.util.function.Consumer
import java.util.function.Predicate

class TransitionDefImplSpec extends Specification {

    def 'constructor should create TransitionDef with valid parameters'() {
        when:
        def transitionDef = new TransitionDefImpl('t1', 'source', 'target')

        then:
        transitionDef.id == 't1'
        transitionDef.sourceStateId == 'source'
        transitionDef.targetStateId == 'target'
        transitionDef.name == null
        transitionDef.description == null
    }

    def 'withName stores the supplied name and returns the def for chaining'() {
        given:
        def td = new TransitionDefImpl<Object, TestContext>('t1', 's1', 's2')
        td.beginConfigurer()

        when:
        def result = td.withName('My Transition')

        then:
        result.is(td)
        td.name == 'My Transition'
    }

    def 'withDescription stores the supplied description and returns the def for chaining'() {
        given:
        def td = new TransitionDefImpl<Object, TestContext>('t1', 's1', 's2')
        td.beginConfigurer()

        when:
        def result = td.withDescription('Performs the X step of Y')

        then:
        result.is(td)
        td.description == 'Performs the X step of Y'
    }

    def 'withName overrides a previously stored name'() {
        given:
        def td = new TransitionDefImpl<Object, TestContext>('t1', 's1', 's2')
        td.beginConfigurer()
        td.withName('first')

        when:
        td.withName('second')

        then:
        td.name == 'second'
    }

    def 'withDescription overrides a previously stored description'() {
        given:
        def td = new TransitionDefImpl<Object, TestContext>('t1', 's1', 's2')
        td.beginConfigurer()
        td.withDescription('first')

        when:
        td.withDescription('second')

        then:
        td.description == 'second'
    }

    @Unroll
    def 'constructor should validate parameters: #scenario'() {
        when:
        new TransitionDefImpl(id, sourceStateId, targetStateId)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == expectedMessage

        where:
        scenario                | id   | sourceStateId | targetStateId | expectedMessage
        'null transition ID'    | null | 'source'      | 'target'      | 'Transition ID cannot be null or blank'
        'blank transition ID'   | '  ' | 'source'      | 'target'      | 'Transition ID cannot be null or blank'
        'empty transition ID'   | ''   | 'source'      | 'target'      | 'Transition ID cannot be null or blank'
        'null source state ID'  | 't1' | null          | 'target'      | 'Source state ID cannot be null or blank'
        'blank source state ID' | 't1' | '  '          | 'target'      | 'Source state ID cannot be null or blank'
        'empty source state ID' | 't1' | ''            | 'target'      | 'Source state ID cannot be null or blank'
        'null target state ID'  | 't1' | 'source'      | null          | 'Target state ID cannot be null or blank'
        'blank target state ID' | 't1' | 'source'      | '  '          | 'Target state ID cannot be null or blank'
        'empty target state ID' | 't1' | 'source'      | ''            | 'Target state ID cannot be null or blank'
    }

    @Unroll
    def 'getter #getter should return #expected'() {
        given:
        def transitionDef = new TransitionDefImpl(id, sourceStateId, targetStateId)

        when:
        def result = transitionDef."$getter"()

        then:
        result == expected

        where:
        getter             | id             | sourceStateId     | targetStateId     | expected
        'getId'            | 'transition-1' | 'source'          | 'target'          | 'transition-1'
        'getSourceStateId' | 't1'           | 'source-state-id' | 'target'          | 'source-state-id'
        'getTargetStateId' | 't1'           | 'source'          | 'target-state-id' | 'target-state-id'
    }

    def 'step(id, Action instance) declares a member on the body'() {
        given:
        def transitionDef = new TransitionDefImpl<Object, Object>('t1', 'source', 'target')
        transitionDef.beginConfigurer()

        when:
        def returned = transitionDef.step('op1', new FooOperation())

        then:
        returned.is(transitionDef)
        transitionDef.actionDef instanceof OperationDefImpl
        memberIds(transitionDef) == ['op1']
    }

    def 'step(id, Consumer) declares a configured member on the body'() {
        given:
        def transitionDef = new TransitionDefImpl<Object, Object>('t1', 'source', 'target')
        transitionDef.beginConfigurer()

        when:
        def returned = transitionDef.step('op1', { StepDef<Object, Object> op ->
            op.withName('Foo').withDescription('Foo desc').using(new FooOperation())
        })

        then:
        returned.is(transitionDef)
        memberIds(transitionDef) == ['op1']

        and: 'the configurer reached the member def, not the body'
        def def0 = transitionDef.actionDef.getMembers()[0].ref().def()
        def0.name == 'Foo'
        def0.description == 'Foo desc'
    }

    def 'step(id, Consumer) should reject null configurer'() {
        given:
        def transitionDef = new TransitionDefImpl<Object, Object>('t1', 'source', 'target')
        transitionDef.beginConfigurer()

        when:
        transitionDef.step('op1', (Consumer<StepDef<Object, Object>>) null)

        then:
        thrown(TransfluxValidationException)
    }

    def 'operation(id, Consumer) declares a container member on the body'() {
        given:
        def transitionDef = new TransitionDefImpl<Object, Object>('t1', 'source', 'target')
        transitionDef.beginConfigurer()

        when:
        def returned = transitionDef.operation('op1', { OperationDef<Object, Object> c ->
            c.step('s1', new FooStep())
        })

        then:
        returned.is(transitionDef)
        memberIds(transitionDef) == ['op1']
        transitionDef.actionDef.getMembers()[0].ref() instanceof ActionRef.InlineOperation
    }

    def 'operation(id, Consumer) should reject null configurer'() {
        given:
        def transitionDef = new TransitionDefImpl<Object, Object>('t1', 'source', 'target')
        transitionDef.beginConfigurer()

        when:
        transitionDef.operation('op1', (Consumer<OperationDef<Object, Object>>) null)

        then:
        thrown(TransfluxValidationException)
    }

    def 'toString should include all fields'() {
        given:
        def transitionDef = new TransitionDefImpl('t1', 'source', 'target')

        when:
        def result = transitionDef.toString()

        then:
        result == "TransitionDefImpl{id='t1', sourceStateId='source', targetStateId='target'}"
    }

    def 'TransitionDef defaults to Object context when none is declared'() {
        given:
        def td = new TransitionDefImpl<UsingCtxEntity, Object>('t1', 's1', 's2')

        expect:
        td.getContextType() == Object
    }

    def "transition's contextType is reachable from the bound transition record"() {
        given:
        def smd = new StateMachineDefImpl<UsingCtxEntity>()
        smd.forEntityType(UsingCtxEntity)
            .withStateResolver({ e -> e.state } as StateResolver<UsingCtxEntity>)
            .state('s1', { s -> s.transitionsTo('s2', 't1', UsingCtx, { t -> }) })
            .state('s2', {})
        def sm = (StateMachineImpl) smd.build()

        when:
        def transition = sm.getTransition('t1')

        then:
        transition instanceof BoundTransition
        transition.contextType() == UsingCtx
    }

    def 'a transition declaring nothing has no body to run'() {
        given: 'an empty body reads as absent, which is what every build pass skips on'
        def td = new TransitionDefImpl<Object, Object>('t1', 's1', 's2')

        expect:
        td.getActionDef() == null
        td.buildBoundAction() == null
    }

    def 'run(id) declares a by-id member on the body'() {
        given:
        def td = new TransitionDefImpl<Object, Object>('t1', 's1', 's2')
        td.beginConfigurer()

        when:
        td.run('my-registered-op')

        then:
        memberIds(td) == ['my-registered-op']
        td.actionDef.getMembers()[0].ref() instanceof ActionRef.ById
    }

    def 'run(null) and run(blank) are rejected'() {
        given:
        def td = new TransitionDefImpl<Object, Object>('t1', 's1', 's2')
        td.beginConfigurer()

        when:
        td.run(id as String)

        then:
        thrown(TransfluxValidationException)

        where:
        id << [null, '', '  ']
    }

    def 'a second declaration appends rather than replacing the first'() {
        given: 'a transition holds an ordered list, so both members run, in the order written'
        def td = new TransitionDefImpl<Object, Object>('t1', 's1', 's2')
        td.beginConfigurer()

        when:
        td.run('first')
        td.step('second', new IdOverloadOp())

        then:
        memberIds(td) == ['first', 'second']
    }

    def 'declaration order is preserved whichever forms are mixed'() {
        given:
        def td = new TransitionDefImpl<Object, Object>('t1', 's1', 's2')
        td.beginConfigurer()

        when:
        td.step('first', new IdOverloadOp())
        td.run('second')
        td.fork('third')

        then:
        memberIds(td) == ['first', 'second', 'third']
        td.actionDef.getMembers()*.forked() == [false, false, true]
    }

    def 'a member declared after the configurer returns is rejected, naming the transition'() {
        given: "the body's guard follows the transition's, so one rule covers both"
        def td = new TransitionDefImpl<Object, Object>('t1', 's1', 's2')
        td.beginConfigurer()
        td.endConfigurer()

        when:
        td.run('after-the-fact')

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("'t1'")
        e.message.contains('transition')
    }

    @Unroll
    def 'trigger declaration rejects a null second argument: #variant'() {
        given:
        def td = new TransitionDefImpl<Object, Object>('t', 's1', 's2')
        td.beginConfigurer()

        when:
        action.call(td)

        then:
        thrown(TransfluxValidationException)

        where:
        variant                               | action
        'addEventTrigger(id, null Consumer)'  | { d -> d.addEventTrigger('e', (Consumer) null) }
        'addManualTrigger(id, null Consumer)' | { d -> d.addManualTrigger('m', (Consumer) null) }
        'addDataTrigger(id, null Consumer)'   | { d -> d.addDataTrigger('dt', (Consumer) null) }
    }


    static class FooStep implements Action<Object, Object> {
        @Override
        void execute(Object entity, Object context, ExecutingTransition<Object, Object> transition) {
        }
    }

    static class FooOperation implements Action<Object, Object> {
        @Override
        void execute(Object entity, Object context, ExecutingTransition<Object, Object> transition) {
        }
    }

    static class UsingCtxEntity {
        String state

        UsingCtxEntity(String state) { this.state = state }
    }

    static class UsingCtx { }

    static class IdOverloadOp implements Action<Object, Object> {
        @Override
        void execute(Object e, Object c, ExecutingTransition<Object, Object> t) {}
    }


    private static List<String> memberIds(TransitionDefImpl<?, ?> td) {
        return td.actionDef.getMembers()*.ref()*.id()
    }
}
