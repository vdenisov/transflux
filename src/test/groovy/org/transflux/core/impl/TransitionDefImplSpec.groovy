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

import org.transflux.core.Identifiable
import org.transflux.core.TestContext
import org.transflux.core.condition.Condition
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.action.OperationDef
import org.transflux.core.action.Action
import org.transflux.core.action.StepDef
import org.transflux.core.state.StateResolver
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

    def 'step(id, Operation instance) should attach an inline step def'() {
        given:
        def transitionDef = new TransitionDefImpl<Object, Object>('t1', 'source', 'target')
        transitionDef.beginConfigurer()

        when:
        def returned = transitionDef.step('op1', new FooOperation())

        then:
        returned.is(transitionDef)
        transitionDef.actionDef instanceof StepDefImpl
        transitionDef.actionDef.id == 'op1'
    }

    def 'step(id, Operation class) should attach an inline step def'() {
        given:
        def transitionDef = new TransitionDefImpl<Object, Object>('t1', 'source', 'target')
        transitionDef.beginConfigurer()

        when:
        def returned = transitionDef.step('op1', FooOperation)

        then:
        returned.is(transitionDef)
        transitionDef.actionDef instanceof StepDefImpl
        transitionDef.actionDef.id == 'op1'
    }

    def 'step(id, Consumer) should attach a configured simple operation def'() {
        given:
        def transitionDef = new TransitionDefImpl<Object, Object>('t1', 'source', 'target')
        transitionDef.beginConfigurer()

        when:
        def returned = transitionDef.step('op1', { StepDef<Object, Object> op ->
            op.withName('Foo').withDescription('Foo desc').using(FooOperation)
        })

        then:
        returned.is(transitionDef)
        transitionDef.actionDef instanceof StepDefImpl
        transitionDef.actionDef.id == 'op1'
        transitionDef.actionDef.name == 'Foo'
        transitionDef.actionDef.description == 'Foo desc'
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

    def 'operation(id, Consumer) should attach a composite operation def'() {
        given:
        def transitionDef = new TransitionDefImpl<Object, Object>('t1', 'source', 'target')
        transitionDef.beginConfigurer()

        when:
        def returned = transitionDef.operation('op1', { OperationDef<Object, Object> c ->
            c.step('s1', new FooStep())
        })

        then:
        returned.is(transitionDef)
        transitionDef.actionDef instanceof OperationDefImpl
        transitionDef.actionDef.id == 'op1'
        ((OperationDefImpl<Object, Object>) transitionDef.actionDef).actionRefs.size() == 1
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

    def 'TransitionDef defaults to Object context when usingContext is not called'() {
        given:
        def td = new TransitionDefImpl<UsingCtxEntity, Object>('t1', 's1', 's2')

        expect:
        td.getContextType() == Object
    }

    def "usingContext narrows the transition's context type and re-types the builder"() {
        given:
        def td = new TransitionDefImpl<UsingCtxEntity, Void>('t1', 's1', 's2', Void)
        td.beginConfigurer()

        when:
        TransitionDef<UsingCtxEntity, UsingCtx> retyped = td.usingContext(UsingCtx)

        then:
        retyped.getContextType() == UsingCtx
        td.getContextType() == UsingCtx
    }

    def "transition's contextType is reachable from the bound transition record"() {
        given:
        def smd = new StateMachineDefImpl<UsingCtxEntity>()
        smd.forEntityType(UsingCtxEntity)
            .withStateResolver({ e -> e.state } as StateResolver<UsingCtxEntity>)
            .state('s1', { s -> s.transitionsTo('s2', 't1', { t -> t.usingContext(UsingCtx) }) })
            .state('s2', {})
        def sm = (StateMachineImpl) smd.build()

        when:
        def transition = sm.getTransition('t1')

        then:
        transition instanceof BoundTransition
        transition.contextType() == UsingCtx
    }

    def 'preCondition(Identifiable) delegates to preCondition(String)'() {
        given:
        def td = new TransitionDefImpl<Object, Object>('t1', 's1', 's2')
        td.beginConfigurer()

        when:
        td.preCondition(identifiable('my-cond'))

        then:
        !td.preConditionDescriptors.isEmpty()
        td.preConditionDescriptors[0].id() == 'my-cond'
    }

    def 'postCondition(Identifiable) delegates to postCondition(String)'() {
        given:
        def td = new TransitionDefImpl<Object, Object>('t1', 's1', 's2')
        td.beginConfigurer()

        when:
        td.postCondition(identifiable('my-cond'))

        then:
        !td.postConditionDescriptors.isEmpty()
        td.postConditionDescriptors[0].id() == 'my-cond'
    }

    @Unroll
    def 'tier-1 Identifiable overload rejects null: #method'() {
        given:
        def td = new TransitionDefImpl<Object, Object>('t1', 's1', 's2')
        td.beginConfigurer()

        when:
        td."$method"(null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.toLowerCase().contains('identifiable')

        where:
        method << ['preCondition', 'postCondition', 'run']
    }

    def 'operation(String) stores the registered op id and clears any prior actionDef'() {
        given:
        def td = new TransitionDefImpl<Object, Object>('t1', 's1', 's2')
        td.beginConfigurer()

        when:
        td.run('my-registered-op')

        then:
        td.registeredActionRefId == 'my-registered-op'
        td.actionDef == null
    }

    def 'operation(Identifiable) delegates to operation(String)'() {
        given:
        def td = new TransitionDefImpl<Object, Object>('t1', 's1', 's2')
        td.beginConfigurer()

        when:
        td.run(identifiable('my-registered-op'))

        then:
        td.registeredActionRefId == 'my-registered-op'
    }

    def 'operation(null) and operation(blank) are rejected'() {
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

    def 'operation(...) followed by step(...) overrides with a warning'() {
        given:
        def td = new TransitionDefImpl<Object, Object>('t1', 's1', 's2')
        td.beginConfigurer()

        when:
        td.run('first')
        td.step('second', new IdOverloadOp())

        then:
        td.registeredActionRefId == null
        td.actionDef != null
        td.actionDef.id == 'second'
    }

    def 'step(...) followed by operation(...) overrides with a warning'() {
        given:
        def td = new TransitionDefImpl<Object, Object>('t1', 's1', 's2')
        td.beginConfigurer()

        when:
        td.step('first', new IdOverloadOp())
        td.run('second')

        then:
        td.actionDef == null
        td.registeredActionRefId == 'second'
    }

    def 'operation(...) is rejected after the configurer returns (scope guard)'() {
        given:
        def td = new TransitionDefImpl<Object, Object>('t1', 's1', 's2')
        td.beginConfigurer()
        td.endConfigurer()

        when:
        td.run('after-the-fact')

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("'t1'")
    }

    @Unroll
    def 'tier-3 step/operation Identifiable overload accepted: #variant'() {
        given:
        def td = new TransitionDefImpl<Object, Object>('t', 's1', 's2')
        td.beginConfigurer()

        when:
        action.call(td)

        then:
        notThrown(Exception)

        where:
        variant                                     | action
        'step(Id, Operation)'            | { d -> d.step(identifiable('op1'), new IdOverloadOp()) }
        'step(Id, Class)'                | { d -> d.step(identifiable('op2'), IdOverloadOp) }
        'step(Id, Consumer)'             | { d -> d.step(identifiable('op3'), { o -> o.using(new IdOverloadOp()) }) }
        'operation(Id, Consumer)'          | { d -> d.operation(identifiable('op4'), { c -> c.run('anything') }) }
    }

    @Unroll
    def 'tier-3 preCondition Identifiable overload accepted: #variant'() {
        given:
        def td = new TransitionDefImpl<Object, Object>('t', 's1', 's2')
        td.beginConfigurer()

        when:
        action.call(td)

        then:
        td.preConditionDescriptors.size() == 1

        where:
        variant                                  | action
        'preCondition(Id, Condition)'            | { d -> d.preCondition(identifiable('pc1'), new IdOverloadCond()) }
        'preCondition(Id, Class)'                | { d -> d.preCondition(identifiable('pc2'), IdOverloadCond) }
        'preCondition(Id, Predicate)'            | { d -> d.preCondition(identifiable('pc3'), { e -> true } as Predicate) }
        'preCondition(Id, String)'               | { d -> d.preCondition(identifiable('pc4'), 'true') }
    }

    @Unroll
    def 'tier-3 postCondition Identifiable overload accepted: #variant'() {
        given:
        def td = new TransitionDefImpl<Object, Object>('t', 's1', 's2')
        td.beginConfigurer()

        when:
        action.call(td)

        then:
        td.postConditionDescriptors.size() == 1

        where:
        variant                                  | action
        'postCondition(Id, Condition)'           | { d -> d.postCondition(identifiable('pc1'), new IdOverloadCond()) }
        'postCondition(Id, Class)'               | { d -> d.postCondition(identifiable('pc2'), IdOverloadCond) }
        'postCondition(Id, Predicate)'           | { d -> d.postCondition(identifiable('pc3'), { e -> true } as Predicate) }
        'postCondition(Id, String)'              | { d -> d.postCondition(identifiable('pc4'), 'true') }
    }

    @Unroll
    def 'tier-3 Identifiable overload rejects null: #variant'() {
        given:
        def td = new TransitionDefImpl<Object, Object>('t', 's1', 's2')
        td.beginConfigurer()

        when:
        action.call(td)

        then:
        thrown(TransfluxValidationException)

        where:
        variant                                  | action
        'step(null, Operation)'       | { d -> d.step((Identifiable) null, new IdOverloadOp()) }
        'step(null, Class)'           | { d -> d.step((Identifiable) null, IdOverloadOp) }
        'step(null, Consumer)'        | { d -> d.step((Identifiable) null, { o -> }) }
        'operation(null, Consumer)'     | { d -> d.operation((Identifiable) null, { c -> }) }
        'preCondition(null, Condition)'          | { d -> d.preCondition((Identifiable) null, new IdOverloadCond()) }
        'preCondition(null, Class)'              | { d -> d.preCondition((Identifiable) null, IdOverloadCond) }
        'preCondition(null, Predicate)'          | { d -> d.preCondition((Identifiable) null, { e -> true } as Predicate) }
        'preCondition(null, String)'             | { d -> d.preCondition((Identifiable) null, 'true') }
        'postCondition(null, Condition)'         | { d -> d.postCondition((Identifiable) null, new IdOverloadCond()) }
        'postCondition(null, Class)'             | { d -> d.postCondition((Identifiable) null, IdOverloadCond) }
        'postCondition(null, Predicate)'         | { d -> d.postCondition((Identifiable) null, { e -> true } as Predicate) }
        'postCondition(null, String)'            | { d -> d.postCondition((Identifiable) null, 'true') }
        'addManualTrigger(null)'                 | { d -> d.addManualTrigger((Identifiable) null) }
        'addEventTrigger(null, String)'          | { d -> d.addEventTrigger((Identifiable) null, 'evt') }
        'addEventTrigger(null, Identifiable)'    | { d -> d.addEventTrigger((Identifiable) null, identifiable('evt')) }
        'addEventTrigger(null event)'            | { d -> d.addEventTrigger((Identifiable) null) }
        'addEventTrigger(null, Consumer)'        | { d -> d.addEventTrigger((Identifiable) null, { t -> } as Consumer) }
        'addManualTrigger(null, Consumer)'       | { d -> d.addManualTrigger((Identifiable) null, { t -> } as Consumer) }
        'addDataTrigger(null, Consumer)'         | { d -> d.addDataTrigger((Identifiable) null, { t -> } as Consumer) }
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
        variant                                     | action
        'addEventTrigger(id, null event Id)'        | { d -> d.addEventTrigger('e', (Identifiable) null) }
        'addEventTrigger(Id, null event Id)'        | { d -> d.addEventTrigger(identifiable('e'), (Identifiable) null) }
        'addEventTrigger(id, null Consumer)'        | { d -> d.addEventTrigger('e', (Consumer) null) }
        'addManualTrigger(id, null Consumer)'       | { d -> d.addManualTrigger('m', (Consumer) null) }
        'addDataTrigger(id, null Consumer)'         | { d -> d.addDataTrigger('dt', (Consumer) null) }
    }

    private static Identifiable identifiable(String value) {
        return { -> value } as Identifiable
    }

    static class FooStep implements Action<Object, Object> {
        @Override
        void execute(Object entity, Object context, Transition<Object, Object> transition) {
        }
    }

    static class FooOperation implements Action<Object, Object> {
        @Override
        void execute(Object entity, Object context, Transition<Object, Object> transition) {
        }
    }

    static class UsingCtxEntity {
        String state

        UsingCtxEntity(String state) { this.state = state }
    }

    static class UsingCtx { }

    static class IdOverloadOp implements Action<Object, Object> {
        @Override
        void execute(Object e, Object c, Transition<Object, Object> t) {}
    }

    static class IdOverloadCond implements Condition<Object, Object> {
        @Override
        boolean test(Object e, Object c, Transition<Object, Object> t) { true }
    }
}
